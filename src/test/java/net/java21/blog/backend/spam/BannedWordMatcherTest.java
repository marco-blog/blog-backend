package net.java21.blog.backend.spam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.text.TextNormalizer;
import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.spam.repository.BannedWordRepository;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 005 T063: 금칙어 정규화·이름류·본문류(거부·가림)·범위·다시 읽기(research M11). */
@ExtendWith(MockitoExtension.class)
class BannedWordMatcherTest {

    @Mock
    private BannedWordRepository repository;

    private static BannedWord word(String word, BannedWordScope scope, BannedWordAction action) {
        return new BannedWord(TestEntities.user(1L), TextNormalizer.nfkcLower(word), scope, action);
    }

    private BannedWordMatcher matcher(BannedWord... words) {
        when(repository.findAll()).thenReturn(List.of(words));
        return new BannedWordMatcher(repository);
    }

    @Test
    void normalizesWithNfkcTrimAndLowercase() {
        assertThat(TextNormalizer.nfkcLower("  ＢＡＤ ")).isEqualTo("bad");
        assertThat(TextNormalizer.compact("나쁜 말, 이다!")).isEqualTo("나쁜말이다");
        assertThat(TextNormalizer.contentKey(" Hello \n  World ")).isEqualTo("hello world");
        assertThat(TextNormalizer.nfkcLower(null)).isEmpty();
        BannedWordMatcher matcher = matcher(word("bad", BannedWordScope.NAME, BannedWordAction.REJECT));
        assertThat(matcher.containsNameWord("Ｂａｄ boy")).isTrue();
        assertThat(matcher.containsNameWord("good")).isFalse();
        assertThat(matcher.containsNameWord(null)).isFalse();
        assertThat(matcher.containsNameWord("  ")).isFalse();
    }

    @Test
    void namesAreCheckedWithoutSpacesAndPunctuation() {
        BannedWordMatcher matcher = matcher(word("나쁜말", BannedWordScope.NAME, BannedWordAction.REJECT),
                word("spam word", BannedWordScope.ALL, BannedWordAction.REJECT));
        assertThat(matcher.containsNameWord("나쁜 말")).isTrue();
        assertThat(matcher.containsNameWord("나-쁜.말")).isTrue();
        assertThat(matcher.containsNameWord("spam_word")).isTrue();
        assertThatThrownBy(() -> matcher.requireCleanName("nickname", "나 쁜 말"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(FieldError.of("nickname", "BANNED_WORD"));
                    assertThat(e.getMessage()).doesNotContain("나쁜");
                });
        List<FieldError> errors = new ArrayList<>();
        matcher.collectName(errors, "handle", "clean");
        matcher.collectName(errors, "title", "나쁜말 블로그");
        assertThat(errors).containsExactly(FieldError.of("title", "BANNED_WORD"));
        matcher.requireCleanName("nickname", "착한 말");
    }

    @Test
    void contentRejectWinsOverMask() {
        BannedWordMatcher matcher = matcher(word("광고", BannedWordScope.CONTENT, BannedWordAction.MASK),
                word("도박", BannedWordScope.ALL, BannedWordAction.REJECT));
        assertThatThrownBy(() -> matcher.filterContent("content", "광고 그리고 도박"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors()).containsExactly(FieldError.of("content", "BANNED_WORD")));
    }

    @Test
    void maskReplacesEveryOccurrenceWithSameLength() {
        BannedWordMatcher matcher = matcher(word("광고", BannedWordScope.CONTENT, BannedWordAction.MASK),
                word("고문", BannedWordScope.ALL, BannedWordAction.MASK),
                word("ＡＤ", BannedWordScope.CONTENT, BannedWordAction.MASK));
        assertThat(matcher.filterContent("content", "광고, 또 광고!")).isEqualTo("**, 또 **!");
        // 겹치는 단어(광고 + 고문)는 이어서 가린다.
        assertThat(matcher.filterContent("content", "광고문")).isEqualTo("***");
        // 전각·대문자는 정규화해 찾고 원문 길이대로 가린다.
        assertThat(matcher.filterContent("content", "Big ＡＤ here")).isEqualTo("Big ** here");
        assertThat(matcher.filterContent("content", "깨끗한 글")).isEqualTo("깨끗한 글");
        assertThat(matcher.filterContent("content", "")).isEmpty();
        assertThat(matcher.filterContent("content", null)).isNull();
    }

    @Test
    void scopesAreSeparate() {
        BannedWordMatcher matcher = matcher(word("이름만", BannedWordScope.NAME, BannedWordAction.REJECT),
                word("본문만", BannedWordScope.CONTENT, BannedWordAction.REJECT));
        assertThat(matcher.containsNameWord("본문만")).isFalse();
        assertThat(matcher.containsNameWord("이름만")).isTrue();
        assertThat(matcher.filterContent("content", "이름만 있는 글")).isEqualTo("이름만 있는 글");
        assertThatThrownBy(() -> matcher.filterContent("content", "본문만"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void emptyListAllowsEverything() {
        BannedWordMatcher matcher = matcher();
        assertThat(matcher.containsNameWord("anything")).isFalse();
        assertThat(matcher.filterContent("content", "anything")).isEqualTo("anything");
    }

    @Test
    void listIsReadOnceAndReloadedAfterChange() {
        when(repository.findAll()).thenReturn(List.of())
                .thenReturn(List.of(word("new", BannedWordScope.ALL, BannedWordAction.REJECT)));
        BannedWordMatcher matcher = new BannedWordMatcher(repository);
        assertThat(matcher.containsNameWord("new")).isFalse();
        assertThat(matcher.containsNameWord("new")).isFalse();
        verify(repository, times(1)).findAll();

        matcher.onChanged(new BannedWordsChangedEvent());
        assertThat(matcher.containsNameWord("brand new")).isTrue();
        verify(repository, times(2)).findAll();
    }
}
