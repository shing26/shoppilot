package com.shoppilot.gateway.store;

import com.shoppilot.gateway.scenario.ScenarioPackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把店铺资料拼进系统提示词（round37）。
 *
 * <p>未登记租户返回空串，不猜、不拿别家店的资料补位——跨店串话术正是 round33 修掉的
 * 那类缺陷。提示词里只放「店名 / 主营」；**「本店承诺」不进 Prompt**：配置里的摘要删掉了
 * 条款的限定条件（除外类目、次数上限、券/现金之别），模型一旦把它当政策复述，就会给出
 * 比真实政策更宽的承诺。承诺类问题走政策检索与引用，读面接口仍可把摘要给前端做店铺名片。
 */
@Component
public class StoreProfileService {

    private static final String HEADER =
            "【本店资料】（只用于介绍店铺与引导；具体政策、时限、金额必须以【政策条款】或工具返回为准，"
                    + "不得从本段推断或承诺；本店没有可查询的商品目录，推荐时只按主营品类给方向，"
                    + "不要编造具体商品名、价格或库存；条款标注【本店】与【平台】，同一事项口径不同时"
                    + "以【本店】为准，只复述条款原文明确写出的时限、次数与金额，不要补全或合并出更宽的承诺）";
    private final Map<String, StoreProfileProperties.Profile> profiles;
    private final ScenarioPackService scenarios;

    public StoreProfileService(StoreProfileProperties properties) {
        this(properties, ScenarioPackService.empty());
    }

    @Autowired
    public StoreProfileService(StoreProfileProperties properties, ScenarioPackService scenarios) {
        this.scenarios = scenarios;
        if (properties == null || properties.profiles() == null) {
            this.profiles = Map.of();
            return;
        }
        // YAML 里 `T005:`（无子键）会绑定成 null value；Map.copyOf 遇 null 直接 NPE 拒启，
        // 而一条空店铺配置不该把整个网关打掉。过滤掉空项即可。
        Map<String, StoreProfileProperties.Profile> cleaned = new HashMap<>();
        properties.profiles().forEach((tenantId, profile) -> {
            if (tenantId != null && profile != null) {
                cleaned.put(tenantId, profile);
            }
        });
        this.profiles = Map.copyOf(cleaned);
    }

    public Optional<StoreProfileProperties.Profile> find(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        StoreProfileProperties.Profile fromPack = scenarios.profileFor(tenantId);
        if (fromPack != null) {
            return Optional.of(fromPack);
        }
        return Optional.ofNullable(profiles.get(tenantId.trim()));
    }

    /** 返回可直接追加到基座提示词后的段落；没有该租户资料时返回空串。 */
    public String augment(String baseSystemPrompt, String tenantId) {
        Optional<StoreProfileProperties.Profile> profile = find(tenantId);
        if (profile.isEmpty()) {
            return baseSystemPrompt;
        }
        StoreProfileProperties.Profile value = profile.get();
        StringBuilder section = new StringBuilder(HEADER).append('\n');
        appendIfPresent(section, "店名", value.name());
        appendIfPresent(section, "主营", value.business());
        // 刻意不拼 guarantees：承诺类事实只由【政策条款】给出（见类注释）。
        String addendum = scenarios.addendumFor(tenantId);
        if (!addendum.isBlank()) {
            section.append("【场景补充】").append(addendum).append('\n');
        }
        return baseSystemPrompt + "\n" + section;
    }

    private static void appendIfPresent(StringBuilder target, String label, String value) {
        if (value != null && !value.isBlank()) {
            target.append(label).append('：').append(value.trim()).append('\n');
        }
    }
}
