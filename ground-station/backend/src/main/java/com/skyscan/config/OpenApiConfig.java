package com.skyscan.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI api() {
        return new OpenAPI().info(new Info()
                .title("SkyScan API")
                .version("0.1.0")
                .description("Drone point-cloud scanning service: sites, missions, live status, deliverables."));
    }
}
