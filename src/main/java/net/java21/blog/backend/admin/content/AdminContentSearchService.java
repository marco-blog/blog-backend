package net.java21.blog.backend.admin.content;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.admin.content.AdminContentSearchRepository.CommentCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.GuestbookCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.PostCriteria;
import net.java21.blog.backend.admin.content.dto.AdminCommentRow;
import net.java21.blog.backend.admin.content.dto.AdminGuestbookRow;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.search.service.SearchQueryParser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콘텐츠 관리 검색의 입력 검증(006 contracts/api.md "콘텐츠 관리 검색", research A4).
 * <ul>
 *   <li>글 {@code q}: 제목 전문 검색. 002 검색어 규칙({@link SearchQueryParser}: 2~100자, 낱말 5개 AND)</li>
 *   <li>댓글·방명록 {@code q}: 내용 부분 일치 2~100자. 글·작성자·블로그 범위가 하나도 없으면 400 field {@code q} {@code INVALID}
 *       ({@code params.reason = SCOPE_REQUIRED}) — 본문 인덱스가 없어 서비스 전체 {@code LIKE}를 막는다(결정 표 9번)</li>
 *   <li>{@code status}·{@code visibility}: 지금 코드의 enum 값만(005가 HIDDEN을 더하면 자동으로 받는다). 아니면 400 {@code INVALID}</li>
 * </ul>
 */
@Service
public class AdminContentSearchService {

    static final String SCOPE_REQUIRED = "SCOPE_REQUIRED";

    private final AdminContentSearchRepository repository;
    private final SearchQueryParser queryParser;

    public AdminContentSearchService(AdminContentSearchRepository repository, SearchQueryParser queryParser) {
        this.repository = repository;
        this.queryParser = queryParser;
    }

    @Transactional(readOnly = true)
    public Page<AdminPostRow> posts(String q, String handle, Long authorId, String status, String visibility,
            Pageable pageable) {
        String titleQuery = blank(q) ? null : queryParser.parse(q).booleanQuery();
        return repository.posts(new PostCriteria(titleQuery, handle(handle), authorId,
                parse(PostStatus.class, "status", status), parse(PostVisibility.class, "visibility", visibility)),
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminCommentRow> comments(Long postId, Long authorId, String handle, String status, String q,
            Pageable pageable) {
        String keyword = keyword(q, postId != null || authorId != null || !blank(handle));
        return repository.comments(new CommentCriteria(postId, authorId, handle(handle),
                parse(CommentStatus.class, "status", status), keyword), pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminGuestbookRow> guestbook(String handle, Long authorId, String status, String q,
            Pageable pageable) {
        String keyword = keyword(q, authorId != null || !blank(handle));
        return repository.guestbook(new GuestbookCriteria(handle(handle), authorId,
                parse(GuestbookStatus.class, "status", status), keyword), pageable);
    }

    /** 댓글·방명록 키워드: 범위가 있어야 하고 2~100자. */
    private static String keyword(String q, boolean scoped) {
        if (blank(q)) {
            return null;
        }
        if (!scoped) {
            throw invalid("q", "INVALID", Map.of("reason", SCOPE_REQUIRED));
        }
        String keyword = q.strip();
        int length = keyword.codePointCount(0, keyword.length());
        if (length < SearchQueryParser.MIN_LENGTH) {
            throw invalid("q", "TOO_SHORT", Map.of("min", SearchQueryParser.MIN_LENGTH));
        }
        if (length > SearchQueryParser.MAX_LENGTH) {
            throw invalid("q", "TOO_LONG", Map.of("max", SearchQueryParser.MAX_LENGTH));
        }
        return keyword;
    }

    private static String handle(String handle) {
        return blank(handle) ? null : handle.strip().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException e) {
            List<String> allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
            throw invalid(field, "INVALID", Map.of("allowed", allowed));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid(String field, String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid content search: " + field,
                List.of(new FieldError(field, code, params)));
    }
}
