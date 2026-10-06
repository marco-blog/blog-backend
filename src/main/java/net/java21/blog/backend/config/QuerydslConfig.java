package net.java21.blog.backend.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL(OpenFeign 포크) 조회용 {@link JPAQueryFactory}. 동적 조건·목록 조회는 QueryDSL로 쓴다(원칙 v2.5.0).
 * 주입되는 {@link EntityManager}는 트랜잭션에 묶인 공유 프록시라 싱글턴 빈으로 둬도 된다.
 */
@Configuration(proxyBeanMethods = false)
public class QuerydslConfig {

    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
