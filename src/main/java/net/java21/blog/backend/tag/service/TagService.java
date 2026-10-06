package net.java21.blog.backend.tag.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.tag.domain.TagNormalizer;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import net.java21.blog.backend.tag.dto.TaggedPostSummaryResponse;
import net.java21.blog.backend.tag.repository.PostTagRepository;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.tag.repository.TagRepository;
import net.java21.blog.backend.tag.repository.TaggedPostRow;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 공통 태그(T179, FR-025·026). 정규화·검증은 {@link TagNormalizer}, 없는 태그는 {@link TagCreator}로 만든다
 * (UNIQUE 경합이면 다시 조회). 글의 태그는 발행 때 통째로 바꾼다.
 */
@Service
public class TagService {

    private final BlogAccess blogAccess;
    private final TagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final TagQueryRepository tagQueryRepository;
    private final TagCreator tagCreator;

    public TagService(BlogAccess blogAccess, TagRepository tagRepository, PostTagRepository postTagRepository,
            TagQueryRepository tagQueryRepository, TagCreator tagCreator) {
        this.blogAccess = blogAccess;
        this.tagRepository = tagRepository;
        this.postTagRepository = postTagRepository;
        this.tagQueryRepository = tagQueryRepository;
        this.tagCreator = tagCreator;
    }

    /**
     * 이름(정규화됨)마다 태그를 찾거나 만든다. DB 정렬 규칙상 같은 태그로 보는 이름(예: 악센트만 다름)은 한 태그로 합쳐진다.
     * 쿼리: 조회 1회 + 없는 이름마다 INSERT(경합이면 조회 1회 더).
     */
    @Transactional
    public List<Tag> resolve(List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        Map<String, Tag> existing = tagRepository.findByNameIn(names).stream()
                .collect(Collectors.toMap(Tag::getName, Function.identity(), (a, b) -> a));
        Map<Long, Tag> tags = new LinkedHashMap<>();
        for (String name : names) {
            Tag tag = existing.get(name);
            if (tag == null) {
                tag = tagRepository.getReferenceById(createOrFind(name));
            }
            tags.putIfAbsent(tag.getId(), tag);
        }
        return List.copyOf(tags.values());
    }

    private Long createOrFind(String name) {
        try {
            return tagCreator.insert(name);
        } catch (DataIntegrityViolationException e) {
            return tagCreator.findId(name).orElseThrow(() -> e);
        }
    }

    /** 글의 태그를 통째로 바꾼다(발행). 이름은 정규화·검증된 것이어야 한다. */
    @Transactional
    public void replacePostTags(Post post, List<String> names) {
        postTagRepository.deleteByPostId(post.getId());
        List<Tag> tags = resolve(names);
        postTagRepository.saveAll(tags.stream().map(tag -> new PostTag(post, tag)).toList());
    }

    /** 블로그 태그 목록 {@code [{ name, postCount }]}. 쿼리 2회(블로그, 집계). */
    @Transactional(readOnly = true)
    public List<BlogTagResponse> blogTags(String handle) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        return tagQueryRepository.findBlogTags(blog.getId());
    }

    /**
     * 서비스 전체 태그별 글(목록 노출 가능만, 최신순). 주소의 이름도 정규화한다. 태그 이름이 될 수 없는 값이면 빈 페이지.
     * 쿼리 3회(목록, 전체 수, 태그 일괄 조회).
     */
    @Transactional(readOnly = true)
    public Page<TaggedPostSummaryResponse> taggedPosts(String rawName, Pageable pageable) {
        String name = TagNormalizer.normalize(rawName);
        if (name.isEmpty() || name.length() > TagNormalizer.NAME_MAX) {
            return Page.empty(pageable);
        }
        Page<TaggedPostRow> rows = tagQueryRepository.findTaggedPosts(name, pageable);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(TaggedPostRow::id).toList());
        return rows.map(row -> new TaggedPostSummaryResponse(
                row.toSummaryRow().toPublicResponse(tags.get(row.id())), row.blogHandle()));
    }
}
