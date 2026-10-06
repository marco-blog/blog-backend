package net.java21.blog.backend.releasenote.domain;

/** 릴리스 노트 상태(006 FR-167). 독자 API는 PUBLISHED만 읽는다(003 FR-161). */
public enum ReleaseNoteStatus {
    DRAFT,
    PUBLISHED
}
