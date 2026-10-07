package net.java21.blog.backend.admin.info;

import java.util.List;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.admin.info.dto.ServiceSettingsResponse;
import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.ReservedHandles;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.legal.LegalProperties;
import net.java21.blog.backend.media.MediaProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘솔의 읽기 전용 정보(006 contracts/api.md "예약어·서비스 설정", research A5): 예약어(코드 상수 {@link ReservedHandles#NAMES})와
 * 운영 설정 프로퍼티 값. 바꾸는 API는 없다(프로퍼티로만 바꾼다).
 */
@RestController
public class AdminInfoController {

    private final LegalProperties legalProperties;
    private final BlogsProperties blogsProperties;
    private final MediaProperties mediaProperties;
    private final AdminProperties adminProperties;

    public AdminInfoController(LegalProperties legalProperties, BlogsProperties blogsProperties,
            MediaProperties mediaProperties, AdminProperties adminProperties) {
        this.legalProperties = legalProperties;
        this.blogsProperties = blogsProperties;
        this.mediaProperties = mediaProperties;
        this.adminProperties = adminProperties;
    }

    /** 예약어를 정렬해서(알파벳순, 코드 상수는 {@code Set}이라 순서가 없다). */
    @GetMapping("/api/v1/admin/reserved-handles")
    ApiResponse<List<String>> reservedHandles() {
        return ApiResponse.ok(ReservedHandles.NAMES.stream().sorted().toList());
    }

    @GetMapping("/api/v1/admin/service-settings")
    ApiResponse<ServiceSettingsResponse> serviceSettings() {
        return ApiResponse.ok(new ServiceSettingsResponse(legalProperties.termsVersion(),
                new ServiceSettingsResponse.Blogs(blogsProperties.defaultMaxPerMember()),
                new ServiceSettingsResponse.Media(mediaProperties.maxSize().toBytes(), mediaProperties.maxPixels(),
                        mediaProperties.tempQuota().toBytes(), mediaProperties.tempTtl().toString(),
                        List.copyOf(mediaProperties.allowedTypes())),
                new ServiceSettingsResponse.Admin(adminProperties.auditRetention().toString(),
                        adminProperties.dashboardCacheTtl().toString())));
    }
}
