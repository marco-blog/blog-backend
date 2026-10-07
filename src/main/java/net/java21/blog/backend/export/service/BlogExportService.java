package net.java21.blog.backend.export.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.dto.BlogExportResponse;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.repository.BlogExportRepository;
import net.java21.blog.backend.export.storage.ExportStorage;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 백업 요청·목록·내려받기(004 FR-145, research B14). 모두 블로그 주인만(001 {@link BlogAccess}: 볼 수 없는 블로그 404,
 * 주인이 아니면 403). 최근 24시간({@code blog.export.min-interval}) 안에 만든 실패하지 않은 백업이 있으면 409
 * {@code EXPORT_LIMIT_EXCEEDED}. 파일은 이 블로그의 READY이고 만료 전인 백업만(아니면 404 {@code EXPORT_NOT_FOUND}).
 */
@Service
public class BlogExportService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final BlogAccess blogAccess;
    private final BlogExportRepository exportRepository;
    private final BlogExportQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final ExportStorage storage;
    private final ExportProperties properties;
    private final BlogCalendar calendar;
    private final Clock clock;

    public BlogExportService(BlogAccess blogAccess, BlogExportRepository exportRepository,
            BlogExportQueryRepository queryRepository, UserRepository userRepository, ExportStorage storage,
            ExportProperties properties, BlogCalendar calendar, Clock clock) {
        this.blogAccess = blogAccess;
        this.exportRepository = exportRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.properties = properties;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** 내려받을 파일과 내려받을 때의 이름({@code {handle}-backup-{yyyyMMdd}.zip}, 완료일은 블로그 시간대 기준). */
    public record ExportFile(Resource resource, String filename, long size) {
    }

    /** 백업을 요청한다(PENDING). 생성 작업이 30초 안에 가져가 만든다. */
    @Transactional
    public BlogExportResponse request(long userId, String handle) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Instant now = clock.instant();
        if (queryRepository.existsActiveSince(blog.getId(), now.minus(properties.minInterval()))) {
            throw new BusinessException(ErrorCode.EXPORT_LIMIT_EXCEEDED,
                    "Export already requested within " + properties.minInterval() + ": " + handle);
        }
        BlogExport export = exportRepository.saveAndFlush(new BlogExport(blog, userRepository.getReferenceById(userId)));
        return BlogExportResponse.of(export);
    }

    /** 최근 백업 10건, 새것부터. */
    @Transactional(readOnly = true)
    public List<BlogExportResponse> list(long userId, String handle) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        return queryRepository.findRecent(blog.getId()).stream().map(BlogExportResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public ExportFile file(long userId, String handle, Long exportId) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Instant now = clock.instant();
        BlogExport export = exportRepository.findById(exportId)
                .filter(e -> e.getBlog().getId().equals(blog.getId()))
                .filter(e -> e.isDownloadable(now))
                .orElseThrow(() -> notFound(exportId));
        Resource resource = storage.open(export.getFilePath());
        if (!resource.exists()) {
            throw notFound(exportId);
        }
        LocalDate day = LocalDate.ofInstant(export.getCompletedAt(), calendar.zone());
        String filename = blog.getHandle() + "-backup-" + FILE_DATE.format(day) + ".zip";
        return new ExportFile(resource, filename, export.getFileSize() == null ? -1 : export.getFileSize());
    }

    private static BusinessException notFound(Long exportId) {
        return new BusinessException(ErrorCode.EXPORT_NOT_FOUND, "Export not found: " + exportId);
    }
}
