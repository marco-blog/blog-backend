package net.java21.blog.backend.report.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.report.dto.RightsRequestRequest;
import net.java21.blog.backend.report.service.RightsRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 권리 침해 신고(005 contracts/api.md "신고"). 비로그인 허용, 202 + {@code result: null}(조회 수단이 없어 접수 번호를 주지 않는다). */
@RestController
public class RightsRequestController {

    private final RightsRequestService rightsRequestService;

    public RightsRequestController(RightsRequestService rightsRequestService) {
        this.rightsRequestService = rightsRequestService;
    }

    /** 요청 IP는 {@code ClientAddressFilter}가 믿는 프록시의 X-Forwarded-For로 정한 방문자 주소다. */
    @PostMapping("/api/v1/rights-requests")
    ResponseEntity<ApiResponse<Void>> submit(@Valid @RequestBody RightsRequestRequest request,
            HttpServletRequest httpRequest) {
        rightsRequestService.submit(request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok());
    }
}
