package net.java21.blog.backend.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 설정(contracts/api.md "프로퍼티").
 *
 * @param bootstrapSuperAdminEmail (선택) 기동 시 SUPER_ADMIN이 한 명도 없으면 이 이메일의 회원을 SUPER_ADMIN으로 지정한다
 *                                 (006 FR-105, 첫 최고 관리자 지정의 유일한 경로). 값은 환경 변수
 *                                 {@code BLOG_ADMIN_BOOTSTRAP_SUPER_ADMIN_EMAIL}로 주며 저장소에 커밋하지 않는다
 */
@ConfigurationProperties("blog.admin")
public record AdminProperties(String bootstrapSuperAdminEmail) {

    public boolean hasBootstrapEmail() {
        return bootstrapSuperAdminEmail != null && !bootstrapSuperAdminEmail.isBlank();
    }
}
