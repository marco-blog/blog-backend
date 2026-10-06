package net.java21.blog.backend.releasenote.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 릴리스 노트(release_notes, 006 data-model, 003 FR-161~166). 버전은 SemVer {@code MAJOR.MINOR.PATCH}이며 정렬은 숫자 세 칸으로 한다.
 * {@code currentRevisionNo}는 낙관적 잠금 겸 지금 내용의 수정본 번호다. 게시·수정 규칙은 관리 서비스(003 US5)가 정한다.
 */
@Entity
@Table(name = "release_notes")
public class ReleaseNote extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String version;

    @Column(name = "version_major", nullable = false)
    private int versionMajor;

    @Column(name = "version_minor", nullable = false)
    private int versionMinor;

    @Column(name = "version_patch", nullable = false)
    private int versionPatch;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ReleaseNoteStatus status = ReleaseNoteStatus.DRAFT;

    @Column(name = "current_revision_no", nullable = false)
    private int currentRevisionNo = 1;

    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    @Column(name = "first_published_revision_no")
    private Integer firstPublishedRevisionNo;

    @Column(name = "published_at")
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;

    protected ReleaseNote() {
    }

    /** 새 초안(수정본 1). */
    public ReleaseNote(String version, int major, int minor, int patch, LocalDate releaseDate, User createdBy) {
        this.version = version;
        this.versionMajor = major;
        this.versionMinor = minor;
        this.versionPatch = patch;
        this.releaseDate = releaseDate;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    /** 내용을 고쳐 저장한다(수정본 번호 + 1). 버전은 게시한 적이 없을 때만 바꿀 수 있다(검증은 서비스). */
    public void revise(String version, int major, int minor, int patch, LocalDate releaseDate, User editor) {
        this.version = version;
        this.versionMajor = major;
        this.versionMinor = minor;
        this.versionPatch = patch;
        this.releaseDate = releaseDate;
        this.updatedBy = editor;
        this.currentRevisionNo++;
    }

    /**
     * 내용을 고쳐 저장한다. 수정본 번호는 저장소의 조건부 UPDATE({@code current_revision_no = base}일 때만 + 1, 006 FR-168)가 이미 올렸으므로
     * 여기서는 바꾸지 않는다. 버전은 게시한 적이 없을 때만 바꿀 수 있다(검증은 서비스).
     */
    public void edit(String version, int major, int minor, int patch, LocalDate releaseDate, User editor) {
        this.version = version;
        this.versionMajor = major;
        this.versionMinor = minor;
        this.versionPatch = patch;
        this.releaseDate = releaseDate;
        this.updatedBy = editor;
    }

    /** 게시. 처음 게시면 처음 게시 시각·수정본 번호를 남긴다. */
    public void publish(Instant now, User editor) {
        this.status = ReleaseNoteStatus.PUBLISHED;
        this.publishedAt = now;
        this.updatedBy = editor;
        if (firstPublishedAt == null) {
            this.firstPublishedAt = now;
            this.firstPublishedRevisionNo = currentRevisionNo;
        }
    }

    /** 게시 중단(초안으로). 처음 게시 기록은 남긴다. */
    public void unpublish(User editor) {
        this.status = ReleaseNoteStatus.DRAFT;
        this.publishedAt = null;
        this.updatedBy = editor;
    }

    public boolean isPublished() {
        return status == ReleaseNoteStatus.PUBLISHED;
    }

    public Long getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public int getVersionMajor() {
        return versionMajor;
    }

    public int getVersionMinor() {
        return versionMinor;
    }

    public int getVersionPatch() {
        return versionPatch;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public ReleaseNoteStatus getStatus() {
        return status;
    }

    public int getCurrentRevisionNo() {
        return currentRevisionNo;
    }

    public Instant getFirstPublishedAt() {
        return firstPublishedAt;
    }

    public Integer getFirstPublishedRevisionNo() {
        return firstPublishedRevisionNo;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }
}
