package com.shoppilot.bizmock.seed;

import com.shoppilot.bizmock.domain.Coupon;
import com.shoppilot.bizmock.domain.Customer;
import com.shoppilot.bizmock.domain.LogisticsNode;
import com.shoppilot.bizmock.domain.Order;
import com.shoppilot.bizmock.domain.Tenant;
import com.shoppilot.bizmock.repo.AddressHistoryRepository;
import com.shoppilot.bizmock.repo.CouponRepository;
import com.shoppilot.bizmock.repo.CustomerRepository;
import com.shoppilot.bizmock.repo.LogisticsRepository;
import com.shoppilot.bizmock.repo.OrderRepository;
import com.shoppilot.bizmock.repo.RefundRepository;
import com.shoppilot.bizmock.repo.TenantRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.view.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幂等 seed：已有数据即跳过，重复调用不翻倍（ticket 03 验收项）。
 *
 * <p>实体带 {@code @TenantId}，插入时的归属列取自当前租户上下文，
 * 因此必须按租户分批、每批切换上下文，不能在一个事务里混写多个租户。
 *
 * <p>B1（round28 / ADR 0061）起拆成两段，各自独立幂等、独立开关：
 * <b>初始化段</b>（租户结构，{@code seed.enabled}）与<b>演示段</b>（买家 / 压测订单 /
 * 优惠券 / 演示固定单，{@code seed.demo-data}，env {@code SHOPPILOT_BIZMOCK_DEMO_SEED}）。
 * 持久档（postgres profile）默认关闭演示段——5 万条假订单落进真库是污染；
 * 默认档行为不变（两段全开）。
 *
 * <p>{@link #getOrder()}：{@link IdentitySeedRunner} 要遍历本播种出来的租户表，所以它排在本类之后。
 * 这里实现 {@link Ordered} 而不是打 {@code @Order} 注解——本包的 {@code domain.Order}（订单）
 * 与 {@code org.springframework.core.annotation.Order} 简名相同，注解会把引用变成二义的。
 */
@Component
public class SeedRunner implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /**
     * 电商租户（round32 与语料对齐）。名字不是随便取的：`knowledge/` 里 T001/T002 的店铺级政策
     * 是按这两家店写的（星选生活旗舰店的十五天无理由与免费退、鲜果驿站的生鲜不退与口感发券），
     * 而种子段以前叫「数码旗舰店 / 生鲜超市」——同两个租户 ID 在同一个仓里有两个身份，
     * 这正是「看起来像演示、不像真店」最机械的那一层。以语料为准改这里，不改语料。
     */
    private static final List<String[]> TENANTS = List.of(
            new String[]{"T001", "星选生活旗舰店", "200"},
            new String[]{"T002", "鲜果驿站", "300"},
            new String[]{"T003", "云裳服饰旗舰店", "100"});

    /**
     * 非电商租户（round32）：**只播租户行，不参与压测订单与买家的生成**。
     *
     * <p>它存在的理由是本轮那句主张——「同一套代码，换一套知识库就换一个行业」。酒店不是店，
     * 所以它没有商品、没有订单、没有物流；它有的是自己那一套 `knowledge/hotel-*.md` 与
     * 它自己的 `POLICY_HOTEL` 意图。把它塞进 {@link #TENANTS} 会让压测订单按四分之一摊给它，
     * 那既不是酒店的业务，也会打乱既有三个租户的订单号分布。
     */
    private static final List<String[]> NON_SHOP_TENANTS = List.<String[]>of(
            new String[]{"T004", "云栖酒店", "100"});

    private static final String[][] CATEGORIES = {
            {"digital", "数码配件", "SEVEN_DAY_RETURN"},
            {"fresh", "生鲜果蔬", "FRESH_GUARANTEE"},
            {"apparel", "服饰鞋包", "SEVEN_DAY_RETURN"},
            {"food", "休闲食品", ""},
            {"home", "家居日用", "SEVEN_DAY_RETURN"}};

    private static final String[][] CARRIERS = {
            {"ZTO", "中通快递"}, {"YTO", "圆通速递"}, {"SF", "顺丰速运"}, {"YD", "韵达快递"}};

    /** 演示固定单的归属买家，seedMasterData 造的 C001 一定存在。 */
    private static final String DEMO_CUSTOMER = "C001";

    private final TenantRepository tenantRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final LogisticsRepository logisticsRepository;
    private final CouponRepository couponRepository;
    private final AddressHistoryRepository addressHistoryRepository;
    private final RefundRepository refundRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${shoppilot.bizmock.seed.customers:200}")
    private int customerCount;

    @Value("${shoppilot.bizmock.seed.orders:50000}")
    private int orderCount;

    /** 演示数据段开关（round28 票 101 / ADR 0061）。持久档在 application-postgres.yml 里默认 false。 */
    @Value("${shoppilot.bizmock.seed.demo-data:true}")
    private boolean demoData;

    @PersistenceContext
    private EntityManager entityManager;

    public SeedRunner(TenantRepository tenantRepository, CustomerRepository customerRepository,
                      OrderRepository orderRepository, LogisticsRepository logisticsRepository,
                      CouponRepository couponRepository, AddressHistoryRepository addressHistoryRepository,
                      RefundRepository refundRepository,
                      JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.tenantRepository = tenantRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.couponRepository = couponRepository;
        this.addressHistoryRepository = addressHistoryRepository;
        this.refundRepository = refundRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    @Override
    public int getOrder() {
        return 10;
    }

    public void seedIfEmpty() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            seedTenantsIfEmpty();
            if (demoData) {
                seedDemoDataIfEmpty();
            } else {
                log.info("演示数据段关闭（shoppilot.bizmock.seed.demo-data=false）：只保证租户结构，不播买家与订单");
            }
        } finally {
            TenantContextHolder.clear();
            running.set(false);
        }
    }

    /**
     * 初始化段：租户结构是系统运作的最小前提，持久档也照常播种。
     *
     * <p>逐租户「缺哪条补哪条」而不是「表非空就整段跳过」（round32）：加了酒店租户之后，
     * 整段跳过的写法会让既有库（H2 默认档与持久档都算）永远看不到 T004，而它们的表现是
     * 「酒店知识库进去了、检索却因为租户不可见而永远查不到」——那种故障要查很久。
     */
    private void seedTenantsIfEmpty() {
        List<String[]> all = allTenants();
        int created = 0;
        for (String[] tenant : all) {
            Integer present = jdbcTemplate.queryForObject(
                    "select count(*) from tenants where id = ?", Integer.class, tenant[0]);
            if (present != null && present > 0) {
                continue;
            }
            transactionTemplate.executeWithoutResult(status ->
                    tenantRepository.save(new Tenant(tenant[0], tenant[1], Integer.parseInt(tenant[2]))));
            created++;
        }
        if (created == 0) {
            log.info("初始化段跳过：{} 个租户都已存在", all.size());
        } else {
            log.info("初始化段完成：新增 {} 个租户，共 {} 个", created, all.size());
        }
    }

    /** 电商租户在前、非电商租户在后；压测订单只摊给前者。 */
    private static List<String[]> allTenants() {
        List<String[]> all = new ArrayList<>(TENANTS);
        all.addAll(NON_SHOP_TENANTS);
        return all;
    }

    /** 演示段：买家、压测订单、优惠券与演示固定单。重启不翻倍的保证沿用 orders 非空即跳过。 */
    private void seedDemoDataIfEmpty() {
        Long existing = jdbcTemplate.queryForObject("select count(*) from orders", Long.class);
        if (existing != null && existing > 0) {
            log.info("seed 跳过：orders 已有 {} 行", existing);
            return;
        }
        long started = System.nanoTime();
        seedCustomers();
        int base = orderCount / TENANTS.size();
        int offset = 0;
        for (int seq = 0; seq < TENANTS.size(); seq++) {
            // 余数摊给前面的租户，保证总数精确等于配置值
            int size = base + (seq < orderCount % TENANTS.size() ? 1 : 0);
            seedTenantOrders(TENANTS.get(seq)[0], seq, offset, size);
            offset += size;
        }
        log.info("seed 完成：{} 租户 / {} 买家 / {} 订单，耗时 {} ms",
                TENANTS.size(), customerCount, orderCount, Duration.ofNanos(System.nanoTime() - started).toMillis());
        seedDemoFixtures();
    }

    private void seedCustomers() {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 1; i <= customerCount; i++) {
                String id = String.format("C%03d", i);
                customerRepository.save(new Customer(id, "买家" + i, "138" + String.format("%08d", i)));
            }
        });
    }

    private void seedTenantOrders(String tenantId, int seq, int startOffset, int size) {
        TenantContextHolder.set(tenantId, null);
        Random random = new Random(tenantId.hashCode() * 31L);
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < size; i++) {
                String orderNo = String.valueOf(10000 + startOffset + i + 1);
                String customerId = String.format("C%03d", (i * 7 + seq * 13) % customerCount + 1);
                String[] category = CATEGORIES[random.nextInt(CATEGORIES.length)];
                OrderStatus orderStatus = pickStatus(random);
                Instant createdAt = Instant.now().minusSeconds(random.nextInt(30 * 24 * 3600));

                Order order = new Order(orderNo, tenantId, customerId, 1990L + random.nextInt(200000), orderStatus,
                        category[0], category[1], createdAt);
                order.setReceiverName("收件人" + customerId.substring(1));
                order.setReceiverPhone("1380000" + String.format("%04d", i % 10000));
                order.setProvince("浙江省");
                order.setCity("杭州市");
                order.setDistrict("西湖区");
                order.setDetailAddress("文三路 " + (100 + i % 900) + " 号");
                if (category[2] != null && !category[2].isBlank()) {
                    order.setServiceFlags(List.of(category[2]));
                }
                if (orderStatus != OrderStatus.CREATED) {
                    order.setPaidAt(createdAt.plusSeconds(600));
                }
                if (orderStatus == OrderStatus.SHIPPED || orderStatus == OrderStatus.DELIVERED
                        || orderStatus == OrderStatus.COMPLETED) {
                    order.setShippedAt(createdAt.plusSeconds(3600 * 12));
                }
                if (orderStatus == OrderStatus.DELIVERED || orderStatus == OrderStatus.COMPLETED) {
                    order.setDeliveredAt(createdAt.plusSeconds(3600 * 60));
                }
                orderRepository.save(order);

                if (order.getShippedAt() != null) {
                    seedLogistics(tenantId, orderNo, order.getShippedAt(), random);
                }
                if (i % 20 == 0) {
                    couponRepository.save(new Coupon(tenantId, customerId, "RULE_PROMO_" + (i % 4 + 1),
                            20000L, 3000L, "UNUSED"));
                }
                if ((i + 1) % 50 == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
        });
    }

    private void seedLogistics(String tenantId, String orderNo, Instant shippedAt, Random random) {
        seedLogistics(tenantId, orderNo, shippedAt, random, 2 + random.nextInt(3));
    }

    /**
     * 演示固定单（ticket 12/19）：随机造数的下单时间是 0~30 天均匀分布，
     * "订单是否还在 7 天退款窗内"会随启动时刻漂移，演示脚本今天能跑明天就可能被状态校验拒掉。
     * 这四单把四种业务状态各钉死一个，且时间相对 now 计算，任何时刻起栈都成立。
     *
     * <p><b>round32 新增 91001/91002</b>：门面场景是「鲜果驿站的死蟹理赔」，它要的是一条
     * **签收后仍在生鲜理赔窗口内**的单，以及一条**已经超窗**的单——窗口是六小时，所以这两单的
     * 签收时刻必须相对 now 算，不能让它们随进程启动时间漂出窗口。它们归 T002，与上面四单的店铺一致
     * （90003 那单本来就属于生鲜店之外的品类，故不改它：它是状态夹具，不是品类夹具）。
     */
    private static final List<String> DEMO_ORDER_NOS =
            List.of("90001", "90002", "90003", "90004", "91001", "91002");

    /**
     * 演示前复位：删掉固定单产生的退款单、改址流水与物流节点，再按初始状态重建。
     * 只碰这几单，随机造数与真实业务数据不受影响。
     */
    public void resetDemoFixtures() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            String in = String.join(",", java.util.Collections.nCopies(DEMO_ORDER_NOS.size(), "?"));
            Object[] ids = DEMO_ORDER_NOS.toArray();
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("delete from refunds where order_id in (" + in + ")", ids);
                jdbcTemplate.update("delete from order_addresses where order_id in (" + in + ")", ids);
                jdbcTemplate.update("delete from logistics where order_id in (" + in + ")", ids);
                jdbcTemplate.update("delete from orders where id in (" + in + ")", ids);
            });
            seedDemoFixtures();
        } finally {
            TenantContextHolder.clear();
            running.set(false);
        }
    }

    private void seedDemoFixtures() {
        Instant now = Instant.now();
        record Fixture(String tenantId, String orderNo, String status, Instant createdAt, String categoryCode,
                       String categoryName, String serviceFlag, Instant shippedAt, Instant deliveredAt) {
        }
        List<Fixture> fixtures = List.of(
                // T001 星选生活旗舰店：四种业务状态各一单（ticket 12/19 起的既有夹具，原样保留）
                new Fixture("T001", "90001", "PAID", now.minus(Duration.ofDays(2)), "fresh", "生鲜果蔬",
                        "FRESH_GUARANTEE", null, null),
                new Fixture("T001", "90002", "SHIPPED", now.minus(Duration.ofDays(3)), "digital", "数码配件",
                        "SEVEN_DAY_RETURN", now.minus(Duration.ofDays(3)).plusSeconds(3600 * 12), null),
                new Fixture("T001", "90003", "DELIVERED", now.minus(Duration.ofDays(20)), "apparel", "服饰鞋包",
                        "SEVEN_DAY_RETURN", now.minus(Duration.ofDays(20)).plusSeconds(3600 * 12),
                        now.minus(Duration.ofDays(20)).plusSeconds(3600 * 60)),
                new Fixture("T001", "90004", "CREATED", now.minus(Duration.ofHours(3)), "home", "家居日用",
                        "SEVEN_DAY_RETURN", null, null),
                // T002 鲜果驿站：门面场景的两张单——一张签收 2 小时（六小时理赔窗口内），一张签收 28 小时（已超窗）
                new Fixture("T002", "91001", "DELIVERED", now.minus(Duration.ofHours(8)), "fresh", "生鲜果蔬",
                        "FRESH_GUARANTEE", now.minus(Duration.ofHours(7)), now.minus(Duration.ofHours(2))),
                new Fixture("T002", "91002", "DELIVERED", now.minus(Duration.ofHours(34)), "fresh", "生鲜果蔬",
                        "FRESH_GUARANTEE", now.minus(Duration.ofHours(33)), now.minus(Duration.ofHours(28))));
        for (String tenantId : List.of("T001", "T002")) {
            TenantContextHolder.set(tenantId, DEMO_CUSTOMER);
            List<Fixture> owned = fixtures.stream().filter(f -> f.tenantId().equals(tenantId)).toList();
            transactionTemplate.executeWithoutResult(status -> {
                for (Fixture fixture : owned) {
                    Order order = new Order(fixture.orderNo(), tenantId, DEMO_CUSTOMER, 19900L,
                            OrderStatus.valueOf(fixture.status()), fixture.categoryCode(), fixture.categoryName(),
                            fixture.createdAt());
                    order.setReceiverName("演示买家");
                    order.setReceiverPhone("13800001234");
                    order.setProvince("浙江省");
                    order.setCity("杭州市");
                    order.setDistrict("西湖区");
                    order.setDetailAddress("文三路 1 号");
                    order.setServiceFlags(List.of(fixture.serviceFlag()));
                    if (order.getStatus() != OrderStatus.CREATED) {
                        order.setPaidAt(fixture.createdAt().plusSeconds(600));
                    }
                    if (fixture.shippedAt() != null) {
                        order.setShippedAt(fixture.shippedAt());
                        seedLogistics(tenantId, fixture.orderNo(), fixture.shippedAt(), new Random(7), 4);
                    }
                    if (fixture.deliveredAt() != null) {
                        order.setDeliveredAt(fixture.deliveredAt());
                    }
                    orderRepository.save(order);
                }
            });
        }
        TenantContextHolder.clear();
        log.info("演示固定单就绪：{}（买家 {}）", fixtures.stream().map(Fixture::orderNo).toList(), DEMO_CUSTOMER);
    }

    private void seedLogistics(String tenantId, String orderNo, Instant shippedAt, Random random, int count) {
        String[] carrier = CARRIERS[random.nextInt(CARRIERS.length)];
        String trackingNo = carrier[0] + System.nanoTime() % 1000000000L;
        String[][] nodes = {
                {"GOT", "商品已从杭州仓发出"},
                {"TRANSPORT", "快件已到达杭州转运中心"},
                {"DELIVERING", "派件员正在为您派送"},
                {"SIGNED", "快件已被签收"}};
        for (int n = 0; n < count; n++) {
            logisticsRepository.save(new LogisticsNode(tenantId, orderNo, carrier[0], carrier[1], trackingNo,
                    n + 1, nodes[n][0], nodes[n][1], shippedAt.plusSeconds(3600L * (n + 1))));
        }
    }

    private OrderStatus pickStatus(Random random) {
        int roll = random.nextInt(100);
        if (roll < 8) {
            return OrderStatus.CREATED;
        }
        if (roll < 26) {
            return OrderStatus.PAID;
        }
        if (roll < 56) {
            return OrderStatus.SHIPPED;
        }
        if (roll < 81) {
            return OrderStatus.DELIVERED;
        }
        if (roll < 95) {
            return OrderStatus.COMPLETED;
        }
        return OrderStatus.CANCELLED;
    }
}
