package net.java21.blog.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.time.TimeConfig;
import net.java21.blog.backend.config.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * {@code @WebMvcTest} 공통 구성. 컨트롤러 테스트는 이것 하나만 가져온다.
 * <pre>{@code
 * @WebMvcTest(PostController.class)
 * @Import(WebMvcTestSupport.class)
 * class PostControllerTest { @MockitoBean PostService postService; @Autowired AuthCookies authCookies; ... }
 * }</pre>
 * <ul>
 *   <li>실제 보안 설정({@link SecurityConfig}: Origin 검사, {@code access_token} 쿠키 인증, 공개 경로)과
 *       그 의존 빈({@link ApiErrorWriter}, UTC Clock {@link TimeConfig})</li>
 *   <li>{@link AuthCookies}: 로그인 쿠키 생성</li>
 *   <li>모든 요청에 허용된 {@code Origin}(application-test.yml)을 실어 상태 변경 요청이 Origin 검사를 통과한다.
 *       Origin 검사 자체를 확인하는 테스트는 이 구성을 쓰지 않는다.</li>
 * </ul>
 * {@code GlobalExceptionHandler}(@RestControllerAdvice)와 {@code CurrentUserArgumentResolver}를 등록하는
 * {@code WebMvcConfig}(WebMvcConfigurer)는 {@code @WebMvcTest}가 스스로 찾아 올린다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, ApiErrorWriter.class, TimeConfig.class, AuthCookies.class})
public class WebMvcTestSupport {

    public static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Bean
    MockMvcBuilderCustomizer sameOriginRequests() {
        return builder -> builder.defaultRequest(get("/").header("Origin", ALLOWED_ORIGIN));
    }
}
