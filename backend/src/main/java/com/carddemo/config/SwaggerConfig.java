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
 * Spring configuration class for OpenAPI 3.0/Swagger documentation.
 * 
 * Provides comprehensive REST API documentation for the CardDemo credit card management system
 * migrated from IBM z/OS COBOL/CICS mainframe to Java Spring Boot cloud-native architecture.
 * 
 * Replaces COBOL/CICS program documentation with interactive Swagger UI accessible at /swagger-ui.html.
 * OpenAPI JSON specification available at /v3/api-docs.
 * 
 * Documents all 9 REST controllers:
 * - AuthController: User authentication and session management (replaces COSGN00C.cbl)
 * - MenuController: Main menu and admin menu navigation (replaces COMEN01C.cbl, COADM01C.cbl)
 * - AccountController: Account operations - view, update, create (replaces COACTUPC.cbl, COACTVWC.cbl)
 * - CardController: Card management - list, update, create (replaces COCRDLIC.cbl, COCRDUPC.cbl)
 * - TransactionController: Transaction processing - list, view, post (replaces COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl)
 * - BillingController: Billing and statement generation (replaces COBIL00C.cbl)
 * - ReportController: Report generation menu (replaces CORPT00C.cbl)
 * - UserController: User administration CRUD operations (replaces COUSR00C.cbl through COUSR03C.cbl)
 * - HealthCheckController: System health monitoring
 * 
 * Security:
 * Configures JWT Bearer token authentication scheme for all API endpoints.
 * Users must obtain JWT token via POST /api/auth/login and include in Authorization header.
 * 
 * Configuration follows MINIMAL CHANGE CLAUSE: implements only API documentation infrastructure
 * required for modernization, no business logic modifications.
 * 
 * @author CardDemo Development Team
 * @since 1.0.0
 */
@Configuration
public class SwaggerConfig {

    /**
     * Configures OpenAPI 3.0 documentation bean.
     * 
     * Defines API metadata, security schemes, and global security requirements.
     * Automatically discovered by springdoc-openapi library (springdoc-openapi-starter-webmvc-ui 2.6.0)
     * to generate interactive Swagger UI documentation at /swagger-ui.html and OpenAPI JSON
     * specification at /v3/api-docs.
     * 
     * Security Configuration:
     * - Defines JWT Bearer token security scheme named "bearerAuth"
     * - Applies JWT authentication globally to all API endpoints by default
     * - Public endpoints (/api/auth/login, /api/health, /swagger-ui/**, /v3/api-docs/**) 
     *   excluded via Spring Security configuration in SecurityConfig.java
     * 
     * API Metadata:
     * - Title: CardDemo Credit Card Management API
     * - Version: 1.0.0 (matches mainframe application version)
     * - Description: Comprehensive REST API for credit card management operations
     * - Contact: CardDemo Development Team
     * - License: Apache License 2.0
     * 
     * Swagger UI Features:
     * - Enabled by default at /swagger-ui.html
     * - Try-it-out functionality enabled for interactive API testing
     * - Authorization button for JWT token input
     * - Operations sorted alphabetically (operationsSorter=alpha via application.yml)
     * - Request/response examples derived from BMS map field definitions
     * 
     * @return OpenAPI configuration object with complete API documentation metadata
     */
    @Bean
    public OpenAPI openAPI() {
        // Define JWT Bearer token security scheme name
        // This name is referenced in SecurityRequirement to apply authentication globally
        final String securitySchemeName = "bearerAuth";
        
        return new OpenAPI()
            .info(createApiInfo())
            .components(new Components()
                .addSecuritySchemes(securitySchemeName, createSecurityScheme()))
            .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }

    /**
     * Creates API metadata information object.
     * 
     * Provides title, version, description, contact information, and license details
     * displayed in Swagger UI header section.
     * 
     * Migration Context:
     * - Replaces 26 COBOL program documentation (COSGN00C.cbl through COUSR03C.cbl)
     * - Replaces 17 BMS map documentation (COSGN00.bms through COUSR03.bms)
     * - Documents REST API endpoints replacing CICS EXEC commands
     * 
     * @return Info object with comprehensive API metadata
     */
    private Info createApiInfo() {
        return new Info()
            .title("CardDemo Credit Card Management API")
            .version("1.0.0")
            .description("REST API for credit card account management, card operations, transaction processing, " +
                "and billing - migrated from IBM z/OS COBOL/CICS mainframe to Java Spring Boot cloud-native " +
                "architecture.\n\n" +
                "**Key Features:**\n" +
                "- Account management: Create, view, and update credit card accounts\n" +
                "- Card operations: Issue, activate, and manage credit cards\n" +
                "- Transaction processing: Post transactions, view history, generate statements\n" +
                "- Billing: Calculate balances, generate billing statements\n" +
                "- User administration: Manage system users and permissions\n" +
                "- Reporting: Generate operational reports\n\n" +
                "**Technology Stack:**\n" +
                "- Java 21 LTS with Spring Boot 3.4.5\n" +
                "- PostgreSQL 16.x database (replacing VSAM)\n" +
                "- JWT Bearer token authentication (replacing RACF)\n" +
                "- RESTful JSON API (replacing 3270 terminal screens)\n\n" +
                "**Security:**\n" +
                "Most endpoints require JWT authentication. Obtain token via POST /api/auth/login. " +
                "Public endpoints: /api/auth/login, /api/health, /swagger-ui/**, /v3/api-docs/**\n\n" +
                "**Functional Equivalence:**\n" +
                "Maintains 100% functional equivalence with original COBOL programs while providing " +
                "modern RESTful interfaces. All business logic, validation rules, and calculation " +
                "methods preserved exactly as implemented in mainframe system.")
            .contact(createContactInfo())
            .license(createLicenseInfo());
    }

    /**
     * Creates contact information for API documentation.
     * 
     * Provides maintainer contact details displayed in Swagger UI footer.
     * 
     * @return Contact object with development team information
     */
    private Contact createContactInfo() {
        return new Contact()
            .name("CardDemo Development Team")
            .email("support@carddemo.com")
            .url("https://github.com/carddemo/carddemo-modernized");
    }

    /**
     * Creates license information for API documentation.
     * 
     * Specifies Apache License 2.0 matching project LICENSE file.
     * Provides legal clarity for API consumers and maintains consistency
     * with project licensing requirements.
     * 
     * @return License object with Apache 2.0 license details
     */
    private License createLicenseInfo() {
        return new License()
            .name("Apache License 2.0")
            .url("https://www.apache.org/licenses/LICENSE-2.0");
    }

    /**
     * Creates JWT Bearer token security scheme definition.
     * 
     * Configures HTTP Bearer authentication with JWT token format for Swagger UI.
     * Enables the "Authorize" button in Swagger UI interface for users to input
     * JWT tokens obtained from POST /api/auth/login endpoint.
     * 
     * Security Scheme Details:
     * - Type: HTTP
     * - Scheme: bearer
     * - Bearer Format: JWT
     * - Header Name: Authorization
     * - Header Format: "Bearer <jwt_token>"
     * 
     * JWT Token Requirements:
     * - Obtained via POST /api/auth/login with valid userId and password
     * - Token expires after 1 hour (configurable in application.yml)
     * - Must be included in Authorization header for protected endpoints
     * - Format: "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
     * 
     * Replaces RACF Authentication:
     * - RACF user profiles → UserSecurity entity in PostgreSQL
     * - RACF resource profiles → Spring Security @PreAuthorize role checks
     * - RACF user types (SEC-USR-TYPE: 'A'=ADMIN, 'U'=USER, 'O'=OPERATOR) 
     *   → Spring Security GrantedAuthority roles
     * 
     * Role-Based Access Control:
     * - ROLE_ADMIN: Administrative functions (/api/users/**, /api/admin/**)
     * - ROLE_USER: Regular operations (/api/accounts/**, /api/cards/**, /api/transactions/**)
     * - ROLE_OPERATOR: Batch job control (/api/batch/**)
     * 
     * @return SecurityScheme object defining JWT Bearer authentication
     */
    private SecurityScheme createSecurityScheme() {
        return new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .name("Authorization")
            .description("JWT Bearer token authentication for secure API access.\n\n" +
                "**How to Authenticate:**\n" +
                "1. Call POST /api/auth/login with valid credentials (userId and password)\n" +
                "2. Extract JWT token from response body (token field)\n" +
                "3. Click 'Authorize' button in Swagger UI\n" +
                "4. Enter token in format: Bearer <your_token_here>\n" +
                "5. Click 'Authorize' to apply token to all subsequent API calls\n\n" +
                "**Token Details:**\n" +
                "- Token expires after 1 hour\n" +
                "- Token contains user identity and role information\n" +
                "- Token must be included in Authorization header for protected endpoints\n" +
                "- Invalid or expired tokens return 401 Unauthorized\n\n" +
                "**User Roles:**\n" +
                "- ADMIN: Full system access including user administration\n" +
                "- USER: Standard operations (accounts, cards, transactions)\n" +
                "- OPERATOR: Batch job monitoring and control\n\n" +
                "**Security Note:**\n" +
                "Never share JWT tokens. Tokens grant full access to API operations " +
                "permitted by the user's role. Keep tokens secure and rotate regularly.");
    }
}
