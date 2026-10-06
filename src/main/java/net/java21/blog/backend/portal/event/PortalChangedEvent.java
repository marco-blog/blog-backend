package net.java21.blog.backend.portal.event;

/**
 * 포털 결과를 바로 바꿔야 하는 관리자 변경(설정·주제·추천·제외·릴리스 노트, 003 research P5). 커밋 뒤
 * {@link PortalCacheInvalidator}가 포털 캐시를 비운다.
 *
 * @param reason 무엇이 바뀌었는지(로그용, 예: {@code setting:portal.min-content-length})
 */
public record PortalChangedEvent(String reason) {
}
