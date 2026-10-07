package net.java21.blog.backend.external.fetch;

/** 새 글을 블로그 기본 주제로(US1). US2의 {@code TopicDecider}가 매핑 규칙·자동 분류를 더해 이 자리를 대신한다. */
@org.springframework.stereotype.Component
public class DefaultTopicAssigner implements TopicAssigner {

    @Override
    public Batch start() {
        return (item, defaultTopicId) -> Decision.defaultTopic(defaultTopicId);
    }
}
