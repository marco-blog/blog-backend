package net.java21.blog.backend.spam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 005 T064: 금칙어 목록(단어순, 부분 일치, 페이지) — 등록 관리자 fetch join으로 쿼리 2회. */
@JpaRepositoryTest
class BannedWordRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private BannedWordRepository repository;

    @Test
    void searchesByWordInOrderWithCreatorInTwoQueries() {
        JpaFixtures fx = new JpaFixtures(em);
        User admin = fx.user("admin");
        User other = fx.user("other");
        em.persist(new BannedWord(admin, "spam", BannedWordScope.ALL, BannedWordAction.REJECT));
        em.persist(new BannedWord(other, "ad", BannedWordScope.CONTENT, BannedWordAction.MASK));
        em.persist(new BannedWord(admin, "spammer", BannedWordScope.NAME, BannedWordAction.REJECT));
        fx.flushAndClear();

        BannedWordQueryRepository query = new BannedWordQueryRepository(queryFactory);
        queryCounter.reset();
        Page<BannedWord> all = query.search(null, PageRequest.of(0, 20));
        all.getContent().forEach(w -> w.getCreatedBy().getNickname());
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(all.getContent()).extracting(BannedWord::getWord).containsExactly("ad", "spam", "spammer");
        assertThat(all.getTotalElements()).isEqualTo(3);

        Page<BannedWord> spam = query.search("spam", PageRequest.of(0, 1));
        assertThat(spam.getContent()).extracting(BannedWord::getWord).containsExactly("spam");
        assertThat(spam.getTotalElements()).isEqualTo(2);
        assertThat(query.search("", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);

        assertThat(repository.existsByWord("ad")).isTrue();
        assertThat(repository.existsByWord("none")).isFalse();
        Long id = all.getContent().getFirst().getId();
        fx.flushAndClear();
        queryCounter.reset();
        BannedWord found = repository.findWithCreator(id).orElseThrow();
        assertThat(found.getCreatedBy().getNickname()).isEqualTo("other");
        assertThat(queryCounter.count()).isEqualTo(1);
    }
}
