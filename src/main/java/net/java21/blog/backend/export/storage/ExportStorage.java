package net.java21.blog.backend.export.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import net.java21.blog.backend.export.ExportProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 백업 zip 파일 보관(004 research B14). 기준 디렉터리는 {@code blog.export.dir}({@code BLOG_DATA_DIR}/exports)이며
 * 파일은 {@code {yyyy}/{MM}/{무작위 UUID}.zip}에 둔다. DB의 {@code file_path}는 이 디렉터리 기준 상대 경로이고, 풀 때 기준 밖을 가리키면
 * 거부한다(경로 조작 방지). 기동 때 디렉터리를 만들고 실제로 써 보아 쓸 수 없으면 기동을 멈춘다(001 이미지 디렉터리와 같은 방식).
 */
@Component
public class ExportStorage {

    private final Path baseDir;

    public ExportStorage(ExportProperties properties) {
        this.baseDir = prepare(Path.of(properties.dir()));
    }

    static Path prepare(Path dir) {
        Path base = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
            Path probe = Files.createTempFile(base, ".write-check-", ".tmp");
            Files.delete(probe);
            return base;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new IllegalStateException("blog.export.dir is not a writable directory: " + base, e);
        }
    }

    /** 새 백업 파일의 상대 경로({@code yyyy/MM/UUID.zip}, UTC 기준 달). 파일은 만들지 않는다. */
    public String newPath(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        return "%04d/%02d/%s.zip".formatted(utc.getYear(), utc.getMonthValue(), UUID.randomUUID());
    }

    /** 쓰기용 스트림(상위 디렉터리를 만든다. 이미 있으면 덮어쓴다). */
    public OutputStream create(String relative) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        return Files.newOutputStream(target);
    }

    public long size(String relative) throws IOException {
        return Files.size(resolve(relative));
    }

    /** 읽기용 파일. 없으면 {@code exists()}가 false. */
    public Resource open(String relative) {
        return new FileSystemResource(resolve(relative));
    }

    /** 지운다. 없으면 false. 경로가 비었으면 아무것도 하지 않는다. */
    public boolean delete(String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            return false;
        }
        return Files.deleteIfExists(resolve(relative));
    }

    /** 기준 디렉터리 안의 경로로 푼다. {@code ..}·절대 경로 등으로 밖을 가리키면 {@link IllegalArgumentException}. */
    Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Empty export path");
        }
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
            throw new IllegalArgumentException("Export path escapes its directory: " + relative);
        }
        return resolved;
    }

    /** 테스트·진단용: 기준 디렉터리. */
    public Path baseDir() {
        return baseDir;
    }
}
