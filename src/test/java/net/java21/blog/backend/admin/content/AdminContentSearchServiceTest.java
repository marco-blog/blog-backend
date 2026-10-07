package net.java21.blog.backend.admin.content;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import net.java21.blog.backend.admin.content.AdminContentSearchRepository.CommentCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.GuestbookCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.PostCriteria;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.service.SearchQueryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 006 T024(contracts/api.md "콘텐츠 관리 검색"): 검색어 규칙, 범위 없는 키워드 400 SCOPE_REQUIRED, enum 값 확인, handle 소문자. */
class AdminContentSearchServiceTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final AdminContentSearchRepository repository = mock(AdminContentSearchRepository.class);
    private final AdminContentSearchService service = new AdminContentSearchService(repository,
            new SearchQueryParser(new SearchProperties(5, 2)));

    @BeforeEach
    void setUp() {
        when(repository.posts(any(), any())).thenReturn(Page.empty());
        when(repository.comments(any(), any())).thenReturn(Page.empty());
        when(repository.guestbook(any(), any())).thenReturn(Page.empty());
    }

    @Test
    void postCriteria() {
        service.posts(" 자바 스프링 ", " MyBlog ", 3L, "DELETED", "PRIVATE", PAGE);
        verify(repository).posts(new PostCriteria("+\"자바\" +\"스프링\"", "myblog", 3L, PostStatus.DELETED,
                PostVisibility.PRIVATE), PAGE);
        service.posts(null, " ", null, "", null, PAGE);
        verify(repository).posts(new PostCriteria(null, null, null, null, null), PAGE);
    }

    @Test
    void postQueryUsesSearchRules() {
        assertCode(() -> service.posts("a", null, null, null, null, PAGE), ErrorCode.VALIDATION_FAILED);
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.posts(null, null, null, "HIDDEN_X", null, PAGE));
        assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("status");
            assertThat(f.code()).isEqualTo("INVALID");
        });
        assertCode(() -> service.posts(null, null, null, null, "SECRET", PAGE), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void commentKeywordNeedsScope() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.comments(null, null, " ", null, "키워드", PAGE));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.fieldErrors()).containsExactly(new FieldError("q", "INVALID",
                Map.of("reason", AdminContentSearchService.SCOPE_REQUIRED)));
        verifyNoInteractions(repository);

        service.comments(7L, null, null, "DELETED", " 키워드 ", PAGE);
        verify(repository).comments(new CommentCriteria(7L, null, null, CommentStatus.DELETED, "키워드"), PAGE);
        service.comments(null, 8L, null, null, null, PAGE);
        verify(repository).comments(new CommentCriteria(null, 8L, null, null, null), PAGE);
        service.comments(null, null, null, null, null, PAGE);
        verify(repository).comments(new CommentCriteria(null, null, null, null, null), PAGE);
    }

    @Test
    void keywordLength() {
        BusinessException shortOne = catchThrowableOfType(BusinessException.class,
                () -> service.comments(1L, null, null, null, "가", PAGE));
        assertThat(shortOne.fieldErrors().get(0).code()).isEqualTo("TOO_SHORT");
        BusinessException longOne = catchThrowableOfType(BusinessException.class,
                () -> service.guestbook("blog", null, null, "가".repeat(101), PAGE));
        assertThat(longOne.fieldErrors().get(0).code()).isEqualTo("TOO_LONG");
        assertThat(longOne.fieldErrors().get(0).params()).containsEntry("max", 100);
    }

    @Test
    void guestbookCriteria() {
        assertCode(() -> service.guestbook(null, null, null, "키워드", PAGE), ErrorCode.VALIDATION_FAILED);
        service.guestbook("Blog", null, "ACTIVE", "키워드", PAGE);
        verify(repository).guestbook(new GuestbookCriteria("blog", null, GuestbookStatus.ACTIVE, "키워드"), PAGE);
        service.guestbook(null, 4L, null, "키워드", PAGE);
        verify(repository).guestbook(new GuestbookCriteria(null, 4L, null, "키워드"), PAGE);
    }
}
