package net.java21.blog.backend.common.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

/**
 * 모든 API 응답의 공통 틀(blog-docs/api-guidelines.md 4절).
 * <pre>{ "header": { "isSuccessful": true, "resultCode": "OK", "resultMessage": "" }, "result": ..., "totalCount": 135 }</pre>
 * {@code result}는 값이 없어도 {@code null}로 내보내고, {@code totalCount}·{@code nextCursor}는 목록일 때만 내보낸다.
 */
public record ApiResponse<T>(
        Header header,
        @JsonInclude(JsonInclude.Include.ALWAYS) T result,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long totalCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

    public static final String OK = "OK";

    /** 단건 또는 짧은 목록. */
    public static <T> ApiResponse<T> ok(T result) {
        return new ApiResponse<>(Header.success(), result, null, null);
    }

    /** 돌려줄 것이 없는 성공(삭제, 로그아웃 등). 204 대신 200 + {@code result: null}. */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(Header.success(), null, null, null);
    }

    /** 페이지 목록: {@code result}는 이번 페이지 항목, {@code totalCount}는 전체 개수. */
    public static <T> ApiResponse<List<T>> page(Page<T> page) {
        return new ApiResponse<>(Header.success(), page.getContent(), page.getTotalElements(), null);
    }

    /** 커서 목록: 다음이 없으면 {@code nextCursor}는 null이며 응답에서 빠진다. */
    public static <T> ApiResponse<List<T>> cursor(List<T> items, String nextCursor) {
        return new ApiResponse<>(Header.success(), items, null, nextCursor);
    }

    public static ApiResponse<Void> failure(Header header) {
        return new ApiResponse<>(header, null, null, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Header(
            @JsonInclude(JsonInclude.Include.ALWAYS) boolean isSuccessful,
            String resultCode,
            @JsonInclude(JsonInclude.Include.ALWAYS) String resultMessage,
            List<FieldError> fieldErrors,
            String traceId) {

        static Header success() {
            return new Header(true, OK, "", null, null);
        }

        public static Header failure(String resultCode, String resultMessage, List<FieldError> fieldErrors, String traceId) {
            return new Header(false, resultCode, resultMessage, fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors, traceId);
        }
    }
}
