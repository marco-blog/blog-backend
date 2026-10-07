package net.java21.blog.backend.external.classify;

import java.util.List;

/**
 * 자동 분류 입력(007 research E9): 제목, 요약(태그 제거 텍스트), 피드 카테고리·태그. 원문 본문은 받지 않는다(SC-021).
 */
public record ClassifierInput(String title, String summary, List<String> terms) {

    public ClassifierInput {
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
