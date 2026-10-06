package net.java21.blog.backend.blog.dto;

/** 블로그 바로가기 {@code { handle, title }}(로그인·{@code /me} 응답의 {@code blogs}). */
public record BlogLink(String handle, String title) {
}
