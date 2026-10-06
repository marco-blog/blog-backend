package net.java21.blog.backend.releasenote.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

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

import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 릴리스 노트 수정본(release_note_revisions, 006 FR-168). 저장할 때마다 한 행을 더하고 바꾸지 않는다. 모든 언어판의 제목·Markdown을
 * {@code contents_json}({@code { "ko": { title, contentMarkdown }, ... }})에 담는다. 독자에게 수정한 관리자는 보이지 않는다(003 FR-166).
 */
@Entity
@Immutable
@Table(name = "release_note_revisions")
@jakarta.persistence.EntityListeners(AuditingEntityListener.class)
public class ReleaseNoteRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_note_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ReleaseNote releaseNote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edited_by", nullable = false)
    private User editedBy;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ReleaseNoteStatus status;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contents_json", nullable = false)
    private Map<String, RevisionContent> contents = new LinkedHashMap<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReleaseNoteRevision() {
    }

    /** 노트의 지금 상태를 수정본으로 남긴다. */
    public ReleaseNoteRevision(ReleaseNote note, User editedBy, Map<String, RevisionContent> contents) {
        this.releaseNote = note;
        this.editedBy = editedBy;
        this.revisionNo = note.getCurrentRevisionNo();
        this.status = note.getStatus();
        this.version = note.getVersion();
        this.releaseDate = note.getReleaseDate();
        this.contents = new LinkedHashMap<>(contents);
    }

    public Long getId() {
        return id;
    }

    public ReleaseNote getReleaseNote() {
        return releaseNote;
    }

    public User getEditedBy() {
        return editedBy;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public ReleaseNoteStatus getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public Map<String, RevisionContent> getContents() {
        return contents == null ? Map.of() : Map.copyOf(contents);
    }

    /** 저장 시각. */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
