package com.shoppilot.bizmock.audit;

import com.shoppilot.bizmock.domain.AuditEventRow;
import com.shoppilot.bizmock.repo.AuditEventRowRepository;
import com.shoppilot.tool.audit.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 审计事件的幂等写入（round23 票 71）。
 *
 * <p>单独一个 bean 而不是 {@code AuditService} 里的私有方法，原因不是洁癖：
 * <b>在事务里 catch 约束冲突是没用的</b>——冲突会把当前事务标成 rollback-only，
 * 即使异常被吃掉，外层提交时照样抛 {@code UnexpectedRollbackException}，整个请求 500。
 * 而「重复事件是常态不是异常」（至少一次投递），所以它绝不能毒化外层事务。
 *
 * <p>因此这里用 {@link Propagation#REQUIRES_NEW} 开一个独立事务：重复事件只让**这一笔**回滚。
 * 另外用 {@code saveAndFlush} 而不是 {@code save}——不 flush 的话 INSERT 要等到提交才发，
 * 约束冲突根本不会在这个 try 里发生，catch 就是摆设。
 *
 * <p>去重落在 {@code event_id} 的唯一索引上，不在应用层的「先查再插」：后者在并发下必然漏，
 * 而漏掉的审计比重复的审计难查得多。
 */
@Component
public class AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventWriter.class);

    private final AuditEventRowRepository repository;

    public AuditEventWriter(AuditEventRowRepository repository) {
        this.repository = repository;
    }

    /**
     * 写入一条审计事件；**重复时让 {@link DataIntegrityViolationException} 逃出去，由调用方 catch**。
     *
     * <p>为什么不在这里 catch：约束冲突会把本事务标成 rollback-only，而 catch 不会清掉那个标志位，
     * 于是方法正常返回后提交照样抛 {@code UnexpectedRollbackException}——第一次就是这么炸的。
     * 让异常逃出这个 {@code REQUIRES_NEW} 边界，内层干净回滚，外层（当时被挂起）不受影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEvent event) {
        repository.saveAndFlush(new AuditEventRow(event.eventId(), event.schemaVersion(), event.action(),
                event.objectType(), event.objectId(), event.tenantId(), event.actor(), event.detail(),
                event.occurredAt(), Instant.now()));
    }
}