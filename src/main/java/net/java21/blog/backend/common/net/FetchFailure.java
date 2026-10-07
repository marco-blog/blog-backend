package net.java21.blog.backend.common.net;

/**
 * 외부 요청 실패 종류(007 research E2). 007 data-model {@code external_blogs.last_fetch_result} 값과 같다. 파싱 실패
 * {@code PARSE_ERROR}는 호출하는 쪽이 정한다.
 */
public enum FetchFailure {
    /** 2xx·304가 아닌 상태 코드, 리다이렉트 상한 초과. */
    HTTP_ERROR,
    /** 연결·응답 시간 초과. */
    TIMEOUT,
    /** 크기 상한 초과. */
    TOO_LARGE,
    /** 허용하지 않는 주소(내부망·우리 서비스·포트·스킴 등, {@link BlockReason}). */
    BLOCKED_ADDRESS,
    /** 이름 해석 실패. */
    DNS_ERROR
}
