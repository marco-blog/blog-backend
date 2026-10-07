package net.java21.blog.backend.common.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 공통 틀의 오류 응답으로 바꾼다(blog-docs/api-guidelines.md 4·5절).
 * HTTP 상태 코드는 {@link ErrorCode}의 값을 그대로 쓴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ResponseEntity<ApiResponse<Void>> response = error(e.errorCode(), e.getMessage(), e.fieldErrors());
        if (e.retryAfterSeconds() == null) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(response.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return error(ErrorCode.VALIDATION_FAILED, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiResponse<Void>> handleParameterValidation(HandlerMethodValidationException e) {
        List<FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> FieldError.of(result.getMethodParameter().getParameterName(),
                                ValidationCodes.of(error.getCodes()))))
                .toList();
        return error(ErrorCode.VALIDATION_FAILED, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()),
                        ValidationCodes.of(v), ValidationCodes.params(v)))
                .toList();
        return error(ErrorCode.VALIDATION_FAILED, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(ErrorCode.VALIDATION_FAILED, e.getMessage(),
                List.of(FieldError.of(e.getParameterName(), ValidationCodes.REQUIRED)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(ErrorCode.VALIDATION_FAILED, "Invalid value for " + e.getName(),
                List.of(FieldError.of(e.getName(), ValidationCodes.INVALID_FORMAT)));
    }

    /** 업로드 크기가 {@code spring.servlet.multipart.max-file-size}(= {@code blog.media.max-size})를 넘었다(T212). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return error(ErrorCode.MEDIA_TOO_LARGE, "Upload too large", List.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return error(ErrorCode.VALIDATION_FAILED, e.getMessage(),
                List.of(FieldError.of(e.getRequestPartName(), ValidationCodes.REQUIRED)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(ErrorCode.VALIDATION_FAILED, "Malformed request body", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return error(ErrorCode.NOT_FOUND, "No endpoint " + e.getResourcePath(), List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return error(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage(), List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), List.of());
    }

    /** 메서드 보안(@PreAuthorize 등)에서 던진 예외는 보안 필터 대신 여기서 받는다. */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleUnauthenticated(AuthenticationCredentialsNotFoundException e) {
        return error(ErrorCode.UNAUTHENTICATED, "Authentication required", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return error(ErrorCode.FORBIDDEN, "Access denied", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return error(ErrorCode.INTERNAL_ERROR, "Internal error", List.of());
    }

    private static ResponseEntity<ApiResponse<Void>> error(ErrorCode code, String message, List<FieldError> fieldErrors) {
        ApiResponse.Header header = ApiResponse.Header.failure(
                code.name(), message, fieldErrors, RequestIdFilter.currentTraceId());
        // 형식을 정해 두어 Accept가 JSON이 아닌 요청(피드 리더의 application/rss+xml 등)에도 공통 틀 JSON으로 답한다(002 contracts/api.md).
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(header));
    }

    private static FieldError toFieldError(org.springframework.validation.FieldError error) {
        if (error.contains(ConstraintViolation.class)) {
            ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
            return new FieldError(error.getField(), ValidationCodes.of(violation), ValidationCodes.params(violation));
        }
        // 타입 변환 실패(typeMismatch) 등 Bean Validation이 아닌 바인딩 오류
        return new FieldError(error.getField(), ValidationCodes.INVALID_FORMAT, Map.of());
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** Bean Validation 제약을 front 메시지 키로 쓰는 검증 오류 코드로 바꾼다. */
    static final class ValidationCodes {

        static final String REQUIRED = "REQUIRED";
        static final String TOO_LONG = "TOO_LONG";
        static final String TOO_SHORT = "TOO_SHORT";
        static final String TOO_SMALL = "TOO_SMALL";
        static final String TOO_LARGE = "TOO_LARGE";
        static final String INVALID_FORMAT = "INVALID_FORMAT";
        static final String INVALID = "INVALID";

        private ValidationCodes() {
        }

        static String of(ConstraintViolation<?> violation) {
            Class<? extends java.lang.annotation.Annotation> type =
                    violation.getConstraintDescriptor().getAnnotation().annotationType();
            FieldErrorCode custom = type.getAnnotation(FieldErrorCode.class);
            if (custom != null) {
                return custom.value();
            }
            String constraint = type.getSimpleName();
            if (constraint.equals("Size") || constraint.equals("Length")) {
                Object min = violation.getConstraintDescriptor().getAttributes().get("min");
                int length = lengthOf(violation.getInvalidValue());
                return min instanceof Integer m && length < m ? TOO_SHORT : TOO_LONG;
            }
            return byName(constraint);
        }

        /** {@code codes}는 Spring이 만든 메시지 코드 목록이며 마지막 항목이 제약 이름이다(예: "NotBlank"). */
        static String of(String[] codes) {
            return codes == null || codes.length == 0 ? INVALID : byName(codes[codes.length - 1]);
        }

        static Map<String, Object> params(ConstraintViolation<?> violation) {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> attributes = violation.getConstraintDescriptor().getAttributes();
            for (String key : List.of("min", "max", "value")) {
                Object value = attributes.get(key);
                if (value instanceof Number || value instanceof String) {
                    params.put(key, value);
                }
            }
            if (params.get("max") instanceof Integer max && max == Integer.MAX_VALUE) {
                params.remove("max");
            }
            if (params.get("min") instanceof Integer min && min == 0) {
                params.remove("min");
            }
            return params;
        }

        private static String byName(String constraint) {
            return switch (constraint) {
                case "NotNull", "NotBlank", "NotEmpty", "AssertTrue" -> REQUIRED;
                case "Size", "Length" -> TOO_LONG;
                case "Min", "DecimalMin", "Positive", "PositiveOrZero" -> TOO_SMALL;
                case "Max", "DecimalMax", "Negative", "NegativeOrZero" -> TOO_LARGE;
                case "Pattern", "Email", "URL" -> INVALID_FORMAT;
                default -> INVALID;
            };
        }

        private static int lengthOf(Object value) {
            if (value instanceof CharSequence text) {
                return text.length();
            }
            if (value instanceof java.util.Collection<?> collection) {
                return collection.size();
            }
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            if (value != null && value.getClass().isArray()) {
                return java.lang.reflect.Array.getLength(value);
            }
            return 0;
        }
    }
}
