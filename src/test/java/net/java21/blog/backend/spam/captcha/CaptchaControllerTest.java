package net.java21.blog.backend.spam.captcha;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;

/** 005 T009: GET /api/v1/captcha/config. */
@WebMvcTest(CaptchaController.class)
@Import(WebMvcTestSupport.class)
class CaptchaControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private CaptchaProperties properties;

    @Test
    void turnstileExposesSiteKeyOnlyAndIsPubliclyCacheable() throws Exception {
        when(properties.provider()).thenReturn(CaptchaProperties.Provider.TURNSTILE);
        when(properties.siteKey()).thenReturn("site-key");
        when(properties.secretKey()).thenReturn("very-secret");

        mvc.perform(get("/api/v1/captcha/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(jsonPath("$.result.provider").value("turnstile"))
                .andExpect(jsonPath("$.result.siteKey").value("site-key"))
                .andExpect(content().string(not(containsString("very-secret"))));
    }

    @Test
    void testProviderHasNoSiteKey() throws Exception {
        when(properties.provider()).thenReturn(CaptchaProperties.Provider.TEST);
        when(properties.siteKey()).thenReturn("ignored");

        mvc.perform(get("/api/v1/captcha/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.provider").value("test"))
                .andExpect(jsonPath("$.result.siteKey").doesNotExist());
    }
}
