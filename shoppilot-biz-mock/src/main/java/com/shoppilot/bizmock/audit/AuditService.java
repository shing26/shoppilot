package com.shoppilot.bizmock.audit;

import com.shoppilot.bizmock.domain.AuditEventRow;
import com.shoppilot.bizmock.repo.AuditEventRowRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.audit.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审计的发布、幂等落地与查询（round23 票 71 / ADR 0054、0056）。
 *
 * <p>三条决定值得写下来：
 * <ol>
 *   <li><b>发布失败走直写兜底</b>：Redis 不可达时事件直接落表，消费者读到的仍然是完整的审计。
 *       两条路都过同一个幂等存储，所以重复投递不会产生重复行。</li>
 *   <li><b>消费惰性</b>：不跑后台线程，读审计时才把流里的事件收下来（与 SLA 打戳同一套理由）。</li>
 *   <li><b>幂等靠唯一索引</b>：重复事件在插入时撞约束，被当成「已处理」而不是错误。
 *       这是至少一次投递唯一正确的处理方式——重投是常态，不是异常。</li>
 * </ol>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String CONSUMER = "biz-mock";

    private final AuditChannel channel;
    private final AuditEventRowRepository repository;
    private final AuditEventWriter writer;

    public AuditService(AuditChannel channel, AuditEventRowRepository repository, AuditEventWriter writer) {
        this.channel = channel;
        this.repository = repository;
        this.writer = writer;
    }

    /** 发布一条审计事件；通道不可用时直写落表，保证「有没有审计」不取决于 Redis 在不在。 */
    @Transactional
    public void publish(String action, String objectType, String objectId, String actor, String detail) {
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString(), AuditEvent.CURRENT_SCHEMA, action,
                objectType, objectId, TenantContextHolder.tenantId(),
                actor == null || actor.isBlank() ? AuditActions.SYSTEM_ACTOR : actor,
                detail, Instant.now());
        if (channel.available()) {
            channel.publish(event);
        } else {
            log.warn("审计通道不可用，事件 {} 直写落表", action);
            store(event);
        }
    }

    /** 把流里还没收下来的事件收进表里（惰性消费）。返回本次新落库的条数。 */
    @Transactional
    public int drain() {
        return channel.consume(CONSUMER, event -> {
            store(event);
            return true;
        });
    }

    /**
     * 幂等落地，细节见 {@link AuditEventWriter}：去重落在唯一索引上，且必须在独立事务里做——
     * 在外层事务里 catch 约束冲突会把那次请求整个变成 500。
     */
    private void store(AuditEvent event) {
        try {
            writer.write(event);
        } catch (DataIntegrityViolationException duplicate) {
            // 至少一次投递下重投是常态，不是错误：撞了唯一约束就当已处理
            log.debug("审计事件 {} 已存在，按已处理计", event.eventId());
        }
    }

    /** 查审计：先收流，再按租户 + 动作倒序返回本店的近期事件。 */
    @Transactional
    public List<AuditEventRow> recent(String action, int limit) {
        drain();
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        return action == null || action.isBlank()
                ? repository.findByOrderByOccurredAtDesc(page)
                : repository.findByActionOrderByOccurredAtDesc(action, page);
    }
}