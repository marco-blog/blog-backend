package net.java21.blog.backend.external.classify;

/**
 * 자동 분류 결과(007 research E9).
 *
 * @param topicId    예측한 소분류(일치가 없으면 null)
 * @param confidence 신뢰도 0~1(일치가 없으면 0)
 * @param version    분류 방식 버전({@code external_posts.classifier_version})
 */
public record ClassificationResult(Long topicId, double confidence, String version) {

    public static ClassificationResult none(String version) {
        return new ClassificationResult(null, 0, version);
    }
}
