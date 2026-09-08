package com.shoppilot.gateway.ingest;

import com.shoppilot.gateway.knowledge.RuleChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策 Markdown 的规则块切分器（ADR 0010）。
 *
 * <p>按二级标题切分，不重叠：重叠块会让同一条款在 RRF 里被数两次，融合分数被自己刷高。
 * {@code ruleId = hash(sourceDoc + headingPath)} 同时作为 ES {@code _id} 与 Qdrant point id，
 * 因此重跑是幂等 upsert，不存在双写一致性窗口。
 */
public final class MarkdownChunker {

    /** 低于此长度的段落没有独立检索价值，并入上一块，避免"本条自 2026 年起生效"这种碎块。 */
    private static final int MIN_CHUNK_CHARS = 120;

    private MarkdownChunker() {
    }

    public static List<RuleChunk> chunk(Path markdownFile, long kbEpoch) throws IOException {
        String raw = Files.readString(markdownFile, StandardCharsets.UTF_8);
        String sourceDoc = markdownFile.getFileName().toString().replace(".md", "");
        Map<String, String> frontMatter = parseFrontMatter(raw);
        String docTitle = frontMatter.getOrDefault("title", sourceDoc);

        List<RuleChunk> chunks = new ArrayList<>();
        List<Section> sections = splitSections(stripFrontMatter(raw));
        StringBuilder pending = new StringBuilder();
        String pendingHeading = null;
        for (Section section : sections) {
            String body = section.body().trim();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() < MIN_CHUNK_CHARS) {
                if (pendingHeading == null) {
                    pendingHeading = section.heading();
                }
                pending.append(pending.isEmpty() ? "" : "\n").append(section.heading()).append('\n').append(body);
                continue;
            }
            if (!pending.isEmpty()) {
                addChunk(chunks, sourceDoc, docTitle, frontMatter, pendingHeading, pending.toString(), kbEpoch);
                pending.setLength(0);
                pendingHeading = null;
            }
            addChunk(chunks, sourceDoc, docTitle, frontMatter, section.heading(), section.heading() + "\n" + body,
                    kbEpoch);
        }
        if (!pending.isEmpty()) {
            addChunk(chunks, sourceDoc, docTitle, frontMatter, pendingHeading, pending.toString(), kbEpoch);
        }
        return chunks;
    }

    private static void addChunk(List<RuleChunk> chunks, String sourceDoc, String docTitle,
                                 Map<String, String> frontMatter, String heading, String text, long kbEpoch) {
        String headingPath = heading == null || heading.isBlank() ? docTitle : docTitle + " > " + heading;
        String ruleId = sha256Hex(sourceDoc + "|" + headingPath);
        chunks.add(new RuleChunk(ruleId, sourceDoc, headingPath, heading == null ? docTitle : heading,
                text.strip(),
                frontMatter.getOrDefault("rule_type", "POLICY"),
                frontMatter.getOrDefault("applicable_category", "ALL"),
                frontMatter.getOrDefault("scope", "PLATFORM"),
                frontMatter.getOrDefault("tenant_id", RuleChunk.PLATFORM_TENANT),
                intentOf(sourceDoc, frontMatter),
                frontMatter.getOrDefault("effective_from", ""),
                kbEpoch));
    }

    /**
     * 意图取自文件名前缀而不是正文关键词：语料命名与 {@code CONTEXT.md} 的四个政策意图一一对应，
     * 比在入库脚本里再养一套关键词表更不容易漂移。
     */
    private static String intentOf(String sourceDoc, Map<String, String> frontMatter) {
        String explicit = frontMatter.get("intent");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (sourceDoc.startsWith("return-")) {
            return "POLICY_RETURN";
        }
        if (sourceDoc.startsWith("fresh-")) {
            return "POLICY_FRESH";
        }
        if (sourceDoc.startsWith("promo-")) {
            return "POLICY_PROMO";
        }
        if (sourceDoc.startsWith("shipping-")) {
            return "POLICY_SHIPPING";
        }
        return "UNKNOWN";
    }

    private record Section(String heading, String body) {
    }

    private static List<Section> splitSections(String raw) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) {
                if (currentHeading != null || !body.isEmpty()) {
                    sections.add(new Section(currentHeading, body.toString()));
                }
                currentHeading = trimmed.substring(3).strip();
                body.setLength(0);
            } else if (!trimmed.startsWith("# ")) {
                body.append(line).append('\n');
            }
            // 一级标题不进入正文，避免与文档标题重复计字
        }
        if (currentHeading != null || !body.toString().isBlank()) {
            sections.add(new Section(currentHeading, body.toString()));
        }
        return sections;
    }

    /** front matter 必须先剥掉，否则元数据行会被当成正文切进第一个规则块。 */
    static String stripFrontMatter(String raw) {
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            return raw;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                return String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
            }
        }
        return raw;
    }

    private static Map<String, String> parseFrontMatter(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            return values;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals("---")) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                values.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        return values;
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }
}
