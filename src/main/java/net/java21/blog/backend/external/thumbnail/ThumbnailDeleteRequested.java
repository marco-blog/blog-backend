package net.java21.blog.backend.external.thumbnail;

import java.util.List;

/** 외부 글을 지운 트랜잭션이 커밋되면 그 썸네일 파일을 지운다(007 research E15·E16). */
public record ThumbnailDeleteRequested(List<String> keys) {
}
