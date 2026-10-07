package net.java21.blog.backend.external.repository;

import static net.java21.blog.backend.external.domain.QExternalBlog.externalBlog;
import static net.java21.blog.backend.external.domain.QExternalPost.externalPost;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.ReportTargetPreview.Author;
import net.java21.blog.backend.report.dto.ReportTargetPreview.BlogRef;
import net.java21.blog.backend.report.dto.ReportTargetPreview.State;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.report.repository.TargetPreviewProvider;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.stereotype.Repository;

/**
 * 외부 글·외부 블로그 신고의 관리자 미리보기(007 research E17). 종류마다 IN 쿼리 1회. 외부 글은 제목·요약·원문 링크·블로그 이름과
 * 관리 회원, 외부 블로그는 이름·피드 주소·블로그 주소와 관리 회원. 내린 글({@code REMOVED})과 차단·거절된 블로그는 되돌릴 수 없으므로
 * {@code DELETED}로 보인다(숨김 해제 버튼이 나오지 않게). 블로그 주소({@code handle})는 서비스 블로그가 아니라 null.
 */
@Repository
public class ExternalReportPreviewRepository implements TargetPreviewProvider {

    private static final QUser member = new QUser("externalMember");

    private final JPAQueryFactory queryFactory;

    public ExternalReportPreviewRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public Set<ReportTargetType> types() {
        return EnumSet.of(ReportTargetType.EXTERNAL_POST, ReportTargetType.EXTERNAL_BLOG);
    }

    @Override
    public void previews(ReportTargetType type, Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        if (type == ReportTargetType.EXTERNAL_POST) {
            posts(ids, out);
        } else if (type == ReportTargetType.EXTERNAL_BLOG) {
            blogs(ids, out);
        }
    }

    private void posts(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(externalPost.id, externalPost.title, externalPost.summary, externalPost.link,
                        externalPost.status, externalBlog.title, externalBlog.feedUrl, member.id, member.nickname)
                .from(externalPost)
                .join(externalPost.externalBlog, externalBlog)
                .leftJoin(externalBlog.member, member)
                .where(externalPost.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(externalPost.id);
            State state = row.get(externalPost.status) == ExternalPostStatus.REMOVED ? State.DELETED : State.ACTIVE;
            out.put(new TargetKey(ReportTargetType.EXTERNAL_POST, id), new ReportTargetPreview(
                    ReportTargetType.EXTERNAL_POST, id, state, row.get(externalPost.title),
                    row.get(externalPost.summary), row.get(externalPost.link),
                    author(row.get(member.id), row.get(member.nickname)),
                    new BlogRef(null, blogTitle(row.get(externalBlog.title), row.get(externalBlog.feedUrl)))));
        }
    }

    private void blogs(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(externalBlog.id, externalBlog.title, externalBlog.feedUrl, externalBlog.siteUrl,
                        externalBlog.status, member.id, member.nickname)
                .from(externalBlog)
                .leftJoin(externalBlog.member, member)
                .where(externalBlog.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(externalBlog.id);
            ExternalBlogStatus status = row.get(externalBlog.status);
            State state = status == ExternalBlogStatus.BLOCKED || status == ExternalBlogStatus.REJECTED
                    ? State.DELETED : State.ACTIVE;
            String feedUrl = row.get(externalBlog.feedUrl);
            String title = blogTitle(row.get(externalBlog.title), feedUrl);
            String site = row.get(externalBlog.siteUrl);
            out.put(new TargetKey(ReportTargetType.EXTERNAL_BLOG, id), new ReportTargetPreview(
                    ReportTargetType.EXTERNAL_BLOG, id, state, title, feedUrl, site == null ? feedUrl : site,
                    author(row.get(member.id), row.get(member.nickname)), new BlogRef(null, title)));
        }
    }

    private static Author author(Long userId, String nickname) {
        return userId == null ? null : new Author(userId, nickname, false);
    }

    private static String blogTitle(String title, String feedUrl) {
        return title == null || title.isBlank() ? feedUrl : title;
    }
}
