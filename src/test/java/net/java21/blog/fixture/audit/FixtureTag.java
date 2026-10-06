package net.java21.blog.fixture.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.java21.blog.backend.common.domain.BaseTimeEntity;

/**
 * {@link BaseTimeEntity} 확인용 테스트 전용 엔티티. 실제 스키마의 {@code tags} 테이블(id, name, created_at, updated_at)에
 * 맞춰 두어 MySQL 테스트에서 {@code ddl-auto=validate}로 컬럼 타입(DATETIME(6))까지 확인한다.
 */
@Entity
@Table(name = "tags")
public class FixtureTag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected FixtureTag() {
    }

    public FixtureTag(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
