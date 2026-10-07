package net.java21.blog.backend.external.fetch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.topic.domain.Topic;
import org.springframework.stereotype.Component;

/**
 * 수집한 항목을 저장한다(007 research E5, FR-115). 같은 글은 {@code guid_hash}·{@code link_hash}의 {@code IN} 조회 2번으로 찾는다:
 * guid가 있으면 guid, 없거나 guid로 못 찾으면 링크(피드가 guid를 바꾼 경우 그 행의 guid를 새 값으로). 기존 글은 바뀐 필드만 갱신하고
 * 주제는 다시 정하지 않으며, 내린(REMOVED) 글은 되살리지 않는다. 호출하는 쪽 트랜잭션 안에서 부른다.
 */
@Component
public class ExternalPostUpserter {

    /** 새로 만든 글과 갱신한 수. */
    public record Result(List<ExternalPost> created, int updated) {
    }

    private final ExternalPostRepository postRepository;
    private final EntityManager em;

    public ExternalPostUpserter(ExternalPostRepository postRepository, EntityManager em) {
        this.postRepository = postRepository;
        this.em = em;
    }

    public Result upsert(long blogId, long defaultTopicId, List<FeedItem> items, TopicAssigner.Batch assigner,
            Instant now) {
        if (items.isEmpty()) {
            return new Result(List.of(), 0);
        }
        Map<FeedItem, String> guidHashes = new LinkedHashMap<>();
        Map<FeedItem, String> linkHashes = new LinkedHashMap<>();
        for (FeedItem item : items) {
            guidHashes.put(item, item.guid() == null ? null : FeedUrlNormalizer.sha256(item.guid()));
            linkHashes.put(item, FeedUrlNormalizer.hash(item.link()));
        }
        List<String> guids = guidHashes.values().stream().filter(h -> h != null).distinct().toList();
        List<String> links = linkHashes.values().stream().distinct().toList();
        Map<String, ExternalPost> byGuid = new HashMap<>();
        if (!guids.isEmpty()) {
            for (ExternalPost p : postRepository.findByGuidHashes(blogId, guids)) {
                byGuid.put(p.getGuidHash(), p);
            }
        }
        Map<String, ExternalPost> byLink = new HashMap<>();
        for (ExternalPost p : postRepository.findByLinkHashes(blogId, links)) {
            byLink.put(p.getLinkHash(), p);
        }
        ExternalBlog blogRef = em.getReference(ExternalBlog.class, blogId);
        List<ExternalPost> created = new ArrayList<>();
        int updated = 0;
        for (FeedItem item : items) {
            String gh = guidHashes.get(item);
            String lh = linkHashes.get(item);
            ExternalPost existing = gh == null ? null : byGuid.get(gh);
            if (existing == null) {
                existing = byLink.get(lh);
                if (existing != null && gh != null && byGuid.containsKey(gh)) {
                    continue;
                }
            }
            if (existing != null) {
                if (!existing.isActive()) {
                    continue;
                }
                String oldGuid = existing.getGuidHash();
                String oldLink = existing.getLinkHash();
                ExternalPost linkOwner = byLink.get(lh);
                String newLink = linkOwner == null || linkOwner == existing ? lh : null;
                ExternalPost guidOwner = gh == null ? null : byGuid.get(gh);
                String newGuid = guidOwner == null || guidOwner == existing ? gh : null;
                if (existing.refresh(item, newGuid, newLink)) {
                    updated++;
                }
                reindex(byGuid, byLink, existing, oldGuid, oldLink);
                continue;
            }
            TopicAssigner.Decision decision = assigner.decide(item, defaultTopicId);
            ExternalPost post = new ExternalPost(blogRef, item, gh, lh, em.getReference(Topic.class, decision.topicId()),
                    decision.source(), now);
            if (decision.classifierVersion() != null) {
                post.recordClassifier(decision.classifierTopicId() == null ? null
                        : em.getReference(Topic.class, decision.classifierTopicId()),
                        decision.confidence() == null ? 0 : decision.confidence(), decision.classifierVersion());
            }
            em.persist(post);
            if (decision.review()) {
                em.persist(new ClassificationReview(post, decision.classifierTopicId() == null ? null
                        : em.getReference(Topic.class, decision.classifierTopicId()),
                        decision.confidence() == null ? 0 : decision.confidence()));
            }
            created.add(post);
            if (gh != null) {
                byGuid.put(gh, post);
            }
            byLink.put(lh, post);
        }
        return new Result(created, updated);
    }

    private static void reindex(Map<String, ExternalPost> byGuid, Map<String, ExternalPost> byLink, ExternalPost post,
            String oldGuid, String oldLink) {
        if (oldGuid != null && !oldGuid.equals(post.getGuidHash())) {
            byGuid.remove(oldGuid);
        }
        if (post.getGuidHash() != null) {
            byGuid.put(post.getGuidHash(), post);
        }
        if (!oldLink.equals(post.getLinkHash())) {
            byLink.remove(oldLink);
        }
        byLink.put(post.getLinkHash(), post);
    }
}
