package com.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration class for OpenAPI 3.0 and Swagger UI documentation.
 * 
 * This configuration enables interactive API documentation for the CardDemo Credit Card
 * Management API, which has been migrated from mainframe COBOL/CICS/VSAM to a modern
 * Java Spring Boot microservices architecture. The API documentation is organized by
 * functional domains and supports JWT bearer token authentication for testing protected
 * endpoints.
 * 
 * Key features:
 * - Grouped API endpoints by functional domain (auth, accounts, cards, transactions, etc.)
 * - JWT bearer token authentication scheme for Swagger UI
 * - Comprehensive API metadata including version, description, and contact information
 * - Interactive "try-it-out" functionality for all endpoints
 * 
 * Access Swagger UI at: /swagger-ui.html
 * Access OpenAPI JSON at: /v3/api-docs
 * 
 * @author CardDemo Development Team
 * @version 1.0.0
 * @since 2024
 */
@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";
    private static final String API_VERSION = "1.0.0";
    private static final String API_TITLE = "CardDemo Credit Card Management API";
    private static final String API_DESCRIPTION = 
        "RESTful API for CardDemo Credit Card Management System. " +
        "This application has been modernized from a mainframe COBOL/CICS/VSAM architecture " +
        "to a cloud-native Java 21 Spring Boot microservices platform, maintaining complete " +
        "functional equivalence while leveraging modern technology stack including PostgreSQL, " +
        "Redis session management, and JWT authentication. " +
        "\n\n" +
        "The API provides comprehensive credit card management capabilities including:\n" +
        "- User authentication and authorization with JWT tokens\n" +
        "- Customer account management and inquiry\n" +
        "- Credit card operations (view, update, list)\n" +
        "- Transaction processing and history\n" +
        "- Bill payment processing\n" +
        "- Transaction reporting and analytics\n" +
        "- Administrative user management functions\n" +
        "\n" +
        "All endpoints (except authentication) require JWT bearer token authentication. " +
        "Use the 'Authorize' button to configure your bearer token for testing.";
    
    private static final String CONTACT_NAME = "CardDemo Support Team";
    private static final String CONTACT_EMAIL = "support@carddemo.com";
    private static final String CONTACT_URL = "https://github.com/carddemo/support";

    /**
     * Configures the main OpenAPI instance with API metadata and security schemes.
     * 
     * This bean defines:
     * - API information (title, version, description, contact details)
     * - JWT bearer token security scheme for authentication
     * - Global security requirements for all endpoints
     * 
     * The security scheme enables the "Authorize" button in Swagger UI, allowing users
     * to input their JWT token once and have it automatically included in all subsequent
     * API requests during testing.
     * 
     * @return OpenAPI instance configured with metadata and security
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title(API_TITLE)
                .version(API_VERSION)
                .description(API_DESCRIPTION)
                .contact(new Contact()
                    .name(CONTACT_NAME)
                    .email(CONTACT_EMAIL)
                    .url(CONTACT_URL)))
            .components(new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                    .name(SECURITY_SCHEME_NAME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Enter JWT bearer token obtained from /api/auth/login endpoint. " +
                        "The token will be automatically included in the Authorization header " +
                        "for all protected endpoints.")))
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * Groups authentication-related API endpoints.
     * 
     * Endpoints included:
     * - POST /api/auth/login - User authentication and JWT token generation
     * - POST /api/auth/logout - User logout and session termination
     * 
     * These endpoints replace the mainframe COSGN00C COBOL program (transaction CC00)
     * which handled signon functionality.
     * 
     * @return GroupedOpenApi for authentication endpoints
     */
    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
            .group("01-authentication")
            .pathsToMatch("/api/auth/**")
            .build();
    }

    /**
     * Groups account management API endpoints.
     * 
     * Endpoints included:
     * - GET /api/accounts/{id} - Retrieve account details and balance
     * - PUT /api/accounts/{id} - Update account information and credit limits
     * 
     * These endpoints replace the mainframe COACTVWC (transaction CAVW) and COACTUPC
     * (transaction CAUP) COBOL programs which handled account view and update operations.
     * 
     * @return GroupedOpenApi for account management endpoints
     */
    @Bean
    public GroupedOpenApi accountApi() {
        return GroupedOpenApi.builder()
            .group("02-accounts")
            .pathsToMatch("/api/accounts/**")
            .build();
    }

    /**
     * Groups card management API endpoints.
     * 
     * Endpoints included:
     * - GET /api/cards - List all cards with pagination (7 cards per page)
     * - GET /api/cards/{id} - Retrieve detailed card information
     * - PUT /api/cards/{id} - Update card status and expiration date
     * 
     * These endpoints replace the mainframe COCRDLIC (transaction CCLI), COCRDSLC
     * (transaction CCDL), and COCRDUPC (transaction CCUP) COBOL programs which handled
     * card list, detail view, and update operations.
     * 
     * @return GroupedOpenApi for card management endpoints
     */
    @Bean
    public GroupedOpenApi cardApi() {
        return GroupedOpenApi.builder()
            .group("03-cards")
            .pathsToMatch("/api/cards/**")
            .build();
    }

    /**
     * Groups transaction processing API endpoints.
     * 
     * Endpoints included:
     * - GET /api/transactions - List transactions with pagination (10 per page) and filtering
     * - GET /api/transactions/{id} - Retrieve detailed transaction information
     * - POST /api/transactions - Create and validate new transaction
     * 
     * These endpoints replace the mainframe COTRN00C (transaction CT00), COTRN01C
     * (transaction CT01), and COTRN02C (transaction CT02) COBOL programs which handled
     * transaction list, view, and add operations including card authorization and
     * balance updates.
     * 
     * @return GroupedOpenApi for transaction processing endpoints
     */
    @Bean
    public GroupedOpenApi transactionApi() {
        return GroupedOpenApi.builder()
            .group("04-transactions")
            .pathsToMatch("/api/transactions/**")
            .build();
    }

    /**
     * Groups billing and payment API endpoints.
     * 
     * Endpoints included:
     * - POST /api/billing/payment - Process bill payment with balance reduction
     * 
     * These endpoints replace the mainframe COBIL00C (transaction CB00) COBOL program
     * which handled bill payment processing with transaction recording and balance updates.
     * 
     * @return GroupedOpenApi for billing operations endpoints
     */
    @Bean
    public GroupedOpenApi billingApi() {
        return GroupedOpenApi.builder()
            .group("05-billing")
            .pathsToMatch("/api/billing/**")
            .build();
    }

    /**
     * Groups reporting and analytics API endpoints.
     * 
     * Endpoints included:
     * - GET /api/reports/transactions - Generate transaction reports with filtering
     * - Export capabilities to PDF/CSV formats
     * 
     * These endpoints replace the mainframe CORPT00C (transaction CR00) COBOL program
     * which handled transaction report generation with date range filtering and
     * category aggregation.
     * 
     * @return GroupedOpenApi for reporting endpoints
     */
    @Bean
    public GroupedOpenApi reportApi() {
        return GroupedOpenApi.builder()
            .group("06-reports")
            .pathsToMatch("/api/reports/**")
            .build();
    }

    /**
     * Groups administrative user management API endpoints.
     * 
     * Endpoints included:
     * - GET /api/admin/users - List all users with search and filtering
     * - POST /api/admin/users - Create new user with password hashing
     * - PUT /api/admin/users/{id} - Update user information and roles
     * - DELETE /api/admin/users/{id} - Soft delete user account
     * 
     * These endpoints replace the mainframe COUSR00C (transaction CU00), COUSR01C
     * (transaction CU01), COUSR02C (transaction CU02), and COUSR03C (transaction CU03)
     * COBOL programs which handled user list, creation, update, and deletion operations.
     * 
     * All endpoints in this group require ADMIN role authorization.
     * 
     * @return GroupedOpenApi for administrative endpoints
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
            .group("07-admin")
            .pathsToMatch("/api/admin/**")
            .build();
    }

    /**
     * Groups menu navigation API endpoints.
     * 
     * Endpoints included:
     * - GET /api/menu - Retrieve main menu options based on user role
     * - GET /api/admin/menu - Retrieve administrative menu (admin users only)
     * 
     * These endpoints replace the mainframe COMEN01C (transaction CM00) and COADM01C
     * (transaction CA00) COBOL programs which handled main menu and admin menu navigation
     * with role-based menu item display.
     * 
     * @return GroupedOpenApi for menu navigation endpoints
     */
    @Bean
    public GroupedOpenApi menuApi() {
        return GroupedOpenApi.builder()
            .group("08-menu")
            .pathsToMatch("/api/menu", "/api/admin/menu")
            .build();
    }
}
