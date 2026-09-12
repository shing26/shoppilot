package com.shoppilot.gateway.identity;

import com.shoppilot.gateway.config.DevDefaultsPolicy;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * mock 身份签发只在回环绑定上注册（ADR 0029）。
 *
 * <p>它不要任何凭证就能领「任意店铺 + 任意买家」的合法身份，在回环上是调试台的正门，
 * 在外面就是整条身份模型唯一的破口。这里按「不注册」处理而不是运行时报错：
 * 404 与「本实例不提供身份领取」是同一个意思，没必要留一个可被探测的 401。
 */
public class MockIdentityCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return DevDefaultsPolicy.isLoopback(context.getEnvironment().getProperty("server.address", ""));
    }
}
