package net.java21.blog.backend.search.service;

import java.util.List;

/**
 * 해석한 검색어. {@code booleanQuery}는 MySQL BOOLEAN MODE 식({@code +"스프링" +"부트"}): 모든 낱말을 포함하고 낱말 안은 구문 일치.
 *
 * @param terms         쓰는 낱말(연산자 문자를 지운 것, 중복 없음)
 * @param booleanQuery  {@code MATCH ... AGAINST}에 넣을 식
 */
public record SearchQuery(List<String> terms, String booleanQuery) {

    public SearchQuery {
        terms = List.copyOf(terms);
    }
}
