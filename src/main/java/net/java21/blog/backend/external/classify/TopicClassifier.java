package net.java21.blog.backend.external.classify;

/**
 * 외부 글 자동 분류(007 FR-118, research E9). 지금 구현은 키워드 사전({@link KeywordTopicClassifier}). 사람이 확정한 결과가 쌓이면 같은
 * 인터페이스로 다른 방식을 붙여 정확도(FR-122)를 비교한다.
 */
public interface TopicClassifier {

    ClassificationResult classify(ClassifierInput input);
}
