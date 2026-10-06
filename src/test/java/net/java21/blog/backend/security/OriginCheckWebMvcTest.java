package net.java21.blog.backend.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.time.TimeConfig;
import net.java21.blog.backend.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** SecurityConfig에 등록된 Origin 검사(R3·R27). 허용 목록은 application-test.yml(http://localhost:5173). */
@WebMvcTest(controllers = OriginCheckTestController.class)
@Import({SecurityConfig.class, ApiErrorWriter.class, TimeConfig.class})
class OriginCheckWebMvcTest {

    private static final String ALLOWED = "http://localhost:5173";

    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser
    void allowedOriginReachesController() throws Exception {
        mvc.perform(post("/api/v1/origin-test").header("Origin", ALLOWED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("created"));
    }

    @Test
    @WithMockUser
    void foreignOriginGets403InCommonFormat() throws Exception {
        mvc.perform(delete("/api/v1/origin-test").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.header.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser
    void missingOriginIsRejected() throws Exception {
        mvc.perform(post("/api/v1/origin-test").header("Referer", ALLOWED + "/write"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    void originIsCheckedBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/v1/origin-test").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    @WithMockUser
    void safeMethodNeedsNoOrigin() throws Exception {
        mvc.perform(get("/api/v1/origin-test"))
                .andExpect(status().isOk());
    }
}
