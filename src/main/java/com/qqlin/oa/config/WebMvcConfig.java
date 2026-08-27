package com.qqlin.oa.config;

import com.qqlin.oa.interceptor.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(jwtAuthenticationInterceptor).addPathPatterns(
                "/auth/logout",
                "/users",
                "/users/**",
                "/departments",
                "/departments/**",
                "/leaves",
                "/leaves/**");
    }
}
