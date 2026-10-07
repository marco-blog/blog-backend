package net.java21.blog.backend.trackback.service;

import java.util.List;

/** 커밋 뒤 보낼 트랙백(005 research M15): 보낸 글과 그 글의 PENDING 기록 ID. */
public record TrackbackSendRequested(Long postId, List<Long> logIds) {

    public TrackbackSendRequested {
        logIds = List.copyOf(logIds);
    }
}
