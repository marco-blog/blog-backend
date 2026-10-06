package net.java21.blog.backend.admin.user.dto;

import jakarta.validation.constraints.Min;

/**
 * {@code PATCH /admin/users/{id}/blog-limit} {@code { maxBlogs: number | null }}(006 FR-160). 0 이상 정수이며 {@code null}은
 * 기본값({@code blog.blogs.default-max-per-member})으로 되돌린다. {@code null}과 "보내지 않음"을 구별해야 하므로
 * setter가 보냈는지를 표시한다. 필드를 빠뜨리면 서비스가 400({@code REQUIRED})을 준다.
 */
public class BlogLimitRequest {

    private Integer maxBlogs;
    private boolean maxBlogsPresent;

    public BlogLimitRequest() {
    }

    public static BlogLimitRequest of(Integer maxBlogs) {
        BlogLimitRequest request = new BlogLimitRequest();
        request.setMaxBlogs(maxBlogs);
        return request;
    }

    @Min(0)
    public Integer getMaxBlogs() {
        return maxBlogs;
    }

    public void setMaxBlogs(Integer maxBlogs) {
        this.maxBlogs = maxBlogs;
        this.maxBlogsPresent = true;
    }

    /** {@code maxBlogs} 필드를 보냈는지({@code null} 포함). */
    public boolean hasMaxBlogs() {
        return maxBlogsPresent;
    }
}
