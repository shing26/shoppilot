package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import com.shoppilot.tool.request.QueryLogisticsRequest;
import com.shoppilot.tool.request.QueryOrderDetailRequest;
import com.shoppilot.tool.view.LogisticsView;
import com.shoppilot.tool.view.ModifyAddressView;
import com.shoppilot.tool.view.OrderView;
import com.shoppilot.tool.view.RefundView;
import com.shoppilot.tool.view.ToolResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 四个工具动作的 HTTP 端点。网关是唯一调用方。 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final BizMockService service;

    public ToolController(BizMockService service) {
        this.service = service;
    }

    @PostMapping("/queryOrderDetail")
    public ToolResponse<OrderView> queryOrderDetail(@Valid @RequestBody QueryOrderDetailRequest request) {
        return service.queryOrderDetail(request.orderNo());
    }

    @PostMapping("/queryLogistics")
    public ToolResponse<LogisticsView> queryLogistics(@Valid @RequestBody QueryLogisticsRequest request) {
        return service.queryLogistics(request.orderNo());
    }

    @PostMapping("/modifyDeliveryAddress")
    public ToolResponse<ModifyAddressView> modifyDeliveryAddress(
            @Valid @RequestBody ModifyDeliveryAddressRequest request) {
        return service.modifyDeliveryAddress(request.orderNo(), request);
    }

    @PostMapping("/applyRefund")
    public ToolResponse<RefundView> applyRefund(
            @Valid @RequestBody ApplyRefundRequest request,
            @RequestHeader("Idempotency-Token") @NotBlank String idempotencyToken) {
        return service.applyRefund(request.orderNo(), request, idempotencyToken);
    }
}
