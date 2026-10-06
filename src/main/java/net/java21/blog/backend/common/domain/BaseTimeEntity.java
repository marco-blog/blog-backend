package net.java21.blog.backend.common.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 공통 컬럼 created_at·updated_at(UTC {@code DATETIME(6)}, data-model 공통 규칙).
 * <ul>
 *   <li>값은 JPA Auditing({@code JpaAuditingConfig})이 저장·수정 때 채운다.
 *       DB 기본값({@code DEFAULT CURRENT_TIMESTAMP(6)}, {@code ON UPDATE CURRENT_TIMESTAMP(6)})은
 *       JPA를 거치지 않는 쓰기(정리 작업의 벌크 SQL 등)를 위한 것이다.</li>
 *   <li>created_at은 처음 저장한 뒤 바꾸지 않는다({@code updatable = false}).</li>
 *   <li>{@code hibernate.jdbc.time_zone=UTC}이므로 {@link Instant}가 UTC 그대로 저장된다.</li>
 * </ul>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
