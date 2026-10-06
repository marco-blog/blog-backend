package net.java21.blog.backend.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.test.context.TestComponent;

/**
 * Repository 테스트에서 실행된 SQL 수를 센다(N+1 확인, research R13).
 * Hibernate {@link Statistics}의 prepared statement 수를 쓰므로 지연 로딩·배치 조회까지 모두 센다.
 * {@code hibernate.generate_statistics=true}가 필요하다({@link JpaRepositoryTest}가 켠다).
 *
 * <pre>{@code
 * em.flush(); em.clear();
 * queryCounter.reset();
 * ... 조회 ...
 * assertThat(queryCounter.count()).isEqualTo(1);
 * }</pre>
 */
@TestComponent
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        if (!statistics.isStatisticsEnabled()) {
            throw new IllegalStateException("Hibernate 통계가 꺼져 있습니다: hibernate.generate_statistics=true 가 필요합니다");
        }
    }

    /** 지금까지 센 값을 지운다. 준비(저장·flush) 쿼리를 빼려면 확인할 동작 바로 앞에서 부른다. */
    public void reset() {
        statistics.clear();
    }

    /** {@link #reset()} 이후 실행된 SQL 문 수. */
    public long count() {
        return statistics.getPrepareStatementCount();
    }
}
