package net.java21.blog.backend.post.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.dto.DraftResponse;
import net.java21.blog.backend.post.dto.DraftWriteRequest;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.SavedDraftResponse;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작성·임시저장(FR-013, FR-016, FR-108). 작성 중 내용은 {@code post_drafts}에만 저장하고 발행본({@code posts})은 바꾸지 않는다.
 * 발행 전 글은 블로그 관리 목록에 보이도록 {@code posts.title}만 사본 제목과 맞춘다.
 */
@Service
public class PostDraftService {

    private final BlogAccess blogAccess;
    private final PostAccess postAccess;
    private final PostRepository postRepository;
    private final PostDraftRepository postDraftRepository;
    private final PostQueryRepository postQueryRepository;
    private final TagQueryRepository tagQueryRepository;
    private final Clock clock;

    public PostDraftService(BlogAccess blogAccess, PostAccess postAccess, PostRepository postRepository,
            PostDraftRepository postDraftRepository, PostQueryRepository postQueryRepository,
            TagQueryRepository tagQueryRepository, Clock clock) {
        this.blogAccess = blogAccess;
        this.postAccess = postAccess;
        this.postRepository = postRepository;
        this.postDraftRepository = postDraftRepository;
        this.postQueryRepository = postQueryRepository;
        this.tagQueryRepository = tagQueryRepository;
        this.clock = clock;
    }

    /** 이 블로그의 새 임시저장 글: posts(DRAFT) + post_drafts. */
    @Transactional
    public SavedDraftResponse create(long userId, String handle, DraftWriteRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Post post = postRepository.save(new Post(blog, nullToEmpty(request.title())));
        PostDraft draft = new PostDraft(post);
        draft.write(request.title(), request.contentMarkdown(), request.categoryId(), request.tags(), clock.instant());
        postDraftRepository.save(draft);
        return new SavedDraftResponse(post.getId(), draft.getSavedAt());
    }

    /** 자동저장·임시저장. 발행된 글이면 사본만 바뀐다(FR-108). */
    @Transactional
    public SavedDraftResponse save(long userId, Long postId, DraftWriteRequest request) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        PostDraft draft = postDraftRepository.findById(postId).orElseGet(() -> new PostDraft(post));
        Instant now = clock.instant();
        draft.write(request.title(), request.contentMarkdown(), request.categoryId(), request.tags(), now);
        postDraftRepository.save(draft);
        post.syncDraftTitle(request.title());
        return new SavedDraftResponse(postId, now);
    }

    /** 작성 화면 불러오기. 사본이 없으면 발행본 내용(카테고리·태그 포함)을 그대로 준다. */
    @Transactional(readOnly = true)
    public DraftResponse get(long userId, Long postId) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        return postDraftRepository.findById(postId)
                .map(d -> new DraftResponse(d.getTitle(), d.getContentMarkdown(), d.getCategoryId(), d.getTags(),
                        d.getSavedAt()))
                .orElseGet(() -> new DraftResponse(post.getTitle(), post.getContentMarkdown(),
                        post.getCategory() == null ? null : post.getCategory().getId(),
                        tagQueryRepository.findTagNames(List.of(postId)).getOrDefault(postId, List.of()),
                        post.getUpdatedAt()));
    }

    /**
     * 작성 중 사본 폐기: 발행된 글이면 사본만 지우고 발행본은 그대로 둔다. 발행 전 글은 409 {@code POST_NOT_PUBLISHED}
     * (발행 전 글은 {@code DELETE /posts/{id}}로 휴지통에 보낸다). 사본이 없어도 성공.
     */
    @Transactional
    public void discard(long userId, Long postId) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        if (!post.isPublished()) {
            throw new BusinessException(ErrorCode.POST_NOT_PUBLISHED, "Post is not published: " + postId);
        }
        postDraftRepository.findById(postId).ifPresent(postDraftRepository::delete);
    }

    /** 이 블로그의 가장 최근 임시저장(이어 쓰기 확인용). 없으면 null. */
    @Transactional(readOnly = true)
    public LatestDraftResponse latest(long userId, String handle) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        return postQueryRepository.findLatestDraft(blog.getId()).orElse(null);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
