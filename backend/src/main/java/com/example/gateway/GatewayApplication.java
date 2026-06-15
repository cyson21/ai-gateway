package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Gateway entry point. Reactive (WebFlux) so the OpenAI-compatible endpoint can
 * stream provider responses. Pipeline wiring (quota, cache, router, providers) is added
 * in the Quota and Cost slice; this foundation provides the application shell.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
