package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.AddressHistory;
import com.shoppilot.bizmock.domain.LogisticsNode;
import com.shoppilot.bizmock.domain.Order;
import com.shoppilot.bizmock.domain.Refund;
import com.shoppilot.bizmock.audit.AuditService;
import com.shoppilot.bizmock.fault.FaultInjector;
import com.shoppilot.bizmock.repo.AddressHistoryRepository;
import com.shoppilot.bizmock.repo.LogisticsRepository;
import com.shoppilot.bizmock.repo.OrderRepository;
import com.shoppilot.bizmock.repo.RefundRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.workitem.TicketSource;
import com.shoppilot.bizmock.workitem.WorkItemClient;
import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import com.shoppilot.tool.view.AddressView;
import com.shoppilot.tool.view.LogisticsNodeView;
import com.shoppilot.tool.view.LogisticsView;
import com.shoppilot.tool.view.ModifyAddressView;
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

    /** 工单出口（round23 票 72）：工单数据在工单服务，这里只能调过去。 */
    private final WorkItemClient workItemClient;

    /** 审计（round23 票 71）：退款放行/驳回是唯一不可逆的资金迁移，必须留痕。 */
    private final AuditService auditService;

    public BizMockService(OrderRepository orderRepository, LogisticsRepository logisticsRepository,
                          AddressHistoryRepository addressHistoryRepository, RefundRepository refundRepository,
                          FaultInjector faultInjector,
                          TransactionTemplate transactionTemplate, WorkItemClient workItemClient,
                          AuditService auditService) {
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.addressHistoryRepository = addressHistoryRepository;
        this.refundRepository = refundRepository;
        this.faultInjector = faultInjector;
        this.transactionTemplate = transactionTemplate;
        this.workItemClient = workItemClient;
        this.auditService = auditService;
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
        // 在同一个事务里 catch 住继续查会拿到半死的 Session，50 并发下直接炸给调用方
        final String orderId = order.getId();
        try {
            Refund saved = transactionTemplate.execute(status -> {
                Refund inserted = refundRepository.save(new Refund(TenantContextHolder.tenantId(), orderId,
                        order.getCustomerId(), amount, reason, idempotencyToken, PENDING_REVIEW,
                        Instant.now()));
                // 受理即开审批工单（round23 票 69/72）：退款从这一刻起就是一个待人工处理的工作项。
                openRefundApprovalWorkItem(inserted, orderId, order.getCustomerId());
                refundRepository.save(inserted);
                order.setStatus(OrderStatus.REFUNDING);
                orderRepository.save(order);
                return inserted;
            });
            // 受理不是放行（ADR 0047）：资金要人工审核后才动。订单已在同一事务里置 REFUNDING
            // （受理即冻结后续改动），所以"同一订单只允许一笔在办退款"由既有的 STATE_NOT_ALLOWED 分支守住。
            return ToolResponse.pendingApproval(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
        } catch (DataIntegrityViolationException raceWithConcurrentSubmit) {
            return transactionTemplate.execute(status ->
                    refundRepository.findByOrderIdAndIdempotencyToken(orderId, idempotencyToken)
                            .map(winner -> replay(ToolName.APPLY_REFUND, winner))
                            .orElseGet(() -> ToolResponse.failure(ToolName.APPLY_REFUND.apiName(),
                                    ToolStatus.IDEMPOTENT_REPLAY, "重复提交已被唯一约束拦截", List.of())));
        }
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
            // 放行是唯一不可逆的资金迁移，必须留痕（ADR 0056）：谁在什么时候放行了哪一笔
            auditService.publish(AuditActions.REFUND_APPROVED, "REFUND", String.valueOf(saved.getId()),
                    reviewer, "退款放行，金额 " + saved.getAmountFen() + " 分");
            return ToolResponse.ok(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
        }
        if ("REJECT".equalsIgnoreCase(decision)) {
            refund.setStatus(REJECTED);
            Refund saved = refundRepository.save(refund);
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

    /**
     * 落一张退款审批工作项（round23 票 72）。
     *
     * <p>工单数据搬到工单服务之后，这里**不再持有** {@code tickets} 表，退款受理只能经
     * {@link WorkItemClient} 调过去。这一步因此跨进程、不再与退款插入同事务——
     * 处置与边界写在这里：<b>审批的真源是 {@code refunds.PENDING_REVIEW}（审核队列读它），
     * 工单是受理侧的工作项</b>。所以开单失败不会动资金状态，只留下一张没人处理的退款；
     * 为此本次受理按**已受理**继续推进并 warn——宁可让队列里多一张单，也不要让买家的退款卡住。
     *
     * <p>回指 {@code refunds.ticket_id} 只在拿到工单号时写；拿不到就留空，
     * 「查不到工单号」比「指向一个不存在的号」诚实。
     */
    private void openRefundApprovalWorkItem(Refund refund, String orderId, String customerId) {
        String ticketId = workItemClient.create(TicketSource.REFUND_APPROVAL, customerId,
                "REFUND_APPROVAL", "订单 " + orderId + " 的退款待人工审核", "", null,
                "{\"refundId\":\"" + refund.getId() + "\",\"orderId\":\"" + orderId + "\"}");
        if (ticketId == null) {
            log.warn("退款 {} 的审批工单没开成；审核队列仍可处理（真源是 refunds 表）", refund.getId());
            return;
        }
        refund.setTicketId(ticketId);
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
