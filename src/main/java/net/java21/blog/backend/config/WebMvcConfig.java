package net.java21.blog.backend.config;

import java.util.List;

import net.java21.blog.backend.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring MVC 설정: {@code @CurrentUser AuthUser} 파라미터 해석기 등록. {@code @WebMvcTest}도 이 설정을 올린다. */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
