package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.AddressHistory;
import com.shoppilot.bizmock.domain.LogisticsNode;
import com.shoppilot.bizmock.domain.Order;
import com.shoppilot.bizmock.domain.Refund;
import com.shoppilot.bizmock.audit.AuditService;
import com.shoppilot.bizmock.fault.FaultInjector;
import com.shoppilot.bizmock.refundoutbox.RefundWorkItemOutboxRepository;
import com.shoppilot.bizmock.refundoutbox.RefundWorkItemProcessor;
import com.shoppilot.bizmock.repo.AddressHistoryRepository;
import com.shoppilot.bizmock.repo.LogisticsRepository;
import com.shoppilot.bizmock.repo.OrderRepository;
import com.shoppilot.bizmock.repo.RefundRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import com.shoppilot.tool.view.AddressView;
import com.shoppilot.tool.view.LogisticsNodeView;
import com.shoppilot.tool.view.LogisticsView;
import com.shoppilot.tool.view.ModifyAddressView;
import com.shoppilot.tool.view.OrderDetailView;
import com.shoppilot.tool.view.OrderLogisticsView;
import com.shoppilot.tool.view.OrderStatus;
import com.shoppilot.tool.view.OrderView;
import com.shoppilot.tool.view.RefundReviewState;
import com.shoppilot.tool.view.RefundView;
import com.shoppilot.tool.view.ToolResponse;
import com.shoppilot.tool.view.ToolStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 四个原子业务动作 + 工单。业务规则（状态前置校验、归属、幂等兜底）全部在这里强制，
 * 网关侧不重复实现，避免两处规则漂移。
 */
@Service
public class BizMockService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BizMockService.class);

    /** 退款时限：下单后 7 天内。 */
    private static final Duration REFUND_WINDOW = Duration.ofDays(7);

    private final OrderRepository orderRepository;
    private final LogisticsRepository logisticsRepository;
    private final AddressHistoryRepository addressHistoryRepository;
    private final RefundRepository refundRepository;
    private final FaultInjector faultInjector;
    private final TransactionTemplate transactionTemplate;
    private final RefundWorkItemOutboxRepository refundOutbox;
    private final RefundWorkItemProcessor refundWorkItemProcessor;

    /** 审计（round23 票 71）：退款放行/驳回是唯一不可逆的资金迁移，必须留痕。 */
    private final AuditService auditService;

    public BizMockService(OrderRepository orderRepository, LogisticsRepository logisticsRepository,
                          AddressHistoryRepository addressHistoryRepository, RefundRepository refundRepository,
                          FaultInjector faultInjector,
                          TransactionTemplate transactionTemplate,
                          RefundWorkItemOutboxRepository refundOutbox,
                          RefundWorkItemProcessor refundWorkItemProcessor,
                          AuditService auditService) {
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.addressHistoryRepository = addressHistoryRepository;
        this.refundRepository = refundRepository;
        this.faultInjector = faultInjector;
        this.transactionTemplate = transactionTemplate;
        this.refundOutbox = refundOutbox;
        this.refundWorkItemProcessor = refundWorkItemProcessor;
        this.auditService = auditService;
    }

    /** 买家自己的最近 20 单（只读）。没有买家上下文时返回空集，不越权查全租户。 */
    @Transactional(readOnly = true)
    public List<OrderView> myOrders() {
        String customerId = TenantContextHolder.customerId();
        if (customerId == null) {
            return List.of();
        }
        return orderRepository.findTop20ByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ToolResponse<OrderView> queryOrderDetail(String orderNo) {
        faultInjector.applyDelay();
        if (faultInjector.shouldFail()) {
            return unavailable(ToolName.QUERY_ORDER_DETAIL);
        }
        return findOwned(orderNo)
                .map(this::orderDetailResponse)
                .orElseGet(() -> notFound(ToolName.QUERY_ORDER_DETAIL));
    }

    /**
     * 订单详情的回执（票 60）：带上退款审核态；有在办退款时把**到账边界**写进 {@code message}
     * —— 模型据它组织买家读回的话术，让"已放行"与"到账"不再混为一谈（ADR 0047 决策六）。
     */
    private ToolResponse<OrderView> orderDetailResponse(Order order) {
        OrderView view = toView(order);
        String note = refundBoundaryNote(view.refundReview());
        return note == null
                ? ToolResponse.ok(ToolName.QUERY_ORDER_DETAIL.apiName(), view)
                : new ToolResponse<>(ToolName.QUERY_ORDER_DETAIL.apiName(), ToolStatus.OK, view, note, List.of());
    }

    /** 到账边界话术：明确写「到账由支付渠道处理」，因为到账的权威在支付通道，本仓没有（ADR 0047）。 */
    private static String refundBoundaryNote(RefundReviewState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case PENDING_REVIEW -> "该订单的退款申请已受理，正在等待人工审核；审核通过后到账由支付渠道处理。";
            case RELEASED -> "该订单的退款申请已放行，资金处理中，到账由支付渠道处理。";
            case REJECTED -> "该订单的退款申请已被驳回，订单已恢复原状态。";
        };
    }

    @Transactional(readOnly = true)
    public ToolResponse<LogisticsView> queryLogistics(String orderNo) {
        faultInjector.applyDelay();
        if (faultInjector.shouldFail()) {
            return unavailable(ToolName.QUERY_LOGISTICS);
        }
        Optional<Order> owned = findOwned(orderNo);
        if (owned.isEmpty()) {
            return notFound(ToolName.QUERY_LOGISTICS);
        }
        Order order = owned.get();
        if (order.getStatus() == OrderStatus.CREATED || order.getStatus() == OrderStatus.PAID) {
            return ToolResponse.failure(ToolName.QUERY_LOGISTICS.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "订单尚未发货，暂无物流轨迹", List.of(ToolName.QUERY_ORDER_DETAIL.apiName()));
        }
        List<LogisticsNode> nodes = logisticsRepository.findByOrderIdOrderBySeqAsc(order.getId());
        if (nodes.isEmpty()) {
            // 订单在这里是查到了的，回 NOT_FOUND 会让助手对顾客说"可能不是本店下单"——那是撒谎。
            // 退款中/刚出库还没落节点都会走到这里，说清状态并指向订单详情（dev 评测 ACT-ORD-17 实测）。
            return ToolResponse.failure(ToolName.QUERY_LOGISTICS.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "订单 " + orderNo + " 当前状态为 " + order.getStatus() + "，暂无物流轨迹节点",
                    List.of(ToolName.QUERY_ORDER_DETAIL.apiName()));
        }
        LogisticsNode first = nodes.get(0);
        LogisticsView view = new LogisticsView(order.getId(), first.getCpCode(), first.getCpName(), first.getTrackingNo(),
                nodes.stream().map(n -> new LogisticsNodeView(n.getSeq(), n.getNodeCode(), n.getDescription(), n.getOccurredAt())).toList());
        return ToolResponse.ok(ToolName.QUERY_LOGISTICS.apiName(), view);
    }

    /**
     * 买家端订单详情（round44 票 189）。**只认带买家上下文的调用**：没有买家号时返回空，
     * 不像工具路径那样回退到"整店可查"——那个回退是给坐席用的，买家入口不能继承它。
     *
     * <p>{@code receiverPhone} 在这里脱敏后才出站。前端脱敏只是把原文换个地方放：
     * 明文照样过网、照样进浏览器内存。
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetailView> buyerOrderDetail(String orderNo) {
        return buyerOwned(orderNo).map(order -> new OrderDetailView(
                order.getId(), order.getAmountFen(), order.getStatus(), order.getCategoryName(),
                order.serviceFlagList(),
                new AddressView(order.getReceiverName(), maskPhone(order.getReceiverPhone()),
                        order.getProvince(), order.getCity(), order.getDistrict(), order.getDetailAddress()),
                order.getAddressVersion(), order.getCreatedAt(), order.getPaidAt(),
                order.getShippedAt(), order.getDeliveredAt(), latestRefundReviewState(order)));
    }

    /**
     * 买家端物流轨迹（round44 票 189）：节点按发生时间**倒序**，最新一条在最前。
     *
     * <p>订单存在但还没有轨迹时返回空 {@code nodes} 而不是空 Optional——调用方据此画"暂无轨迹"
     * 的空态，而不是对买家说"查不到这单"。
     */
    @Transactional(readOnly = true)
    public Optional<OrderLogisticsView> buyerOrderLogistics(String orderNo) {
        return buyerOwned(orderNo).map(order -> {
            List<LogisticsNode> nodes = logisticsRepository.findByOrderIdOrderBySeqAsc(order.getId());
            List<LogisticsNodeView> ordered = nodes.stream()
                    .sorted(Comparator.comparing(LogisticsNode::getOccurredAt)
                            .thenComparingInt(LogisticsNode::getSeq)
                            .reversed())
                    .map(n -> new LogisticsNodeView(n.getSeq(), n.getNodeCode(), n.getDescription(), n.getOccurredAt()))
                    .toList();
            LogisticsNode head = nodes.isEmpty() ? null : nodes.get(0);
            return new OrderLogisticsView(order.getId(),
                    head == null ? null : head.getCpCode(),
                    head == null ? null : head.getCpName(),
                    head == null ? null : head.getTrackingNo(),
                    ordered);
        });
    }

    /** 买家入口的归属：两个条件都必填，缺买家号就当作不存在（不确认存在）。 */
    private Optional<Order> buyerOwned(String orderNo) {
        String customerId = TenantContextHolder.customerId();
        if (customerId == null || customerId.isBlank() || orderNo == null || orderNo.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findByIdAndCustomerId(orderNo, customerId);
    }

    /**
     * 手机号脱敏：留前 3 后 4，中间固定四个星号（{@code 138****5678}）。
     *
     * <p>不做成"按长度保留百分比"：座席与买家看到的形状必须稳定，否则截图与工单粘贴
     * 里的号码对不上。短到无法保留前后段时整串打码，绝不回显原文。
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.trim();
        if (digits.length() >= 7) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    @Transactional
    public ToolResponse<ModifyAddressView> modifyDeliveryAddress(String orderNo, ModifyDeliveryAddressRequest request) {
        faultInjector.applyDelay();
        if (faultInjector.shouldFail()) {
            return unavailable(ToolName.MODIFY_DELIVERY_ADDRESS);
        }
        Optional<Order> owned = findOwned(orderNo);
        if (owned.isEmpty()) {
            return notFound(ToolName.MODIFY_DELIVERY_ADDRESS);
        }
        Order order = owned.get();
        if (!order.getStatus().addressModifiable()) {
            return ToolResponse.failure(ToolName.MODIFY_DELIVERY_ADDRESS.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "订单当前状态为 " + order.getStatus() + "，已发货后无法修改收货地址",
                    List.of(ToolName.QUERY_LOGISTICS.apiName(), ToolName.APPLY_REFUND.apiName()));
        }
        AddressView before = new AddressView(order.getReceiverName(), order.getReceiverPhone(), order.getProvince(),
                order.getCity(), order.getDistrict(), order.getDetailAddress());
        // 地址四段可以留空（"只换个收件人"这类高频诉求），留空 = 沿用原值；
        // 写历史与回给模型的都是合并后的完整地址，避免半条地址落库。
        String receiverName = keepIfBlank(request.receiverName(), order.getReceiverName());
        String receiverPhone = keepIfBlank(request.receiverPhone(), order.getReceiverPhone());
        String province = keepIfBlank(request.province(), order.getProvince());
        String city = keepIfBlank(request.city(), order.getCity());
        String district = keepIfBlank(request.district(), order.getDistrict());
        String detailAddress = keepIfBlank(request.detailAddress(), order.getDetailAddress());
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setProvince(province);
        order.setCity(city);
        order.setDistrict(district);
        order.setDetailAddress(detailAddress);
        int version = order.bumpAddressVersion();
        orderRepository.save(order);
        addressHistoryRepository.save(new AddressHistory(TenantContextHolder.tenantId(), order.getId(),
                receiverName, receiverPhone, province, city, district, detailAddress, version, Instant.now()));
        AddressView after = new AddressView(receiverName, receiverPhone, province, city, district, detailAddress);
        return ToolResponse.ok(ToolName.MODIFY_DELIVERY_ADDRESS.apiName(), new ModifyAddressView(order.getId(), before, after, version));
    }

    /**
     * 退款幂等：先查已有记录，再靠 {@code (order_id, idempotency_token)} 唯一约束兜底。
     * 并发下两条同时插入时，第二条会抛 DataIntegrityViolationException，转为重放返回。
     */
    public ToolResponse<RefundView> applyRefund(String orderNo, ApplyRefundRequest request, String idempotencyToken) {
        faultInjector.applyDelay();
        if (faultInjector.shouldFail()) {
            return unavailable(ToolName.APPLY_REFUND);
        }
        Optional<Order> owned = findOwned(orderNo);
        if (owned.isEmpty()) {
            return notFound(ToolName.APPLY_REFUND);
        }
        Order order = owned.get();

        Optional<Refund> existing = refundRepository.findByOrderIdAndIdempotencyToken(order.getId(), idempotencyToken);
        if (existing.isPresent()) {
            return replay(ToolName.APPLY_REFUND, existing.get());
        }

        if (!order.getStatus().refundable()) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "订单当前状态为 " + order.getStatus() + "，不支持发起退款",
                    List.of(ToolName.QUERY_ORDER_DETAIL.apiName(), ToolName.QUERY_LOGISTICS.apiName()));
        }
        if (Duration.between(order.getCreatedAt(), Instant.now()).compareTo(REFUND_WINDOW) > 0) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "已超过 7 天退款时限", List.of(ToolName.QUERY_ORDER_DETAIL.apiName()));
        }

        long amount = request.amountFen() == null ? order.getAmountFen() : request.amountFen();
        // reason 在 schema 里是可选的：用户只说"这单退款"时不该被反问原因，落一个业务上成立的默认值
        String reason = keepIfBlank(request.reason(), "买家主观原因");
        if (amount > order.getAmountFen()) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "退款金额不得超过订单实付金额", List.of());
        }
        // 插入必须独占一个事务：约束冲突后当前事务已被标记 rollback-only，
        // 在同一个事务里 catch 住继续查会拿到半死的 Session，50 并发下直接炸给调用方。
        // 2026-10-22 评审 P0：DB 层加在办唯一键（active_order_id），并发双退由数据库兜底。
        // round63 票 234：outbox 意图与退款、订单冻结同事务；跨进程开单放到提交后补偿。
        final String orderId = order.getId();
        final String tenantId = TenantContextHolder.tenantId();
        final Instant acceptedAt = Instant.now();
        final Refund saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Refund inserted = refundRepository.saveAndFlush(new Refund(tenantId, orderId,
                        order.getCustomerId(), amount, reason, idempotencyToken, PENDING_REVIEW,
                        acceptedAt));
                order.setStatus(OrderStatus.REFUNDING);
                orderRepository.save(order);
                refundOutbox.enqueue(tenantId, inserted.getId(), orderId, order.getCustomerId(), acceptedAt);
                return inserted;
            });
        } catch (DataIntegrityViolationException raceWithConcurrentSubmit) {
            return refundRepository.findByOrderIdAndIdempotencyToken(orderId, idempotencyToken)
                    .<ToolResponse<RefundView>>map(winner -> replay(ToolName.APPLY_REFUND, winner))
                    .orElseGet(() -> refundRepository.findByActiveOrderId(orderId)
                            .<ToolResponse<RefundView>>map(active -> ToolResponse.failure(
                                    ToolName.APPLY_REFUND.apiName(),
                                    ToolStatus.STATE_NOT_ALLOWED,
                                    "该订单已有一笔退款在办，不能重复发起", List.of()))
                            .orElseThrow(() -> raceWithConcurrentSubmit));
        }
        try {
            refundWorkItemProcessor.processRefund(saved.getId(), tenantId, Instant.now());
        } catch (RuntimeException compensationFailure) {
            // 受理已提交，开单失败只能留给补偿器；这里绝不能再抛给买家。
            log.warn("退款 {} 的审批工单立即补偿失败，等待下一轮重试: {}",
                    saved.getId(), compensationFailure.getMessage());
        }
        // 受理不是放行（ADR 0047）：资金要人工审核后才动。订单已在同一事务里置 REFUNDING，
        // 在办唯一键由 active_order_id 唯一索引保证；网关的订单锁是第二道，不是唯一一道。
        return ToolResponse.pendingApproval(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
    }

    /** 退款单状态：受理后待人工审核。 */
    private static final String PENDING_REVIEW = "PENDING_REVIEW";
    /** 审核放行后进入资金处理。 */
    private static final String PROCESSING = "PROCESSING";
    /** 审核驳回。 */
    private static final String REJECTED = "REJECTED";

    /** 审核队列（ADR 0047）：只列待审核的退款单，租户由仓储的 {@code @TenantId} 谓词兜住。 */
    @Transactional(readOnly = true)
    public List<RefundView> listPendingRefunds() {
        return refundRepository.findByStatusOrderByCreatedAtAsc(PENDING_REVIEW).stream()
                .map(this::toRefundView).toList();
    }

    /**
     * 人工审核退款申请（ADR 0047）：受理与放行拆成两态，资金放行必须由人触发。
     *
     * <p>{@code APPROVE} 把 {@code PENDING_REVIEW → PROCESSING}（**唯一不可逆迁移**）；
     * {@code REJECT} 置 {@code REJECTED} 并按订单已落的时间戳**推导**回滚（不加 {@code prior_status} 列、
     * 不做迁移）—— 回滚后订单回到 {@code PAID|SHIPPED|DELIVERED}，买家可再申请。
     * 已审过的再审一律 {@code STATE_NOT_ALLOWED}。
     *
     * <p>{@code note} 不落库：驳回理由在 v1 不承诺给买家，而给审核者自己看的理由没有消费者；
     * "审核发生过"由状态迁移 + 指标 + request-id 日志证明（ADR 0047 决策四）。
     */
    @Transactional
    public ToolResponse<RefundView> reviewRefund(String refundId, String decision, String note,
                                              Actor reviewer) {
        Long id = parseRefundId(refundId);
        if (id == null) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.NOT_FOUND,
                    "未找到该退款申请", List.of());
        }
        Optional<Refund> found = refundRepository.findById(id);
        if (found.isEmpty()) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.NOT_FOUND,
                    "未找到该退款申请", List.of());
        }
        Refund refund = found.get();
        if (!TenantContextHolder.tenantId().equals(refund.getTenantId())) {
            // find(id) 不拼接 @TenantId 谓词（只有查询会），所以这里显式比对一次——
            // 与订单归属同一口径：跨租户一律归入 NOT_FOUND，不区分"存在但不可见"。
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.NOT_FOUND,
                    "未找到该退款申请", List.of());
        }
        if (!PENDING_REVIEW.equals(refund.getStatus())) {
            return ToolResponse.failure(ToolName.APPLY_REFUND.apiName(), ToolStatus.STATE_NOT_ALLOWED,
                    "该退款申请已审核过，当前状态为 " + refund.getStatus(), List.of());
        }
        if ("APPROVE".equalsIgnoreCase(decision)) {
            refund.setStatus(PROCESSING);
            Refund saved = refundRepository.save(refund);
            refundOutbox.cancelByRefundId(saved.getId(), TenantContextHolder.tenantId(), Instant.now());
            // 放行是唯一不可逆的资金迁移，必须留痕（ADR 0056）：谁在什么时候放行了哪一笔
            auditService.publish(AuditActions.REFUND_APPROVED, "REFUND", String.valueOf(saved.getId()),
                    reviewer, "退款放行，金额 " + saved.getAmountFen() + " 分");
            return ToolResponse.ok(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
        }
        if ("REJECT".equalsIgnoreCase(decision)) {
            refund.setStatus(REJECTED);
            Refund saved = refundRepository.save(refund);
            refundOutbox.cancelByRefundId(saved.getId(), TenantContextHolder.tenantId(), Instant.now());
            rollbackOrder(refund.getOrderId());
            auditService.publish(AuditActions.REFUND_REJECTED, "REFUND", String.valueOf(saved.getId()),
                    reviewer, note == null || note.isBlank() ? "驳回，未填原因" : "驳回：" + note);
            return ToolResponse.ok(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
        }
        throw new IllegalArgumentException("未知审核决定 " + decision + "，可选 APPROVE / REJECT");
    }

    /**
     * 驳回回滚：先前状态由订单已落的时间戳**无损推导**（ADR 0047 决策四）。
     * 退款只对 {@code PAID|SHIPPED|DELIVERED} 开放，故这四个分支覆盖全部合法入手态。
     */
    private void rollbackOrder(String orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(derivePriorStatus(order));
            orderRepository.save(order);
        });
    }

    private static OrderStatus derivePriorStatus(Order order) {
        if (order.getDeliveredAt() != null) {
            return OrderStatus.DELIVERED;
        }
        if (order.getShippedAt() != null) {
            return OrderStatus.SHIPPED;
        }
        if (order.getPaidAt() != null) {
            return OrderStatus.PAID;
        }
        return OrderStatus.CREATED;
    }

    private static Long parseRefundId(String refundId) {
        if (refundId == null || refundId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(refundId.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 可选参数留空 = 不改这一项；退款原因的默认值见 applyRefund。 */
    private static String keepIfBlank(String incoming, String current) {
        return incoming == null || incoming.isBlank() ? current : incoming.trim();
    }

    /** 归属双条件的第二条件在这里；第一条件由 @TenantId 自动拼接。 */
    private Optional<Order> findOwned(String orderNo) {
        String customerId = TenantContextHolder.customerId();
        if (customerId == null) {
            return orderRepository.findById(orderNo);
        }
        return orderRepository.findByIdAndCustomerId(orderNo, customerId);
    }

    private OrderView toView(Order order) {
        return new OrderView(order.getId(), order.getAmountFen(), order.getStatus(), order.getCategoryName(),
                order.serviceFlagList(),
                new AddressView(order.getReceiverName(), order.getReceiverPhone(), order.getProvince(),
                        order.getCity(), order.getDistrict(), order.getDetailAddress()),
                order.getCreatedAt(), order.getPaidAt(), order.getShippedAt(), latestRefundReviewState(order));
    }

    /** 订单最近一笔退款的审核态（票 60）；无退款、或退款处于未知状态时返回 null。 */
    private RefundReviewState latestRefundReviewState(Order order) {
        return refundRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId())
                .map(refund -> switch (refund.getStatus()) {
                    case PENDING_REVIEW -> RefundReviewState.PENDING_REVIEW;
                    case PROCESSING -> RefundReviewState.RELEASED;
                    case REJECTED -> RefundReviewState.REJECTED;
                    default -> null;
                })
                .orElse(null);
    }

    private RefundView toRefundView(Refund refund) {
        return new RefundView(String.valueOf(refund.getId()), refund.getOrderId(), refund.getAmountFen(),
                refund.getStatus(), refund.getCreatedAt(), refund.getReason());
    }


    private ToolResponse<RefundView> replay(ToolName tool, Refund refund) {
        return new ToolResponse<>(tool.apiName(), ToolStatus.IDEMPOTENT_REPLAY, toRefundView(refund),
                "该请求已处理过，返回首次执行结果", List.of());
    }

    private <T> ToolResponse<T> notFound(ToolName tool) {
        return ToolResponse.failure(tool.apiName(), ToolStatus.NOT_FOUND,
                "未在本店找到该订单，若您在其他店铺购买请联系对应店铺客服", List.of());
    }

    private <T> ToolResponse<T> unavailable(ToolName tool) {
        return ToolResponse.failure(tool.apiName(), ToolStatus.UNAVAILABLE, "业务系统暂时不可用", List.of());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
