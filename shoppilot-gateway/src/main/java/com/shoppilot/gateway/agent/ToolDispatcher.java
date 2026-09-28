package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.schema.ToolSchemaGenerator;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行前的槽位校验与调用分发。
 *
 * <p>缺必填槽位一律交回状态机追问，绝不猜（猜订单号等于拿别人的订单）。
 * 订单号还要再过一道**溯源**：格式合法但买家在对话里没说过，同样按编造处理——模型照抄工具
 * schema 描述里的示例值 `例如 10023` 就是这么进来的（ADR 0045 票 56）。
 */
@Component
public class ToolDispatcher {

    private final BizMockClient bizMockClient;
    private final IdempotencyService idempotency;
    private final MeterRegistry registry;

    public ToolDispatcher(BizMockClient bizMockClient, IdempotencyService idempotency, MeterRegistry registry) {
        this.bizMockClient = bizMockClient;
        this.idempotency = idempotency;
        this.registry = registry;
    }

    /** 订单号格式与 biz-mock 的造数一致；模型凭空编一个长串时按编造处理，不发起查询。 */
    private static final java.util.regex.Pattern ORDER_NO = java.util.regex.Pattern.compile("\\d{1,12}");

    /**
     * @param fabricated 模型给出的订单号**不可信**：格式非法、或格式合法但买家在对话里没说过。
     *                   两种情况都不允许发起查询（ADR 0045 票 56）
     */
    public record Dispatch(ToolName tool, ToolStatus status, String json, List<String> missingSlots, String label,
                           boolean fabricated, boolean duplicate) {

        public boolean needsSlot() {
            return missingSlots != null && !missingSlots.isEmpty();
        }

        public boolean degraded() {
            return status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE;
        }

        /** 模型编出了不存在的工具名：不能拿 null 工具往下走，直接兜底。 */
        public boolean unknown() {
            return tool == null;
        }
    }

    /**
     * @param dialogue 买家说过的话（本轮 query + 历史买家轮次），用于订单号溯源；由状态机传进来，
     *                 因为它才是掌握会话的地方。见 {@link #isUntrustedOrderNo}
     */
    public Dispatch dispatch(LlmTypes.ToolCall call, String idempotencyToken, String dialogue) {
        return dispatch(call, idempotencyToken, dialogue, null);
    }

    /**
     * @param requestFingerprint 本轮 query 的指纹，用于请求级幂等回放索引；拿不到就传 null
     *                           （那时不写、也不查索引，行为与 ADR 0008 时期一致）。见
     *                           {@link IdempotencyService#lookupByClientToken}
     */
    public Dispatch dispatch(LlmTypes.ToolCall call, String idempotencyToken, String dialogue,
                             String requestFingerprint) {
        ToolName tool = ToolName.fromApiName(call.name());
        if (tool == null) {
            return new Dispatch(null, ToolStatus.UNAVAILABLE,
                    "{\"status\":\"UNAVAILABLE\",\"message\":\"未知工具 " + call.name() + "\"}", List.of(), null,
                    false, false);
        }
        Map<String, Object> arguments = call.arguments() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(call.arguments());
        List<String> missing = missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            return new Dispatch(tool, null, null, missing, label(tool, arguments), false, false);
        }
        if (isUntrustedOrderNo(arguments.get("orderNo"), dialogue)) {
            // 宁可回一句"请提供正确订单号"，也不能拿一个编造的单号去撞库：那是越权探测的入口
            return new Dispatch(tool, null, null, List.of("orderNo"), null, true, false);
        }
        if (IdempotencyService.isWrite(tool)) {
            return executeWrite(tool, arguments, idempotencyToken, requestFingerprint);
        }
        BizMockClient.Outcome outcome = bizMockClient.call(tool, arguments,
                null);
        return new Dispatch(tool, outcome.status(), outcome.json(), List.of(), label(tool, arguments), false, false);
    }

    /**
     * 请求级回放查询（ADR 0046 票 58）。放在这里而不是状态机，是因为"什么算同一次提交"这件事
     * 本来就归 {@link IdempotencyService} 管，而它只被本类持有——状态机不必为它多一个依赖。
     */
    public java.util.Optional<IdempotencyService.Replay> lookupReplay(String idempotencyToken,
                                                                      String requestFingerprint) {
        return idempotency.lookupByClientToken(TenantContext.tenantId(), TenantContext.customerId(),
                idempotencyToken, requestFingerprint);
    }

    /** 写操作：先过幂等与业务锁，只有执行成功的结果才落成幂等结果。 */
    private Dispatch executeWrite(ToolName tool, Map<String, Object> arguments, String clientToken,
                                  String requestFingerprint) {
        String tenantId = TenantContext.tenantId();
        String customerId = TenantContext.customerId();
        IdempotencyService.Guard guard =
                idempotency.begin(tenantId, customerId, tool, arguments, clientToken, requestFingerprint);
        if (guard.duplicate()) {
            // 幂等重放 = 买家把同一个动作又发了一遍，是满意度的隐式负信号（ADR 0039 / 票 37）
            registry.counter("shoppilot_feedback_implied_total", "kind", "implied_retry").increment();
            return new Dispatch(tool, ToolStatus.IDEMPOTENT_REPLAY, guard.cachedJson(), List.of(),
                    label(tool, arguments), false, true);
        }
        if (guard.lockBusy()) {
            return new Dispatch(tool, ToolStatus.UNAVAILABLE,
                    "{\"status\":\"UNAVAILABLE\",\"message\":\"该订单正在处理中，请稍后重试\"}",
                    List.of(), null, false, false);
        }
        try {
            BizMockClient.Outcome outcome = bizMockClient.call(tool, arguments, guard.token());
            if (outcome.status() == ToolStatus.OK || outcome.status() == ToolStatus.PENDING_APPROVAL) {
                // 受理（PENDING_APPROVAL）也算"首次执行成功"：它是一条确定的结果，必须落幂等。
                // 不落这行，同 token 第二次会落到 biz-mock 的 IDEMPOTENT_REPLAY 而不经网关 duplicate 分支，
                // duplicate_submit 会消失（ADR 0047 决策九）。两者都不是失败，都不该 abandon。
                idempotency.complete(tenantId, customerId, tool, guard, outcome.json());
            } else {
                // 业务拒绝不能固化：状态改好了以后用户有权再试一次
                idempotency.abandon(tenantId, customerId, tool, guard);
            }
            return new Dispatch(tool, outcome.status(), outcome.json(), List.of(), label(tool, arguments),
                    false, false);
        } catch (RuntimeException failure) {
            idempotency.abandon(tenantId, customerId, tool, guard);
            throw failure;
        } finally {
            guard.release();
        }
    }

    /**
     * 订单号是否**不可信**。两道判据叠加，缺一不可（ADR 0045 票 56 定了口径）：
     *
     * <p>① **格式**：与 biz-mock 的造数一致（1-12 位数字），拦住明显不是单号的长串；
     * <p>② **溯源**：格式合法还不够，这个值必须**在买家说过的话里出现过**。
     *
     * <p>为什么要第二道：本地 3B 模型会把工具 schema 描述里的示例值（`平台订单号，例如 10023`）
     * 直接填进 `orderNo`，而 `10023` 格式完全合法——只查格式拦不住，工具就真的带着一个没人报过的
     * 单号去撞库。这条也是 gold 的要求：`eval/cases-part2-action.jsonl` 的 `ACT-LOG-12` 逐字写着
     * `"slotAsk": true` 且 `"mustNotContainArgs": ["orderNo"]`。
     *
     * <p>**判据面只算买家说过的**（本轮 query + 历史买家轮次，由状态机传入）：助手回复可能转述过
     * 模型编的单号，检索回来的政策条款里也可能有数字——把它们算进来等于用自己编的东西给自己背书。
     *
     * <p>**已知边界**：溯源用子串匹配。所以"买家把单号拆开写、模型又给归一化了"（如买家写
     * `900-02`、模型给 `90002`）会被判成不可信、退回追问一次。宁可多问一句也不拿没出处的东西
     * 撞库——追问的代价是体验，撞库的代价是越权探测。换更宽的匹配（例如只比数字串）会让
     * "模型从别处抄个数字"重新变得可能，得不偿失。
     *
     * <p>**另一条边界（写在代码里，免得下一个人踩）**：判据面**只算买家说过的话，不含前步工具结果**。
     * 今天这样够用：四个工具都不会返回「别的订单」，计划链后步引用的单号本来就是买家报给前步的那个值，
     * 所以在买家话里找得到。**若将来加入 `queryOrderList` 这类一次返回多单的工具**，后步就可能引用
     * 一个买家从没报过的单号，那时必须把前步结果一并纳入判据面，否则计划链会被误拦。登记在
     * round20 spec 的登记节。
     */
    private static boolean isUntrustedOrderNo(Object orderNo, String dialogue) {
        if (orderNo == null) {
            return false;
        }
        String value = String.valueOf(orderNo).trim();
        if (!ORDER_NO.matcher(value).matches()) {
            return true;
        }
        return dialogue == null || !dialogue.contains(value);
    }

    public List<String> missingSlots(ToolName tool, Map<String, Object> arguments) {
        List<String> missing = new ArrayList<>();
        for (String param : ToolSchemaGenerator.requiredParams(ToolContracts.requestType(tool))) {
            Object value = arguments.get(param);
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(param);
            }
        }
        return missing;
    }

    /** 追问文案：只问缺的那一个，不重复问已给的。金额与退款原因、地址四段都是可选项，不会走到追问。 */
    public String question(ToolName tool, List<String> missingSlots) {
        if (missingSlots.contains("orderNo")) {
            // 刻意**不给示例单号**（原先写的是「例如 10023」，而 10023 按 SeedRunner 的编号规则是 T001 内
            // 买家 C155 的真实订单号）：买家照抄它只会拿到 NOT_FOUND，模型也可能把示例值抄进参数。
            // 工具 schema 描述里的示例值另有一层溯源守卫兜着（ADR 0045 票 56），买家可见话术这层直接不给数字。
            return "请提供您的订单号（订单详情页可查），我需要它才能为您查询或办理。";
        }
        if (tool == ToolName.MODIFY_DELIVERY_ADDRESS) {
            return "还需要您补充收件信息：" + String.join("、", missingSlots) + "。";
        }
        return "还需要您补充：" + String.join("、", missingSlots) + "。";
    }

    private String label(ToolName tool, Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", ""));
        return switch (tool) {
            case QUERY_ORDER_DETAIL -> "正在查询订单 " + orderNo + " 的状态...";
            case QUERY_LOGISTICS -> "正在查询订单 " + orderNo + " 的实时物流轨迹...";
            case MODIFY_DELIVERY_ADDRESS -> "正在为您修改订单 " + orderNo + " 的收货地址...";
            case APPLY_REFUND -> "正在为订单 " + orderNo + " 提交退款申请...";
        };
    }

}
