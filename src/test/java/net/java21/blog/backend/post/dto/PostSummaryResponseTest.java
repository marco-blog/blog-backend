package net.java21.blog.backend.post.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import org.junit.jupiter.api.Test;

/** 주인 외 목록 한 줄(T074, AS2, research B1): 보호 글은 제목만, 예약 시각은 싣지 않는다. 공개 글은 그대로. */
class PostSummaryResponseTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Test
    void protectedPostKeepsOnlyTheTitleForReaders() {
        PostSummaryResponse reader = summary(PostVisibility.PROTECTED).forReader();

        assertThat(reader.title()).isEqualTo("제목");
        assertThat(reader.summary()).isNull();
        assertThat(reader.thumbnailUrl()).isNull();
        assertThat(reader.scheduledAt()).isNull();
        assertThat(reader.tags()).containsExactly("spring");
        assertThat(reader.visibility()).isEqualTo(PostVisibility.PROTECTED);
    }

    @Test
    void publicPostIsUnchangedExceptScheduledAt() {
        PostSummaryResponse reader = summary(PostVisibility.PUBLIC).forReader();

        assertThat(reader.summary()).isEqualTo("요약");
        assertThat(reader.thumbnailUrl()).isEqualTo("/media/k3Jd9fQ2xLmA7pZ0bR5tYw");
        assertThat(reader.scheduledAt()).isNull();
        assertThat(reader).usingRecursiveComparison().ignoringFields("scheduledAt")
                .isEqualTo(summary(PostVisibility.PUBLIC));
    }

    private static PostSummaryResponse summary(PostVisibility visibility) {
        return new PostSummaryResponse(1L, "제목", "요약", "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", null, List.of("spring"), 3,
                1, visibility, PostStatus.PUBLISHED, NOW, NOW, false, false, NOW.plusSeconds(60), null, null);
    }
}
