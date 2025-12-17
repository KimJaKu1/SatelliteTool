package org.sat_tool.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
//public class SwaggerConfig implements WebMvcConfigurer {
public class SwaggerConfig{
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("v1-definition")
                .pathsToMatch("/**")
                .build();
    }
    @Bean
    public OpenAPI openAPI() {
        // 보안 스키마/요구사항 없이 Info만 설정
        return new OpenAPI()
                .info(new Info()
                        .title("Web_CSG_Server API")
                        .description("Web_CSG_Server API 명세서")
                        .version("v0.0.1"));
    }
}
