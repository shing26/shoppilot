package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.AddressHistory;
import com.shoppilot.bizmock.domain.LogisticsNode;
import com.shoppilot.bizmock.domain.Order;
import com.shoppilot.bizmock.domain.Refund;
import com.shoppilot.bizmock.domain.Ticket;
import com.shoppilot.bizmock.fault.FaultInjector;
import com.shoppilot.bizmock.repo.AddressHistoryRepository;
import com.shoppilot.bizmock.repo.LogisticsRepository;
import com.shoppilot.bizmock.repo.OrderRepository;
import com.shoppilot.bizmock.repo.RefundRepository;
import com.shoppilot.bizmock.repo.TicketRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import com.shoppilot.tool.view.AddressView;
import com.shoppilot.tool.view.LogisticsNodeView;
import com.shoppilot.tool.view.LogisticsView;
import com.shoppilot.tool.view.ModifyAddressView;
import com.shoppilot.tool.view.OrderStatus;
import com.shoppilot.tool.view.OrderView;
import com.shoppilot.tool.view.RefundView;
import com.shoppilot.tool.view.TicketView;
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

    /** 退款时限：下单后 7 天内。 */
    private static final Duration REFUND_WINDOW = Duration.ofDays(7);

    private final OrderRepository orderRepository;
    private final LogisticsRepository logisticsRepository;
    private final AddressHistoryRepository addressHistoryRepository;
    private final RefundRepository refundRepository;
    private final TicketRepository ticketRepository;
    private final FaultInjector faultInjector;
    private final TransactionTemplate transactionTemplate;

    public BizMockService(OrderRepository orderRepository, LogisticsRepository logisticsRepository,
                          AddressHistoryRepository addressHistoryRepository, RefundRepository refundRepository,
                          TicketRepository ticketRepository, FaultInjector faultInjector,
                          TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.addressHistoryRepository = addressHistoryRepository;
        this.refundRepository = refundRepository;
        this.ticketRepository = ticketRepository;
        this.faultInjector = faultInjector;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public ToolResponse<OrderView> queryOrderDetail(String orderNo) {
        faultInjector.applyDelay();
        if (faultInjector.shouldFail()) {
            return unavailable(ToolName.QUERY_ORDER_DETAIL);
        }
        return findOwned(orderNo)
                .map(order -> ToolResponse.ok(ToolName.QUERY_ORDER_DETAIL.apiName(), toView(order)))
                .orElseGet(() -> notFound(ToolName.QUERY_ORDER_DETAIL));
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
            return notFound(ToolName.QUERY_LOGISTICS);
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
        order.setReceiverName(request.receiverName());
        order.setReceiverPhone(request.receiverPhone());
        order.setProvince(request.province());
        order.setCity(request.city());
        order.setDistrict(request.district());
        order.setDetailAddress(request.detailAddress());
        int version = order.bumpAddressVersion();
        orderRepository.save(order);
        addressHistoryRepository.save(new AddressHistory(TenantContextHolder.tenantId(), order.getId(),
                request.receiverName(), request.receiverPhone(), request.province(), request.city(),
                request.district(), request.detailAddress(), version, Instant.now()));
        AddressView after = new AddressView(request.receiverName(), request.receiverPhone(), request.province(),
                request.city(), request.district(), request.detailAddress());
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
                        order.getCustomerId(), amount, request.reason(), idempotencyToken, "PROCESSING",
                        Instant.now()));
                order.setStatus(OrderStatus.REFUNDING);
                orderRepository.save(order);
                return inserted;
            });
            return ToolResponse.ok(ToolName.APPLY_REFUND.apiName(), toRefundView(saved));
        } catch (DataIntegrityViolationException raceWithConcurrentSubmit) {
            return transactionTemplate.execute(status ->
                    refundRepository.findByOrderIdAndIdempotencyToken(orderId, idempotencyToken)
                            .map(winner -> replay(ToolName.APPLY_REFUND, winner))
                            .orElseGet(() -> ToolResponse.failure(ToolName.APPLY_REFUND.apiName(),
                                    ToolStatus.IDEMPOTENT_REPLAY, "重复提交已被唯一约束拦截", List.of())));
        }
    }

    @Transactional
    public TicketView createTicket(String customerId, String reason, String userQuery, String transcript) {
        Instant now = Instant.now();
        String id = "T" + now.toEpochMilli() + "-" + Integer.toHexString(java.util.Objects.hash(customerId, userQuery, now));
        Ticket ticket = new Ticket(id, TenantContextHolder.tenantId(), customerId, reason, truncate(userQuery),
                transcript == null ? "" : transcript, "OPEN", now);
        return toTicketView(ticketRepository.save(ticket));
    }

    @Transactional(readOnly = true)
    public List<TicketView> listTickets() {
        return ticketRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toTicketView).toList();
    }

    @Transactional
    public Optional<TicketView> updateTicketStatus(String ticketId, String status) {
        return ticketRepository.findById(ticketId).map(ticket -> {
            ticket.setStatus(status);
            return toTicketView(ticketRepository.save(ticket));
        });
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
                order.getCreatedAt(), order.getPaidAt(), order.getShippedAt());
    }

    private RefundView toRefundView(Refund refund) {
        return new RefundView(String.valueOf(refund.getId()), refund.getOrderId(), refund.getAmountFen(),
                refund.getStatus(), refund.getCreatedAt());
    }

    private TicketView toTicketView(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getTenantId(), ticket.getCustomerId(), ticket.getReason(),
                ticket.getUserQuery(), ticket.getStatus(), ticket.getCreatedAt());
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
