package net.java21.blog.backend.blog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 블로그 설정(contracts/api.md "프로퍼티").
 *
 * @param defaultMaxPerMember 회원별 한도({@code users.max_blogs})가 없을 때 회원 1명이 가질 수 있는 블로그 수(FR-158)
 */
@ConfigurationProperties("blog.blogs")
public record BlogsProperties(@DefaultValue("3") int defaultMaxPerMember) {

    public BlogsProperties {
        if (defaultMaxPerMember < 1) {
            throw new IllegalArgumentException("blog.blogs.default-max-per-member must be at least 1");
        }
    }
}
