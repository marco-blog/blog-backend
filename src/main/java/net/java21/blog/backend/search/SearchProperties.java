package net.java21.blog.backend.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 검색 설정(002 contracts/api.md "프로퍼티", research D4).
 *
 * @param maxTerms       검색어에서 쓰는 최대 낱말 수
 * @param minTermLength  쓰는 낱말의 최소 길이(MySQL {@code ngram_token_size}와 같게)
 */
@ConfigurationProperties("blog.search")
public record SearchProperties(@DefaultValue("5") int maxTerms, @DefaultValue("2") int minTermLength) {

    public SearchProperties {
        if (maxTerms < 1) {
            throw new IllegalArgumentException("blog.search.max-terms must be positive");
        }
        if (minTermLength < 1) {
            throw new IllegalArgumentException("blog.search.min-term-length must be positive");
        }
    }
}
