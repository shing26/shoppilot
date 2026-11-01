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
    @DisplayName("round32：hotel- 前缀映射到 POLICY_HOTEL，且租户归属跟着 front matter 走（不挂平台级）")
    void hotelPrefixMapsToTheHotelIntentAndStaysTenantScoped(@TempDir Path dir) throws IOException {
        Path file = write(dir, "hotel-99-demo.md", """
                ---
                title: 演示酒店政策
                rule_type: HOTEL_POLICY
                scope: SHOP
                tenant_id: T004
                ---
                ## 入住
                %s
                """.formatted(longBody("癸")));

        RuleChunk chunk = MarkdownChunker.chunk(file, EPOCH).get(0);

        assertEquals("POLICY_HOTEL", chunk.intent(),
                "入库映射漏了这一支，酒店语料会以 UNKNOWN 进库：检索得到、但永远不准入缓存");
        // 挂成 PLATFORM 会让酒店规则对所有电商租户可见（可见范围是 tenantId + PLATFORM），
        // 那是跨行业的污染，不是"顺手多一份语料"。
        assertEquals("T004", chunk.tenantId());
        assertNotEquals(RuleChunk.PLATFORM_TENANT, chunk.tenantId());
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

    /**
     * round43 票 186（ADR 0078）：知识修订不走文件，frontmatter 由调用方显式给出。
     * "rev-" 前缀没有意图含义 —— 前缀猜测那条路对修订稿必须失效，intent 只能来自修订行；
     * 同理 tenant_id/scope 缺省会挂平台级，而修订稿的归属是发布时人工确认的，不许吃缺省。
     */
    @Test
    @DisplayName("字符串重载：frontmatter 显式给出就一字不差地落进块；rev- 前缀不吃文件名猜测")
    void stringOverloadCarriesTheExplicitFrontMatter() {
        List<RuleChunk> chunks = MarkdownChunker.chunk("rev-KR1", """
                ## 生效范围
                %s
                """.formatted(longBody("甲")), java.util.Map.of(
                        "title", "退换货口径",
                        "intent", "POLICY_RETURN",
                        "domain", "ECOMMERCE",
                        "scope", "SHOP",
                        "tenant_id", "T001"), EPOCH);

        assertEquals(1, chunks.size());
        RuleChunk chunk = chunks.get(0);
        assertEquals("POLICY_RETURN", chunk.intent(), "意图只认显式修订行，不吃前缀猜测");
        assertEquals("SHOP", chunk.scope());
        assertEquals("T001", chunk.tenantId(), "修订稿的租户归属是人工确认的，缺省成平台级就是跨租户污染");
        assertEquals("ECOMMERCE", chunk.domain().name());
        // title 沿用文件语料的语义：带标题的块取标题行，文档标题进 headingPath。
        assertEquals("生效范围", chunk.title());
        assertTrue(chunk.headingPath().contains("退换货口径") && chunk.headingPath().contains("生效范围"));
        assertEquals(EPOCH, chunk.kbEpoch());
    }

    @Test
    @DisplayName("字符串重载：ruleId 仍是 hash(sourceDoc|headingPath)，重跑幂等")
    void stringOverloadRuleIdsStayDeterministic() {
        String markdown = "## 口径\n%s".formatted(longBody("乙"));
        java.util.Map<String, String> frontMatter = java.util.Map.of("title", "修订标题");

        List<RuleChunk> first = MarkdownChunker.chunk("rev-KR9", markdown, frontMatter, EPOCH);
        List<RuleChunk> second = MarkdownChunker.chunk("rev-KR9", markdown, frontMatter, EPOCH);

        assertEquals(first.get(0).ruleId(), second.get(0).ruleId());
        assertEquals(MarkdownChunker.sha256Hex("rev-KR9|修订标题 > 口径"), first.get(0).ruleId(),
                "ruleId 公式与文件语料一字不差：两侧同 id 才能同走幂等 upsert");
    }

    @Test
    @DisplayName("Path 委托解析文件内 frontmatter（title 进 headingPath），不靠调用方重传")
    void pathOverloadParsesFrontMatterItself(@TempDir Path dir) throws IOException {
        Path file = write(dir, "promo-96-demo.md", """
                ---
                title: 演示政策
                ---
                ## 叠加
                %s
                """.formatted(longBody("丙")));

        RuleChunk chunk = MarkdownChunker.chunk(file, EPOCH).get(0);

        assertEquals("POLICY_PROMO", MarkdownChunker.chunk("promo-96-demo",
                Files.readString(file, StandardCharsets.UTF_8), java.util.Map.of(), EPOCH).get(0).intent(),
                "同一份语料走两条入口，意图必须一致（promo- 前缀）");
        assertTrue(chunk.headingPath().contains("演示政策"),
                "Path 入口的 title 来自文件内 frontmatter，丢了它 headingPath 就退回文件名");
        assertEquals("POLICY_PROMO", chunk.intent());
    }
}
