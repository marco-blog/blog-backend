package net.java21.blog.backend.post.service;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.PostSummaryRow;
import net.java21.blog.backend.post.repository.RelatedPostQueryRepository;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관련 글(002 FR-068, contracts/api.md 관련 글 절). 기준 글을 볼 수 있는 사람만({@link PostExposure#isDetailVisibleTo}, 아니면 404
 * {@code POST_NOT_FOUND}) 같은 블로그의 관련 글 최대 {@value #LIMIT}편을 받는다. 쿼리 3회(기준 글, 관련 글, 태그).
 */
@Service
public class RelatedPostService {

    public static final int LIMIT = 5;

    private final PostRepository postRepository;
    private final RelatedPostQueryRepository relatedRepository;
    private final TagQueryRepository tagQueryRepository;

    public RelatedPostService(PostRepository postRepository, RelatedPostQueryRepository relatedRepository,
            TagQueryRepository tagQueryRepository) {
        this.postRepository = postRepository;
        this.relatedRepository = relatedRepository;
        this.tagQueryRepository = tagQueryRepository;
    }

    @Transactional(readOnly = true)
    public List<PostSummaryResponse> related(Long postId, Long viewerId) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        Long categoryId = post.getCategory() == null ? null : post.getCategory().getId();
        List<PostSummaryRow> rows = relatedRepository.findRelated(post.getBlog().getId(), post.getId(), categoryId,
                LIMIT);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(rows.stream().map(PostSummaryRow::id).toList());
        return rows.stream().map(row -> row.toPublicResponse(tags.get(row.id()))).toList();
    }
}
