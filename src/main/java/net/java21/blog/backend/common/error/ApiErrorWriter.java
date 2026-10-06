package net.java21.blog.backend.common.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 컨트롤러 밖(보안 필터 등)에서 공통 틀의 오류 응답을 직접 쓴다. */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.failure(ApiResponse.Header.failure(
                errorCode.name(), message, null, RequestIdFilter.currentTraceId()));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
