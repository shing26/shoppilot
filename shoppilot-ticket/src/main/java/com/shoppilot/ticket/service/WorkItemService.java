package com.shoppilot.ticket.service;

import com.shoppilot.ticket.audit.AuditPublisher;
import com.shoppilot.ticket.audit.OutboundPublisher;
import com.shoppilot.ticket.domain.Ticket;
import com.shoppilot.ticket.domain.TicketStatus;
import com.shoppilot.ticket.repo.TicketRepository;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.view.TicketView;
import com.shoppilot.tool.workitem.TicketSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单的落库与坐席动作（round23 票 69/70/72，ADR 0055）。
 *
 * <p>四种来路共用 {@link #create} 这一条写入路径，所以分流规则表才有单一分母；
 * 落库**当场分派**，不存在「这张单还没有队列」的中间态。
 *
 * <p>坐席模型是 pull（ADR 0055）：领取是坐席主动收下，靠一条条件更新保证
 * 「同一张单恰好一人领得到」；本服务不派单、不看谁在线。
 */
@Service
public class WorkItemService {

    private final TicketRepository ticketRepository;
    private final RoutingService routingService;
    private final AuditPublisher auditPublisher;
    private final OutboundPublisher outboundPublisher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public WorkItemService(TicketRepository ticketRepository, RoutingService routingService,
                           AuditPublisher auditPublisher, OutboundPublisher outboundPublisher,
                           org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.ticketRepository = ticketRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.routingService = routingService;
        this.auditPublisher = auditPublisher;
        this.outboundPublisher = outboundPublisher;
    }

    /**
     * 落一张工作项并当场分派。
     *
     * @param payload 有上游记录时指回上游的 JSON；自包含来源传 null
     */
    @Transactional
    public TicketView create(TicketSource source, String customerId, String reason, String userQuery,
                              String transcript, String priority, String payload) {
        return create(source, customerId, reason, userQuery, transcript, priority, payload, null, null);
    }

    /**
     * 落单并挂上渠道与投递目标（round26 票 86 / ADR 0059）。
     *
     * <p>两格都可选：复核单与退款审批单没有渠道，网关的 web 降级单有渠道但没有目标
     * （买家在自己浏览器里等，不存在「送回去」这件事）。
     */
    @Transactional
    public TicketView create(TicketSource source, String customerId, String reason, String userQuery,
                              String transcript, String priority, String payload, String channel, String contact) {
        Instant now = Instant.now();
        RoutingService.Assignment assignment =
                routingService.assign(TenantContextHolder.tenantId(), source, reason, priority);
        Ticket ticket = new Ticket(Ticket.nextId(now), TenantContextHolder.tenantId(), customerId, reason,
                truncate(userQuery, 512), transcript == null ? "" : transcript, TicketStatus.OPEN.name(), now,
                assignment.priority().literal(), source, assignment.queue(), null, assignment.slaDeadline(), payload);
        ticket.attachDelivery(channel, contact);
        return toView(ticketRepository.save(ticket));
    }

    /**
     * 由既有 {@code reason} 反推来源的落单入口（网关的降级单与邮件回执走这条）。
     *
     * <p>网关只发 reason 不发 source——跨模块契约不因拆服务而变，来源在服务侧推导。
     */
    @Transactional
    public TicketView createFromReason(String customerId, String reason, String userQuery, String transcript,
                                       String priority) {
        return createFromReason(customerId, reason, userQuery, transcript, priority, null, null);
    }

    /** 同上，但带上渠道与投递目标（网关的降级单与邮件回执单走这条，ADR 0059）。 */
    @Transactional
    public TicketView createFromReason(String customerId, String reason, String userQuery, String transcript,
                                       String priority, String channel, String contact) {
        return create(TicketSource.ofReason(reason), customerId, reason, userQuery, transcript, priority, null,
                channel, contact);
    }

    /**
     * 全平台工单总数（运维面板用）。
     *
     * <p>用原生 SQL 刻意绕开 {@code @TenantId}：平台侧运维要的是**跨租户总量**
     * （与 biz-mock 的 {@code /api/admin/stats} 同一口径），而带 {@code @TenantId} 的仓储查询
     * 在没有请求上下文时只会数到 PLATFORM 那一份，等于恒为 0。
     *
     * <p>这条路径按 ADR 0005 属于平台级读取，所以内部认证过滤器对它单独放行（不经租户上下文）。
     */
    @Transactional(readOnly = true)
    public long countAll() {
        Long total = jdbcTemplate.queryForObject("select count(*) from tickets", Long.class);
        return total == null ? 0L : total;
    }

    /**
     * 直接流转状态（运维调试台用，round23 票 75 补回）。
     *
     * <p><b>为什么补</b>：票 72 把工单表搬走时，运维页那张「状态流转」按钮跟到了新服务，
     * 而新服务只实现了 claim/release/resolve 三个动作端点、没有 {@code /status}——
     * 于是那条按钮 404，{@code verify-console.mjs} 的「工单状态流转」断言整条红。
     *
     * <p><b>它与三个动作端点的分工</b>：动作端点带审计与归属校验（谁领的、谁结的）；
     * 这个是运维直改状态机，**同样走 {@link TicketStatus} 的流转校验**，不额外开口子。
     */
    @Transactional
    public boolean transition(String ticketId, String rawStatus) {
        TicketStatus target = TicketStatus.parse(rawStatus);
        if (target == null) {
            throw new IllegalArgumentException("未知工单状态 " + rawStatus + "，可选 " + TicketStatus.names());
        }
        return ticketRepository.findByIdAndTenantId(ticketId, TenantContextHolder.tenantId())
                .map(ticket -> {
                    TicketStatus current = TicketStatus.parse(ticket.getStatus());
                    if (current == null || !current.canTransitionTo(target)) {
                        throw new IllegalStateException("工单不允许从 " + ticket.getStatus() + " 流转到 " + target);
                    }
                    ticket.setStatus(target.name());
                    ticketRepository.save(ticket);
                    return true;
                })
                .orElse(false);
    }

    /** 按号取一张本店的工单；跨租户与不存在同答案（404）。 */
    @Transactional(readOnly = true)
    public Optional<TicketView> get(String ticketId) {
        return ticketRepository.findByIdAndTenantId(ticketId, TenantContextHolder.tenantId())
                .map(WorkItemService::toView);
    }

    /** 工单列表。读之前先结算 SLA 超时打戳（惰性求值，省掉一个定时线程池）。 */
    @Transactional
    public List<TicketView> list(String queue, String status) {
        String tenantId = TenantContextHolder.tenantId();
        routingService.escalateOverdue(tenantId, Instant.now());
        if (queue != null && !queue.isBlank()) {
            TicketStatus wanted = TicketStatus.parse(status);
            List<Ticket> rows = wanted == null
                    ? ticketRepository.findByQueueAndStatusNotOrderByPriorityAscCreatedAtAsc(queue,
                            TicketStatus.RESOLVED.name())
                    : ticketRepository.findByQueueAndStatusNotOrderByPriorityAscCreatedAtAsc(queue,
                            TicketStatus.RESOLVED.name()).stream().filter(t -> wanted.name().equals(t.getStatus())).toList();
            return rows.stream().map(WorkItemService::toView).toList();
        }
        return ticketRepository.findAllByOrderByCreatedAtDesc().stream().map(WorkItemService::toView).toList();
    }

    /**
     * **某个买家自己的**工单（round27 票 92，买家端页面唯一的新依赖）。
     *
     * <p>{@code customerId} 由调用方给，而调用方（网关）传的是**已验签身份里的买家号**——
     * 本服务不再从任何请求参数里读它（ADR 0005 防线一）。租户由 {@link TenantContextHolder} 给，
     * 与本类其余查询同一口径。
     *
     * <p><b>买家这一侧刻意少一格</b>：{@link TicketView} 带 transcript（坐席看的会话原文），
     * 而 {@link #toBuyerView} 不带。买家看自己的诉求、状态、优先级与处理结论就够了，
     * 坐席与买家之间那些来回不是给他看的。
     */
    @Transactional(readOnly = true)
    public List<TicketView> listOwn(String customerId) {
        String tenantId = TenantContextHolder.tenantId();
        return ticketRepository.findByTenantIdAndCustomerIdOrderByCreatedAtDesc(tenantId, customerId).stream()
                .map(WorkItemService::toBuyerView)
                .toList();
    }

    /** 买家视角的视图：与坐席同一形状，只少 {@code transcript} 一格。 */
    private static TicketView toBuyerView(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getTenantId(), ticket.getCustomerId(), ticket.getReason(),
                ticket.getUserQuery(), ticket.getStatus(), ticket.getPriority(), ticket.getCreatedAt(),
                ticket.getSource(), ticket.getQueue(), ticket.getAssignee(), ticket.getSlaDeadline(),
                ticket.getPayload(), ticket.getEscalatedAt(), ticket.getChannel(), null);
    }

    /**
     * 领取。返回 false 表示「已被别人领走 / 已结单 / 不存在」，调用方回 409。
     *
     * <p>刻意用条件更新而不是「查出来再改再存」：后者在并发下两个坐席都会看到 assignee 为空。
     */
    @Transactional
    public ClaimOutcome claim(String ticketId, Actor actor) {
        String tenantId = TenantContextHolder.tenantId();
        // 先判存在性：**跨租户一律 404**，不与「已被领走」共用一个答案——
        // 混成一个答案就等于告诉别人「这张单存在只是你领不到」。
        if (ticketRepository.findByIdAndTenantId(ticketId, tenantId).isEmpty()) {
            return ClaimOutcome.NOT_FOUND;
        }
        if (ticketRepository.claim(ticketId, tenantId, actor.name()) == 0) {
            return ClaimOutcome.TAKEN;
        }
        auditPublisher.publish(AuditActions.TICKET_CLAIMED, "TICKET", ticketId, actor, "领取工单");
        return ClaimOutcome.CLAIMED;
    }

    /** 领取的三种结局。分开表达是为了让端点能给出不同的状态码。 */
    public enum ClaimOutcome {
        CLAIMED, TAKEN, NOT_FOUND
    }

    /** 释放：只允许释放自己领的那张。 */
    @Transactional
    public ClaimOutcome release(String ticketId, Actor actor) {
        String tenantId = TenantContextHolder.tenantId();
        if (ticketRepository.findByIdAndTenantId(ticketId, tenantId).isEmpty()) {
            return ClaimOutcome.NOT_FOUND;
        }
        if (ticketRepository.release(ticketId, tenantId, actor.name()) == 0) {
            return ClaimOutcome.TAKEN;
        }
        auditPublisher.publish(AuditActions.TICKET_RELEASED, "TICKET", ticketId, actor, "释放工单");
        return ClaimOutcome.CLAIMED;
    }

    /**
     * 处理完成。
     *
     * <p>只允许由**领取人**结单：结单是这条链路的终点证据，让任意人可结等于让「已处理」这个数可被随手改写。
     * 状态流转仍走 {@link TicketStatus} 的状态机，不在这里另发明一套。
     */
    @Transactional
    public boolean resolve(String ticketId, Actor actor, String note) {
        Optional<Ticket> found =
                ticketRepository.findByIdAndTenantId(ticketId, TenantContextHolder.tenantId());
        if (found.isEmpty()) {
            return false;
        }
        Ticket ticket = found.get();
        TicketStatus current = TicketStatus.parse(ticket.getStatus());
        if (current == null || !current.canTransitionTo(TicketStatus.RESOLVED)) {
            return false;
        }
        if (ticket.getAssignee() != null && !ticket.getAssignee().equals(actor.name())) {
            return false;
        }
        ticket.setStatus(TicketStatus.RESOLVED.name());
        ticketRepository.save(ticket);
        auditPublisher.publish(AuditActions.TICKET_RESOLVED, "TICKET", ticketId, actor,
                note == null || note.isBlank() ? "处理完成，未填说明" : "处理完成：" + truncate(note, 200));
        // 结果回流（round26 票 87 / ADR 0059）：**结单成功之后**才发，顺序不能反——
        // 先发事件再落状态的话，消费者可能拿到一条「结论已送达」而工单其实没结成。
        // 没有投递目标（web 渠道、复核单、退款审批单）时 publisher 自己按规则不发，只计数。
        outboundPublisher.publish(ticket.getChannel(), ticket.getContact(), ticketId,
                note == null || note.isBlank() ? "已处理完成" : truncate(note, 200));
        return true;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static TicketView toView(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getTenantId(), ticket.getCustomerId(), ticket.getReason(),
                ticket.getUserQuery(), ticket.getStatus(), ticket.getPriority(), ticket.getCreatedAt(),
                ticket.getSource(), ticket.getQueue(), ticket.getAssignee(), ticket.getSlaDeadline(),
                ticket.getPayload(), ticket.getEscalatedAt(), ticket.getChannel(), ticket.getContact());
    }
}
