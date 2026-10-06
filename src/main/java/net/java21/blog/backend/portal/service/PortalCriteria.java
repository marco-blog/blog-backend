package net.java21.blog.backend.portal.service;

import java.time.Duration;
import java.time.Instant;

/**
 * 포털 노출 판단의 입력(003 research P1): 기준 시각과 운영 설정값. 조건값이 운영 중 바뀌므로 상수가 아니라 이 값으로 받는다.
 *
 * @param newMemberDelay   가입 후 이 시간이 지나야 포털에 나온다
 * @param minContentLength 본문 텍스트 최소 문자 수
 */
public record PortalCriteria(Instant now, Duration newMemberDelay, int minContentLength) {

    /** 이 시각 이전(포함)에 가입한 회원의 글만 포털에 나온다. */
    public Instant joinedBefore() {
        return now.minus(newMemberDelay);
    }
}
