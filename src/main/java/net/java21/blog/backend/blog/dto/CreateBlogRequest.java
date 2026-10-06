package net.java21.blog.backend.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.blog.domain.Blog;

/**
 * {@code POST /blogs}. 주소 규칙·예약어는 서비스가 422로 판단한다.
 *
 * @param title 1~100자, 생략하면 "{닉네임}의 블로그"
 */
public record CreateBlogRequest(
        @NotBlank String handle,
        @Size(min = 1, max = Blog.TITLE_MAX) String title) {
}
