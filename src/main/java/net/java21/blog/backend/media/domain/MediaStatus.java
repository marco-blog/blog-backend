package net.java21.blog.backend.media.domain;

/** 이미지 상태(data-model "이미지 상태 전이"). */
public enum MediaStatus {
    /** 올렸지만 아직 어디에도 저장하지 않음(temp-dir, 올린 회원에게만 제공). */
    TEMP,
    /** 글·프로필·블로그 대표 이미지가 참조(upload-dir, 누구나). */
    ATTACHED,
    /** 아무 곳도 참조하지 않음. 정리 작업이 지운다(그 전에 다시 참조하면 ATTACHED). */
    ORPHANED
}
