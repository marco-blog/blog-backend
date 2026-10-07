package net.java21.blog.backend.external.domain;

/** 주제 출처(007 FR-118). OWNER·REVIEW는 사람이 정한 것이라 자동으로 덮어쓰지 않는다. */
public enum TopicSource {
    OWNER, REVIEW, RULE, AUTO, DEFAULT;

    public boolean isHuman() {
        return this == OWNER || this == REVIEW;
    }
}
