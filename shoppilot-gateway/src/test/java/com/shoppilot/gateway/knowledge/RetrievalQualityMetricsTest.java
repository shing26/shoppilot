package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.ingest.MarkdownChunker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量对比（round26 收尾，2026-10-05）：把「混合检索到底赢在哪」从**定性说法**变成**数字**。
 *
 * <p><b>为什么必须离线算</b>：三路的完整序已经录在 round22 的夹具里（每路 20 深），
 * 而要算质量指标只需要**哪一条排在前面**——不需要模型、不需要引擎、不需要栈。
 * 于是这张表是 <b>0 token、干净 runner 可复现</b>的（这与 ADR 0049 那道录放门是同一层机制）。
 *
 * <p><b>为什么上一份对比没有判别力</b>（{@code docs/retrieval-comparison.md}）：
 * 它比的是 hit@5，而两路在那 16 条查询上都 **16/16**，差异 0 条——指标饱和了。
 * 所以本类的主指标是 <b>MRR</b>（第一个命中的名次倒数），它在那份数据里也全是 1.0 时，
 * 说明<b>这批查询对「谁更准」几乎没有区分度</b>；真正能区分的是**名次如何被融合改写**，
 * 那部分由 {@link RetrievalFusionReplayTest} 守（确定性）而本类给出可读的那张表。
 *
 * <p><b>口径诚实</b>：三臂都给 top-5（生产里 {@code fusedTopK=5}，而 single-path 的 20 只是融合输入），
 * 所以 hit@5 / MRR@5 是**同一把尺子**。另有一张表按各臂的<b>候选深度</b>给「首个命中的名次」——
 * 它<b>不可跨臂比较</b>，只说明「没截断的话它本来排第几」，表里写明了这一点。
 */
class RetrievalQualityMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture;
    private static JsonNode labels;
    private static Map<String, String> ruleIdToDoc;

    @BeforeAll
    static void loadFixtureLabelsAndProductionChunking() throws IOException {
        fixture = MAPPER.readTree(Files.readString(newestFixture(), StandardCharsets.UTF_8));
        labels = MAPPER.readTree(Files.readString(repoRoot().resolve("eval/retrieval-labels.json"),
                StandardCharsets.UTF_8));
        // ruleId -> 源文件名：用**生产那一份**切分器现算，不在测试里另写一遍哈希规则。
        ruleIdToDoc = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("knowledge"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                for (RuleChunk chunk : MarkdownChunker.chunk(file, 1L)) {
                    ruleIdToDoc.put(chunk.ruleId(), chunk.sourceDoc());
                }
            }
        }
    }

    @Test
    @DisplayName("夹具里的每个 ruleId 都能被生产切分器还原成源文件（否则下面的指标是在算哈希）")
    void everyRecordedRuleIdResolvesToASourceDoc() {
        List<String> unknown = new ArrayList<>();
        for (JsonNode oneCase : fixture.path("cases")) {
            for (String path : List.of("dense", "lexical", "fused")) {
                for (JsonNode id : oneCase.path(path)) {
                    if (!ruleIdToDoc.containsKey(id.asText())) {
                        unknown.add(oneCase.path("id").asText() + "/" + path + "/" + id.asText());
                    }
                }
            }
        }
        assertThat(unknown).as("语料与夹具不同源（改了 knowledge/*.md 就该重录夹具）").isEmpty();
    }

    @Test
    @DisplayName("标注集与夹具一一对应，且每条都指到一个存在的语料文件")
    void labelsLineUpWithTheFixture() {
        assertThat(labels.path("cases")).hasSize(fixture.path("cases").size());
        List<String> fixtureIds = new ArrayList<>();
        fixture.path("cases").forEach(c -> fixtureIds.add(c.path("id").asText()));
        List<String> labelIds = new ArrayList<>();
        labels.path("cases").forEach(c -> labelIds.add(c.path("fixture").asText()));
        assertThat(labelIds).containsExactlyInAnyOrderElementsOf(fixtureIds);
        assertThat(ruleIdToDoc.values()).containsAll(
                labels.path("cases").findValuesAsText("expect_doc"));
    }

    @Test
    @DisplayName("三臂在同一把尺子（top-5）上的 hit@5 与 MRR@5")
    void threeArmsOnTheSameRuler() {
        Map<String, double[]> mrr = new LinkedHashMap<>();
        int[] hit = new int[3];
        int n = 0;

        for (JsonNode label : labels.path("cases")) {
            JsonNode oneCase = caseById(label.path("fixture").asText());
            String expect = label.path("expect_doc").asText();
            // 顺序必须与 name(arm) 一致：arm 0 → "lexical"、1 → "dense"、2 → "fused"。
            // 第一版把 dense 放在第 0 位，于是汇总表把 BM25 与稠密**接反了**——
            // 而它之所以被发现，是因为**汇总里 MRR=0.875、逐条表里却全是 1**，两处对不上。
            List<List<String>> arms = List.of(
                    texts(oneCase, "lexical", 5),
                    texts(oneCase, "dense", 5),
                    texts(oneCase, "fused", 5));
            double[] perCase = new double[3];
            for (int arm = 0; arm < arms.size(); arm++) {
                int rank = firstRank(arms.get(arm), expect);
                if (rank > 0) {
                    hit[arm]++;
                    perCase[arm] = 1.0d / rank;
                }
            }
            for (int arm = 0; arm < arms.size(); arm++) {
                mrr.computeIfAbsent(name(arm), k -> new double[labels.path("cases").size()])[n] = perCase[arm];
            }
            n++;
        }

        int cases = labels.path("cases").size();
        StringBuilder md = new StringBuilder();
        md.append("# 检索质量：BM25 / 稠密 / RRF 混合（同一把尺子 top-5）\n\n");
        md.append("> 生成：`RetrievalQualityMetricsTest`（JVM，0 token，干净 runner 可复现）。\n")
          .append("> 数据源：`eval/retrieval-fixture-20260928.json`（round22 录的三路完整序）+ ")
          .append("`eval/retrieval-labels.json`（标注）。**不重跑检索、不连模型、不连引擎。**\n\n")
          .append("相关 = 期望语料文件的**任一规则块**（沿用 `docs/retrieval-comparison.md` 的判据口径）。\n")
          .append("三臂都给 top-5：生产里 `fusedTopK=5`，而 single-path 的 20 只是**融合输入**，不是它对外的答案长度。\n\n")
          .append("## 汇总\n\n")
          .append("| 臂 | hit@5 | MRR@5 |\n|---|---|---|\n");
        String[] armNames = {"BM25（lexical）", "稠密（dense）", "RRF 混合（fused）"};
        for (int arm = 0; arm < armNames.length; arm++) {
            double avg = mrr.get(name(arm)).length == 0 ? 0
                    : java.util.Arrays.stream(mrr.get(name(arm))).average().orElse(0);
            md.append(String.format("| %s | %d/%d | %.3f |\n", armNames[arm], hit[arm], cases, avg));
        }
        md.append("\n");

        // 断言：数字被钉住。语料或夹具一变，这里先红，表才跟着重生成。
        assertThat(hit[0]).as("BM25 hit@5").isEqualTo(EXPECT_HIT_LEXICAL);
        assertThat(hit[1]).as("稠密 hit@5").isEqualTo(EXPECT_HIT_DENSE);
        assertThat(hit[2]).as("混合 hit@5").isEqualTo(EXPECT_HIT_FUSED);
        assertThat(mrr.get("lexical")).as("MRR 数组的长度必须等于查询数，否则平均被悄悄少算").hasSize(cases);
        for (int arm = 0; arm < armNames.length; arm++) {
            assertThat(hit[arm]).as("%s 的 hit@5 与 MRR 不能一个 10/10 一个不是 1.000——那说明臂接反了",
                    armNames[arm]).isEqualTo(cases);
        }

        writeReport(md.toString(), mrr, hit, cases);
    }

    // 下面这组期望值是**首次实跑后钉住的**，不是先写数字再凑实现。
    // 改动它们的唯一理由是「语料或夹具真的变了」，而那时 `everyRecordedRuleIdResolvesToASourceDoc` 会先红。
    //
    // **三臂都是 10/10**：hit@5 在这批查询上**完全饱和**，与前作那份 16 条对比是同一个现象。
    // 所以「混合检索更好」这句话在这批数据上**测不出来**——能测出来的只有 MRR 的细微差别，
    // 而那点差别落在单条查询的名次上，不是系统性优势。这句话必须写进报告，不能让读者
    // 从「三列都有 10」里自己推断出「所以都一样好」。
    private static final int EXPECT_HIT_LEXICAL = 10;
    private static final int EXPECT_HIT_DENSE = 10;
    private static final int EXPECT_HIT_FUSED = 10;

    /**
     * 生成入库的 markdown。
     *
     * <p>**换行一律显式反斜杠 n，不用 {@code %n}**：后者在 Windows 上是回车换行，
     * 会与文件其余部分的 LF 混排，然后 {@code git diff --check} 逐行判成 trailing whitespace
     * ——产物入库之后，这类混排就是「机器判据自己红自己」。
     */
    private void writeReport(String head, Map<String, double[]> mrr, int[] hit, int cases) {
        StringBuilder md = new StringBuilder(head);
        md.append("## 逐条：首个命中的名次（**三臂同深 top-5**）\n\n")
          .append("| 查询 | 期望语料 | BM25 | 稠密 | 混合 |\n|---|---|---|---|---|\n");
        for (JsonNode label : labels.path("cases")) {
            JsonNode oneCase = caseById(label.path("fixture").asText());
            String expect = label.path("expect_doc").asText();
            md.append(String.format("| %s | `%s` | %s | %s | %s |\n", label.path("query").asText(), expect,
                    cell(texts(oneCase, "lexical", 5), expect),
                    cell(texts(oneCase, "dense", 5), expect),
                    cell(texts(oneCase, "fused", 5), expect)));
        }
        md.append("\n## 融合到底改了什么（**不需要标注**的一层）\n\n")
          .append("质量指标饱和时，能问的另一个问题是「融合把名次改成了什么样」。这一层**不依赖标注**，\n")
          .append("所以它的结论不受上面那份标注的偏差影响。\n\n")
          .append("| 查询 | dense top-1 | BM25 top-1 | 混合 top-1 | 混合的 top-1 来自 |\n|---|---|---|---|---|\n");
        int fromDense = 0;
        int fromLexical = 0;
        int fromNeither = 0;
        int sameAsDense = 0;
        for (JsonNode label : labels.path("cases")) {
            JsonNode oneCase = caseById(label.path("fixture").asText());
            String d1 = top1(oneCase, "dense");
            String l1 = top1(oneCase, "lexical");
            String f1 = top1(oneCase, "fused");
            if (f1.equals(d1)) {
                sameAsDense++;
            }
            String from;
            if (f1.equals(d1) && !f1.equals(l1)) {
                from = "与 dense 相同（两路本来就一致）";
            } else if (f1.equals(l1)) {
                from = "**BM25**";
                fromLexical++;
            } else if (f1.equals(d1)) {
                from = "dense";
                fromDense++;
            } else {
                from = "**两路都不是**（第三来源）";
                fromNeither++;
            }
            md.append(String.format("| %s | %s | %s | %s | %s |\n", label.path("query").asText(), d1, l1, f1, from));
        }
        md.append(String.format("\n- 混合的 top-1 与 dense 相同：**%d / %d** 条\n", sameAsDense, cases));
        md.append(String.format("- 混合的 top-1 改由 **BM25** 提供：**%d** 条（融合把稀疏路顶上来的地方）\n", fromLexical));
        md.append(String.format("- 混合的 top-1 来自**两路都不是**的第三来源：**%d** 条\n", fromNeither));
        md.append(String.format("- 混合的 top-1 来自 dense 但与 BM25 不同：**%d** 条\n", fromDense));
        md.append("\n**这张表的读法**：融合在这一批数据上的作用主要是**不让 BM25 的好结果丢掉**，"
          + "而不是把稠密的结果改好。\n");

        md.append("\n## 结论（按实测算，不按设计意图写）\n\n");
        md.append("1. **hit@5 在这批查询上完全饱和**（三臂都 10/10），所以「谁更准」这句话**测不出来**——"
          + "这与 `docs/retrieval-comparison.md` 那 16 条是同一个现象，不是新问题。\n");
        md.append(String.format("2. **MRR@5：稠密 %.3f、混合 %.3f、BM25 %.3f**。"
          + "也就是说**在这批查询上融合追平了最好的一臂、比 BM25 高，但它没有超过稠密**。\n",
          avg(mrr.get("dense")), avg(mrr.get("fused")), avg(mrr.get("lexical"))));
        md.append("3. **所以诚实的说法不是「混合更好」，而是「混合没有更差」**：它保住了 BM25 在这批查询里的好名次，"
          + "而代价（多一次融合计算）在这批数据上**没有换成更高的命中**。\n");
        md.append("4. 要证明融合在**困难查询**上有价值，需要一批**故意难**的样本（同义改写、多意图、需要跨文档综合的）；"
          + "这批 10 条的挑选标准是「两路结果不同」，**对融合有利**，所以上面的结论**不能外推**。\n");

        md.append("\n## 这张表测不出什么（口径的边界，不藏）\n\n")
          .append("1. **它测「查得到 / 查不到」，不测「答得对 / 答错」**。标注按文件而非按块，\n")
          .append("   所以需要**跨文档综合**才能回答的问题（round19 QA 记过：「退款到哪了」需先查再答）会被判成不命中。\n")
          .append("2. **标注由作者读标题拟定、未对排序盲化**。这会在「同义但不同词」的查询上略微偏向稠密召回；\n")
          .append("   偏差方向写在这里而不是脚注里——一张有偏的表，读者有权知道它往哪边偏。\n")
          .append("3. **10 条查询的样本量不足以做显著性判断**。它能回答「这批查询上谁没掉队」，\n")
          .append("   不能回答「A 优于 B」。要下那个结论需要人工标注的百条量级，那是另一笔投入。\n")
          .append("4. **它复用了 round22 录的那 10 条**，而那份录制的选择标准不是「难查询」而是「两路结果不同」——\n")
          .append("   所以这批查询**对混合检索是有偏利的样本**。BM少赢几条不代表它在别处也弱。\n");
        writeFile(repoRoot().resolve("docs/retrieval-quality-metrics.md"), md.toString());
    }

    private static double avg(double[] values) {
        return values.length == 0 ? 0 : java.util.Arrays.stream(values).average().orElse(0);
    }

    /** 某一路 top-1 对应的源文件名；空序时给 "-" 而不是抛——表要能印出来。 */
    private static String top1(JsonNode oneCase, String arm) {
        List<String> docs = texts(oneCase, arm, 1);
        return docs.isEmpty() ? "-" : docs.get(0);
    }

    private static String cell(List<String> rankedDocs, String expect) {
        int rank = firstRank(rankedDocs, expect);
        return rank > 0 ? String.valueOf(rank) : "未命中";
    }

    private static int firstRank(List<String> rankedDocs, String expectDoc) {
        for (int i = 0; i < rankedDocs.size(); i++) {
            if (expectDoc.equals(rankedDocs.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 把一条路径的 ruleId 序换成源文件名序——**按文件折叠**（同一篇的多个块都算命中）。 */
    private static List<String> texts(JsonNode oneCase, String arm, int limit) {
        List<String> docs = new ArrayList<>();
        for (JsonNode id : oneCase.path(arm)) {
            String doc = ruleIdToDoc.get(id.asText());
            if (doc != null && !docs.contains(doc)) {
                docs.add(doc);
            }
            if (docs.size() >= limit) {
                break;
            }
        }
        return docs;
    }

    private static JsonNode caseById(String id) {
        for (JsonNode oneCase : fixture.path("cases")) {
            if (id.equals(oneCase.path("id").asText())) {
                return oneCase;
            }
        }
        throw new IllegalStateException("夹具里没有 case " + id + "（重录夹具后标注集要一起更新）");
    }

    private static String name(int arm) {
        return List.of("lexical", "dense", "fused").get(arm);
    }

    private static void writeFile(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("写不出 " + path + "：" + failure.getMessage(), failure);
        }
    }

    private static Path newestFixture() throws IOException {
        try (Stream<Path> files = Files.list(repoRoot().resolve("eval"))) {
            List<Path> fixtures = files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("retrieval-fixture-") && n.endsWith(".json");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            assertThat(fixtures).as("eval/ 下没有 retrieval-fixture-*.json").isNotEmpty();
            return fixtures.get(0);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("eval")) && Files.isDirectory(current.resolve("knowledge"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("往上找不到仓库根（含 eval/ 与 knowledge/）");
    }
}
