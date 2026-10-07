package net.java21.blog.backend.spam.domain;

/** 금칙어 적용 범위(banned_words.scope, 005 FR-143): 이름류(NAME), 본문류(CONTENT), 둘 다(ALL). */
public enum BannedWordScope {
    NAME,
    CONTENT,
    ALL
}
