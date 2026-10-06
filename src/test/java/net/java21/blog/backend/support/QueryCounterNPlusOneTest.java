package net.java21.blog.backend.support;

import static net.java21.blog.fixture.nplusone.QFixtureChild.fixtureChild;
import static net.java21.blog.fixture.nplusone.QFixtureParent.fixtureParent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import net.java21.blog.fixture.nplusone.FixtureChild;
import net.java21.blog.fixture.nplusone.FixtureParent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link QueryCounter}로 N+1을 잡아내는 예시. 테스트 전용 엔티티(부모 3개, 각 자식 2개)를 쓴다.
 * <ul>
 *   <li>배치 조회 없이 반복문에서 LAZY 컬렉션을 건드리면 1 + N번 조회한다(N+1).</li>
 *   <li>{@code default_batch_fetch_size=100}이면 IN 절로 묶여 2번으로 준다.</li>
 *   <li>QueryDSL fetch join이면 1번이다(목록 조회의 기본 방식).</li>
 * </ul>
 */
@JpaRepositoryTest
@Import(QueryCounterNPlusOneTest.FixtureEntities.class)
class QueryCounterNPlusOneTest {

    private static final int PARENTS = 3;

    /** {@code @Nested} 클래스도 같은 엔티티를 쓰도록 {@code @Import}로 올린다(중첩 설정 자동 감지는 바깥 클래스에만 적용). */
    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = FixtureParent.class)
    static class FixtureEntities {
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Autowired
    private QueryCounter queryCounter;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < PARENTS; i++) {
            FixtureParent parent = new FixtureParent("parent-" + i);
            em.persist(parent);
            em.persist(new FixtureChild("child-" + i + "-a", parent));
            em.persist(new FixtureChild("child-" + i + "-b", parent));
        }
        em.flush();
        em.clear();
        queryCounter.reset();
    }

    @Test
    void defaultBatchFetchSizeGroupsLazyLoads() {
        List<FixtureParent> parents = em.createQuery("select p from FixtureParent p", FixtureParent.class)
                .getResultList();
        int children = countChildren(parents);

        assertThat(children).isEqualTo(PARENTS * 2);
        assertThat(queryCounter.count()).isEqualTo(2);
    }

    @Test
    void querydslFetchJoinRunsOneQuery() {
        List<FixtureParent> parents = queryFactory
                .selectFrom(fixtureParent)
                .distinct()
                .join(fixtureParent.children, fixtureChild).fetchJoin()
                .orderBy(fixtureParent.id.asc())
                .fetch();
        int children = countChildren(parents);

        assertThat(parents).hasSize(PARENTS);
        assertThat(children).isEqualTo(PARENTS * 2);
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void resetClearsCount() {
        em.createQuery("select p from FixtureParent p", FixtureParent.class).getResultList();
        assertThat(queryCounter.count()).isEqualTo(1);

        queryCounter.reset();

        assertThat(queryCounter.count()).isZero();
    }

    /** 배치 조회를 끈 설정(별도 컨텍스트)에서 반복문 지연 로딩이 N+1을 만드는 것을 보인다. */
    @Nested
    @TestPropertySource(properties = "spring.jpa.properties.hibernate.default_batch_fetch_size=1")
    class WithoutBatchFetch {

        @Test
        void naiveLoopRunsOnePlusNQueries() {
            List<FixtureParent> parents = em.createQuery("select p from FixtureParent p", FixtureParent.class)
                    .getResultList();
            int children = countChildren(parents);

            assertThat(children).isEqualTo(PARENTS * 2);
            assertThat(queryCounter.count()).isEqualTo(1 + PARENTS);
        }
    }

    private static int countChildren(List<FixtureParent> parents) {
        int total = 0;
        for (FixtureParent parent : parents) {
            total += parent.getChildren().size();
        }
        return total;
    }
}
