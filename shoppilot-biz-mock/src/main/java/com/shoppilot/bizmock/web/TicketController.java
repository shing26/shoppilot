package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.BizMockService;
import com.shoppilot.tool.view.TicketView;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 工单端点（ADR 0009）。浏览器不直连本服务，一律经网关代理。 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final BizMockService service;

    public TicketController(BizMockService service) {
        this.service = service;
    }

    @PostMapping
    public TicketView create(@RequestBody CreateTicketRequest request) {
        return service.createTicket(request.customerId(), request.reason(), request.userQuery(), request.transcript());
    }

    @GetMapping
    public List<TicketView> list() {
        return service.listTickets();
    }

    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<TicketView> updateStatus(@PathVariable String ticketId,
                                                   @RequestBody Map<String, @NotBlank String> body) {
        return service.updateTicketStatus(ticketId, body.get("status"))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateTicketRequest(String customerId, String reason, String userQuery, String transcript) {
    }
}
