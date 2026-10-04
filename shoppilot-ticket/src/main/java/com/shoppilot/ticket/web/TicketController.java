package com.shoppilot.ticket.web;

import com.shoppilot.ticket.domain.TicketStatus;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.ActorHeaders;
import com.shoppilot.tool.workitem.TicketSource;
import com.shoppilot.ticket.service.WorkItemService;
import com.shoppilot.tool.view.TicketView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工单与坐席端点（round23 票 72 / ADR 0053、0055）。
 *
 * <p>浏览器不直连本服务，一律经网关代理；调用方必须带内部 token 与租户上下文（{@link InternalAuthFilter}）。
 *
 * <p><b>{@code X-Agent} 已退役</b>（round25 票 82 / ADR 0058）：它由调用方自报，带上内部 token
 * 就能写成任何名字，于是「谁领了这张单」一度没有根据。现在只认 {@code X-Actor} 与
 * {@code X-Actor-Authenticated}，都由网关从已验签的令牌解出来再下发；
 * 后者缺失即按未认证处理，本服务不接受任何来自客户端的身份声明。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final WorkItemService service;

    public TicketController(WorkItemService service) {
        this.service = service;
    }

    /**
     * 落一张工单。网关的降级单与邮件回执走这里；biz-mock 的退款审批单与复核单也走这里。
     *
     * <p><b>{@code source} 可选</b>：网关那条老契约只发 {@code reason}，来源由本服务推导
     * （{@code EmailReceiptWriter} 就只发 {@code reason=EMAIL_REPLY}）；而退款审批与复核单需要指名来源，
     * 所以给了一个可选字段。**两个都支持**是因为拆服务不该顺手改契约——
     * 让「搬家」和「改行为」混成一次变更，出了事没人说得清是哪一件引起的。
     *
     * <p>指名了但解析不出来时回 400，而不是悄悄退回 DEGRADE：拼错的来源名落到默认队列上，
     * 正是本仓反复在防的那类静默降级。
     */
    @PostMapping
    public ResponseEntity<TicketView> create(@RequestBody CreateTicketRequest request) {
        TicketView created;
        if (request.source() == null || request.source().isBlank()) {
            created = service.createFromReason(request.customerId(), request.reason(), request.userQuery(),
                    request.transcript(), request.priority(), request.channel(), request.contact());
        } else {
            TicketSource source = TicketSource.parse(request.source());
            if (source == null) {
                throw new IllegalArgumentException("未知工单来源 " + request.source() + "，可选 " + TicketSource.names());
            }
            created = service.create(source, request.customerId(), request.reason(), request.userQuery(),
                    request.transcript(), request.priority(), request.payload(), request.channel(), request.contact());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 队列列表（坐席台主查询）。{@code queue} 省略时返回本店全部工单。 */
    @GetMapping
    public List<TicketView> list(@RequestParam(required = false) String queue,
                                 @RequestParam(required = false) String status) {
        if (status != null && !status.isBlank() && TicketStatus.parse(status) == null) {
            throw new IllegalArgumentException("未知工单状态 " + status + "，可选 " + TicketStatus.names());
        }
        return service.list(queue, status);
    }

    /**
     * 本店工单总数。单独一个端点而不是让调用方拉全量自己数：
     * 「数一下」这个需求不该变成一次全表传输。
     */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", service.countAll());
    }

    /**
     * **这个买家自己的**工单（round27 票 92）。
     *
     * <p>买家号取自 {@code X-Customer-Id}（网关从已验签身份补上），**不从查询参数读**：
     * 那样就等于让任何人传一个别人的买家号来读别人的单（ADR 0005 防线一）。
     * 这个头在本服务里已由 {@link InternalAuthFilter} 校验过内部凭证后才落进租户上下文，
     * 所以它可信——能打到这里的调用方本来就是网关。
     */
    @GetMapping("/mine")
    public List<TicketView> mine() {
        return service.listOwn(TenantContextHolder.customerId());
    }

    /**
    /**
     * 运维直改状态机（调试台的状态流转按钮）。走与动作端点同一套流转校验与租户口径；
     * 非法流转回 409、未知状态值回 400。
     */
    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<Void> transition(@PathVariable String ticketId,
                                           @RequestBody StatusRequest request) {
        try {
            return service.transition(ticketId, request.status())
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException unknownStatus) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException illegalTransition) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * 按号取一张工单。跨租户与不存在同答案（404）——不区分「存在但不可见」。
     */
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketView> get(@PathVariable String ticketId) {
        return service.get(ticketId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 领取。三种结局给三种状态码：204 领到 / 409 已被别人领走或已结单 / 404 不存在或不是本店的。
     *
     * <p>跨租户必须是 404 而不是 409：共用一个答案等于告诉别人「这张单存在，只是你领不到」。
     */
    @PostMapping("/{ticketId}/claim")
    public ResponseEntity<Void> claim(@PathVariable String ticketId,
                                      @RequestHeader(value = ActorHeaders.ACTOR, required = false) String actor,
                                      @RequestHeader(value = ActorHeaders.ACTOR_AUTHENTICATED,
                                              required = false) String authenticated) {
        return switch (service.claim(ticketId, ActorHeaders.of(actor, authenticated))) {
            case CLAIMED -> ResponseEntity.noContent().build();
            case TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/{ticketId}/release")
    public ResponseEntity<Void> release(@PathVariable String ticketId,
                                        @RequestHeader(value = ActorHeaders.ACTOR, required = false) String actor,
                                        @RequestHeader(value = ActorHeaders.ACTOR_AUTHENTICATED,
                                                required = false) String authenticated) {
        return switch (service.release(ticketId, ActorHeaders.of(actor, authenticated))) {
            case CLAIMED -> ResponseEntity.noContent().build();
            case TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String ticketId,
                                        @RequestBody ResolveRequest request,
                                        @RequestHeader(value = ActorHeaders.ACTOR, required = false) String actor,
                                        @RequestHeader(value = ActorHeaders.ACTOR_AUTHENTICATED,
                                                required = false) String authenticated) {
        return service.resolve(ticketId, ActorHeaders.of(actor, authenticated), request.note())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * {@code source} 与 {@code payload} 是票 72 为跨进程调用方加的可选字段，老契约不传它们；
     * {@code channel} 与 {@code contact} 是票 86 加的**结果回流前置**，同样可选（ADR 0059）。
     *
     * <p>两格都空是最常见的形态——复核单、退款审批单、以及 web 渠道的降级单都不填。
     */
    public record CreateTicketRequest(String source, String customerId, String reason, String userQuery,
                                      String transcript, String priority, String payload,
                                      String channel, String contact) {
    }

    public record ResolveRequest(String note) {
    }

    public record StatusRequest(String status) {
    }
}