package com.shoppilot.gateway.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.store.StoreProfileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 场景包（票 172）：把“租户属于哪个场景、这个场景的店铺人格/补充提示”收成可复制文件。
 *
 * <p>包只覆盖配置层：行业域、店铺人格、场景补充提示。政策时限/金额仍只来自
 * knowledge/ 检索与工具返回；包里的 promptAddendum 不得写具体承诺，否则会和条款打架。
 *
 * <p>优先级：场景包 &gt; application.yml 的 tenant-domains/store-profiles。
 * 同一个 tenant 出现在两个包直接拒启——静默取最后一个会让租户归属不可审计。
 */
@Component
public class ScenarioPackService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioPackService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Pack(String id, Domain domain, Map<String, StoreProfileProperties.Profile> stores,
                       String promptAddendum) {
    }

    private final Map<String, Pack> packsById;
    private final Map<String, Domain> domains;
    private final Map<String, StoreProfileProperties.Profile> profiles;
    private final Map<String, String> addenda;

    public ScenarioPackService(@Value("${shoppilot.scenario-packs.dir:config/scenarios}") String dir) {
        this(loadPacks(dir));
    }

    private ScenarioPackService(Loaded loaded) {
        this.packsById = Map.copyOf(loaded.packs);
        this.domains = Map.copyOf(loaded.domains);
        this.profiles = Map.copyOf(loaded.profiles);
        this.addenda = Map.copyOf(loaded.addenda);
        if (!this.packsById.isEmpty()) {
            log.info("场景包已加载：{}（租户 {} 个）", this.packsById.keySet(), this.domains.size());
        }
    }

    /** 测试/无场景包时使用。 */
    public static ScenarioPackService empty() {
        return new ScenarioPackService(new Loaded());
    }

    public Collection<Pack> packs() {
        return packsById.values();
    }

    public Domain domainFor(String tenantId) {
        return tenantId == null ? null : domains.get(tenantId.trim());
    }

    public StoreProfileProperties.Profile profileFor(String tenantId) {
        return tenantId == null ? null : profiles.get(tenantId.trim());
    }

    public String addendumFor(String tenantId) {
        return tenantId == null ? "" : addenda.getOrDefault(tenantId.trim(), "");
    }

    private static Loaded loadPacks(String dir) {
        Loaded loaded = new Loaded();
        if (dir == null || dir.isBlank()) {
            return loaded;
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return loaded;
        }
        try (Stream<Path> files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> loadFile(path, loaded));
        } catch (IOException failure) {
            throw new IllegalStateException("场景包目录读取失败：" + root.toAbsolutePath(), failure);
        }
        return loaded;
    }

    private static void loadFile(Path path, Loaded loaded) {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(path));
        } catch (Exception failure) {
            throw new IllegalStateException("场景包读取失败：" + path.toAbsolutePath(), failure);
        }
        String id = root.path("id").asText("").trim();
        if (id.isBlank()) {
            throw new IllegalStateException("场景包 " + path.getFileName() + " 缺 id");
        }
        if (loaded.packs.containsKey(id)) {
            throw new IllegalStateException("场景包 id 重复：" + id);
        }
        String domainRaw = root.path("domain").asText("").trim();
        Domain domain;
        try {
            domain = Domain.valueOf(domainRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("场景包 " + id + " domain 非法：" + domainRaw);
        }
        if (domain == Domain.UNKNOWN) {
            throw new IllegalStateException("场景包 " + id + " domain 不能是 UNKNOWN");
        }
        Map<String, StoreProfileProperties.Profile> stores = new LinkedHashMap<>();
        JsonNode storesNode = root.path("stores");
        if (storesNode.isObject()) {
            storesNode.fields().forEachRemaining(entry -> {
                String tenantId = entry.getKey().trim();
                if (tenantId.isBlank()) {
                    throw new IllegalStateException("场景包 " + id + " 有空 tenantId");
                }
                if (loaded.domains.containsKey(tenantId)) {
                    throw new IllegalStateException("tenant " + tenantId + " 出现在多个场景包：" + id);
                }
                JsonNode node = entry.getValue();
                String name = node.path("name").asText("").trim();
                String business = node.path("business").asText("").trim();
                String guarantees = node.path("guarantees").asText("").trim();
                if (name.isBlank() || business.isBlank()) {
                    throw new IllegalStateException("场景包 " + id + " 的 " + tenantId + " 缺 name/business");
                }
                stores.put(tenantId, new StoreProfileProperties.Profile(name, business, guarantees));
                loaded.domains.put(tenantId, domain);
            });
        }
        String addendum = root.path("promptAddendum").asText("").trim();
        Pack pack = new Pack(id, domain, Map.copyOf(stores), addendum);
        loaded.packs.put(id, pack);
        stores.forEach((tenantId, profile) -> {
            loaded.profiles.put(tenantId, profile);
            if (!addendum.isBlank()) {
                loaded.addenda.put(tenantId, addendum);
            }
        });
    }

    private static final class Loaded {
        private final Map<String, Pack> packs = new HashMap<>();
        private final Map<String, Domain> domains = new HashMap<>();
        private final Map<String, StoreProfileProperties.Profile> profiles = new HashMap<>();
        private final Map<String, String> addenda = new HashMap<>();
    }
}
