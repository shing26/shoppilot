package com.shoppilot.bizmock.workitem;

import com.shoppilot.tool.workitem.TicketSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用的工单出口（**只在测试源集里**）。
 *
 * <p>工单服务在本轮起不来（裁定 A：不起全栈），而「退款受理要开一张审批单」这类行为必须继续被测到。
 * 所以生产实现走 HTTP，测试实现留在内存——与票 71 的审计通道同一套形状。
 *
 * <p>它**只**替代传输：单号、payload、回指全都照样产生，所以
 * {@code refunds.ticket_id} 的断言仍然是真的，不是被替身造出来的假绿。
 */
public class InMemoryWorkItemClient implements WorkItemClient {

    public record Created(TicketSource source, String customerId, String reason, String userQuery, String transcript,
                          String priority, String payload, String ticketId) {
    }

    private final List<Created> created = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private boolean unavailable;

    /** 模拟工单服务不可达：调用方据此决定是继续还是中止。 */
    public void unreachable() {
        this.unavailable = true;
    }

    public List<Created> created() {
        return List.copyOf(created);
    }

    public Created lastCreated() {
        return created.isEmpty() ? null : created.get(created.size() - 1);
    }

    @Override
    public String create(TicketSource source, String customerId, String reason, String userQuery, String transcript,
                         String priority, String payload) {
        if (unavailable) {
            return null;
        }
        String ticketId = "T-stub-" + sequence.incrementAndGet();
        created.add(new Created(source, customerId, reason, userQuery, transcript, priority, payload, ticketId));
        return ticketId;
    }

    @Override
    public boolean available() {
        return !unavailable;
    }
}