package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.writing")
public class WritingProperties {

    private String defaultStyle = "formal";

    private List<String> supportedStyles = new ArrayList<>();

    private String defaultFormat = "markdown";

    private int maxContextMessages = 20;
}
