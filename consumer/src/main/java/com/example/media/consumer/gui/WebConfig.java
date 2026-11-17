package com.example.media.consumer.gui;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for Media Upload GUI.
 * Configures static resources. Spring Boot auto-configures ResourceRegionHttpMessageConverter.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static assets (HTML/CSS/JS)
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
