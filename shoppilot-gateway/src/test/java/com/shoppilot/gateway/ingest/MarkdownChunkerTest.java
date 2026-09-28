package com.shoppilot.gateway.ingest;

import com.shoppilot.gateway.knowledge.RuleChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * round22 票 64 同交付：`CODE_MAP.md:91` 挂的那笔债（`MarkdownChunker` 缺聚焦单测）。
 *
 * <p>为什么和检索录放门同一票：融合的上游就是切块。`ruleId = sha256(sourceDoc|headingPath)`
 * 同时是 ES `_id` 与 Qdrant point id —— 它一变，两边的既有文档全部变成孤儿（旧 id 还在库里、
 * 新 id 另起一份）。所以「重跑幂等」与「切块不重叠」是融合门的地基，不是另一件事。
 */
class MarkdownChunkerTest {

    private static final long EPOCH = 7L;

    private static Path write(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /** 正文要长于 MIN_CHUNK_CHARS(120)，否则会被并进上一块 —— 与本用例要验的东西无关。 */
    private static String longBody(String marker) {
        return (marker + "。这一段的正文刻意写长一些，避免被并入相邻块，").repeat(6);
    }

    @Test
    @DisplayName("按二级标题切块、不重叠：同一段正文不会出现在两个块里")
    void sectionsBecomeNonOverlappingChunks(@TempDir Path dir) throws IOException {
        Path file = write(dir, "return-99-demo.md", """
                ---
                title: 演示政策
                rule_type: RETURN_POLICY
                ---
                # 演示政策

                ## 第一段
                %s

                ## 第二段
                %s
                """.formatted(longBody("甲"), longBody("乙")));

        List<RuleChunk> chunks = MarkdownChunker.chunk(file, EPOCH);

        assertEquals(2, chunks.size(), "两个二级标题应当切出两块");
        assertTrue(chunks.get(0).text().contains("甲"));
        assertFalse(chunks.get(0).text().contains("乙"), "重叠块会让同一条款在 RRF 里被数两次、自己把分数刷高");
        assertTrue(chunks.get(1).text().contains("乙"));
    }

    @Test
    @DisplayName("重跑幂等：同一份语料的 ruleId 逐字不变（它是 ES _id 与 Qdrant point id）")
    void ruleIdsAreStableAcrossRuns(@TempDir Path dir) throws IOException {
        Path file = write(dir, "shipping-99-demo.md", """
                ---
                title: 演示政策
                ---
                ## 时效
                %s
                """.formatted(longBody("丙")));

        List<RuleChunk> first = MarkdownChunker.chunk(file, EPOCH);
        List<RuleChunk> second = MarkdownChunker.chunk(file, EPOCH);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).ruleId(), second.get(i).ruleId(),
                    "ruleId 不稳定 = 每次入库都另起一份文档，旧的那份永久留在库里");
        }
    }

    @Test
    @DisplayName("ruleId 认的是「文档 + 标题路径」：换标题即换 id，换正文不换 id")
    void ruleIdFollowsTheHeadingPathNotTheBody(@TempDir Path dir) throws IOException {
        Path original = write(dir, "promo-99-demo.md", """
                ---
                title: 演示政策
                ---
                ## 叠加
                %s
                """.formatted(longBody("丁")));
        String firstId = MarkdownChunker.chunk(original, EPOCH).get(0).ruleId();

        Path sameHeadingNewBody = write(dir, "promo-99-demo.md", """
                ---
                title: 演示政策
                ---
                ## 叠加
                %s
                """.formatted(longBody("戊")));
        assertEquals(firstId, MarkdownChunker.chunk(sameHeadingNewBody, EPOCH).get(0).ruleId(),
                "只改正文不该换 id —— 换了就等于每次改文案都留一份孤儿文档");

        Path newHeading = write(dir, "promo-99-demo.md", """
                ---
                title: 演示政策
                ---
                ## 叠加规则
                %s
                """.formatted(longBody("丁")));
        assertNotEquals(firstId, MarkdownChunker.chunk(newHeading, EPOCH).get(0).ruleId(),
                "改标题路径必须换 id —— 否则新条款会覆盖旧文档的同一块");
    }

    @Test
    @DisplayName("意图取自文件名前缀；front matter 显式声明优先")
    void intentComesFromFileNamePrefixUnlessDeclared(@TempDir Path dir) throws IOException {
        Path fromName = write(dir, "fresh-99-demo.md", """
                ---
                title: 演示政策
                ---
                ## X
                %s
                """.formatted(longBody("己")));
        assertEquals("POLICY_FRESH", MarkdownChunker.chunk(fromName, EPOCH).get(0).intent());

        Path declared = write(dir, "fresh-98-demo.md", """
                ---
                title: 演示政策
                intent: POLICY_RETURN
                ---
                ## X
                %s
                """.formatted(longBody("庚")));
        assertEquals("POLICY_RETURN", MarkdownChunker.chunk(declared, EPOCH).get(0).intent(),
                "front matter 写了就以它为准");
    }

    @Test
    @DisplayName("front matter 不进正文；scope/tenant_id 缺省按平台级")
    void frontMatterIsStrippedAndDefaultsArePlatformScoped(@TempDir Path dir) throws IOException {
        Path file = write(dir, "return-98-demo.md", """
                ---
                title: 演示政策
                rule_type: RETURN_POLICY
                ---
                ## 某一段
                %s
                """.formatted(longBody("辛")));

        RuleChunk chunk = MarkdownChunker.chunk(file, EPOCH).get(0);

        assertFalse(chunk.text().contains("rule_type"), "front matter 行被切进正文就等于把元数据当条款答给买家");
        assertEquals("PLATFORM", chunk.scope());
        assertEquals(RuleChunk.PLATFORM_TENANT, chunk.tenantId());
        assertEquals(EPOCH, chunk.kbEpoch(), "纪元要跟着入库时点走，检索过滤器才有意义");
        assertNotNull(chunk.headingPath());
        assertTrue(chunk.headingPath().contains("演示政策"), "headingPath 至少带文档标题，引用与归因靠它");
    }

    @Test
    @DisplayName("相邻的短段落并成一块，不各自成块（避免「本条自某年起生效」这类碎块）")
    void adjacentShortSectionsMergeIntoOneChunk(@TempDir Path dir) throws IOException {
        Path file = write(dir, "return-97-demo.md", """
                ---
                title: 演示政策
                ---
                ## 短甲
                很短。

                ## 短乙
                也很短。

                ## 长段落
                %s
                """.formatted(longBody("壬")));

        List<RuleChunk> chunks = MarkdownChunker.chunk(file, EPOCH);

        // 短段落先攒在 pending 里，遇到长段落时**并成一块**吐出，然后才是长段落那一块。
        // 注意不是并进后面那块 —— 那是两个不同的形状，用例钉的是实际这一个。
        assertEquals(2, chunks.size(), "两段短的并成一块，长段落另成一块");
        assertTrue(chunks.get(0).text().contains("很短") && chunks.get(0).text().contains("也很短"),
                "相邻的短段落应当并进同一块");
        assertTrue(chunks.get(1).text().contains("壬"));
        assertFalse(chunks.get(0).text().contains("壬"), "长段落不该被并进短块");
    }
}
