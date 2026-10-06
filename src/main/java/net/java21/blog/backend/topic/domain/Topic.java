package net.java21.blog.backend.topic.domain;

import java.util.Map;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;

/**
 * 서비스 주제(topics, 003 FR-075·079·147). 대분류({@code parent} NULL)와 소분류 2단계. {@code slug}는 서비스 전체에서 유일하고
 * 만든 뒤 바꿀 수 없다. 이름은 4개 언어 모두 필수다. 주제는 삭제하지 않고 숨긴다({@code adminHidden}). 대분류가 숨김이면 소분류도
 * 숨김으로 본다(저장값은 그대로). 자동 숨김(FR-147)은 저장하지 않고 글 수로 계산한다.
 */
@Entity
@Table(name = "topics")
public class Topic extends BaseTimeEntity {

    public static final int SLUG_MAX = 40;
    public static final int NAME_MAX = 50;
    /** 주소용 영문 식별자 형식(data-model topics). 길이는 2~40자. */
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    public static final Pattern CARD_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대분류. NULL이면 이 주제가 대분류다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", updatable = false)
    private Topic parent;

    @Column(nullable = false, unique = true, length = SLUG_MAX, updatable = false)
    private String slug;

    @Column(name = "name_ko", nullable = false, length = NAME_MAX)
    private String nameKo;

    @Column(name = "name_en", nullable = false, length = NAME_MAX)
    private String nameEn;

    @Column(name = "name_ja", nullable = false, length = NAME_MAX)
    private String nameJa;

    @Column(name = "name_zh_cn", nullable = false, length = NAME_MAX)
    private String nameZhCn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "admin_hidden", nullable = false)
    private boolean adminHidden;

    @Column(name = "pinned_on_tab", nullable = false)
    private boolean pinnedOnTab;

    @Column(name = "card_color", length = 7, columnDefinition = "char(7)")
    private String cardColor;

    protected Topic() {
    }

    public Topic(Topic parent, String slug, TopicNames names, int sortOrder, String cardColor, boolean pinnedOnTab) {
        if (parent != null && parent.getParent() != null) {
            throw new IllegalArgumentException("Topics have two levels only: " + slug);
        }
        this.parent = parent;
        this.slug = slug;
        rename(names);
        this.sortOrder = sortOrder;
        this.cardColor = cardColor;
        this.pinnedOnTab = pinnedOnTab;
    }

    public boolean isMajor() {
        return parent == null;
    }

    /** 자신 또는 대분류가 운영자 숨김인지(FR-079). 부모가 읽혀 있어야 한다. */
    public boolean isEffectivelyHidden() {
        return adminHidden || (parent != null && parent.isAdminHidden());
    }

    public void rename(TopicNames names) {
        this.nameKo = names.ko();
        this.nameEn = names.en();
        this.nameJa = names.ja();
        this.nameZhCn = names.zhCn();
    }

    public void changeColor(String cardColor) {
        this.cardColor = cardColor;
    }

    public void hide() {
        this.adminHidden = true;
    }

    public void unhide() {
        this.adminHidden = false;
    }

    public void pin() {
        this.pinnedOnTab = true;
    }

    public void unpin() {
        this.pinnedOnTab = false;
    }

    public void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Topic getParent() {
        return parent;
    }

    /** 대분류 id(지연 로딩 프록시를 초기화하지 않는다). 대분류면 null. */
    public Long getParentId() {
        return parent == null ? null : parent.getId();
    }

    public String getSlug() {
        return slug;
    }

    public TopicNames getNames() {
        return new TopicNames(nameKo, nameEn, nameJa, nameZhCn);
    }

    /** 4개 언어 이름(키 {@code ko}, {@code en}, {@code ja}, {@code zh-CN}). */
    public Map<String, String> namesByLanguage() {
        return getNames().asMap();
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isAdminHidden() {
        return adminHidden;
    }

    public boolean isPinnedOnTab() {
        return pinnedOnTab;
    }

    public String getCardColor() {
        return cardColor;
    }
}
