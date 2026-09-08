package com.shoppilot.tool.schema;

import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaGeneratorTest {

    @Test
    @SuppressWarnings("unchecked")
    void parameterNamesComeFromRecordComponents() {
        Map<String, Object> schema = ToolSchemaGenerator.parameterSchema(ModifyDeliveryAddressRequest.class);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsOnlyKeys(
                "orderNo", "receiverName", "receiverPhone", "province", "city", "district", "detailAddress");
        assertThat((List<String>) schema.get("required"))
                .containsExactly("orderNo", "receiverName", "receiverPhone", "province", "city", "district", "detailAddress");
    }

    @Test
    void optionalParamsAreNotRequired() {
        // amountFen 标了 required = false：留空表示全额退款，不应触发 SLOT_ASK
        assertThat(ToolSchemaGenerator.requiredParams(ApplyRefundRequest.class))
                .containsExactly("orderNo", "reason");
    }

    @Test
    @SuppressWarnings("unchecked")
    void functionDescriptorCarriesNameDescriptionAndSchema() {
        Map<String, Object> descriptor = ToolSchemaGenerator.functionDescriptor(
                ToolName.QUERY_LOGISTICS, com.shoppilot.tool.request.QueryLogisticsRequest.class);

        assertThat(descriptor.get("type")).isEqualTo("function");
        Map<String, Object> function = (Map<String, Object>) descriptor.get("function");
        assertThat(function.get("name")).isEqualTo("queryLogistics");
        assertThat((String) function.get("description")).isNotBlank();
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        Map<String, Object> orderNo = (Map<String, Object>) properties.get("orderNo");
        assertThat(orderNo.get("type")).isEqualTo("string");
        assertThat((String) orderNo.get("description")).contains("10023");
    }
}
