package net.java21.blog.backend.report.service;

/** 숨김·해제 전후 상태(작업 기록 before·after의 {@code status}). {@code changed}가 false면 이미 그 상태였다(멱등). */
public record HideChange(String before, String after, boolean changed) {
}
