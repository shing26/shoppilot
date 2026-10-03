package com.shoppilot.tool.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作人与其下发头的契约（round25 票 82 / ADR 0058 第 4 条）。
 *
 * <p>这些是纯函数，而它们守的是一条安全相关的默认取向：**少传一个头的结果是「记下名字但标明不可信」**，
 * 不是「默认可信」。所以值得在契约库这一层单独钉住，而不是只在某个服务的集成用例里间接覆盖。
 */
class ActorAndActorHeadersTest {

    @Test
    @DisplayName("自报的名字默认不可信，认证过的名字才带 true")
    void selfReportedNamesAreNotAuthenticated() {
        assertThat(Actor.of("ops-on-duty").authenticated()).isFalse();
        assertThat(Actor.authenticated("U0007").authenticated()).isTrue();
        assertThat(Actor.of("ops-on-duty").name()).isEqualTo("ops-on-duty");
    }

    @Test
    @DisplayName("名字为空落到 system，且标成已认证——系统动作不是「缺了操作人」")
    void emptyNameBecomesSystem() {
        assertThat(Actor.of(null).name()).isEqualTo(AuditActions.SYSTEM_ACTOR);
        assertThat(Actor.of("   ").name()).isEqualTo(AuditActions.SYSTEM_ACTOR);
        assertThat(Actor.of(null).authenticated())
                .as("空 actor 只在确实没有真人参与时出现，标成不可信会让真正的自报淹没在噪声里").isTrue();
    }

    @Test
    @DisplayName("X-Actor-Authenticated 缺失或不是 true，一律按未认证处理")
    void missingAuthenticatedHeaderDegradesToUnverified() {
        assertThat(ActorHeaders.of("alice", null).authenticated()).isFalse();
        assertThat(ActorHeaders.of("alice", "").authenticated()).isFalse();
        assertThat(ActorHeaders.of("alice", "TRUE").authenticated()).isTrue();
        assertThat(ActorHeaders.of("alice", " true ").authenticated()).isTrue();
        assertThat(ActorHeaders.of("alice", "yes").authenticated()).as("只认 true 这一个词").isFalse();
    }

    @Test
    @DisplayName("系统动作经过头解析后仍是 system，不会因为缺头降级成不可信")
    void systemActorSurvivesHeaderParsing() {
        assertThat(ActorHeaders.of(null, null)).isEqualTo(Actor.SYSTEM);
    }

    @Test
    @DisplayName("九参构造落到未认证：忘了传标注的旧调用点会被查询面挑出来")
    void legacyAuditEventConstructorDefaultsToUnverified() {
        AuditEvent event = new AuditEvent("e1", AuditEvent.CURRENT_SCHEMA, "REFUND_APPROVED", "REFUND", "1",
                "T001", "someone", "放行", Instant.now());

        assertThat(event.actorAuthenticated()).isFalse();
        assertThat(event.actor()).isEqualTo("someone");
        assertThat(new AuditView("e1", "REFUND_APPROVED", "REFUND", "1", "someone", "放行", Instant.now())
                .actorAuthenticated()).isFalse();
    }
}