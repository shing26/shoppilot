package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.view.OrderView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家只读订单入口（ADR 0075）。租户与买家都取自 {@code InternalAuthFilter} 写入的上下文，
 * 不接受路径或查询参数里的身份；跨租户 / 跨买家由既有归属条件挡。
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
}
