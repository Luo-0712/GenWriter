package com.example.genwriter.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CharacterEncodingFilter;

/**
 * 编码配置类
 * 确保请求和响应使用UTF-8编码，正确处理中文字符
 */
@Configuration
public class EncodingConfig {

    /**
     * 字符编码过滤器
     * 强制所有请求和响应使用UTF-8编码
     */
    @Bean
    public FilterRegistrationBean<CharacterEncodingFilter> customCharacterEncodingFilter() {
        FilterRegistrationBean<CharacterEncodingFilter> filter = new FilterRegistrationBean<>();
        CharacterEncodingFilter encodingFilter = new CharacterEncodingFilter();
        encodingFilter.setEncoding("UTF-8");
        encodingFilter.setForceEncoding(true);
        encodingFilter.setForceRequestEncoding(true);
        encodingFilter.setForceResponseEncoding(true);
        filter.setFilter(encodingFilter);
        filter.addUrlPatterns("/*");
        filter.setOrder(1);
        return filter;
    }
}
