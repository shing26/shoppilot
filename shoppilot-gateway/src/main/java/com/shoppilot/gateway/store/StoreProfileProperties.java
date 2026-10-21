package com.shoppilot.gateway.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 店铺人格配置（round37）：按 tenantId 给对话层一段可读的店铺资料。
 *
 * <p>这里只放**介绍性摘要**，不是业务事实的第二份来源：具体政策、时限、金额仍以
 * {@code knowledge/} 检索出的条款与工具返回为准。名称与主营必须与 biz-mock 的
 * {@code SeedRunner} 和店铺级条款保持一致，改一边就要改另一边。
 */
@ConfigurationProperties(prefix = "shoppilot.store-profiles")
public record StoreProfileProperties(Map<String, Profile> profiles) {

    public record Profile(String name, String business, String guarantees) {
    }
}
