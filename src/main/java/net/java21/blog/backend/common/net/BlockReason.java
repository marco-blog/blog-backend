package net.java21.blog.backend.common.net;

/**
 * 주소를 거부한 이유(007 contracts/api.md {@code EXTERNAL_FEED_URL_NOT_ALLOWED}의 {@code params.reason}).
 */
public enum BlockReason {
    /** 주소 형식(호스트 없음, 숫자 호스트 표기 등). */
    INVALID_URL,
    /** http·https가 아님. */
    SCHEME,
    /** 허용하지 않는 포트. */
    PORT,
    /** {@code user@} 사용자 정보. */
    CREDENTIALS,
    /** 내부망·루프백·링크로컬 등. */
    PRIVATE_ADDRESS,
    /** 우리 서비스 주소({@code blog.base-url}·{@code blog.external.forbidden-hosts}와 그 하위 도메인). */
    SELF
}
