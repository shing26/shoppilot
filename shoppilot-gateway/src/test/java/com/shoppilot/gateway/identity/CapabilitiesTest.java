package com.shoppilot.gateway.identity;

import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 票 221：角色到能力的投影只在这里钉一次。 */
class CapabilitiesTest {

    @Test
    @DisplayName("买家没有任何工作台能力")
    void buyerHasNoStaffCapabilities() {
        assertThat(Capabilities.of(UserRole.BUYER)).isEmpty();
        assertThat(Capabilities.allows(UserRole.BUYER, Capabilities.TICKETS)).isFalse();
    }

    @Test
    @DisplayName("坐席拿到五项日常能力，但没有渠道与店铺管理")
    void agentHasDailySurfacesButNoAdminSurfaces() {
        assertThat(Capabilities.of(UserRole.AGENT)).containsExactly(
                "tickets", "conversations", "refunds", "knowledge", "quality");
        assertThat(Capabilities.allows(UserRole.AGENT, Capabilities.CHANNELS)).isFalse();
        assertThat(Capabilities.allows(UserRole.AGENT, Capabilities.TENANTS)).isFalse();
    }

    @Test
    @DisplayName("管理员在坐席能力之上增加渠道与店铺")
    void adminHasAllCapabilities() {
        assertThat(Capabilities.of(UserRole.ADMIN)).containsExactly(
                "tickets", "conversations", "refunds", "knowledge", "quality", "channels", "tenants");
        assertThat(Capabilities.allows(UserRole.ADMIN, Capabilities.CHANNELS)).isTrue();
        assertThat(Capabilities.allows(UserRole.ADMIN, Capabilities.TENANTS)).isTrue();
    }

    @Test
    @DisplayName("没有角色按买家处理，不抛出")
    void nullRoleIsBuyer() {
        assertThat(Capabilities.of(null)).isEmpty();
        assertThat(Capabilities.allows(null, Capabilities.TICKETS)).isFalse();
    }
}
