package com.ssafy.a303.monitoringtest.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class HelloController {
    private final MeterRegistry meterRegistry;
    private final Counter counter;

    public HelloController(MeterRegistry registry) {
        this.meterRegistry = registry;
        this.counter = Counter.builder("api_hello_requests_total")
                .description("hello endpoint calls")
                .register(registry);
    }

    @GetMapping("/hello")
    public String hello() {
        log.info("hello api");
        meterRegistry.counter("api_hello_requests_total").increment();
        counter.increment();
        return "hello";
    }
}
