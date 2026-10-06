package net.java21.blog.backend.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** 상태 변경 요청이 Origin 검사(OriginCheckFilter)를 통과하도록 모든 MockMvc 요청에 허용된 Origin을 싣는다. */
@TestConfiguration(proxyBeanMethods = false)
class SameOriginRequests {

    @Bean
    MockMvcBuilderCustomizer sameOrigin() {
        return builder -> builder.defaultRequest(get("/").header("Origin", "http://localhost:5173"));
    }
}
