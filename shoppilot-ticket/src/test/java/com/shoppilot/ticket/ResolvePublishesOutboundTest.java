package com.shoppilot.ticket;

import com.shoppilot.ticket.audit.AuditPublisher;
import com.shoppilot.ticket.audit.OutboundPublisher;
import com.shoppilot.ticket.domain.Ticket;
import com.shoppilot.ticket.repo.TicketRepository;
import com.shoppilot.ticket.service.RoutingService;
import com.shoppilot.ticket.service.WorkItemService;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结单那一刻发出站事件（round26 票 87 / ADR 0059）。
 *
 * <p>纯单元测试，不起容器：{@code resolve} 只碰三个协作者（仓储、审计发布器、出站发布器），
 * 全部可以用 mock 给出确定的结局。真正往 Redis 写的那一步由 {@code OutboundPublishTest} 断，
 * 两边合起来才是「结单 → 出站事件」这条链。
 */
class ResolvePublishesOutboundTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final AuditPublisher audit = mock(AuditPublisher.class);
    private final OutboundPublisher outbound = mock(OutboundPublisher.class);
    private final WorkItemService service =
            new WorkItemService(tickets, mock(RoutingService.class), audit, outbound, mock(JdbcTemplate.class));

    @BeforeEach
    void enterTenant() {
        TenantContextHolder.set("T001", "C001");
    }

    @AfterEach
    void leaveTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("结单成功才发出站事件：带上渠道、目标与坐席的处理结论")
    void resolvePublishesWithTheResolutionNote() {
        givenTicket("webhook", "https://shop.example/hook", "ASSIGNED", "agent");

        assertThat(service.resolve("T-9", Actor.authenticated("agent"), "已为您补发配件")).isTrue();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbound).publish(eq("webhook"), eq("https://shop.example/hook"), eq("T-9"), body.capture());
        assertThat(body.getValue()).as("送出去的就是坐席写的那句结论").isEqualTo("已为您补发配件");
    }

    @Test
    @DisplayName("没填说明时送出去的是「已处理完成」，不是空字符串")
    void resolveWithoutNoteStillSendsSomething() {
        givenTicket("webhook", "https://shop.example/hook", "ASSIGNED", "agent");

        assertThat(service.resolve("T-9", Actor.authenticated("agent"), null)).isTrue();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbound).publish(anyString(), anyString(), eq("T-9"), body.capture());
        assertThat(body.getValue()).isEqualTo("已处理完成");
    }

    @Test
    @DisplayName("结不成的单一律不发事件：不存在、状态不对、不是你的单")
    void nothingIsPublishedWhenResolveFails() {
        when(tickets.findByIdAndTenantId(eq("T-404"), anyString())).thenReturn(Optional.empty());
        assertThat(service.resolve("T-404", Actor.authenticated("agent"), "x")).isFalse();

        givenTicket("webhook", "https://shop.example/hook", "RESOLVED", "agent");
        assertThat(service.resolve("T-9", Actor.authenticated("agent"), "x"))
                .as("已结单不能再结").isFalse();

        givenTicket("webhook", "https://shop.example/hook", "ASSIGNED", "someone-else");
        assertThat(service.resolve("T-9", Actor.authenticated("agent"), "x"))
                .as("不是你的单，结不掉").isFalse();

        verify(outbound, never()).publish(anyString(), anyString(), anyString(), anyString());
    }

    private void givenTicket(String channel, String contact, String status, String assignee) {
        Ticket ticket = new Ticket("T-9", "T001", "C001", "TOOL_UNAVAILABLE", "查一下", "t", status,
                Instant.now(), "normal", com.shoppilot.tool.workitem.TicketSource.DEGRADE, "ESCALATION",
                assignee, Instant.now().plusSeconds(3600), null);
        ticket.attachDelivery(channel, contact);
        when(tickets.findByIdAndTenantId(eq("T-9"), anyString())).thenReturn(Optional.of(ticket));
        when(tickets.save(any(Ticket.class))).thenAnswer(call -> call.getArgument(0));
    }
}