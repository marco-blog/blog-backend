package net.java21.blog.backend.admin.info.dto;

import java.util.List;

/**
 * {@code GET /admin/service-settings}(006 contracts/api.md {@code ServiceSettings}, FR-160). 읽기 전용 운영 설정 값이며 비밀 값
 * (암호화 키, JWT, 메일·CAPTCHA 비밀 키)은 넣지 않는다. 바이트·픽셀은 숫자, 기간은 ISO-8601 문자열.
 */
public record ServiceSettingsResponse(String termsVersion, Blogs blogs, Media media, Admin admin) {

    /** {@code blog.blogs.default-max-per-member} */
    public record Blogs(int defaultMaxPerMember) {
    }

    /** {@code blog.media.*}: 파일 한도(바이트), 픽셀 한도, 임시 파일 할당량(바이트)·보관 기간, 허용 형식 */
    public record Media(long maxFileSize, long maxPixels, long tempQuota, String tempTtl, List<String> allowedTypes) {
    }

    /** {@code blog.admin.audit-retention}, {@code blog.admin.dashboard-cache-ttl} */
    public record Admin(String auditRetention, String dashboardCacheTtl) {
    }
}
