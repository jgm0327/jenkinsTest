package com.ssafy.a303.monitoringtest.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties()
public class MyConfig {
    private final String username;
    private final String password;
    private final String jdbcUrl;
}
