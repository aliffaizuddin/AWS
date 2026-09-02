package dev.cloudlite.s3.iamclient;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class AuthWebMvcConfigurer implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebMvcConfigurer(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).excludePathPatterns("/healthz", "/error", "/actuator/**");
    }
}
