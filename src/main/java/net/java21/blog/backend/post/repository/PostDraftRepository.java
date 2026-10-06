package net.java21.blog.backend.post.repository;

import net.java21.blog.backend.post.domain.PostDraft;
import org.springframework.data.jpa.repository.JpaRepository;

/** 작성 중 사본. id는 글 id({@code post_id})다. */
public interface PostDraftRepository extends JpaRepository<PostDraft, Long> {
}
