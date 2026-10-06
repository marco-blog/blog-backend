package net.java21.blog.backend.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

import net.java21.blog.backend.media.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

/** 로컬 보관(T203): saveTemp·promote·open·delete, 기동 때 디렉터리 생성과 쓰기 확인, 기준 디렉터리 밖 경로 거부. */
class LocalMediaStorageTest {

    @TempDir
    Path root;

    @Test
    void createsTheThreeDirectoriesOnStartup() {
        LocalMediaStorage storage = new LocalMediaStorage(TestImages.properties(root.resolve("nested/media")));

        assertThat(root.resolve("nested/media/upload")).isDirectory();
        assertThat(root.resolve("nested/media/temp")).isDirectory();
        assertThat(root.resolve("nested/media/thumb")).isDirectory();
        assertThat(storage.uploadDir()).isEqualTo(root.resolve("nested/media/upload").toAbsolutePath());
        assertThat(storage.tempDir()).isEqualTo(root.resolve("nested/media/temp").toAbsolutePath());
    }

    @Test
    void startupFailsWhenADirectoryCannotBeCreatedOrWritten() throws IOException {
        Path file = Files.writeString(root.resolve("not-a-dir"), "x");
        assertThatThrownBy(() -> new LocalMediaStorage(TestImages.properties(file)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blog.media.upload-dir");
    }

    @Test
    void saveTempPromoteOpenAndDelete() throws IOException {
        LocalMediaStorage storage = new LocalMediaStorage(TestImages.properties(root));
        byte[] bytes = TestImages.png(3, 3);

        String tempPath = storage.saveTemp(new ByteArrayInputStream(bytes), "abc.png");
        assertThat(tempPath).isEqualTo("abc.png");
        Resource temp = storage.open(MediaStorage.Area.TEMP, tempPath);
        assertThat(temp.exists()).isTrue();
        assertThat(temp.getContentAsByteArray()).isEqualTo(bytes);
        try (var files = Files.list(root.resolve("temp"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("abc.png");
        }

        String uploadPath = storage.promote(tempPath, YearMonth.of(2026, 3));
        assertThat(uploadPath).isEqualTo("2026/03/abc.png");
        assertThat(storage.open(MediaStorage.Area.TEMP, tempPath).exists()).isFalse();
        assertThat(storage.open(MediaStorage.Area.UPLOAD, uploadPath).getContentAsByteArray()).isEqualTo(bytes);

        storage.demote(uploadPath, tempPath);
        assertThat(storage.open(MediaStorage.Area.TEMP, tempPath).exists()).isTrue();
        storage.promote(tempPath, YearMonth.of(2026, 3));

        assertThat(storage.delete(MediaStorage.Area.UPLOAD, uploadPath)).isTrue();
        assertThat(storage.delete(MediaStorage.Area.UPLOAD, uploadPath)).isFalse();
        assertThat(storage.open(MediaStorage.Area.UPLOAD, uploadPath).exists()).isFalse();
    }

    @Test
    void pathsOutsideTheBaseDirectoryAreRejected() {
        LocalMediaStorage storage = new LocalMediaStorage(TestImages.properties(root));

        assertThatThrownBy(() -> storage.open(MediaStorage.Area.UPLOAD, "../temp/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(MediaStorage.Area.TEMP, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(MediaStorage.Area.TEMP, "."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.saveTemp(new ByteArrayInputStream(new byte[1]), "../../escape.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(MediaStorage.Area.TEMP, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.getParent().resolve("escape.png")).doesNotExist();
    }

    @Test
    void promoteOfMissingFileFails() {
        LocalMediaStorage storage = new LocalMediaStorage(TestImages.properties(root));
        assertThatThrownBy(() -> storage.promote("missing.png", YearMonth.of(2026, 10)))
                .isInstanceOf(IOException.class);
    }
}
