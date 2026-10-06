package net.java21.blog.fixture.nplusone;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * N+1 확인용 테스트 전용 엔티티(부모). 운영 패키지 밖({@code net.java21.blog.fixture})에 둬서
 * 다른 JPA 테스트의 엔티티 스캔에 잡히지 않는다. 이 엔티티를 쓰는 테스트만 {@code @EntityScan}으로 올린다.
 */
@Entity
@Table(name = "zz_fixture_parent")
public class FixtureParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "parent")
    private List<FixtureChild> children = new ArrayList<>();

    protected FixtureParent() {
    }

    public FixtureParent(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<FixtureChild> getChildren() {
        return children;
    }
}
