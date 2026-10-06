package net.java21.blog.backend.notification.controller;

import java.util.List;

import jakarta.validation.Valid;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.notification.dto.BulkNotificationRequest;
import net.java21.blog.backend.notification.dto.BulkNotificationResponse;
import net.java21.blog.backend.notification.dto.NotificationResponse;
import net.java21.blog.backend.notification.service.NotificationService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 알림(002 contracts/api.md 알림 절, FR-033). 모두 로그인 회원 전용이다. */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

    /** 최신순 고정(정렬 매개변수 없음). */
    private static final PageRequests LIST = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    ApiResponse<List<NotificationResponse>> list(@CurrentUser AuthUser user,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(notificationService.list(user.userId(), LIST.resolve(page, size, null)));
    }

    @PostMapping("/{id}/read")
    ApiResponse<NotificationResponse> read(@CurrentUser AuthUser user, @PathVariable long id) {
        return ApiResponse.ok(notificationService.read(user.userId(), id));
    }

    @PostMapping("/bulk")
    ApiResponse<BulkNotificationResponse> bulk(@CurrentUser AuthUser user,
            @Valid @RequestBody BulkNotificationRequest request) {
        return ApiResponse.ok(notificationService.bulk(user.userId(), request));
    }
}
