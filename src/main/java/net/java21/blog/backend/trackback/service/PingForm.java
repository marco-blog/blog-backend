package net.java21.blog.backend.trackback.service;

/** 받은 핑의 폼 값(TrackBack 1.2 {@code url}·{@code title}·{@code excerpt}·{@code blog_name}). 정리 전 원래 값. */
public record PingForm(String url, String title, String excerpt, String blogName) {
}
