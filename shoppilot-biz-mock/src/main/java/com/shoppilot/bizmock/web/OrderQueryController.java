package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.view.OrderDetailView;
import com.shoppilot.tool.view.OrderLogisticsView;
import com.shoppilot.tool.view.OrderView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家只读订单入口（ADR 0075；round44 票 189 增详情与物流）。租户与买家都取自
 * {@code InternalAuthFilter} 写入的上下文，不接受路径或查询参数里的身份；跨租户 / 跨买家由既有
 * 归属条件挡。
 *
 * <p>越权与不存在一律 **404 且空体**：两者回同一个响应，调用方无法据此判断"这单存在但不是你的"。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderQueryController {

    private final BizMockService service;

    public OrderQueryController(BizMockService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public List<OrderView> mine() {
        return service.myOrders();
    }

    @GetMapping("/mine/{orderNo}")
    public ResponseEntity<OrderDetailView> detail(@PathVariable String orderNo) {
        return service.buyerOrderDetail(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 订单存在但无轨迹时返回 200 + 空 {@code nodes}，不是 404（见 {@link OrderLogisticsView}）。 */
    @GetMapping("/mine/{orderNo}/logistics")
    public ResponseEntity<OrderLogisticsView> logistics(@PathVariable String orderNo) {
        return service.buyerOrderLogistics(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
