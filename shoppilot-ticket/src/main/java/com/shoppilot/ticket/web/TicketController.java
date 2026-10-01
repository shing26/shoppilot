package com.shoppilot.ticket.web;

import com.shoppilot.ticket.domain.TicketStatus;
import com.shoppilot.tool.workitem.TicketSource;
import com.shoppilot.ticket.service.WorkItemService;
import com.shoppilot.tool.view.TicketView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单与坐席端点（round23 票 72 / ADR 0053、0055）。
 *
 * <p>浏览器不直连本服务，一律经网关代理；调用方必须带内部 token 与租户上下文（{@link InternalAuthFilter}）。
 *
 * <p>{@code X-Agent} 是**坐席自报**的身份，**不是认证过的身份**（身份域是下一轮，ADR 0056）：
 * 它进的是审计不是权限。带上内部 token 就能写这个头——这条缺口照登。
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
                    request.transcript(), request.priority());
        } else {
            TicketSource source = TicketSource.parse(request.source());
            if (source == null) {
                throw new IllegalArgumentException("未知工单来源 " + request.source() + "，可选 " + TicketSource.names());
            }
            created = service.create(source, request.customerId(), request.reason(), request.userQuery(),
                    request.transcript(), request.priority(), request.payload());
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
                                      @RequestHeader(value = "X-Agent", required = false) String agent) {
        return switch (service.claim(ticketId, agent)) {
            case CLAIMED -> ResponseEntity.noContent().build();
            case TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/{ticketId}/release")
    public ResponseEntity<Void> release(@PathVariable String ticketId,
                                        @RequestHeader(value = "X-Agent", required = false) String agent) {
        return switch (service.release(ticketId, agent)) {
            case CLAIMED -> ResponseEntity.noContent().build();
            case TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String ticketId,
                                        @RequestBody ResolveRequest request,
                                        @RequestHeader(value = "X-Agent", required = false) String agent) {
        return service.resolve(ticketId, agent, request.note()) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** {@code source} 与 {@code payload} 是票 72 为跨进程调用方加的可选字段，老契约不传它们。 */
    public record CreateTicketRequest(String source, String customerId, String reason, String userQuery,
                                      String transcript, String priority, String payload) {
    }

    public record ResolveRequest(String note) {
    }
}