package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/**
 * 블로그 글 목록 조건({@code GET /blogs/{handle}/posts?category=&tag=&year=&month=}, FR-026, 004 FR-061). 모두 생략할 수 있다.
 * 조건이 하나도 없으면 블로그 홈 목록이며 공지 글을 뺀다(004 FR-059). 월 조건의 발행 시각 범위는 서비스가
 * {@code BlogCalendar}로 채운다({@link #withRange}).
 *
 * @param categoryId    카테고리(상위면 하위 카테고리 글 포함)
 * @param tag           정규화한 태그 이름
 * @param month         발행 연·월(004, {@code blog.stats.time-zone} 기준)
 * @param publishedFrom 발행 시각 하한(포함)
 * @param publishedTo   발행 시각 상한(제외)
 */
public record PostListFilter(Long categoryId, String tag, YearMonth month, Instant publishedFrom,
        Instant publishedTo) {

    public static final PostListFilter NONE = new PostListFilter(null, null);

    /** 보관함 연도 하한·상한 */
    public static final int MIN_YEAR = 1970;
    public static final int MAX_YEAR = 9999;

    public PostListFilter(Long categoryId, String tag) {
        this(categoryId, tag, null, null, null);
    }

    public static PostListFilter ofMonth(YearMonth month) {
        return new PostListFilter(null, null, month, null, null);
    }

    /**
     * 요청 매개변수 검증: {@code year}·{@code month}는 둘 다 있어야 하고 범위 안이어야 하며 {@code category}·{@code tag}와 함께 쓸 수 없다.
     * 아니면 400 {@code VALIDATION_FAILED}.
     */
    public static PostListFilter of(Long categoryId, String tag, Integer year, Integer month) {
        if (year == null && month == null) {
            return new PostListFilter(categoryId, tag);
        }
        List<FieldError> errors = new ArrayList<>();
        if (year == null) {
            errors.add(FieldError.of("year", "REQUIRED"));
        } else if (year < MIN_YEAR || year > MAX_YEAR) {
            errors.add(new FieldError("year", "INVALID", Map.of("min", MIN_YEAR, "max", MAX_YEAR)));
        }
        if (month == null) {
            errors.add(FieldError.of("month", "REQUIRED"));
        } else if (month < 1 || month > 12) {
            errors.add(new FieldError("month", "INVALID", Map.of("min", 1, "max", 12)));
        }
        if (categoryId != null || tag != null) {
            errors.add(FieldError.of(categoryId != null ? "category" : "tag", "INVALID"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid post list filter", errors);
        }
        return ofMonth(YearMonth.of(year, month));
    }

    public PostListFilter withRange(Instant from, Instant to) {
        return new PostListFilter(categoryId, tag, month, from, to);
    }

    /** 조건 없는 블로그 홈 목록인지(공지 제외). */
    public boolean isHome() {
        return categoryId == null && tag == null && month == null;
    }
}
