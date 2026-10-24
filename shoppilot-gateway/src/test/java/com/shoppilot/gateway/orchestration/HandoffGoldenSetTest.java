package com.shoppilot.gateway.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handoff golden set（ADR 0072）：判据是**路由纯函数 + 工具候选面 + 有向边**，
 * 不连模型、不连中间件。它独立于 180 条 gold，不并分母。
 */
class HandoffGoldenSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<JsonNode> cases;

    @BeforeAll
    static void loadGoldenSet() throws IOException {
        cases = Files.readAllLines(repoRoot().resolve("eval/handoff-golden.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return MAPPER.readTree(line);
                    } catch (IOException malformed) {
                        throw new IllegalStateException("handoff golden line 不是 JSON: " + line, malformed);
                    }
                })
                .toList();
    }

    @Test
    @DisplayName("golden 集必须覆盖：两条售前意图、写/UNKNOWN 售后、行业门与四类 Handoff 边界")
    void fixtureCoversTheDeclaredSurface() {
        List<String> ids = cases.stream().map(c -> c.path("id").asText()).toList();
        assertThat(ids).contains(
                "H-ROUTE-001", "H-ROUTE-003", "H-ROUTE-005",
                "H-INDUSTRY-001", "H-EDGE-001", "H-EDGE-002", "H-EDGE-003", "H-EDGE-004");
    }

    @Test
    @DisplayName("逐条重算路由与工具面，golden 判据一字不改")
    void recomputesEveryRouteCase() {
        for (JsonNode c : cases) {
            if (!"route".equals(c.path("kind").asText())) {
                continue;
            }
            Intent intent = Intent.valueOf(c.path("intent").asText());
            Domain domain = Domain.valueOf(c.path("domain").asText());
            AgentRole role = AgentRole.routeFor(intent);
            List<Map<String, Object>> surface = ToolSurfacePolicy.toolsFor(role, domain, intent);

            assertThat(role.name()).as("case %s role", c.path("id").asText())
                    .isEqualTo(c.path("expectedRole").asText());
            String actual = surface.isEmpty() ? "NO_TOOLS"
                    : (surface.containsAll(ToolContracts.actionDescriptors())
                            && surface.size() == ToolContracts.actionDescriptors().size()
                            ? "ACTION_SURFACE" : "UNEXPECTED");
            assertThat(actual).as("case %s surface", c.path("id").asText())
                    .isEqualTo(c.path("expectedSurface").asText());
        }
    }

    @Test
    @DisplayName("逐条重算 Handoff 边：允许/拒绝原因都必须命中")
    void recomputesEveryHandoffCase() {
        HandoffGraph graph = new HandoffGraph();
        for (JsonNode c : cases) {
            if (!"handoff".equals(c.path("kind").asText())) {
                continue;
            }
            var decision = graph.next(AgentRole.valueOf(c.path("from").asText()),
                    AgentRole.valueOf(c.path("to").asText()), c.path("depth").asInt());
            String actual = decision.allowed() ? "ALLOWED"
                    : decision.blockedReason().map(HandoffBlockReason::name).orElse("UNEXPECTED");
            assertThat(actual).as("case %s handoff", c.path("id").asText())
                    .isEqualTo(c.path("expectedResult").asText());
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("eval/handoff-golden.jsonl"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("往上找不到仓库根（含 eval/handoff-golden.jsonl）");
        }
        return path;
    }
}
