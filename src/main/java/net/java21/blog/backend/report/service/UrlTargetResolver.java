package net.java21.blog.backend.report.service;

import java.net.URI;
import java.util.Optional;

/**
 * 서비스 글 주소 규칙 밖의 권리 침해 주소를 신고 대상으로 바꾸는 추가 해석기(007 research E17: 외부 글의 visit 주소·원문 링크).
 * {@link ReportUrlResolver}가 기본 규칙보다 먼저 묻는다. 해석할 수 없으면 빈 값.
 */
public interface UrlTargetResolver {

    /**
     * @param uri  신고 주소
     * @param base 서비스 주소({@code blog.base-url})
     */
    Optional<ReportTarget> resolveUrl(URI uri, URI base);
}
