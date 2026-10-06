package net.java21.blog.backend.topic.service;

import java.util.List;

/**
 * 주제 페이지가 보여줄 주제(003 FR-078). 대분류면 운영자 숨김이 아닌 소속 소분류 전체, 소분류면 자기 자신.
 *
 * @param topicId  주소의 주제
 * @param topicIds 글을 모을 소분류 id(대분류의 소분류가 모두 숨김이면 빈 목록)
 */
public record TopicPage(Long topicId, String slug, boolean major, List<Long> topicIds) {
}
