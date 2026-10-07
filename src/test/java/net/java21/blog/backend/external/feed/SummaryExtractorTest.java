package net.java21.blog.backend.external.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 007 T010: 요약 200자(코드 포인트, 글자 묶음 경계). */
class SummaryExtractorTest {

    @Test
    void shortTextIsKept() {
        assertThat(SummaryExtractor.summaryOf("<p>짧은 요약</p>")).isEqualTo("짧은 요약");
        assertThat(SummaryExtractor.summaryOf("<script>x</script>")).isNull();
        assertThat(SummaryExtractor.ellipsize(null, 10)).isNull();
    }

    @Test
    void exactly200IsKeptAnd201IsEllipsized() {
        String s200 = "가".repeat(200);
        assertThat(SummaryExtractor.summaryOf(s200)).isEqualTo(s200);

        String s201 = "가".repeat(201);
        String summary = SummaryExtractor.summaryOf(s201);
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(200);
        assertThat(summary).endsWith("…").startsWith("가".repeat(199));
    }

    @Test
    void countsCodePointsNotChars() {
        String emoji = "😀".repeat(200); // 200 코드 포인트, 400 char
        assertThat(SummaryExtractor.ellipsize(emoji, 200)).isEqualTo(emoji);
    }

    @Test
    void doesNotCutInsideGraphemeClusters() {
        // 198자 + 결합 문자가 붙은 글자(e + U+0301) + 꼬리: 199번째 경계가 결합 문자 앞이면 묶음 전체를 뺀다.
        String text = "a".repeat(198) + "é" + "zzz";
        String out = SummaryExtractor.ellipsize(text, 200);
        assertThat(out).isEqualTo("a".repeat(198) + "…");

        String family = "a".repeat(198) + "👨‍👩‍👧" + "zz";
        String cut = SummaryExtractor.ellipsize(family, 200);
        assertThat(cut).isEqualTo("a".repeat(198) + "…");
        assertThat(cut).doesNotContain("‍");
    }

    @Test
    void truncateWithoutEllipsis() {
        assertThat(SummaryExtractor.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(SummaryExtractor.truncate("ab", 3)).isEqualTo("ab");
        assertThat(SummaryExtractor.truncate(null, 3)).isNull();
    }
}
