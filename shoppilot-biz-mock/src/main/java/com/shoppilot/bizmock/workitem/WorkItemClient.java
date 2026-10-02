package com.shoppilot.bizmock.workitem;

import com.shoppilot.tool.workitem.TicketSource;

/**
 * 落一张工作项的出口（round23 票 72 / ADR 0053）。
 *
 * <p>工单数据搬到工单服务之后，业务侧**不再持有** {@code tickets} 表——它连一个本地写入点都没有。
 * 退款审批单与复核单都要落工单，于是这条路径从「本地 save」变成「跨进程调用」。
 *
 * <p>刻意做成端口而不是直接 {@code RestClient}：本模块的测试跑不起工单服务
 * （裁定 A：本轮不起全栈），而「退款受理时要开一张审批单」这条行为必须继续被测到。
 * 所以生产实现走 HTTP，测试实现是内存的一份——与票 71 的审计通道同一套形状。
 */
public interface WorkItemClient {

    /**
     * 落一张工作项。
     *
     * @return 工单号；调用方用它做回指（{@code refunds.ticket_id}）
     */
    String create(TicketSource source, String customerId, String reason, String userQuery, String transcript,
                  String priority, String payload);

    /** 通道是否可用。不可用时调用方要决定是照常发起资金动作还是中止——**别静默假装落了单**。 */
    boolean available();

    /**
     * 本店工单数（运维面板用）。
     *
     * <p>工单数据搬去工单服务之后（ADR 0053），业务侧不再持有那张表，所以这个数**只能问它**——
     * 清场日的活体验收正是因为「还在查本地那张已被删掉的表」而让 stats 与 demo/reset 一起 500。
     *
     * <p><b>口径是跨租户总量</b>（同 biz-mock 的 {@code /api/admin/stats}）：那条路径没有租户上下文，
     * 按租户去数只会得到 PLATFORM 那一档的 0——实测就是这样。所以问的也是平台级计数。
     *
     * <p>不可达时返回 0：让「读不到一个数」把退款数、订单数一起拖下水才是真事故。
     */
    int count();
}