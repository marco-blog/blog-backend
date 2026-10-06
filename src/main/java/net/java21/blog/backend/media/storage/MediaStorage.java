package net.java21.blog.backend.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;

import org.springframework.core.io.Resource;

/**
 * 원본 이미지 파일 보관(T215, research R11). 지금은 로컬 디렉터리({@link LocalMediaStorage})이고 나중에 S3 등으로 바꿀 수 있게 감쌌다.
 * 경로는 모두 영역(임시·정식)의 기준 디렉터리로부터의 상대 경로이며, 기준 밖을 가리키면 {@link IllegalArgumentException}.
 */
public interface MediaStorage {

    /** 보관 영역. TEMP 이미지는 임시, 그 외는 정식. */
    enum Area {
        TEMP,
        UPLOAD
    }

    /**
     * 임시 영역에 {@code storedName}으로 저장한다.
     *
     * @return 임시 영역 기준 상대 경로
     */
    String saveTemp(InputStream content, String storedName) throws IOException;

    /**
     * 임시 파일을 정식 영역의 {@code yyyy/MM/}로 옮긴다(같은 파일시스템이면 원자적 이동).
     *
     * @return 정식 영역 기준 상대 경로
     */
    String promote(String tempPath, YearMonth month) throws IOException;

    /** {@link #promote}를 되돌린다(트랜잭션 롤백 때). */
    void demote(String uploadPath, String tempPath) throws IOException;

    /** 읽기용 파일. 없으면 {@code exists()}가 false. */
    Resource open(Area area, String path);

    /** 지운다. 없으면 false. */
    boolean delete(Area area, String path) throws IOException;
}
