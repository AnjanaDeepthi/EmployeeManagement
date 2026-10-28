package com.restaurant.labor.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CallerInterceptor callerInterceptor;

    public WebConfig(CallerInterceptor callerInterceptor) {
        this.callerInterceptor = callerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(callerInterceptor).addPathPatterns("/v1/**");
    }
}
