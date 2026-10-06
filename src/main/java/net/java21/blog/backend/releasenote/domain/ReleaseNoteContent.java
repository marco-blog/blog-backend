package net.java21.blog.backend.releasenote.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * 릴리스 노트 언어판(release_note_contents). 한국어판은 필수(서비스 검증). 저장할 때 Markdown을 변환·살균한 HTML, 검색용 텍스트,
 * 목차를 함께 저장한다(003 research P11). 노트를 지우면 DB의 {@code ON DELETE CASCADE}로 함께 지워진다.
 */
@Entity
@Table(name = "release_note_contents")
public class ReleaseNoteContent extends BaseTimeEntity {

    @EmbeddedId
    private ReleaseNoteContentId id;

    @MapsId("releaseNoteId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_note_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ReleaseNote releaseNote;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "content_md", nullable = false, columnDefinition = "mediumtext")
    private String contentMarkdown;

    @Column(name = "content_html", columnDefinition = "mediumtext")
    private String contentHtml;

    @Column(name = "content_text", columnDefinition = "mediumtext")
    private String contentText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "toc_json")
    private List<TocEntry> toc = new ArrayList<>();

    protected ReleaseNoteContent() {
    }

    public ReleaseNoteContent(ReleaseNote releaseNote, String lang) {
        this.releaseNote = releaseNote;
        this.id = new ReleaseNoteContentId(releaseNote.getId(), lang);
    }

    /** 언어판 내용을 바꾼다(변환 결과 포함). */
    public void write(String title, String contentMarkdown, String contentHtml, String contentText, List<TocEntry> toc) {
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.contentHtml = contentHtml;
        this.contentText = contentText;
        this.toc = toc == null ? new ArrayList<>() : new ArrayList<>(toc);
    }

    public ReleaseNoteContentId getId() {
        return id;
    }

    public String getLang() {
        return id.lang();
    }

    public ReleaseNote getReleaseNote() {
        return releaseNote;
    }

    public String getTitle() {
        return title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public String getContentHtml() {
        return contentHtml;
    }

    public String getContentText() {
        return contentText;
    }

    public List<TocEntry> getToc() {
        return toc == null ? List.of() : List.copyOf(toc);
    }
}
