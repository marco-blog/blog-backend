package net.java21.blog.backend.user.repository;

import net.java21.blog.backend.user.domain.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** 로그인 기록 저장. 조회·정리는 {@link LoginHistoryQueryRepository}(QueryDSL). */
public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
}
