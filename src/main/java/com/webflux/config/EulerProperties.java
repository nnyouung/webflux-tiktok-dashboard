package com.webflux.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "euler")
public class EulerProperties {
    private String apiKey;
    private String wsUrl;
}
