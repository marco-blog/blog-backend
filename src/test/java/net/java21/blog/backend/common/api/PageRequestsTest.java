package net.java21.blog.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** api-guidelines 4절: page 0부터, size 기본 20·최대 50(넘으면 50), 허용한 sort 필드만. */
class PageRequestsTest {

    private final PageRequests posts = PageRequests.sortableBy(
            Sort.by(Sort.Direction.DESC, "publishedAt"), "publishedAt", "createdAt", "title");

    @Test
    void defaultsToFirstPageOf20WithDefaultSort() {
        Pageable pageable = posts.resolve(null, null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "publishedAt"));
    }

    @Test
    void pageStartsAtZero() {
        assertThat(posts.resolve(0, 10, null).getPageNumber()).isZero();
        assertThat(posts.resolve(3, 10, null).getOffset()).isEqualTo(30);
    }

    @Test
    void sizeAbove50IsClampedTo50() {
        assertThat(posts.resolve(0, 50, null).getPageSize()).isEqualTo(50);
        assertThat(posts.resolve(0, 51, null).getPageSize()).isEqualTo(PageRequests.MAX_SIZE).isEqualTo(50);
        assertThat(posts.resolve(0, 10_000, null).getPageSize()).isEqualTo(50);
    }

    @Test
    void negativePageIsValidationFailed() {
        expectFieldError(() -> posts.resolve(-1, 20, null), "page", "TOO_SMALL");
    }

    @Test
    void sizeBelowOneIsValidationFailed() {
        expectFieldError(() -> posts.resolve(0, 0, null), "size", "TOO_SMALL");
    }

    @Test
    void parsesAllowedSortWithDirection() {
        assertThat(posts.resolve(0, 20, "createdAt,desc").getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(posts.resolve(0, 20, "title,ASC").getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "title"));
        assertThat(posts.resolve(0, 20, " title ").getSort())
                .as("방향을 빼면 오름차순")
                .isEqualTo(Sort.by(Sort.Direction.ASC, "title"));
        assertThat(posts.resolve(0, 20, "").getSort())
                .as("빈 값은 기본 정렬")
                .isEqualTo(Sort.by(Sort.Direction.DESC, "publishedAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password,desc", "id", "user.email,asc", "createdAt,sideways", "createdAt,desc,title",
            ",desc"})
    void disallowedSortIsValidationFailed(String sort) {
        expectFieldError(() -> posts.resolve(0, 20, sort), "sort", "INVALID");
    }

    @Test
    void unsortedDefaultIsAllowed() {
        PageRequests plain = PageRequests.sortableBy(Sort.unsorted());
        assertThat(plain.resolve(null, null, null).getSort().isUnsorted()).isTrue();
        expectFieldError(() -> plain.resolve(0, 20, "createdAt"), "sort", "INVALID");
    }

    private static void expectFieldError(ThrowingCallable call, String field, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement().satisfies(fieldError -> {
                        assertThat(fieldError.field()).isEqualTo(field);
                        assertThat(fieldError.code()).isEqualTo(code);
                    });
                });
    }
}
