package net.java21.blog.backend.spam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 반복 스팸 기준의 기본값(005 FR-144, {@code blog.spam.duplicate-comment.*}). 운영 설정 키 {@code spam.duplicate-comment}에 값이 없을 때
 * 쓴다. 범위를 벗어나면 기동하지 않는다(창 1~1440분, 횟수 2~100, 최소 길이 1 이상).
 */
@ConfigurationProperties("blog.spam")
public record SpamProperties(@DefaultValue DuplicateComment duplicateComment) {

    public SpamProperties {
        if (duplicateComment == null) {
            duplicateComment = DuplicateComment.defaults();
        }
    }

    /** 기본값(테스트용). */
    public static SpamProperties defaults() {
        return new SpamProperties(DuplicateComment.defaults());
    }

    /**
     * @param windowMinutes 같은 내용을 세는 창(분)
     * @param maxCount      창 안에서 허용하는 같은 내용 수(다음 쓰기부터 거부)
     * @param minLength     정규화 후 이보다 짧은 내용은 세지 않는다(인사말 보호, 결정 표 18번)
     */
    public record DuplicateComment(
            @DefaultValue("10") int windowMinutes,
            @DefaultValue("3") int maxCount,
            @DefaultValue("10") int minLength) {

        public DuplicateComment {
            if (windowMinutes < 1 || windowMinutes > 1440) {
                throw new IllegalArgumentException("blog.spam.duplicate-comment.window-minutes must be 1..1440");
            }
            if (maxCount < 2 || maxCount > 100) {
                throw new IllegalArgumentException("blog.spam.duplicate-comment.max-count must be 2..100");
            }
            if (minLength < 1) {
                throw new IllegalArgumentException("blog.spam.duplicate-comment.min-length must be at least 1");
            }
        }

        public static DuplicateComment defaults() {
            return new DuplicateComment(10, 3, 10);
        }
    }
}
