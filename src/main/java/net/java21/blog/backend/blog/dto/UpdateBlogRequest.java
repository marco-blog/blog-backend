package net.java21.blog.backend.blog.dto;

import jakarta.validation.constraints.Size;

import net.java21.blog.backend.blog.domain.Blog;

/**
 * {@code PATCH /blogs/{handle}}(JSON Merge Patch, api-guidelines 3절): 보낸 필드만 바꾸고 {@code null}은 값을 지운다.
 * 보냈는지 여부를 알아야 하므로 record 대신 setter가 표시를 남기는 클래스로 둔다.
 * 제목과 댓글 허용은 지울 수 없으므로 {@code null}이면 서비스가 400을 준다. 대표 이미지는 US4.
 */
public class UpdateBlogRequest {

    private String title;
    private boolean titlePresent;
    private String description;
    private boolean descriptionPresent;
    private Boolean commentEnabled;
    private boolean commentEnabledPresent;

    public UpdateBlogRequest() {
    }

    @Size(min = 1, max = Blog.TITLE_MAX)
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.titlePresent = true;
    }

    @Size(max = Blog.DESCRIPTION_MAX)
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    public Boolean getCommentEnabled() {
        return commentEnabled;
    }

    public void setCommentEnabled(Boolean commentEnabled) {
        this.commentEnabled = commentEnabled;
        this.commentEnabledPresent = true;
    }

    public boolean hasTitle() {
        return titlePresent;
    }

    public boolean hasDescription() {
        return descriptionPresent;
    }

    public boolean hasCommentEnabled() {
        return commentEnabledPresent;
    }
}
