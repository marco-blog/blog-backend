package net.java21.blog.backend.common.dto;

/**
 * 댓글·방명록의 작성자(004 contracts/api.md, 001 {@code CommentAuthor}를 옮겨 공용으로). 회원은 {@code userId}·닉네임·프로필
 * 이미지({@code /media/{key}} 또는 null), 비회원은 {@code userId} null·이름·{@code guest: true}.
 */
public record AuthorResponse(Long userId, String nickname, String profileImageUrl, boolean guest) {

    public static AuthorResponse member(Long userId, String nickname, String profileImageUrl) {
        return new AuthorResponse(userId, nickname, profileImageUrl, false);
    }

    public static AuthorResponse guest(String name) {
        return new AuthorResponse(null, name, null, true);
    }
}
