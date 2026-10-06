package com.shoppilot.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按库用户隔离的接线守卫（round29 票 105 / ADR 0062）。
 *
 * <p>隔离的真判据是活体的「跨库连接 FATAL」（本仓跑不了 Postgres 的 JVM 层验不了它），
 * 但**接线本身可以被机器钉住**：init 脚本必须真的建两个角色、收走 PUBLIC 的 CONNECT；
 * compose 必须真的给两个服务发不同的用户名。少任何一行，隔离就从事实退化成注释——
 * 与 {@code RedisConfigConsistencyTest} 同一家法：断配置本身，不连任何外部服务。
 */
class PostgresIsolationScriptTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    @DisplayName("init 脚本建两个应用角色、收走 PUBLIC CONNECT、按角色授回并转移库属主")
    void initScriptDefinesIsolatedRoles() throws IOException {
        String script = Files.readString(REPO.resolve("deploy/postgres-init.sh"), StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE ROLE bizmock_app").contains("CREATE ROLE ticket_app");
        assertThat(script).contains("REVOKE CONNECT ON DATABASE bizmock FROM PUBLIC")
                .contains("REVOKE CONNECT ON DATABASE ticket FROM PUBLIC");
        assertThat(script).contains("GRANT CONNECT ON DATABASE bizmock TO bizmock_app")
                .contains("GRANT CONNECT ON DATABASE ticket TO ticket_app");
        assertThat(script).contains("ALTER DATABASE bizmock OWNER TO bizmock_app")
                .contains("ALTER DATABASE ticket OWNER TO ticket_app");
    }

    @Test
    @DisplayName("compose 给 biz-mock 与 ticket 发的是各自的角色名，不是共享变量")
    void composeAssignsDistinctUsernamesPerService() throws IOException {
        String compose = Files.readString(REPO.resolve("docker-compose.yml"), StandardCharsets.UTF_8);

        String bizMock = sectionOf(compose, "  biz-mock:");
        String ticket = sectionOf(compose, "  ticket:");
        assertThat(bizMock).as("biz-mock 必须用 bizmock_app").contains("SHOPPILOT_DB_USERNAME: bizmock_app");
        assertThat(ticket).as("ticket 必须用 ticket_app").contains("SHOPPILOT_DB_USERNAME: ticket_app");
        assertThat(bizMock).as("隔离后服务侧不该再共享 SHOPPILOT_DB_USERNAME 变量")
                .doesNotContain("SHOPPILOT_DB_USERNAME: ${");
    }

    /** 取 compose 里某个服务段的文本（从段头到下一个顶格段头）。 */
    private static String sectionOf(String compose, String sectionHeader) {
        List<String> lines = compose.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(sectionHeader)) {
                start = i;
                break;
            }
        }
        assertThat(start).as("compose 里找不到服务段 %s", sectionHeader).isNotNegative();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > start && !line.isBlank() && !line.startsWith(" ")) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
