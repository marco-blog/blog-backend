package net.java21.blog.backend.media.domain;

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
 * 올린 이미지(media, T214, FR-038·039, FR-071~074, FR-156). 주소는 {@code /media/{media_key}}이고 파일 위치가 바뀌어도 그대로다.
 * {@code stored_path}는 상태에 따른 기준 디렉터리(TEMP는 temp-dir, 그 외 upload-dir)로부터의 상대 경로다.
 */
@Entity
@Table(name = "media")
public class Media extends BaseTimeEntity {

    public static final String URL_PREFIX = "/media/";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "media_key", nullable = false, unique = true, length = 22, columnDefinition = "char(22)")
    private String mediaKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "owner_type", nullable = false, length = 12)
    private MediaPurpose ownerType = MediaPurpose.POST;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private MediaStatus status = MediaStatus.TEMP;

    @Column(name = "stored_name", nullable = false, length = 100)
    private String storedName;

    @Column(name = "stored_path", nullable = false, length = 300)
    private String storedPath;

    @Column(nullable = false, length = 20)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    protected Media() {
    }

    /** 방금 temp-dir에 저장한 이미지(TEMP). */
    public Media(User owner, String mediaKey, MediaPurpose ownerType, String storedName, String storedPath, String mime,
            int sizeBytes, int width, int height) {
        this.owner = owner;
        this.mediaKey = mediaKey;
        this.ownerType = ownerType;
        this.storedName = storedName;
        this.storedPath = storedPath;
        this.mime = mime;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
    }

    /** 이미지 주소. */
    public static String urlOf(String mediaKey) {
        return mediaKey == null ? null : URL_PREFIX + mediaKey;
    }

    public String url() {
        return urlOf(mediaKey);
    }

    public boolean isTemp() {
        return status == MediaStatus.TEMP;
    }

    /** TEMP 파일을 upload-dir로 옮긴 뒤 부른다. */
    public void attachAt(String uploadRelativePath) {
        if (status != MediaStatus.TEMP) {
            throw new IllegalStateException("Media is not TEMP: " + mediaKey);
        }
        this.storedPath = uploadRelativePath;
        this.status = MediaStatus.ATTACHED;
    }

    /** 정리 대상이었던 이미지를 다시 참조할 때(정리 전까지). */
    public void reattach() {
        if (status == MediaStatus.ORPHANED) {
            this.status = MediaStatus.ATTACHED;
        }
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getMediaKey() {
        return mediaKey;
    }

    public MediaPurpose getOwnerType() {
        return ownerType;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public String getStoredName() {
        return storedName;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public String getMime() {
        return mime;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
