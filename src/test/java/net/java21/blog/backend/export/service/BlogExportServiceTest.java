package net.java21.blog.backend.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;
import net.java21.blog.backend.export.dto.BlogExportResponse;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.repository.BlogExportRepository;
import net.java21.blog.backend.export.storage.ExportStorage;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 백업 요청·목록·파일(T100, FR-145): 주인만(403), 24시간 안에 실패하지 않은 백업이 있으면 409 {@code EXPORT_LIMIT_EXCEEDED},
 * 목록 10건, 파일은 이 블로그의 READY이고 만료 전이며 파일이 있을 때만(아니면 404 {@code EXPORT_NOT_FOUND}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlogExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T16:24:19Z");
    private static final long OWNER = 1L;

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private BlogExportRepository exportRepository;
    @Mock
    private BlogExportQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;

    @TempDir
    Path exportDir;

    private final MutableClock clock = new MutableClock(NOW);
    private ExportStorage storage;
    private BlogExportService service;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties(exportDir.toString(), Duration.ofDays(7),
                Duration.ofHours(24), Duration.ofHours(1));
        storage = new ExportStorage(properties);
        service = new BlogExportService(new BlogAccess(blogRepository), exportRepository, queryRepository,
                userRepository, storage, properties, new BlogCalendar(StatsProperties.defaults(), clock), clock);
        owner = TestEntities.user(OWNER);
        blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(userRepository.getReferenceById(OWNER)).thenReturn(owner);
        when(exportRepository.saveAndFlush(any(BlogExport.class))).thenAnswer(invocation -> {
            BlogExport saved = invocation.getArgument(0);
            TestEntities.with(saved, "id", 3L);
            TestEntities.with(saved, "createdAt", NOW);
            return saved;
        });
    }

    @Test
    void requestCreatesPendingWhenNothingActiveInTheLastDay() {
        when(queryRepository.existsActiveSince(10L, NOW.minus(Duration.ofHours(24)))).thenReturn(false);

        BlogExportResponse created = service.request(OWNER, "marco");

        assertThat(created.id()).isEqualTo(3L);
        assertThat(created.status()).isEqualTo(ExportStatus.PENDING);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.fileSize()).isNull();
    }

    @Test
    void requestWithinTheDayIsConflict() {
        when(queryRepository.existsActiveSince(10L, NOW.minus(Duration.ofHours(24)))).thenReturn(true);

        assertCode(() -> service.request(OWNER, "marco"), ErrorCode.EXPORT_LIMIT_EXCEEDED);
        verify(exportRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlyTheOwner() {
        assertCode(() -> service.request(2L, "marco"), ErrorCode.FORBIDDEN);
        assertCode(() -> service.list(2L, "marco"), ErrorCode.FORBIDDEN);
        assertCode(() -> service.file(2L, "marco", 3L), ErrorCode.FORBIDDEN);
        when(blogRepository.findByHandleWithOwner("nobody")).thenReturn(Optional.empty());
        assertCode(() -> service.request(OWNER, "nobody"), ErrorCode.BLOG_NOT_FOUND);
        verify(queryRepository, never()).existsActiveSince(anyLong(), any());
    }

    @Test
    void listMapsRecentExportsWithoutFilePath() {
        BlogExport ready = export(5L, blog);
        ready.markReady("2026/10/a.zip", 1234, NOW, Duration.ofDays(7));
        when(queryRepository.findRecent(10L)).thenReturn(List.of(ready));

        List<BlogExportResponse> list = service.list(OWNER, "marco");

        assertThat(list).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ExportStatus.READY);
            assertThat(r.fileSize()).isEqualTo(1234);
            assertThat(r.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        });
        assertThat(BlogExportResponse.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("path"));
    }

    @Test
    void readyFileOfThisBlogBeforeExpiryIsServedWithLocalDateName() throws IOException {
        BlogExport ready = export(5L, blog);
        ready.markReady("2026/10/a.zip", 3, NOW, Duration.ofDays(7));
        Files.createDirectories(exportDir.resolve("2026/10"));
        Files.writeString(exportDir.resolve("2026/10/a.zip"), "PK!");
        when(exportRepository.findById(5L)).thenReturn(Optional.of(ready));

        BlogExportService.ExportFile file = service.file(OWNER, "marco", 5L);

        assertThat(file.filename()).as("16:24 UTC는 서울 날짜로 다음 날").isEqualTo("marco-backup-20261007.zip");
        assertThat(file.size()).isEqualTo(3);
        assertThat(file.resource().exists()).isTrue();
        assertThat(NOW.atZone(ZoneOffset.UTC).getDayOfMonth()).isEqualTo(6);
    }

    @Test
    void fileIsNotFoundUnlessReadyUnexpiredOwnedAndPresent() throws IOException {
        when(exportRepository.findById(404L)).thenReturn(Optional.empty());
        assertCode(() -> service.file(OWNER, "marco", 404L), ErrorCode.EXPORT_NOT_FOUND);

        BlogExport pending = export(6L, blog);
        when(exportRepository.findById(6L)).thenReturn(Optional.of(pending));
        assertCode(() -> service.file(OWNER, "marco", 6L), ErrorCode.EXPORT_NOT_FOUND);

        BlogExport expired = export(7L, blog);
        expired.markReady("2026/10/old.zip", 3, NOW.minus(Duration.ofDays(8)), Duration.ofDays(7));
        when(exportRepository.findById(7L)).thenReturn(Optional.of(expired));
        assertCode(() -> service.file(OWNER, "marco", 7L), ErrorCode.EXPORT_NOT_FOUND);

        Blog otherBlog = TestEntities.blog(11L, owner, "other");
        BlogExport foreign = export(8L, otherBlog);
        foreign.markReady("2026/10/f.zip", 3, NOW, Duration.ofDays(7));
        Files.createDirectories(exportDir.resolve("2026/10"));
        Files.writeString(exportDir.resolve("2026/10/f.zip"), "PK!");
        when(exportRepository.findById(8L)).thenReturn(Optional.of(foreign));
        assertCode(() -> service.file(OWNER, "marco", 8L), ErrorCode.EXPORT_NOT_FOUND);

        BlogExport noFile = export(9L, blog);
        noFile.markReady("2026/10/missing.zip", 3, NOW, Duration.ofDays(7));
        when(exportRepository.findById(9L)).thenReturn(Optional.of(noFile));
        assertCode(() -> service.file(OWNER, "marco", 9L), ErrorCode.EXPORT_NOT_FOUND);
    }

    private BlogExport export(long id, Blog target) {
        BlogExport export = new BlogExport(target, owner);
        TestEntities.with(export, "id", id);
        TestEntities.with(export, "createdAt", NOW);
        return export;
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
