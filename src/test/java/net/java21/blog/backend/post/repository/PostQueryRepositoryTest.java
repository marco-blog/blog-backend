package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.tag.domain.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 004 글 목록 조건(T049, FR-059, FR-061, research B8·B12): 공지 목록, 홈 목록의 공지 제외(카테고리·태그 목록은 포함), 기준 시간대
 * 월 범위(그 달 시작 포함·다음 달 시작 제외). 쿼리 수는 그대로 2회.
 */
@JpaRepositoryTest
@Import(PostQueryRepository.class)
class PostQueryRepositoryTest {

    private static final BlogCalendar CALENDAR = new BlogCalendar(StatsProperties.defaults(),
            java.time.Clock.systemUTC());

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private PostQueryRepository repository;

    private JpaFixtures fx;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("marco"), "marco");
    }

    @Test
    void noticesAreListableNoticePostsNewestFirst() {
        Post older = notice(fx.published(blog, "오래된 공지", null, 1));
        Post newer = notice(fx.published(blog, "새 공지", null, 3));
        notice(fx.post(blog, "비공개 공지", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4));
        notice(fx.post(blog, "휴지통 공지", null, PostStatus.DELETED, PostVisibility.PUBLIC, 5));
        fx.published(blog, "일반 글", null, 2);
        notice(fx.published(fx.blog(fx.user("polo"), "polo"), "남의 공지", null, 6));
        fx.flushAndClear();

        queryCounter.reset();
        Page<PostSummaryRow> page = repository.findNotices(blog.getId(), PageRequest.of(0, 1));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(PostSummaryRow::id).containsExactly(newer.getId());
        assertThat(page.getContent().getFirst().notice()).isTrue();
        assertThat(repository.findNotices(blog.getId(), PageRequest.of(1, 1)).getContent())
                .extracting(PostSummaryRow::id).containsExactly(older.getId());
    }

    @Test
    void homeListExcludesNoticesButCategoryAndTagListsKeepThem() {
        Category spring = fx.category(blog, null, "Spring", 0);
        Tag tag = fx.tag("jpa");
        Post noticePost = notice(fx.published(blog, "공지", spring, 2));
        fx.tagPost(noticePost, tag);
        Post plain = fx.published(blog, "일반", spring, 1);
        fx.tagPost(plain, tag);
        fx.flushAndClear();

        queryCounter.reset();
        Page<PostSummaryRow> home = repository.findListablePosts(blog.getId(), PostListFilter.NONE,
                PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(home.getContent()).extracting(PostSummaryRow::id).containsExactly(plain.getId());
        assertThat(home.getTotalElements()).isEqualTo(1);
        assertThat(ids(new PostListFilter(spring.getId(), null))).containsExactly(noticePost.getId(), plain.getId());
        assertThat(ids(new PostListFilter(null, "jpa"))).containsExactly(noticePost.getId(), plain.getId());
    }

    @Test
    void monthRangeIncludesStartAndExcludesNextMonthInServiceTimeZone() {
        // 2026-10-01 00:00 KST = 2026-09-30T15:00Z
        Post first = at("10월 첫 순간", "2026-09-30T15:00:00Z");
        Post last = at("10월 마지막", "2026-10-31T14:59:59Z");
        at("9월 마지막", "2026-09-30T14:59:59Z");
        at("11월 첫 순간", "2026-10-31T15:00:00Z");
        Post noticeInMonth = notice(at("10월 공지", "2026-10-10T00:00:00Z"));
        fx.flushAndClear();

        BlogCalendar.Range range = CALENDAR.monthRange(YearMonth.of(2026, 10));
        queryCounter.reset();
        List<Long> ids = repository.findListablePosts(blog.getId(),
                PostListFilter.ofMonth(YearMonth.of(2026, 10)).withRange(range.from(), range.to()),
                PageRequest.of(0, 20)).getContent().stream().map(PostSummaryRow::id).toList();

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(ids).containsExactly(last.getId(), noticeInMonth.getId(), first.getId());
    }

    private List<Long> ids(PostListFilter filter) {
        return repository.findListablePosts(blog.getId(), filter, PageRequest.of(0, 20)).getContent().stream()
                .map(PostSummaryRow::id).toList();
    }

    private Post at(String title, String publishedAt) {
        Post p = new Post(blog, title);
        p.publish(title, "본문", "<p>본문</p>", "본문", "요약", null, PostVisibility.PUBLIC, true,
                Instant.parse(publishedAt));
        em.persist(p);
        return p;
    }

    private static Post notice(Post post) {
        post.changeNotice(true);
        return post;
    }
}
