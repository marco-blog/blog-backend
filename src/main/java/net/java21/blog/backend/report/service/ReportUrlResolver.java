package net.java21.blog.backend.report.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.springframework.stereotype.Component;

/**
 * 권리 침해 신고 주소 → 신고 대상(005 research M2). {@code blog.base-url}과 같은 호스트의 {@code /{handle}/{postId}}면 글,
 * {@code #comment-{id}}·{@code #trackback-{id}} 조각이 있으면 그 글에 속한 댓글·트랙백, {@code /{handle}/guestbook#guestbook-{id}}면 그
 * 블로그의 방명록 글. 쿼리와 끝 {@code /}는 무시한다. 다른 호스트·해석 실패·없는 대상·소속이 맞지 않으면 빈 값(관리자가 지정한다).
 */
@Component
public class ReportUrlResolver {

    private static final Pattern POST_PATH = Pattern.compile("^/([^/]+)/(\\d{1,18})/?$");
    private static final Pattern GUESTBOOK_PATH = Pattern.compile("^/([^/]+)/guestbook/?$");
    private static final Pattern FRAGMENT = Pattern.compile("^(comment|guestbook|trackback)-(\\d{1,18})$");

    private final SiteProperties site;
    private final ReportTargetHandlers handlers;

    public ReportUrlResolver(SiteProperties site, ReportTargetHandlers handlers) {
        this.site = site;
        this.handlers = handlers;
    }

    public Optional<ReportTarget> resolve(String url) {
        URI uri;
        URI base;
        try {
            uri = new URI(url.strip());
            base = new URI(site.baseUrl());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
        if (uri.getHost() == null || base.getHost() == null
                || !uri.getHost().toLowerCase(Locale.ROOT).equals(base.getHost().toLowerCase(Locale.ROOT))
                || effectivePort(uri) != effectivePort(base)) {
            return Optional.empty();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        if (!basePath.isEmpty()) {
            if (!path.startsWith(basePath + "/")) {
                return Optional.empty();
            }
            path = path.substring(basePath.length());
        }
        Matcher fragment = uri.getFragment() == null ? null : FRAGMENT.matcher(uri.getFragment());
        boolean hasFragment = fragment != null && fragment.matches();
        try {
            Matcher guestbook = GUESTBOOK_PATH.matcher(path);
            if (guestbook.matches()) {
                if (!hasFragment || !"guestbook".equals(fragment.group(1))) {
                    return Optional.empty();
                }
                return find(ReportTargetType.GUESTBOOK, Long.parseLong(fragment.group(2)))
                        .filter(t -> guestbook.group(1).equals(t.blogHandle()));
            }
            Matcher postPath = POST_PATH.matcher(path);
            if (!postPath.matches()) {
                return Optional.empty();
            }
            String handle = postPath.group(1);
            long postId = Long.parseLong(postPath.group(2));
            if (hasFragment && !"guestbook".equals(fragment.group(1))) {
                ReportTargetType type = "comment".equals(fragment.group(1)) ? ReportTargetType.COMMENT
                        : ReportTargetType.TRACKBACK;
                return find(type, Long.parseLong(fragment.group(2)))
                        .filter(t -> Long.valueOf(postId).equals(t.postId()) && handle.equals(t.blogHandle()));
            }
            return find(ReportTargetType.POST, postId).filter(t -> handle.equals(t.blogHandle()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<ReportTarget> find(ReportTargetType type, long id) {
        return handlers.find(type).flatMap(handler -> {
            try {
                return Optional.of(handler.resolveForAdmin(id));
            } catch (BusinessException e) {
                return Optional.empty();
            }
        });
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }
}
