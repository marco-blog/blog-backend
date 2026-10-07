package net.java21.blog.backend.external.verify;

/** 인증 코드 발급 {@code { feedUrl }}과 확인(선택 {@code feedUrl}: 서버가 피드 주소를 기억하지 못할 때 같은 해시인지 확인 후 사용). */
public record VerificationRequest(String feedUrl) {
}
