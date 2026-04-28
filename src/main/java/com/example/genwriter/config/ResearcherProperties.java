package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.researcher")
public class ResearcherProperties {

    private int maxSearchQueries = 5;

    private int maxSearchResultsPerQuery = 5;

    private boolean enabled = true;
}
