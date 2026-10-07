package net.java21.blog.backend.admin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.content.AdminContentSearchRepository.CommentCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.GuestbookCriteria;
import net.java21.blog.backend.admin.content.AdminContentSearchRepository.PostCriteria;
import net.java21.blog.backend.admin.content.dto.AdminCommentRow;
import net.java21.blog.backend.admin.content.dto.AdminGuestbookRow;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 006 T022(FR-102·104, research A4): 글·댓글·방명록 관리 검색의 조건(블로그·작성자·상태·공개 범위·범위 안 키워드), 최신 생성순,
 * 쿼리 2회, 응답 칼럼(본문·요약·대표 이미지·비밀번호 해시·작성 IP 없음, 비밀 글 내용 null, 내용 200자). 글 제목 FULLTEXT는 MySQL
 * 시험(T023)이 맡는다.
 */
@JpaRepositoryTest
@Import(AdminContentSearchRepository.class)
class AdminContentSearchRepositoryTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminContentSearchRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private User owner;
    private User writer;
    private Blog blog;
    private Blog other;
    private Post publicPost;
    private Post privatePost;
    private Post trashed;
    private Comment memberComment;
    private Comment guestSecret;
    private Comment reply;
    private GuestbookEntry entry;
    private GuestbookEntry secretEntry;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        owner = fx.user("cs-owner");
        writer = fx.user("cs-writer");
        writer.withdraw(JpaFixtures.T0);
        blog = fx.blog(owner, "csblog");
        other = fx.blog(writer, "csother");
        publicPost = fx.published(blog, "공개 글", null, 1);
        privatePost = fx.post(blog, "비공개 글", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2);
        trashed = fx.post(blog, "지운 글", null, PostStatus.DELETED, PostVisibility.PUBLIC, 3);
        fx.post(other, "남의 초안", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 4);
        memberComment = new Comment(publicPost, writer, null, "가".repeat(250));
        em.persist(memberComment);
        guestSecret = Comment.byGuest(publicPost, null, "손님", "$2a$hash", "10.0.0.1", "비밀 댓글 내용", true);
        em.persist(guestSecret);
        reply = new Comment(publicPost, owner, memberComment, "답글 키워드");
        em.persist(reply);
        Comment elsewhere = new Comment(privatePost, owner, null, "다른 글 키워드");
        em.persist(elsewhere);
        entry = new GuestbookEntry(blog, writer, null, "방명록 키워드", false);
        em.persist(entry);
        secretEntry = GuestbookEntry.byGuest(blog, "손님", "$2a$hash", "10.0.0.2", "비밀 방명록", true);
        em.persist(secretEntry);
        em.persist(new GuestbookEntry(other, owner, null, "다른 블로그 방명록", false));
        fx.flushAndClear();
    }

    @Test
    void postsFilterAndProjectWithoutBodyColumns() {
        statistics().clear();
        queryCounter.reset();
        Page<AdminPostRow> page = repository.posts(new PostCriteria(null, "csblog", null, null, null), PAGE);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminPostRow::id)
                .containsExactly(trashed.getId(), privatePost.getId(), publicPost.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        AdminPostRow row = page.getContent().get(0);
        assertThat(row.blog().handle()).isEqualTo("csblog");
        assertThat(row.author().userId()).isEqualTo(owner.getId());
        assertThat(row.status()).isEqualTo(PostStatus.DELETED);
        assertThat(row.deletedAt()).isNotNull();
        assertThat(Arrays.stream(statistics().getQueries()).toList())
                .allSatisfy(hql -> assertThat(hql).doesNotContain("contentMarkdown", "contentHtml", "contentText",
                        "summary", "thumbnailUrl", "passwordHash"));
        assertThat(Arrays.stream(AdminPostRow.class.getRecordComponents()).map(c -> c.getName()).toList())
                .doesNotContain("contentMarkdown", "contentHtml", "contentText", "summary", "thumbnailUrl",
                        "passwordHash");

        assertThat(repository.posts(new PostCriteria(null, null, writer.getId(), null, null), PAGE).getContent())
                .singleElement().satisfies(r -> {
                    assertThat(r.title()).isEqualTo("남의 초안");
                    assertThat(r.author().status()).isEqualTo(UserStatus.WITHDRAWN);
                });
        assertThat(repository.posts(new PostCriteria(null, null, null, PostStatus.PUBLISHED,
                PostVisibility.PRIVATE), PAGE).getContent()).extracting(AdminPostRow::id)
                .contains(privatePost.getId()).doesNotContain(publicPost.getId());
        assertThat(repository.posts(new PostCriteria(null, "nobody", null, null, null), PAGE)).isEmpty();
    }

    @Test
    void commentsHideSecretContentAndGuestIp() {
        queryCounter.reset();
        Page<AdminCommentRow> page = repository.comments(
                new CommentCriteria(publicPost.getId(), null, null, null, null), PAGE);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminCommentRow::id)
                .containsExactly(reply.getId(), guestSecret.getId(), memberComment.getId());
        AdminCommentRow replyRow = page.getContent().get(0);
        assertThat(replyRow.parentId()).isEqualTo(memberComment.getId());
        assertThat(replyRow.blogHandle()).isEqualTo("csblog");
        assertThat(replyRow.postTitle()).isEqualTo("공개 글");
        AdminCommentRow guest = page.getContent().get(1);
        assertThat(guest.author()).isNull();
        assertThat(guest.guestName()).isEqualTo("손님");
        assertThat(guest.secret()).isTrue();
        assertThat(guest.content()).isNull();
        AdminCommentRow long250 = page.getContent().get(2);
        assertThat(long250.content()).hasSize(AdminContentSearchRepository.CONTENT_PREVIEW);
        assertThat(long250.author().status()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(Arrays.stream(AdminCommentRow.class.getRecordComponents()).map(c -> c.getName()).toList())
                .doesNotContain("guestIp", "guestPasswordHash");

        assertThat(repository.comments(new CommentCriteria(null, null, "csblog", null, "키워드"), PAGE).getContent())
                .extracting(AdminCommentRow::content).containsExactly("다른 글 키워드", "답글 키워드");
        assertThat(repository.comments(new CommentCriteria(null, writer.getId(), null, CommentStatus.ACTIVE, null),
                PAGE).getContent()).extracting(AdminCommentRow::id).containsExactly(memberComment.getId());
        assertThat(repository.comments(new CommentCriteria(null, null, null, CommentStatus.DELETED, null), PAGE)
                .getContent()).extracting(AdminCommentRow::id).doesNotContain(memberComment.getId());
    }

    @Test
    void guestbookByBlogAuthorStatusKeyword() {
        queryCounter.reset();
        Page<AdminGuestbookRow> page = repository.guestbook(new GuestbookCriteria("csblog", null, null, null), PAGE);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminGuestbookRow::id)
                .containsExactly(secretEntry.getId(), entry.getId());
        assertThat(page.getContent().get(0).content()).isNull();
        assertThat(page.getContent().get(0).author()).isNull();
        assertThat(page.getContent().get(1).content()).isEqualTo("방명록 키워드");
        assertThat(page.getContent().get(1).blogHandle()).isEqualTo("csblog");

        assertThat(repository.guestbook(new GuestbookCriteria(null, writer.getId(), GuestbookStatus.ACTIVE, "키워드"),
                PAGE).getContent()).extracting(AdminGuestbookRow::id).containsExactly(entry.getId());
        assertThat(repository.guestbook(new GuestbookCriteria("csother", null, null, null), PAGE).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void previewCountsCodePoints() {
        String emoji = "😀".repeat(201);
        assertThat(AdminContentSearchRepository.preview(emoji).codePointCount(0,
                AdminContentSearchRepository.preview(emoji).length())).isEqualTo(200);
        assertThat(AdminContentSearchRepository.preview("짧음")).isEqualTo("짧음");
        assertThat(AdminContentSearchRepository.preview(null)).isNull();
        assertThat(List.of(AdminContentSearchRepository.CONTENT_PREVIEW)).containsExactly(200);
    }

    private org.hibernate.stat.Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }
}
