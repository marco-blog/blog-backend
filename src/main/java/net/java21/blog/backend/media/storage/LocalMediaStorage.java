package net.java21.blog.backend.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;

import net.java21.blog.backend.media.MediaProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 로컬 디렉터리 보관(T215). 기동 때 {@code blog.media.upload-dir}·{@code temp-dir}·{@code thumbnail-dir}를 만들고
 * 실제로 파일을 써 보아 쓸 수 없으면 기동을 멈춘다. 모든 경로는 기준 디렉터리 안으로만 풀린다(경로 조작 방지).
 */
@Component
public class LocalMediaStorage implements MediaStorage {

    private final Path uploadDir;
    private final Path tempDir;

    public LocalMediaStorage(MediaProperties properties) {
        this.uploadDir = prepare(properties.uploadDir(), "upload-dir");
        this.tempDir = prepare(properties.tempDir(), "temp-dir");
        prepare(properties.thumbnailDir(), "thumbnail-dir");
    }

    /** 디렉터리를 만들고 쓰기를 확인한다. 실패하면 {@link IllegalStateException}(기동 중단). */
    static Path prepare(Path dir, String name) {
        Path base = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
            Path probe = Files.createTempFile(base, ".write-check-", ".tmp");
            Files.delete(probe);
            return base;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new IllegalStateException("blog.media." + name + " is not a writable directory: " + base, e);
        }
    }

    @Override
    public String saveTemp(InputStream content, String storedName) throws IOException {
        Path target = resolve(tempDir, storedName);
        Path partial = Files.createTempFile(tempDir, ".upload-", ".part");
        try {
            Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING);
            move(partial, target);
        } finally {
            Files.deleteIfExists(partial);
        }
        return tempDir.relativize(target).toString();
    }

    @Override
    public String promote(String tempPath, YearMonth month) throws IOException {
        Path source = resolve(tempDir, tempPath);
        String relative = "%04d/%02d/%s".formatted(month.getYear(), month.getMonthValue(), source.getFileName());
        Path target = resolve(uploadDir, relative);
        Files.createDirectories(target.getParent());
        move(source, target);
        return relative;
    }

    @Override
    public void demote(String uploadPath, String tempPath) throws IOException {
        move(resolve(uploadDir, uploadPath), resolve(tempDir, tempPath));
    }

    @Override
    public Resource open(Area area, String path) {
        return new FileSystemResource(resolve(base(area), path));
    }

    @Override
    public boolean delete(Area area, String path) throws IOException {
        return Files.deleteIfExists(resolve(base(area), path));
    }

    private Path base(Area area) {
        return area == Area.TEMP ? tempDir : uploadDir;
    }

    /** 기준 디렉터리 안의 경로로 푼다. {@code ..}·절대 경로 등으로 밖을 가리키면 거부한다. */
    static Path resolve(Path base, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Empty media path");
        }
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("Media path escapes its directory: " + relative);
        }
        return resolved;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 다른 파일시스템: 복사 후 삭제
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 테스트·진단용: 정식 영역 기준 디렉터리. */
    Path uploadDir() {
        return uploadDir;
    }

    /** 테스트·진단용: 임시 영역 기준 디렉터리. */
    Path tempDir() {
        return tempDir;
    }
}
