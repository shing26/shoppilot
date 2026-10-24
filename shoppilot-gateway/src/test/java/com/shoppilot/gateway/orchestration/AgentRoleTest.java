package com.shoppilot.gateway.orchestration;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0072：路由是纯函数，工具面只是候选集裁剪。 */
class AgentRoleTest {

    @Test
    @DisplayName("售前只接物流与优惠两块纯咨询")
    void routesPreSalesQuestions() {
        assertThat(AgentRole.routeFor(Intent.POLICY_SHIPPING)).isEqualTo(AgentRole.PRE_SALES);
        assertThat(AgentRole.routeFor(Intent.POLICY_PROMO)).isEqualTo(AgentRole.PRE_SALES);
    }

    @Test
    @DisplayName("写意图与 UNKNOWN 归售后，交既有安全出口兜底")
    void routesActionAndUnknownToAfterSales() {
        assertThat(AgentRole.routeFor(Intent.ACTION_REFUND)).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(AgentRole.routeFor(Intent.ACTION_ADDRESS)).isEqualTo(AgentRole.AFTER_SALES);
        assertThat(AgentRole.routeFor(Intent.UNKNOWN)).isEqualTo(AgentRole.AFTER_SALES);
    }

    @Test
    @DisplayName("PRE_SALES 在电商域也拿不到工具；AFTER_SALES 保持现有动作面")
    void scopesToolSurface() {
        assertThat(ToolSurfacePolicy.toolsFor(AgentRole.PRE_SALES, Domain.ECOMMERCE, Intent.POLICY_SHIPPING))
                .isEmpty();
        assertThat(ToolSurfacePolicy.toolsFor(AgentRole.PRE_SALES, Domain.ECOMMERCE, Intent.ACTION_REFUND))
                .isEmpty();
        assertThat(ToolSurfacePolicy.toolsFor(AgentRole.AFTER_SALES, Domain.ECOMMERCE, Intent.ACTION_REFUND))
                .containsExactlyElementsOf(ToolContracts.actionDescriptors());
        assertThat(ToolSurfacePolicy.toolsFor(AgentRole.AFTER_SALES, Domain.ECOMMERCE, Intent.POLICY_RETURN))
                .isEmpty();
    }

    @Test
    @DisplayName("行业门优先于角色面：非电商域任何角色都不见电商工具")
    void keepsIndustryGateAheadOfRoleSurface() {
        var hotelAfterSales = ToolSurfacePolicy.decide(AgentRole.AFTER_SALES, Domain.HOTEL, Intent.ACTION_REFUND);
        var hotelPreSales = ToolSurfacePolicy.decide(AgentRole.PRE_SALES, Domain.HOTEL, Intent.POLICY_HOTEL);
        assertThat(hotelAfterSales.tools()).isEmpty();
        assertThat(hotelAfterSales.reason()).isEqualTo("DENIED:DOMAIN_ISOLATION");
        assertThat(hotelPreSales.tools()).isEmpty();
        assertThat(hotelPreSales.reason()).isEqualTo("DENIED:DOMAIN_ISOLATION");
    }

    @Test
    @DisplayName("工具面判定输出可解释原因：售前只读、政策不配工具、动态意图给动作面")
    void explainsToolSurfaceReasons() {
        assertThat(ToolSurfacePolicy.decide(AgentRole.PRE_SALES, Domain.ECOMMERCE, Intent.POLICY_SHIPPING).reason())
                .isEqualTo("DENIED:PRE_SALES_READ_ONLY");
        assertThat(ToolSurfacePolicy.decide(AgentRole.AFTER_SALES, Domain.ECOMMERCE, Intent.POLICY_RETURN).reason())
                .isEqualTo("DENIED:POLICY_CONSULTATION");
        assertThat(ToolSurfacePolicy.decide(AgentRole.AFTER_SALES, Domain.ECOMMERCE, Intent.ACTION_REFUND).reason())
                .isEqualTo("ALLOWED:AFTER_SALES_ACTION_SURFACE");
        assertThat(ToolSurfacePolicy.decide(AgentRole.AFTER_SALES, Domain.ECOMMERCE, Intent.UNKNOWN).reason())
                .isEqualTo("ALLOWED:UNKNOWN_DYNAMIC_SURFACE");
    }

    @Test
    @DisplayName("AFTER_SALES 不改既有 Prompt，PRE_SALES 只加边界段且无业务事实")
    void scopesPromptSegment() {
        assertThat(AgentRole.AFTER_SALES.promptSegment()).isEmpty();
        String segment = AgentRole.PRE_SALES.promptSegment();
        assertThat(segment).contains("售前咨询").contains("不能退款");
        assertThat(segment).doesNotContain("48").doesNotContain("七天").doesNotContain("六小时");
    }
}
