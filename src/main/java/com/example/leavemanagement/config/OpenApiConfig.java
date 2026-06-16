package com.example.leavemanagement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger metadata. The spec itself is generated from the controllers
 * and DTOs by springdoc; this bean only supplies the document-level info.
 *
 * <p>Once the app is running:
 *
 * <ul>
 *   <li>Swagger UI — http://localhost:8080/swagger-ui.html
 *   <li>OpenAPI JSON — http://localhost:8080/v3/api-docs
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI leaveManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Leave Management — Public Holiday Calendar API")
                        .description("Upload public holidays for a year, read calendar data "
                                + "(weekends + holidays), and apply for leave with chargeable "
                                + "working days computed by excluding weekends and public holidays.")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact().name("Leave Management"))
                        .license(new License().name("Apache 2.0")))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local")));
    }
}
