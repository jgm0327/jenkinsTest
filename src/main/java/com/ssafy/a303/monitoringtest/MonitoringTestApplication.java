package com.ssafy.a303.monitoringtest;

import com.ssafy.a303.monitoringtest.config.MyConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(MyConfig.class)
public class MonitoringTestApplication {

    private final MyConfig myConfig;

    public MonitoringTestApplication(MyConfig myConfig) {
        this.myConfig = myConfig;
    }

    public static void main(String[] args) {
        SpringApplication.run(MonitoringTestApplication.class, args);
    }

    @PostConstruct
    public void init() {
        log.info("username: {}", myConfig.getUsername());
        log.info("password: {}", myConfig.getPassword());
        log.info("jdbcUrl: {}", myConfig.getJdbcUrl());
    }

}
