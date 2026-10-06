package net.java21.blog.backend.admin.topic;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.topic.dto.AdminTopicNode;
import net.java21.blog.backend.admin.topic.dto.CreateTopicRequest;
import net.java21.blog.backend.admin.topic.dto.TopicOrderRequest;
import net.java21.blog.backend.admin.topic.dto.UpdateTopicRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 주제 API(003 contracts/api.md "관리자: 주제"). {@code AdminAccessFilter}가 DB 권한을 확인한 뒤에만 온다.
 * 응답은 로그인 응답이라 {@code Cache-Control: no-store}(Spring Security 기본값).
 */
@RestController
@RequestMapping("/api/v1/admin/topics")
public class AdminTopicController {

    private final AdminTopicService service;

    public AdminTopicController(AdminTopicService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AdminTopicNode>> tree() {
        return ApiResponse.ok(service.tree());
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminTopicNode>> create(@CurrentUser AuthUser admin,
            @RequestBody CreateTopicRequest request, HttpServletRequest http) {
        AdminTopicNode node = service.create(admin.userId(), request, http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/topics/" + node.id())).body(ApiResponse.ok(node));
    }

    @PatchMapping("/{id}")
    ApiResponse<AdminTopicNode> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody UpdateTopicRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(admin.userId(), id, request, http.getRemoteAddr()));
    }

    @PutMapping("/order")
    ApiResponse<List<AdminTopicNode>> reorder(@CurrentUser AuthUser admin, @RequestBody TopicOrderRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.reorder(admin.userId(), request, http.getRemoteAddr()));
    }
}
