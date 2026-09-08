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
import com.shoppilot.bizmock.repo.TicketRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.view.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幂等 seed：已有数据即跳过，重复调用不翻倍（ticket 03 验收项）。
 *
 * <p>实体带 {@code @TenantId}，插入时的归属列取自当前租户上下文，
 * 因此必须按租户分批、每批切换上下文，不能在一个事务里混写多个租户。
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private static final List<String[]> TENANTS = List.of(
            new String[]{"T001", "数码旗舰店", "200"},
            new String[]{"T002", "生鲜超市", "300"},
            new String[]{"T003", "服饰官方店", "100"});

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
    private final TicketRepository ticketRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${shoppilot.bizmock.seed.customers:200}")
    private int customerCount;

    @Value("${shoppilot.bizmock.seed.orders:50000}")
    private int orderCount;

    @PersistenceContext
    private EntityManager entityManager;

    public SeedRunner(TenantRepository tenantRepository, CustomerRepository customerRepository,
                      OrderRepository orderRepository, LogisticsRepository logisticsRepository,
                      CouponRepository couponRepository, AddressHistoryRepository addressHistoryRepository,
                      RefundRepository refundRepository, TicketRepository ticketRepository,
                      JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.tenantRepository = tenantRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.couponRepository = couponRepository;
        this.addressHistoryRepository = addressHistoryRepository;
        this.refundRepository = refundRepository;
        this.ticketRepository = ticketRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    public void seedIfEmpty() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Long existing = jdbcTemplate.queryForObject("select count(*) from orders", Long.class);
            if (existing != null && existing > 0) {
                log.info("seed 跳过：orders 已有 {} 行", existing);
                return;
            }
            long started = System.nanoTime();
            seedMasterData();
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
        } finally {
            TenantContextHolder.clear();
            running.set(false);
        }
    }

    private void seedMasterData() {
        transactionTemplate.executeWithoutResult(status -> {
            for (String[] tenant : TENANTS) {
                tenantRepository.save(new Tenant(tenant[0], tenant[1], Integer.parseInt(tenant[2])));
            }
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
     */
    private static final List<String> DEMO_ORDER_NOS = List.of("90001", "90002", "90003", "90004");

    /**
     * 演示前复位：删掉固定单产生的退款单、改址流水与物流节点，再按初始状态重建。
     * 只碰这四单，随机造数与真实业务数据不受影响。
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
        record Fixture(String orderNo, String status, Instant createdAt, String categoryCode, String categoryName,
                       String serviceFlag) {
        }
        List<Fixture> fixtures = List.of(
                new Fixture("90001", "PAID", now.minus(Duration.ofDays(2)), "fresh", "生鲜果蔬", "FRESH_GUARANTEE"),
                new Fixture("90002", "SHIPPED", now.minus(Duration.ofDays(3)), "digital", "数码配件", "SEVEN_DAY_RETURN"),
                new Fixture("90003", "DELIVERED", now.minus(Duration.ofDays(20)), "apparel", "服饰鞋包", "SEVEN_DAY_RETURN"),
                new Fixture("90004", "CREATED", now.minus(Duration.ofHours(3)), "home", "家居日用", "SEVEN_DAY_RETURN"));
        TenantContextHolder.set("T001", DEMO_CUSTOMER);
        transactionTemplate.executeWithoutResult(status -> {
            for (Fixture fixture : fixtures) {
                Order order = new Order(fixture.orderNo(), "T001", DEMO_CUSTOMER, 19900L,
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
                if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
                    order.setShippedAt(fixture.createdAt().plusSeconds(3600 * 12));
                    seedLogistics("T001", fixture.orderNo(), order.getShippedAt(), new Random(7), 4);
                }
                if (order.getStatus() == OrderStatus.DELIVERED) {
                    order.setDeliveredAt(fixture.createdAt().plusSeconds(3600 * 60));
                }
                orderRepository.save(order);
            }
        });
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
