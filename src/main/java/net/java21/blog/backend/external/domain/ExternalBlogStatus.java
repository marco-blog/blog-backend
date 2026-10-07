package net.java21.blog.backend.external.domain;

import java.util.EnumSet;
import java.util.Set;

/** 외부 블로그 상태(007 data-model external_blogs.status). */
public enum ExternalBlogStatus {
    PENDING, REJECTED, ACTIVE, PAUSED, STOPPED, BLOCKED, RELEASED;

    /** 이미 수집된 글이 포털에 나오는 상태(research E13, 결정 표 24번). */
    public static final Set<ExternalBlogStatus> EXPOSED = EnumSet.of(ACTIVE, PAUSED, STOPPED, RELEASED);
    /** 회원 한도에서 세지 않는 상태(research E8). */
    public static final Set<ExternalBlogStatus> NOT_COUNTED = EnumSet.of(REJECTED, RELEASED);
    /** 해제할 수 있는 상태. */
    public static final Set<ExternalBlogStatus> RELEASABLE = EnumSet.of(PENDING, ACTIVE, PAUSED, STOPPED);
    /** 넘겨받을 수 있는 상태(research E8). */
    public static final Set<ExternalBlogStatus> CLAIMABLE = EnumSet.of(PENDING, ACTIVE, PAUSED, STOPPED);

    /** 같은 피드의 다른 등록을 막는 상태(거절·해제가 아님, {@code active_feed_hash}가 채워짐). */
    public boolean holdsFeed() {
        return !NOT_COUNTED.contains(this);
    }
}
