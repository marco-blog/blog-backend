package net.java21.blog.backend.blog.dto;

import jakarta.validation.constraints.Size;

import net.java21.blog.backend.blog.domain.Blog;

/**
 * {@code PATCH /blogs/{handle}}(JSON Merge Patch, api-guidelines 3절): 보낸 필드만 바꾸고 {@code null}은 값을 지운다.
 * 보냈는지 여부를 알아야 하므로 record 대신 setter가 표시를 남기는 클래스로 둔다.
 * 제목과 댓글 허용은 지울 수 없으므로 {@code null}이면 서비스가 400을 준다.
 * 대표 이미지({@code coverImageMediaKey})는 {@code purpose=BLOG_COVER}로 올린 본인 이미지의 키, null이면 지운다.
 * 피드 설정(002 FR-046): {@code feedItemCount}(10·20·30·50)와 {@code feedContentMode}(FULL·SUMMARY)는 지울 수 없고, 허용 값이 아니면
 * 서비스가 400 {@code INVALID}(params.allowed)를 준다. 모르는 값도 서비스에서 같은 오류로 답하려고 공개 형태는 문자열로 받는다.
 * 포털 설정(003 FR-077·089): {@code portalEnabled}는 지울 수 없고(null이면 400 {@code REQUIRED}), {@code defaultTopicId}는
 * null이면 지우며 값이면 고를 수 있는 소분류여야 한다(지금 값과 같으면 검사하지 않음).
 * 004 방명록·비회원 쓰기 설정(FR-058, FR-066): {@code guestbookEnabled}·{@code guestWriteEnabled}는 지울 수 없다(null이면 400 {@code REQUIRED}).
 * 005 트랙백 받기(FR-053): {@code trackbackEnabled}는 지울 수 없다(null이면 400 {@code REQUIRED}).
 */
public class UpdateBlogRequest {

    private String title;
    private boolean titlePresent;
    private String description;
    private boolean descriptionPresent;
    private Boolean commentEnabled;
    private boolean commentEnabledPresent;
    private String coverImageMediaKey;
    private boolean coverImageMediaKeyPresent;
    private Integer feedItemCount;
    private boolean feedItemCountPresent;
    private String feedContentMode;
    private boolean feedContentModePresent;
    private Boolean portalEnabled;
    private boolean portalEnabledPresent;
    private Long defaultTopicId;
    private boolean defaultTopicIdPresent;
    private Boolean guestbookEnabled;
    private boolean guestbookEnabledPresent;
    private Boolean guestWriteEnabled;
    private boolean guestWriteEnabledPresent;
    private Boolean trackbackEnabled;
    private boolean trackbackEnabledPresent;

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

    public String getCoverImageMediaKey() {
        return coverImageMediaKey;
    }

    public void setCoverImageMediaKey(String coverImageMediaKey) {
        this.coverImageMediaKey = coverImageMediaKey;
        this.coverImageMediaKeyPresent = true;
    }

    public boolean hasCoverImageMediaKey() {
        return coverImageMediaKeyPresent;
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

    public Integer getFeedItemCount() {
        return feedItemCount;
    }

    public void setFeedItemCount(Integer feedItemCount) {
        this.feedItemCount = feedItemCount;
        this.feedItemCountPresent = true;
    }

    public boolean hasFeedItemCount() {
        return feedItemCountPresent;
    }

    public String getFeedContentMode() {
        return feedContentMode;
    }

    public void setFeedContentMode(String feedContentMode) {
        this.feedContentMode = feedContentMode;
        this.feedContentModePresent = true;
    }

    public boolean hasFeedContentMode() {
        return feedContentModePresent;
    }

    public Boolean getPortalEnabled() {
        return portalEnabled;
    }

    public void setPortalEnabled(Boolean portalEnabled) {
        this.portalEnabled = portalEnabled;
        this.portalEnabledPresent = true;
    }

    public boolean hasPortalEnabled() {
        return portalEnabledPresent;
    }

    public Long getDefaultTopicId() {
        return defaultTopicId;
    }

    public void setDefaultTopicId(Long defaultTopicId) {
        this.defaultTopicId = defaultTopicId;
        this.defaultTopicIdPresent = true;
    }

    public boolean hasDefaultTopicId() {
        return defaultTopicIdPresent;
    }

    public Boolean getGuestbookEnabled() {
        return guestbookEnabled;
    }

    public void setGuestbookEnabled(Boolean guestbookEnabled) {
        this.guestbookEnabled = guestbookEnabled;
        this.guestbookEnabledPresent = true;
    }

    public boolean hasGuestbookEnabled() {
        return guestbookEnabledPresent;
    }

    public Boolean getGuestWriteEnabled() {
        return guestWriteEnabled;
    }

    public void setGuestWriteEnabled(Boolean guestWriteEnabled) {
        this.guestWriteEnabled = guestWriteEnabled;
        this.guestWriteEnabledPresent = true;
    }

    public boolean hasGuestWriteEnabled() {
        return guestWriteEnabledPresent;
    }

    public Boolean getTrackbackEnabled() {
        return trackbackEnabled;
    }

    public void setTrackbackEnabled(Boolean trackbackEnabled) {
        this.trackbackEnabled = trackbackEnabled;
        this.trackbackEnabledPresent = true;
    }

    public boolean hasTrackbackEnabled() {
        return trackbackEnabledPresent;
    }
}
