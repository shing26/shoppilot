package com.shoppilot.bizmock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两侧迁移集必须逐版本对齐（round28 票 100 / ADR 0061）。
 *
 * <p>{@code db/migration} 是 H2（默认档）的模式唯一产生源，{@code db/migration/postgresql}
 * 是 PG（持久档）的。两侧版本钟必须同步——否则「V9 只落一边」这种漂移会在某次换档启动时
 * 才炸出来，而且是在别人的机器上。PG 侧 V1-V8 是「现状全量基线 + 版本占位」（内容口径见
 * PG V1 文件头），本守卫只管**文件集对齐**；SQL 内容的两方言差异是刻意的，不比。
 */
class PostgresMigrationParityTest {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    @Test
    @DisplayName("db/migration 与 db/migration/postgresql 的迁移版本集逐字一致")
    void postgresMigrationSetMatchesH2Set() throws IOException {
        List<String> h2 = versionsOf(Path.of("src/main/resources/db/migration"));
        List<String> pg = versionsOf(Path.of("src/main/resources/db/migration-postgresql"));
        assertThat(pg).as("PG 侧迁移集不能为空——持久档没有模式产生源就起不来").isNotEmpty();
        assertThat(pg).as("两侧迁移版本集必须一致（H2 侧 = %s）", h2).isEqualTo(h2);
    }

    /** 迁移文件名抽出版本号；两边都按版本号排序，重复或缺失都会让两个列表不相等。 */
    private static List<String> versionsOf(Path dir) throws IOException {
        assertThat(dir).as("迁移目录不存在：%s（surefire 工作目录应当是模块目录）", dir).exists();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .map(n -> {
                        Matcher m = VERSION.matcher(n);
                        return m.find() ? m.group(1) : n;
                    })
                    .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
                    .toList();
        }
    }
}
