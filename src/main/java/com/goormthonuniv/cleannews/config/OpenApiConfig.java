package com.goormthonuniv.cleannews.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CleanNews Verification API")
                        .description("SNS/뉴스 피드 사실검증 해커톤용 API")
                        .version("v0.1.0")
                        .contact(new Contact().name("CleanNews").email("hack@clean.news")))
                .externalDocs(new ExternalDocumentation().description("Swagger UI").url("/swagger-ui.html"));
    }
}