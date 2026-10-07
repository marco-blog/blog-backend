package net.java21.blog.backend.admin.spam.dto;

/**
 * 금칙어 추가({@code word}·{@code scope}·{@code action} 필수)와 변경({@code scope}·{@code action}만, 보낸 값만). 값 검증은
 * {@code BannedWordService}.
 */
public record BannedWordRequest(String word, String scope, String action) {
}
