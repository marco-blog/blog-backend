package net.java21.blog.backend.topic.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.topic.dto.TopicNode;
import net.java21.blog.backend.topic.service.TopicService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 주제 트리(003 contracts/api.md 주제 절, FR-075·147). */
@RestController
public class TopicController {

    private final TopicService topicService;

    public TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    /** 운영자 숨김이 아닌 주제 트리(대분류 순서, 각 소분류 순서). 비로그인 허용. */
    @GetMapping("/api/v1/topics")
    ApiResponse<List<TopicNode>> topics() {
        return ApiResponse.ok(topicService.publicTree());
    }
}
