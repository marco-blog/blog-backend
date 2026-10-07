package net.java21.blog.backend.post.dto;

/** 보호 글 열기({@code POST /posts/{id}/unlock}, 004). 비밀번호는 본문으로만 받는다(쿼리 문자열은 기록에 남는다). */
public record UnlockPostRequest(String password) {

    /** 로그에 비밀번호가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "UnlockPostRequest[password=****]";
    }
}
