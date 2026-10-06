package net.java21.blog.backend.common.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 페이지 목록 요청({@code ?page=0&size=20&sort=createdAt,desc})을 해석한다(api-guidelines 4절).
 * <ul>
 *   <li>{@code page}는 0부터, 음수면 400 {@code VALIDATION_FAILED}(field {@code page}, {@code TOO_SMALL}).</li>
 *   <li>{@code size}는 기본 {@value #DEFAULT_SIZE}, 최대 {@value #MAX_SIZE}이며 넘으면 {@value #MAX_SIZE}로 줄인다.
 *       1보다 작으면 400({@code TOO_SMALL}).</li>
 *   <li>{@code sort}는 {@code 필드} 또는 {@code 필드,asc|desc} 하나. 허용 목록 밖이면 400(field {@code sort}, {@code INVALID}).
 *       없으면 기본 정렬.</li>
 * </ul>
 * 오류는 {@link BusinessException}으로 던져 {@code GlobalExceptionHandler}가 공통 틀로 바꾼다. 응답은 {@link ApiResponse#page}.
 * <pre>{@code
 * private static final PageRequests POSTS = PageRequests.sortableBy(Sort.by(DESC, "publishedAt"), "publishedAt", "createdAt");
 *
 * @GetMapping ApiResponse<List<PostSummary>> list(@RequestParam(required = false) Integer page,
 *         @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
 *     return ApiResponse.page(postQuery.list(POSTS.resolve(page, size, sort)));
 * }
 * }</pre>
 * {@code sort}를 {@code String}으로 받는다. {@code List<String>}으로 받으면 Spring이 쉼표로 나눠 방향이 필드로 바뀐다.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private final Sort defaultSort;
    private final Set<String> sortableFields;

    private PageRequests(Sort defaultSort, Set<String> sortableFields) {
        this.defaultSort = defaultSort;
        this.sortableFields = sortableFields;
    }

    /** 정렬할 수 있는 필드(엔티티·DTO 속성 이름) 허용 목록과 기본 정렬. */
    public static PageRequests sortableBy(Sort defaultSort, String... sortableFields) {
        return new PageRequests(defaultSort, Set.of(sortableFields));
    }

    public Pageable resolve(Integer page, Integer size, String sort) {
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw invalid("page", "TOO_SMALL", Map.of("min", 0));
        }
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1) {
            throw invalid("size", "TOO_SMALL", Map.of("min", 1));
        }
        return PageRequest.of(pageNumber, Math.min(pageSize, MAX_SIZE), parseSort(sort));
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",", -1);
        String field = parts[0].strip();
        if (parts.length > 2 || !sortableFields.contains(field)) {
            throw invalid("sort", "INVALID", Map.of());
        }
        if (parts.length == 1) {
            return Sort.by(Sort.Direction.ASC, field);
        }
        return switch (parts[1].strip().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.by(Sort.Direction.ASC, field);
            case "desc" -> Sort.by(Sort.Direction.DESC, field);
            default -> throw invalid("sort", "INVALID", Map.of());
        };
    }

    private static BusinessException invalid(String field, String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid page request: " + field,
                List.of(new FieldError(field, code, params)));
    }
}
