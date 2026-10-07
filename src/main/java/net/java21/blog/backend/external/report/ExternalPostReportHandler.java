package net.java21.blog.backend.external.report;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.portal.ExternalPortalQueryRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.service.HideChange;
import net.java21.blog.backend.report.service.ReportTarget;
import net.java21.blog.backend.report.service.ReportTargetHandler;
import net.java21.blog.backend.report.service.UrlTargetResolver;
import org.springframework.stereotype.Component;

/**
 * 외부 글 신고({@code EXTERNAL_POST}, 007 research E17, FR-127·129). 회원 신고는 포털에 노출 중인 글만(글을 남기고 해제한 블로그 글 포함,
 * 아니면 404 {@code EXTERNAL_POST_NOT_FOUND}), 그 블로그의 관리 회원은 신고 대신 해제·삭제를 쓴다(422). 대상 작성 회원은 관리 회원(없으면
 * NULL), 블로그는 NULL. 조치는 {@code REMOVE_FROM_PORTAL}(내림, {@code REPORT})이고 되돌리지 않으므로 숨김·해제(HIDE_CONTENT)는
 * 지원하지 않는다(422 {@code REPORT_ACTION_NOT_ALLOWED}). 권리 침해 주소가 visit 주소이거나 외부 글 원문 링크면 대상으로 해석한다.
 */
@Component
public class ExternalPostReportHandler implements ReportTargetHandler, UrlTargetResolver {

    private static final Pattern VISIT_PATH = Pattern.compile("^/api/v1/external-posts/(\\d{1,18})/visit/?$");

    private final ExternalPostRepository postRepository;
    private final ExternalPortalQueryRepository portalQueries;
    private final Clock clock;

    public ExternalPostReportHandler(ExternalPostRepository postRepository,
            ExternalPortalQueryRepository portalQueries, Clock clock) {
        this.postRepository = postRepository;
        this.portalQueries = portalQueries;
        this.clock = clock;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.EXTERNAL_POST;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        if (portalQueries.findVisibleLink(targetId, clock.instant()).isEmpty()) {
            throw notFound(targetId);
        }
        ExternalPost post = postRepository.findWithBlog(targetId).orElseThrow(() -> notFound(targetId));
        if (post.getExternalBlog().isManagedBy(reporterId)) {
            throw new BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT,
                    "Cannot report own external post: " + targetId);
        }
        return target(post);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(postRepository.findWithBlog(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND,
                        "Content not found: EXTERNAL_POST " + targetId)));
    }

    @Override
    public HideChange hide(long targetId) {
        throw unsupported();
    }

    @Override
    public HideChange unhide(long targetId) {
        throw unsupported();
    }

    @Override
    public Optional<ReportTarget> resolveUrl(URI uri, URI base) {
        if (uri.getHost() == null || uri.getScheme() == null) {
            return Optional.empty();
        }
        if (base.getHost() != null && uri.getHost().equalsIgnoreCase(base.getHost())) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            if (!basePath.isEmpty() && path.startsWith(basePath + "/")) {
                path = path.substring(basePath.length());
            }
            Matcher visit = VISIT_PATH.matcher(path);
            if (visit.matches()) {
                return postRepository.findWithBlog(Long.parseLong(visit.group(1))).map(this::target);
            }
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        String hash;
        try {
            hash = FeedUrlNormalizer.hash(uri.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return postRepository.findByLinkHashWithBlog(hash).stream()
                .sorted((a, b) -> Boolean.compare(b.isActive(), a.isActive()))
                .findFirst()
                .map(this::target);
    }

    private ReportTarget target(ExternalPost post) {
        return new ReportTarget(type(), post.getId(), post.getExternalBlog().getMember(), null, null, null);
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND, "External post not found: " + id);
    }

    static BusinessException unsupported() {
        return new BusinessException(ErrorCode.REPORT_ACTION_NOT_ALLOWED,
                "External report targets are handled by REMOVE_FROM_PORTAL or BLOCK_EXTERNAL_BLOG");
    }
}
