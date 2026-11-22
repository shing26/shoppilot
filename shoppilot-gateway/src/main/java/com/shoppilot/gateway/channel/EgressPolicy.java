package com.shoppilot.gateway.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 渠道出站的 SSRF 边界（round56 票 225 / ADR 0087）。
 *
 * <p>三道门：
 * <ol>
 *   <li>scheme 只允许 http/https，拒绝 userinfo；</li>
 *   <li>每次请求前解析 DNS，所有地址都必须是公网单播；</li>
 *   <li>重定向逐跳复检，只接受 307/308，最多 3 跳。</li>
 * </ol>
 *
 * <p><b>allowlist 是运维覆盖</b>：显式配置的 host/host:port 可以跳过私网/回环判断，
 * 用于本机联调或内网自建 webhook；云元数据地址、组播与非法 scheme 始终拒绝。
 *
 * <p>错误对外只给一句「目标地址不允许」，不回显解析出的 IP——SSRF 的报错本身
 * 也可能成为探测内网的工具。
 */
@Component
public class EgressPolicy {

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final Set<String> allowlist;
    private final HttpClient http;

    @Autowired
    public EgressPolicy(@Value("${shoppilot.outbound.allowlist:}") String allowlist) {
        this(allowlist, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    EgressPolicy(String allowlist, HttpClient http) {
        this.allowlist = parseAllowlist(allowlist);
        this.http = http;
    }

    /** 给测试与诊断用：当前 allowlist 快照。 */
    public Set<String> allowlist() {
        return Set.copyOf(allowlist);
    }

    /** 只校验，不发请求。 */
    public Decision check(URI uri) {
        if (uri == null) {
            return new Decision(false, "目标地址为空");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return new Decision(false, "只允许 http/https 目标");
        }
        if (uri.getUserInfo() != null) {
            return new Decision(false, "目标地址不允许携带用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new Decision(false, "目标地址缺少主机名");
        }
        boolean allowedByOperator = allowlisted(host, uri.getPort());
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException unknownHost) {
            return new Decision(false, "目标域名无法解析");
        }
        if (addresses.length == 0) {
            return new Decision(false, "目标域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (isMetadata(address) || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                return new Decision(false, "目标地址不允许（元数据/组播/未指定地址）");
            }
            if (!allowedByOperator && isPrivate(address)) {
                return new Decision(false, "目标地址不允许（回环/私网/链路本地）");
            }
        }
        return new Decision(true, "");
    }

    /**
     * POST 一个 JSON 出站请求；自动处理 307/308 重定向并逐跳复检。
     *
     * @throws DeniedException 目标被策略拒绝或跳转非法
     */
    public HttpResponse<String> post(URI target, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        URI current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Decision decision = check(current);
            if (!decision.allowed()) {
                throw new DeniedException(decision.reason());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            headers.forEach(builder::header);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }
            if (status != 307 && status != 308) {
                // 301/302/303 会把 POST 改成 GET；跟随它就等于放弃出站方法语义。
                throw new DeniedException("目标返回了不允许的重定向");
            }
            if (hop == MAX_REDIRECTS) {
                throw new DeniedException("重定向次数过多");
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                throw new DeniedException("重定向缺少 Location");
            }
            URI next;
            try {
                next = current.resolve(location);
            } catch (IllegalArgumentException malformed) {
                throw new DeniedException("重定向地址不合法");
            }
            if (next.getScheme() == null
                    || !(next.getScheme().equalsIgnoreCase("http") || next.getScheme().equalsIgnoreCase("https"))) {
                throw new DeniedException("重定向目标只允许 http/https");
            }
            current = next;
        }
        throw new DeniedException("重定向次数过多");
    }

    private boolean allowlisted(String host, int port) {
        String lower = host.toLowerCase();
        String withPort = port <= 0 ? lower : lower + ":" + port;
        return allowlist.contains(lower) || allowlist.contains(withPort);
    }

    private static Set<String> parseAllowlist(String raw) {
        Set<String> parsed = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter((entry) -> !entry.isEmpty())
                .map(String::toLowerCase)
                .forEach(parsed::add);
        return parsed;
    }

    private static boolean isMetadata(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xff) == 169 && (bytes[1] & 0xff) == 254
                && (bytes[2] & 0xff) == 169 && (bytes[3] & 0xff) == 254;
    }

    /** 回环、RFC1918、链路本地、IPv6 ULA、CGNAT、保留/基准测试网段。 */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
            if (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) {
                return true;
            }
            // IPv4 文档/基准测试网段（RFC 5737）
            if ((first == 192 && second == 0 && (bytes[2] & 0xff) == 2)
                    || (first == 198 && second == 51 && (bytes[2] & 0xff) == 100)
                    || (first == 203 && second == 0 && (bytes[2] & 0xff) == 113)) {
                return true;
            }
            return first >= 224;
        }
        if (bytes.length == 16) {
            // 2001:db8::/32 IPv6 文档前缀（RFC 3849）
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
            // fc00::/7 唯一本地地址
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    public record Decision(boolean allowed, String reason) {
    }

    /** 对外消息必须已经是「不泄露内网细节」的人话。 */
    public static final class DeniedException extends IOException {
        public DeniedException(String message) {
            super(message);
        }
    }
}
