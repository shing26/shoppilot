package com.shoppilot.gateway.ingest;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.knowledge.RuleChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语料内容一致性门（round33 / ADR 0067，修 D9）。
 *
 * <p>这一层的存在理由很具体：`return-01-7day-basic.md` 的标题写着"七天无理由"，正文却写成
 * "十四天内可申请"，而它同时是平台级条款、被 180 条 gold 与检索夹具引用。标题与正文互相打架
 * 时，JVM 不会红、检索门不会红、评测也可能照过——只有把"正文说了几天"写成断言，这个矛盾才会暴露。
 */
class KnowledgeConsistencyTest {

    @Test
    @DisplayName("return-01 正文写七天：标题、窗口与店铺十五天延长规则三者不打架")
    void returnWindowTextMatchesItsTitle() throws IOException {
        String body = read("return-01-7day-basic.md");

        assertTrue(body.contains("七天内可申请七天无理由退货"),
                "标题说七天、正文必须说七天；正文写十四天就是标题与内容互相矛盾");
        assertFalse(body.contains("十四天内可申请"),
                "平台七天无理由被写成十四天，是 round32 实机暴露的内容冲突（D9）");
    }

    @Test
    @DisplayName("T002 门店死蟹细则入库：六小时窗口、按只折算、订单核验、看照片不能定责")
    void t002DeadCrabPolicyCarriesTheStoreLevelFacts() throws IOException {
        Path file = repoRoot().resolve("knowledge").resolve("fresh-08-shop-t002-dead-crab.md");
        assertTrue(Files.isRegularFile(file), "round32 的门面叙事点名死蟹理赔，语料里必须有这一份");

        List<RuleChunk> chunks = MarkdownChunker.chunk(file, 7L);
        assertFalse(chunks.isEmpty(), "细则切不出规则块，等于这份语料没进检索范围");

        String text = chunks.stream().map(RuleChunk::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("六小时"), "死蟹窗口是六小时：这是 T002 与平台生鲜规则的关键差异");
        assertTrue(text.contains("按只") || text.contains("只数"), "补偿口径必须写清按只折算");
        assertTrue(text.contains("订单"), "申请要先核验订单，不能凭一句话就赔");
        assertTrue(text.contains("转人工") || text.contains("人工复核"),
                "看照片定不了责，必须写成转人工——这正是'系统办不了'那条真实边界");

        // 门店级而不是平台级：挂成 PLATFORM 会让这份细则对所有电商租户可见（可见范围含平台级）
        assertTrue(chunks.stream().allMatch(chunk -> "T002".equals(chunk.tenantId())),
                "死蟹细则是鲜果驿站自己的承诺，必须是 SHOP 级 T002");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.domain() == Domain.ECOMMERCE),
                "生鲜属于电商域：domain 要从 fresh- 前缀的意图推导出来");
    }

    private static String read(String name) throws IOException {
        return Files.readString(repoRoot().resolve("knowledge").resolve(name), StandardCharsets.UTF_8);
    }

    /** surefire 的工作目录是模块目录，往上找到含 eval/ 与 knowledge/ 的那一层。 */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("eval")) && Files.isDirectory(current.resolve("knowledge"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根");
    }
}
