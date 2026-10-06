package net.java21.blog.backend.subscription.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.subscription.dto.FeedPostResponse;
import net.java21.blog.backend.subscription.dto.SubscriptionStateResponse;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 구독과 구독 피드(002 contracts/api.md 구독 절, FR-031·032). PUT·DELETE는 멱등이며 200과 현재 상태·수를 준다. */
@RestController
@RequestMapping("/api/v1/me")
public class SubscriptionController {

    /** 구독 피드는 발행 최신순 고정(정렬 매개변수 없음). */
    private static final PageRequests FEED = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "publishedAt"));

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PutMapping("/subscriptions/{handle}")
    ApiResponse<SubscriptionStateResponse> subscribe(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(subscriptionService.subscribe(user.userId(), handle));
    }

    @DeleteMapping("/subscriptions/{handle}")
    ApiResponse<SubscriptionStateResponse> unsubscribe(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(subscriptionService.unsubscribe(user.userId(), handle));
    }

    @GetMapping("/feed")
    ApiResponse<List<FeedPostResponse>> feed(@CurrentUser AuthUser user,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(subscriptionService.feed(user.userId(), FEED.resolve(page, size, null)));
    }
}
