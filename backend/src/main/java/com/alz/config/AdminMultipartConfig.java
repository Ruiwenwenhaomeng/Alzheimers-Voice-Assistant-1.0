// AdminMultipartConfig.java
package com.alz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.MultipartConfigFactory;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.util.unit.DataSize;

import java.nio.file.Paths;

@Configuration
public class AdminMultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();

        // 单个文件最大 200MB
        factory.setMaxFileSize(DataSize.parse("200MB"));

        // 总上传大小最大 500MB
        factory.setMaxRequestSize(DataSize.parse("500MB"));

        return factory.createMultipartConfig();
    }
}
