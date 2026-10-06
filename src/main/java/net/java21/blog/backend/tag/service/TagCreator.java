package net.java21.blog.backend.tag.service;

import java.util.Optional;

import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.tag.repository.TagRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 공통 태그 만들기(get-or-create의 "create"). 별도 트랜잭션에서 INSERT해, 같은 이름을 다른 요청이 먼저 만들어 UNIQUE에 걸려도
 * 발행 트랜잭션이 깨지지 않게 한다. 그때는 새 트랜잭션에서 다시 조회한다(먼저 커밋된 행이 보인다).
 */
@Component
public class TagCreator {

    private final TagRepository tagRepository;

    public TagCreator(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    /** 새 태그 id. 이름이 이미 있으면 {@code DataIntegrityViolationException}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insert(String name) {
        return tagRepository.saveAndFlush(new Tag(name)).getId();
    }

    /** 다른 요청이 먼저 만든 태그 id(새 트랜잭션에서 조회). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Long> findId(String name) {
        return tagRepository.findByName(name).map(Tag::getId);
    }
}
