package com.shoppilot.gateway.web;

import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.store.StoreProfileProperties;
import com.shoppilot.gateway.store.StoreProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 当前店铺的只读资料（round37）。
 *
 * <p>租户只从已验签身份取，不接受请求参数；未登记租户返回 404，前端据此只显示店铺码，
 * 不编造店名。买家端与坐席端共用这一个读面。
 */
@RestController
@RequestMapping("/api/v1/support")
public class StoreProfileController {

    private final StoreProfileService storeProfiles;

    public StoreProfileController(StoreProfileService storeProfiles) {
        this.storeProfiles = storeProfiles;
    }

    @GetMapping("/store-profile")
    public ResponseEntity<Map<String, Object>> profile() {
        String tenantId = TenantContext.tenantId();
        Optional<StoreProfileProperties.Profile> found = storeProfiles.find(tenantId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        StoreProfileProperties.Profile profile = found.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("name", profile.name());
        body.put("business", profile.business());
        body.put("guarantees", profile.guarantees());
        return ResponseEntity.ok(body);
    }
}
