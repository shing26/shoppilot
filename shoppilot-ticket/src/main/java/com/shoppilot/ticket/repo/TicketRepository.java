package com.shoppilot.ticket.repo;

import com.shoppilot.ticket.domain.Ticket;
import com.shoppilot.tool.workitem.TicketSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findAllByOrderByCreatedAtDesc();

    /**
     * 按 id 取**本店的**工单。
     *
     * <p>用派生查询而不是 {@code findById}：后者不拼 {@code @TenantId} 谓词，跨租户能读到别人的单
     * （biz-mock 侧为此专门修过一次，见票 69）。这里显式带 tenantId，口径与列表一致。
     */
    java.util.Optional<Ticket> findByIdAndTenantId(String id, String tenantId);

    /** 坐席台主查询：按队列取未结的工单，按优先级与时间排（队列天然给了上界）。 */
    List<Ticket> findByQueueAndStatusNotOrderByPriorityAscCreatedAtAsc(String queue, String resolvedStatus);

    /**
     * 按来源计数。分流的分母是这张表，所以「降级工单有多少」必须能单独数出来——
     * 否则新增三种来源后，「降级 9 种」那个公开口径就分母不保了。
     */
    long countBySource(String source);

    default long countBySource(TicketSource source) {
        return countBySource(source.name());
    }

    /**
     * 领取：一条条件更新胜过 {@code @Version}。
     *
     * <p>它同时表达三件事——只能领没人领的、只能领没结的、只能领本店的。
     * 返回 0 行即「被别人先领走了 / 已结单 / 不存在」，调用方据此回 409。
     *
     * <p>{@code tenantId} 必须显式带上：{@code find(id)} 不拼接 {@code @TenantId} 谓词（只有查询会），
     * 而这个更新是查询——不写就是一次跨租户领取。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Ticket t
               set t.assignee = :assignee, t.status = 'ASSIGNED'
             where t.id = :id
               and t.tenantId = :tenantId
               and t.assignee is null
               and t.status <> 'RESOLVED'
            """)
    int claim(@Param("id") String id, @Param("tenantId") String tenantId, @Param("assignee") String assignee);

    /** 释放：只允许释放**自己**领的那张，否则一个坐席能抢别人的活。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Ticket t
               set t.assignee = null, t.status = 'OPEN'
             where t.id = :id
               and t.tenantId = :tenantId
               and t.assignee = :assignee
            """)
    int release(@Param("id") String id, @Param("tenantId") String tenantId, @Param("assignee") String assignee);
}
