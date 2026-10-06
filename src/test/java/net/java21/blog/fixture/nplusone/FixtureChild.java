package net.java21.blog.fixture.nplusone;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** N+1 확인용 테스트 전용 엔티티(자식). 연관관계는 원칙대로 LAZY. */
@Entity
@Table(name = "zz_fixture_child")
public class FixtureChild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private FixtureParent parent;

    protected FixtureChild() {
    }

    public FixtureChild(String name, FixtureParent parent) {
        this.name = name;
        this.parent = parent;
        parent.getChildren().add(this);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public FixtureParent getParent() {
        return parent;
    }
}
