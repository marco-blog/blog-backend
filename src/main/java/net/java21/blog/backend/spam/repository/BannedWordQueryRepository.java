package net.java21.blog.backend.spam.repository;

import static net.java21.blog.backend.spam.domain.QBannedWord.bannedWord;

import java.util.List;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 관리 화면 금칙어 목록(단어순, {@code q} 부분 일치). 등록 관리자를 fetch join — 쿼리 2회(목록, 개수). */
@Repository
public class BannedWordQueryRepository {

    private final JPAQueryFactory queryFactory;

    public BannedWordQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<BannedWord> search(String normalizedQuery, Pageable pageable) {
        QUser creator = new QUser("creator");
        BooleanExpression condition = normalizedQuery == null || normalizedQuery.isEmpty() ? null
                : bannedWord.word.contains(normalizedQuery);
        List<BannedWord> words = queryFactory.selectFrom(bannedWord)
                .join(bannedWord.createdBy, creator).fetchJoin()
                .where(condition)
                .orderBy(bannedWord.word.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(bannedWord.count()).from(bannedWord).where(condition).fetchOne();
        return new PageImpl<>(words, pageable, total == null ? 0 : total);
    }
}
