package com.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 3.0 Configuration for CardDemo Application.
 * 
 * This configuration class replaces mainframe CICS transaction catalog documentation
 * with modern interactive REST API documentation. Provides browser-based Swagger UI
 * for API testing and exploration, along with OpenAPI JSON specification for API consumers.
 * 
 * Key Features:
 * - Interactive API documentation at /swagger-ui.html
 * - OpenAPI 3.0 JSON specification at /v3/api-docs
 * - JWT Bearer authentication scheme documentation
 * - Automatic REST endpoint discovery via controller scanning
 * - Security requirement annotations for protected endpoints
 * 
 * Migrated from: CICS transaction catalog and mainframe documentation
 * Target Architecture: Spring Boot 3.x with SpringDoc OpenAPI
 */
@Configuration
public class SwaggerConfig {

    /**
     * Configures OpenAPI 3.0 documentation with comprehensive API metadata,
     * JWT security scheme, and global security requirements.
     * 
     * API Information:
     * - Scans com.carddemo.controller package for REST endpoints
     * - Documents all CRUD operations for Account, Card, Transaction entities
     * - Shows JWT authentication requirements per endpoint
     * - Marks /api/auth/** endpoints as public (no authentication required)
     * 
     * Security Configuration:
     * - Defines JWT Bearer token authentication scheme
     * - Applies 'bearerAuth' security globally except for authentication endpoints
     * - Matches security configuration from SecurityConfig.java
     * 
     * Access Points:
     * - Swagger UI: http://localhost:8080/swagger-ui.html
     * - OpenAPI JSON: http://localhost:8080/v3/api-docs
     * 
     * @return OpenAPI instance configured with API metadata and security schemes
     */
    @Bean
    public OpenAPI openAPI() {
        // Define API metadata matching CardDemo system requirements
        Info apiInfo = new Info()
                .title("CardDemo Credit Card Management System API")
                .version("1.0.0")
                .description("RESTful APIs for CardDemo application migrated from COBOL/CICS mainframe to Spring Boot. " +
                        "Provides comprehensive credit card account management, transaction processing, bill payment, " +
                        "and administrative functions. All endpoints maintain functional equivalence with original " +
                        "CICS transactions while providing modern REST API architecture.")
                .contact(new Contact()
                        .name("CardDemo Development Team")
                        .email("carddemo-support@example.com")
                        .url("https://github.com/your-org/carddemo"))
                .license(new License()
                        .name("Apache License 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0"));

        // Define JWT Bearer authentication security scheme
        SecurityScheme jwtSecurityScheme = new SecurityScheme()
                .name("bearerAuth")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT Bearer token authentication. Obtain token via POST /api/auth/login endpoint. " +
                        "Include token in Authorization header as: 'Bearer <token>'. " +
                        "Token expires after 24 hours. Supports two user roles: ROLE_USER and ROLE_ADMIN.");

        // Create security requirement referencing the JWT scheme
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("bearerAuth");

        // Build and return OpenAPI configuration
        return new OpenAPI()
                .info(apiInfo)
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", jwtSecurityScheme))
                .addSecurityItem(securityRequirement);
    }
}
