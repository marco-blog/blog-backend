package net.java21.blog.backend.tag.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** post_tags의 복합 키(post_id, tag_id). */
@Embeddable
public record PostTagId(@Column(name = "post_id") Long postId, @Column(name = "tag_id") Long tagId)
        implements Serializable {
}
