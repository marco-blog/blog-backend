package net.java21.blog.backend.spam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 금칙어(banned_words, 005 FR-143). 단어는 정규화(NFKC·앞뒤 공백 제거·소문자)한 값을 저장하고 중복을 막는다
 * ({@code uk_banned_words_word}, H2에도 {@link UniqueConstraint}). 등록한 관리자는 LAZY.
 */
@Entity
@Table(name = "banned_words", uniqueConstraints = @UniqueConstraint(name = "uk_banned_words_word",
        columnNames = "word"))
public class BannedWord extends BaseTimeEntity {

    public static final int WORD_MAX = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(nullable = false, length = WORD_MAX)
    private String word;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private BannedWordScope scope = BannedWordScope.ALL;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private BannedWordAction action = BannedWordAction.REJECT;

    protected BannedWord() {
    }

    public BannedWord(User createdBy, String word, BannedWordScope scope, BannedWordAction action) {
        this.createdBy = createdBy;
        this.word = word;
        this.scope = scope;
        this.action = action;
    }

    public void change(BannedWordScope scope, BannedWordAction action) {
        if (scope != null) {
            this.scope = scope;
        }
        if (action != null) {
            this.action = action;
        }
    }

    public Long getId() {
        return id;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getWord() {
        return word;
    }

    public BannedWordScope getScope() {
        return scope;
    }

    public BannedWordAction getAction() {
        return action;
    }
}
