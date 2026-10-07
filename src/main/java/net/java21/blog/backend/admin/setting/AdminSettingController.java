package net.java21.blog.backend.admin.setting;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.setting.dto.SettingResponse;
import net.java21.blog.backend.admin.setting.dto.SettingValueRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 설정 API(003 contracts/api.md "관리자: 운영 설정"). 키에 점이 들어간다(예: {@code portal.score-weights}). */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingController {

    private final AdminSettingService service;

    public AdminSettingController(AdminSettingService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<SettingResponse>> list(@RequestParam(required = false) String prefix) {
        return ApiResponse.ok(service.list(prefix));
    }

    @PutMapping("/{key}")
    ApiResponse<SettingResponse> set(@CurrentUser AuthUser admin, @PathVariable String key,
            @RequestBody SettingValueRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.set(admin.userId(), key, request.value(), http.getRemoteAddr()));
    }

    @DeleteMapping("/{key}")
    ApiResponse<SettingResponse> reset(@CurrentUser AuthUser admin, @PathVariable String key,
            HttpServletRequest http) {
        return ApiResponse.ok(service.reset(admin.userId(), key, http.getRemoteAddr()));
    }
}
