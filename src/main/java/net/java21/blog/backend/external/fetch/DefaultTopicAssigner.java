package net.java21.blog.backend.external.fetch;

/**
 * 새 글을 늘 블로그 기본 주제로(분류 없음). 운영 빈은 {@link net.java21.blog.backend.external.classify.TopicDecider}(매핑 규칙·자동
 * 분류)이고, 이 구현은 수집 흐름만 보는 시험이 쓴다.
 */
public class DefaultTopicAssigner implements TopicAssigner {

    @Override
    public Batch start() {
        return (item, defaultTopicId) -> Decision.defaultTopic(defaultTopicId);
    }
}
