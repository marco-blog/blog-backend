package net.java21.blog.backend.topic.repository;

import static net.java21.blog.backend.topic.domain.QTopic.topic;

import java.util.List;
import java.util.Optional;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.topic.domain.QTopic;
import org.springframework.stereotype.Repository;

/**
 * 주제 조회(003 research P2). 모두 쿼리 1회이며 DTO projection이다. 정렬은 대분류 순서 → 대분류 안 소분류 순서다
 * (대분류는 자기 자신의 순서, 소분류는 부모의 순서로 먼저 묶는다).
 */
@Repository
public class TopicQueryRepository {

    private static final QTopic parent = new QTopic("parentTopic");

    private final JPAQueryFactory queryFactory;

    public TopicQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 숨김을 포함한 전체 주제(관리자 트리, seed 검사). */
    public List<TopicRow> findAll() {
        return select().fetch();
    }

    /** 운영자 숨김이 아닌 주제(숨긴 대분류의 소분류 제외). 공개 트리(GET /topics)와 사이트맵. */
    public List<TopicRow> findVisible() {
        return select().where(visible()).fetch();
    }

    public Optional<TopicRow> findBySlug(String slug) {
        return Optional.ofNullable(select().where(topic.slug.eq(slug)).fetchFirst());
    }

    /** 대분류의 소분류 중 운영자 숨김이 아닌 것의 id(순서대로). 대분류 자신이 숨김이면 빈 목록. */
    public List<Long> findVisibleChildIds(Long majorId) {
        return queryFactory.select(topic.id)
                .from(topic)
                .join(topic.parent, parent)
                .where(parent.id.eq(majorId), topic.adminHidden.isFalse(), parent.adminHidden.isFalse())
                .orderBy(topic.sortOrder.asc(), topic.id.asc())
                .fetch();
    }

    private JPAQuery<TopicRow> select() {
        return queryFactory
                .select(Projections.constructor(TopicRow.class, topic.id, parent.id, topic.slug, topic.nameKo,
                        topic.nameEn, topic.nameJa, topic.nameZhCn, topic.sortOrder, topic.adminHidden,
                        parent.adminHidden.coalesce(false), topic.pinnedOnTab, topic.cardColor, topic.createdAt,
                        topic.updatedAt))
                .from(topic)
                .leftJoin(topic.parent, parent)
                .orderBy(parent.sortOrder.coalesce(topic.sortOrder).asc(), parent.id.coalesce(topic.id).asc(),
                        Expressions.numberTemplate(Integer.class, "case when {0} is null then 0 else 1 end",
                                parent.id).asc(),
                        topic.sortOrder.asc(), topic.id.asc());
    }

    private static BooleanExpression visible() {
        return topic.adminHidden.isFalse().and(parent.id.isNull().or(parent.adminHidden.isFalse()));
    }
}
