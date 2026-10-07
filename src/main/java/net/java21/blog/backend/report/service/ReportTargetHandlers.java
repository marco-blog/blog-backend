package net.java21.blog.backend.report.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.springframework.stereotype.Component;

/** 대상 종류 → 처리기(005 research M4). 처리기가 없는 종류(1.0의 {@code EXTERNAL_*})는 400 {@code targetType INVALID}. */
@Component
public class ReportTargetHandlers {

    private final Map<ReportTargetType, ReportTargetHandler> handlers = new EnumMap<>(ReportTargetType.class);

    public ReportTargetHandlers(List<ReportTargetHandler> handlers) {
        for (ReportTargetHandler handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Duplicate report target handler: " + handler.type());
            }
        }
    }

    public Optional<ReportTargetHandler> find(ReportTargetType type) {
        return Optional.ofNullable(type == null ? null : handlers.get(type));
    }

    /** 처리기가 있는 종류(선택지). */
    public Set<ReportTargetType> supported() {
        return handlers.keySet();
    }

    /**
     * 요청 값(문자열)을 처리기로 바꾼다.
     *
     * @param field 오류 필드 이름(보통 {@code targetType})
     * @throws BusinessException 400 {@code VALIDATION_FAILED}(field {@code REQUIRED}, 또는 {@code INVALID}·{@code params.allowed})
     */
    public ReportTargetHandler require(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of(field, "REQUIRED")));
        }
        ReportTargetType type;
        try {
            type = ReportTargetType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            type = null;
        }
        ReportTargetHandler handler = type == null ? null : handlers.get(type);
        if (handler == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unsupported report target type: " + raw,
                    List.of(new FieldError(field, "INVALID",
                            Map.of("allowed", supported().stream().map(Enum::name).toList()))));
        }
        return handler;
    }

    /** 이미 정해진 종류의 처리기(없으면 422 {@code REPORT_ACTION_NOT_ALLOWED}). */
    public ReportTargetHandler require(ReportTargetType type) {
        return find(type).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_ACTION_NOT_ALLOWED,
                "No handler for report target type: " + type));
    }
}
