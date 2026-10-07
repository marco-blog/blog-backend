package net.java21.blog.backend.external.fetch;

import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;

/**
 * 새로 수집한 글의 주제를 정한다(007 research E10). 피드 한 번 수집마다 {@link #start()}로 묶음을 열어(규칙 등을 한 번만 읽음) 글마다
 * {@link Batch#decide}를 부른다.
 */
public interface TopicAssigner {

    Batch start();

    interface Batch {
        Decision decide(FeedItem item, long defaultTopicId);
    }

    /**
     * @param topicId           노출 주제
     * @param source            주제 출처
     * @param classifierTopicId 자동 분류 예측(분류하지 않았으면 null)
     * @param confidence        자동 분류 신뢰도(분류하지 않았으면 null)
     * @param classifierVersion 분류 방식 버전(분류하지 않았으면 null)
     * @param review            검수 대기(PENDING)를 만들지
     */
    record Decision(long topicId, TopicSource source, Long classifierTopicId, Double confidence,
            String classifierVersion, boolean review) {

        public static Decision defaultTopic(long defaultTopicId) {
            return new Decision(defaultTopicId, TopicSource.DEFAULT, null, null, null, false);
        }
    }
}
