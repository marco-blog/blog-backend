package net.java21.blog.backend.portal.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/**
 * 포털 목록 출처 필터(007 FR-124, contracts/api.md): {@code ?source=all|internal|external}, 기본 {@code all}. 다른 값이면 400
 * {@code VALIDATION_FAILED}(field {@code source}, {@code INVALID}, {@code params.allowed}).
 */
public enum PortalSourceFilter {
    ALL,
    INTERNAL,
    EXTERNAL;

    public static final List<String> ALLOWED = List.of("all", "internal", "external");

    public static PortalSourceFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "all" -> ALL;
            case "internal" -> INTERNAL;
            case "external" -> EXTERNAL;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid source: " + raw,
                    List.of(new FieldError("source", "INVALID", Map.of("allowed", ALLOWED))));
        };
    }

    public boolean includes(PortalSourceType type) {
        return this == ALL || name().equals(type.name());
    }

    /** 캐시 키에 넣는 값. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
