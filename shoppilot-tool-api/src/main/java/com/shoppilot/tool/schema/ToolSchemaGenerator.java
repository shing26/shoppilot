package com.shoppilot.tool.schema;

import com.shoppilot.tool.ToolName;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function Schema 由工具入参 record 的 component 反射生成（ADR 0002）。
 *
 * <p>刻意不引入第三方 schema 生成器：参数名直接来自 record component，
 * 因此"给模型的描述"与"反序列化用的签名"不可能漂移，改字段名两边同时变。
 */
public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {
    }

    /** 生成 OpenAI function calling 格式的完整 tool 描述。 */
    public static Map<String, Object> functionDescriptor(ToolName tool, Class<?> requestType) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.apiName());
        function.put("description", tool.description());
        function.put("parameters", parameterSchema(requestType));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", "function");
        descriptor.put("function", function);
        return descriptor;
    }

    public static Map<String, Object> parameterSchema(Class<?> requestType) {
        if (!requestType.isRecord()) {
            throw new IllegalArgumentException("工具入参必须是 record: " + requestType.getName());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : requestType.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            Map<String, Object> property = propertySchema(component.getGenericType());
            if (param != null) {
                property.put("description", param.description());
                if (param.required()) {
                    required.add(component.getName());
                }
            } else {
                required.add(component.getName());
            }
            properties.put(component.getName(), property);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 供网关校验模型回填的参数是否齐全。 */
    public static List<String> requiredParams(Class<?> requestType) {
        List<String> required = new ArrayList<>();
        for (RecordComponent component : requestType.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            if (param == null || param.required()) {
                required.add(component.getName());
            }
        }
        return required;
    }

    private static Map<String, Object> propertySchema(Type type) {
        Class<?> raw = type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType()
                : (type instanceof Class<?> clazz ? clazz : Object.class);
        if (raw == String.class || raw == char.class || raw == Character.class) {
            return new LinkedHashMap<>(Map.of("type", "string"));
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return new LinkedHashMap<>(Map.of("type", "boolean"));
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class) {
            return new LinkedHashMap<>(Map.of("type", "integer"));
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            return new LinkedHashMap<>(Map.of("type", "number"));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Map<String, Object> array = new LinkedHashMap<>();
            array.put("type", "array");
            Type itemType = type instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0]
                    : String.class;
            array.put("items", propertySchema(itemType));
            return array;
        }
        return new LinkedHashMap<>(Map.of("type", "object"));
    }
}
