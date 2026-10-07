package net.java21.blog.backend.manage.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.dto.AuthorResponse;
import net.java21.blog.backend.comment.repository.BlogCommentRow;
import net.java21.blog.backend.comment.repository.CommentQueryRepository;
import net.java21.blog.backend.manage.dto.ManageCommentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 관리 댓글(T196, 006 FR-099·100의 001 범위): 내 블로그 모든 글의 댓글 목록(최신순)과 대시보드 댓글 수치.
 * 휴지통 글의 댓글과 삭제 자리는 빠진다. 지우기는 댓글 API({@code DELETE /comments/{id}}, 글 주인 권한)를 쓴다.
 */
@Service
public class ManageCommentService {

    /** 대시보드 "새 댓글" 기간 */
    static final Duration NEW_COMMENT_WINDOW = Duration.ofDays(7);

    private final BlogAccess blogAccess;
    private final CommentQueryRepository repository;
    private final Clock clock;

    public ManageCommentService(BlogAccess blogAccess, CommentQueryRepository repository, Clock clock) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.clock = clock;
    }

    /** 대시보드 댓글 수치 */
    public record CommentStats(long newComments7d, List<ManageCommentResponse> recentComments) {
    }

    /** 관리 댓글 목록. 쿼리 3회(블로그, 목록, 전체 수). 남의 블로그 403, 없는·삭제된 블로그 404. */
    @Transactional(readOnly = true)
    public Page<ManageCommentResponse> comments(long userId, String handle, Pageable pageable) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        return repository.findBlogComments(blog.getId(), pageable).map(ManageCommentService::toResponse);
    }

    /** 대시보드: 최근 7일 새 댓글 수와 최근 댓글 {@code limit}건. 쿼리 2회. 블로그 확인은 부른 쪽이 먼저 한다. */
    @Transactional(readOnly = true)
    public CommentStats stats(Long blogId, int limit) {
        long newComments = repository.countBlogCommentsSince(blogId, clock.instant().minus(NEW_COMMENT_WINDOW));
        List<ManageCommentResponse> recent = repository.findRecentBlogComments(blogId, limit).stream()
                .map(ManageCommentService::toResponse)
                .toList();
        return new CommentStats(newComments, recent);
    }

    private static ManageCommentResponse toResponse(BlogCommentRow row) {
        AuthorResponse author = row.userId() == null ? null : AuthorResponse.member(row.userId(), row.nickname(),
                Media.urlOf(row.profileMediaKey()));
        return new ManageCommentResponse(row.id(), row.content(), author, false, row.createdAt(), row.updatedAt(),
                row.postId(), row.postTitle());
    }
}
