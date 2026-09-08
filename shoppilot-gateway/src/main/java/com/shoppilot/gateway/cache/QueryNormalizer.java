package com.shoppilot.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 归一化 + 哈希。L1 精确命中靠它，且这条路径刻意不做任何向量化（ADR 0011 的前提）。 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 全角转半角
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }
            if (isPunctuation(c)) {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    private static boolean isPunctuation(char c) {
        switch (c) {
            case '，': case '。': case '？': case '！': case '、': case '；': case '：': case '“': case '”':
            case '（': case '）': case '《': case '》': case '·': case '～':
            case ',': case '.': case '?': case '!': case ';': case ':': case '"': case '\'':
            case '(': case ')': case '-': case '_': case '~': case '`':
                return true;
            default:
                return false;
        }
    }

    public static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 不可用", impossible);
        }
    }
}
