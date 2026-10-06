package net.java21.blog.backend.subscription.event;

/**
 * 구독 행이 새로 생겼다(002 research D3). 커밋 뒤 블로그 주인에게 NEW_SUBSCRIBER 알림을 만든다. 이미 구독 중이면 발행하지 않는다.
 *
 * @param subscriberId 구독한 회원
 * @param blogId       구독한 블로그
 */
public record BlogSubscribedEvent(long subscriberId, long blogId) {
}
