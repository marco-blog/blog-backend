package net.java21.blog.backend.post.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.RenderedContent;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행·수정 발행(FR-013, FR-015, FR-070, FR-107·108). 작성 중 사본(없으면 지금 발행본)을 HTML로 바꾸고 살균해 {@code posts}에
 * 반영한 뒤 사본을 지운다. 제목 1~200자 필수, 본문이 비면 422 {@code POST_CONTENT_EMPTY}.
 * 글 번호는 바뀌지 않고 {@code published_at}은 처음 발행할 때만 정한다. 카테고리·태그 반영은 US2(T169).
 */
@Service
public class PostPublishService {

    static final int TITLE_MAX = 200;

    private final PostAccess postAccess;
    private final PostDraftRepository postDraftRepository;
    private final MarkdownRenderer markdownRenderer;
    private final PostService postService;
    private final Clock clock;

    public PostPublishService(PostAccess postAccess, PostDraftRepository postDraftRepository,
            MarkdownRenderer markdownRenderer, PostService postService, Clock clock) {
        this.postAccess = postAccess;
        this.postDraftRepository = postDraftRepository;
        this.markdownRenderer = markdownRenderer;
        this.postService = postService;
        this.clock = clock;
    }

    @Transactional
    public PostDetailResponse publish(long userId, Long postId, PublishSettingsRequest settings) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        PostDraft draft = postDraftRepository.findById(postId).orElse(null);
        String title = draft != null ? draft.getTitle() : post.getTitle();
        String markdown = draft != null ? draft.getContentMarkdown() : post.getContentMarkdown();

        title = title == null ? "" : title.strip();
        if (title.isEmpty()) {
            throw invalid(new FieldError("title", "REQUIRED", Map.of()));
        }
        if (title.length() > TITLE_MAX) {
            throw invalid(new FieldError("title", "TOO_LONG", Map.of("max", TITLE_MAX)));
        }
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(ErrorCode.POST_CONTENT_EMPTY, "Post content is empty: " + postId);
        }

        RenderedContent content = markdownRenderer.render(markdown);
        String thumbnailUrl = thumbnailUrl(content, settings.thumbnailMediaKey());
        boolean commentEnabled = settings.commentEnabled() == null || settings.commentEnabled();
        post.publish(title, markdown, content.html(), content.text(), content.summary(), thumbnailUrl,
                settings.visibility(), commentEnabled, clock.instant());
        if (draft != null) {
            postDraftRepository.delete(draft);
        }
        postDraftRepository.flush();
        return postService.detailOf(post, true);
    }

    /** 대표 이미지: 고른 키가 본문 이미지면 그것, 고르지 않았으면 본문 첫 이미지(FR-107). */
    private static String thumbnailUrl(RenderedContent content, String mediaKey) {
        if (mediaKey == null) {
            return content.firstMediaImageUrl();
        }
        if (!content.containsMediaImage(mediaKey)) {
            throw invalid(new FieldError("thumbnailMediaKey", "INVALID", Map.of()));
        }
        return "/media/" + mediaKey;
    }

    private static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
