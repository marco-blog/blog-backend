package net.java21.blog.backend.export.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import net.java21.blog.backend.export.ExportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 백업 파일 보관(T107): {@code yyyy/MM/UUID.zip}, 기준 디렉터리 밖 경로 거부, 쓰기·크기·열기·지우기, 쓸 수 없으면 기동 실패. */
class ExportStorageTest {

    @TempDir
    Path dir;

    @Test
    void writesReadsAndDeletesInsideTheDirectory() throws IOException {
        ExportStorage storage = storage(dir.resolve("exports"));
        String path = storage.newPath(Instant.parse("2026-01-31T23:59:59Z"));
        assertThat(path).matches("2026/01/[0-9a-f-]{36}\\.zip");

        try (OutputStream out = storage.create(path)) {
            out.write(new byte[] {'P', 'K', 3, 4});
        }
        assertThat(storage.size(path)).isEqualTo(4);
        assertThat(storage.open(path).exists()).isTrue();
        assertThat(storage.baseDir()).isEqualTo(dir.resolve("exports").toAbsolutePath().normalize());
        assertThat(storage.delete(path)).isTrue();
        assertThat(storage.delete(path)).isFalse();
        assertThat(storage.delete(null)).isFalse();
        assertThat(storage.delete(" ")).isFalse();
        assertThat(storage.open(path).exists()).isFalse();
    }

    @Test
    void pathsEscapingTheDirectoryAreRejected() {
        ExportStorage storage = storage(dir);
        assertThatThrownBy(() -> storage.open("../secret.zip")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unwritableDirectoryStopsStartup() throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-dir"), "x");
        assertThatThrownBy(() -> storage(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blog.export.dir");
    }

    @Test
    void propertiesValidateValues() {
        assertThatThrownBy(() -> new ExportProperties(" ", Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportProperties("x", Duration.ZERO, Duration.ofHours(24), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExportStorage storage(Path base) {
        return new ExportStorage(new ExportProperties(base.toString(), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofHours(1)));
    }
}
