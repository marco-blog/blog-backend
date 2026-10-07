package net.java21.blog.backend.admin.spam.dto;

import java.time.Instant;

import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;

/** 005 contracts/api.md {@code BannedWord}. */
public record BannedWordResponse(long id, String word, BannedWordScope scope, BannedWordAction action,
        Creator createdBy, Instant createdAt, Instant updatedAt) {

    public record Creator(long id, String nickname) {
    }

    public static BannedWordResponse of(BannedWord word) {
        return new BannedWordResponse(word.getId(), word.getWord(), word.getScope(), word.getAction(),
                new Creator(word.getCreatedBy().getId(), word.getCreatedBy().getNickname()), word.getCreatedAt(),
                word.getUpdatedAt());
    }
}
