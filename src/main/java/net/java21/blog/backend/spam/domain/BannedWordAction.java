package net.java21.blog.backend.spam.domain;

/** 금칙어 처리(banned_words.action): 저장 거부(REJECT) 또는 가림(MASK). 이름류에는 늘 거부(FR-143). */
public enum BannedWordAction {
    REJECT,
    MASK
}
