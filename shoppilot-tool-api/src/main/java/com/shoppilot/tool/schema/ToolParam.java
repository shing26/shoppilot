package com.shoppilot.tool.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 record component 上，供 ToolSchemaGenerator 生成 function 参数描述。
 * 参数名一律取 record component 名，不允许在此重复声明，避免 schema 与签名漂移。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
public @interface ToolParam {
    /** 给模型看的参数说明。 */
    String description();

    /** 是否必填。缺槽位时由状态机走 SLOT_ASK 追问，绝不猜值。 */
    boolean required() default true;
}
