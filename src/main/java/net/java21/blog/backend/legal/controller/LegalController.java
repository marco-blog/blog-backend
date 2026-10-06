package net.java21.blog.backend.legal.controller;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.legal.LegalDocumentType;
import net.java21.blog.backend.legal.dto.LegalDocumentResponse;
import net.java21.blog.backend.legal.service.LegalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 약관·개인정보처리방침(contracts/api.md legal 절, FR-137·155). 비로그인 허용({@code SecurityConfig.PUBLIC_GET}). */
@RestController
@RequestMapping("/api/v1/legal")
public class LegalController {

    private final LegalService legalService;

    public LegalController(LegalService legalService) {
        this.legalService = legalService;
    }

    @GetMapping("/terms")
    ApiResponse<LegalDocumentResponse> terms(@RequestParam(required = false) String lang) {
        return ApiResponse.ok(legalService.document(LegalDocumentType.TERMS, lang));
    }

    @GetMapping("/privacy")
    ApiResponse<LegalDocumentResponse> privacy(@RequestParam(required = false) String lang) {
        return ApiResponse.ok(legalService.document(LegalDocumentType.PRIVACY, lang));
    }
}
