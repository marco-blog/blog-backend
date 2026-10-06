package net.java21.blog.backend.tag.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/**
 * 태그 정규화와 검증(T177, FR-025, data-model tags). 앞뒤 공백을 지우고 소문자로 바꾼다. 단어 사이 공백은 그대로 둔다
 * (" Spring Boot " → "spring boot"). 각 1~{@value #NAME_MAX}자, 정규화 뒤 중복을 지운 개수가 {@value #MAX_PER_POST}개를 넘으면
 * 422 {@code TAG_LIMIT_EXCEEDED}.
 */
public final class TagNormalizer {

    public static final int NAME_MAX = 30;
    public static final int MAX_PER_POST = 10;

    private TagNormalizer() {
    }

    /** 태그 하나를 정규화한다. null은 빈 문자열. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 글의 태그 목록을 정규화·중복 제거(처음 나온 순서 유지)하고 검증한다.
     *
     * @param field 오류를 붙일 필드 이름({@code tags})
     */
    public static List<String> normalizeAll(List<String> raw, String field) {
        if (raw == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String value : raw) {
            String name = normalize(value);
            if (name.isEmpty()) {
                throw invalid(field, "REQUIRED", Map.of());
            }
            if (name.length() > NAME_MAX) {
                throw invalid(field, "TOO_LONG", Map.of("max", NAME_MAX));
            }
            names.add(name);
        }
        if (names.size() > MAX_PER_POST) {
            throw new BusinessException(ErrorCode.TAG_LIMIT_EXCEEDED,
                    "Too many tags: " + names.size() + " > " + MAX_PER_POST);
        }
        return new ArrayList<>(names);
    }

    private static BusinessException invalid(String field, String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid tag",
                List.of(new FieldError(field, code, params)));
    }
}
