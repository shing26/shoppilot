package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.KnowledgeRevisionService;
import com.shoppilot.tool.audit.ActorHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识修订端点（round43 票 185 / ADR 0078）。浏览器不直连本服务，一律经网关代理（票 186）。
 *
 * <p>错误三分：形状错 400（附稿请求缺字段 / 意图不是政策意图）、不存在 404（跨租户同样落在
 * 「不确认存在」）、状态不允许 409（未复核先附稿、重复发布）。与反馈复核端点同一家法。
 */
@RestController
public class KnowledgeRevisionController {

    private final KnowledgeRevisionService service;

    public KnowledgeRevisionController(KnowledgeRevisionService service) {
        this.service = service;
    }

    /** 附稿：挂在已复核完成的反馈下。X-Actor 两个头由网关从已验签令牌解出后下发。 */
    @PostMapping("/api/feedback/{feedbackId}/revision")
    public ResponseEntity<?> submit(
            @PathVariable String feedbackId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Actor-Authenticated", required = false) String actorAuthenticated) {
        try {
            KnowledgeRevisionService.validateDraft(body.get("title"), body.get("body"),
                    body.get("intent"), body.get("domain"), body.get("scope"));
        } catch (IllegalArgumentException invalidShape) {
            return ResponseEntity.badRequest().body(Map.of("error", invalidShape.getMessage()));
        }
        try {
            return ResponseEntity.ok(service.submit(feedbackId, ActorHeaders.of(actor, actorAuthenticated),
                    body.get("title"), body.get("body"), body.get("intent"), body.get("domain"),
                    body.get("scope")));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException premature) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/api/knowledge/revisions/{id}/publish")
    public ResponseEntity<KnowledgeRevisionService.RevisionView> publish(
            @PathVariable String id,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Actor-Authenticated", required = false) String actorAuthenticated) {
        return transition(id, actor, actorAuthenticated, false);
    }

    @PostMapping("/api/knowledge/revisions/{id}/reject")
    public ResponseEntity<KnowledgeRevisionService.RevisionView> reject(
            @PathVariable String id,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Actor-Authenticated", required = false) String actorAuthenticated) {
        return transition(id, actor, actorAuthenticated, true);
    }

    /** 本店修订列表（发布面前置）。 */
    @GetMapping("/api/knowledge/revisions")
    public List<KnowledgeRevisionService.RevisionView> list(
            @RequestParam(value = "status", required = false) String status) {
        return service.listForCurrentTenant(status);
    }

    /**
     * ingest 拉取口：跨租户、只吐 PUBLISHED。挂在 {@code /api/admin/} 段——那是
     * {@code InternalAuthFilter} 的平台段（免租户上下文），语义正好是「平台级只读数据面」。
     */
    @GetMapping("/api/admin/knowledge/revisions")
    public List<KnowledgeRevisionService.RevisionView> publishedForIngest() {
        return service.listPublishedAcrossTenants();
    }

    private ResponseEntity<KnowledgeRevisionService.RevisionView> transition(
            String id, String actor, String actorAuthenticated, boolean reject) {
        try {
            KnowledgeRevisionService.RevisionView view = reject
                    ? service.reject(id, ActorHeaders.of(actor, actorAuthenticated))
                    : service.publish(id, ActorHeaders.of(actor, actorAuthenticated));
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException illegalTransition) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
