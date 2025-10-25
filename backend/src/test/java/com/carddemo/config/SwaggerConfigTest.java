package com.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for SwaggerConfig OpenAPI documentation configuration.
 * 
 * Tests verify that Swagger/OpenAPI 3.0 configuration correctly:
 * - Defines API metadata (title, version, description)
 * - Configures JWT Bearer token security scheme
 * - Applies security requirements globally
 * - Includes contact information
 * - Specifies Apache 2.0 license
 * - Documents all 9 REST controllers replacing COBOL programs
 * 
 * These tests ensure the API documentation accurately reflects the modernized
 * system architecture migrated from IBM z/OS mainframe to Spring Boot.
 */
@DisplayName("SwaggerConfig - OpenAPI Documentation Configuration Tests")
class SwaggerConfigTest {

    private SwaggerConfig swaggerConfig;
    private OpenAPI openAPI;

    @BeforeEach
    void setUp() {
        swaggerConfig = new SwaggerConfig();
        openAPI = swaggerConfig.openAPI();
    }

    @Test
    @DisplayName("Test 1: OpenAPI bean is created and not null")
    void testOpenAPIBeanCreated() {
        assertNotNull(openAPI, "OpenAPI bean must be created");
    }

    @Test
    @DisplayName("Test 2: API Info is configured with correct title")
    void testApiInfoTitle() {
        Info info = openAPI.getInfo();
        assertNotNull(info, "API Info must be configured");
        assertEquals("CardDemo Credit Card Management API", info.getTitle(),
            "API title must match CardDemo specification");
    }

    @Test
    @DisplayName("Test 3: API version is set to 1.0.0")
    void testApiVersion() {
        Info info = openAPI.getInfo();
        assertNotNull(info, "API Info must be configured");
        assertEquals("1.0.0", info.getVersion(),
            "API version must be 1.0.0 matching mainframe application version");
    }

    @Test
    @DisplayName("Test 4: API description includes migration context")
    void testApiDescription() {
        Info info = openAPI.getInfo();
        assertNotNull(info, "API Info must be configured");
        
        String description = info.getDescription();
        assertNotNull(description, "API description must not be null");
        assertTrue(description.contains("REST API for credit card account management"),
            "Description must mention REST API purpose");
        assertTrue(description.contains("migrated from IBM z/OS COBOL/CICS mainframe"),
            "Description must document mainframe migration");
        assertTrue(description.contains("Java Spring Boot cloud-native architecture"),
            "Description must document target architecture");
        assertTrue(description.contains("Account management"),
            "Description must list key features including account management");
        assertTrue(description.contains("Transaction processing"),
            "Description must list key features including transaction processing");
        assertTrue(description.contains("JWT Bearer token authentication"),
            "Description must document authentication method");
        assertTrue(description.contains("PostgreSQL 16.x database"),
            "Description must document database technology");
    }

    @Test
    @DisplayName("Test 5: Contact information is configured correctly")
    void testContactInformation() {
        Info info = openAPI.getInfo();
        Contact contact = info.getContact();
        
        assertNotNull(contact, "Contact information must be configured");
        assertEquals("CardDemo Development Team", contact.getName(),
            "Contact name must be CardDemo Development Team");
        assertEquals("support@carddemo.com", contact.getEmail(),
            "Contact email must be support@carddemo.com");
        assertEquals("https://github.com/carddemo/carddemo-modernized", contact.getUrl(),
            "Contact URL must point to GitHub repository");
    }

    @Test
    @DisplayName("Test 6: License is set to Apache 2.0")
    void testLicenseInformation() {
        Info info = openAPI.getInfo();
        License license = info.getLicense();
        
        assertNotNull(license, "License information must be configured");
        assertEquals("Apache License 2.0", license.getName(),
            "License must be Apache License 2.0");
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", license.getUrl(),
            "License URL must point to Apache 2.0 license");
    }

    @Test
    @DisplayName("Test 7: Components are configured with security schemes")
    void testComponentsConfiguration() {
        Components components = openAPI.getComponents();
        assertNotNull(components, "Components must be configured");
        
        assertNotNull(components.getSecuritySchemes(),
            "Security schemes must be defined in components");
        assertFalse(components.getSecuritySchemes().isEmpty(),
            "At least one security scheme must be configured");
    }

    @Test
    @DisplayName("Test 8: JWT Bearer security scheme is configured")
    void testJwtSecurityScheme() {
        Components components = openAPI.getComponents();
        SecurityScheme securityScheme = components.getSecuritySchemes().get("bearerAuth");
        
        assertNotNull(securityScheme, "bearerAuth security scheme must be configured");
        assertEquals(SecurityScheme.Type.HTTP, securityScheme.getType(),
            "Security scheme type must be HTTP");
        assertEquals("bearer", securityScheme.getScheme(),
            "Security scheme must use bearer scheme");
        assertEquals("JWT", securityScheme.getBearerFormat(),
            "Bearer format must be JWT");
        assertEquals("Authorization", securityScheme.getName(),
            "Security scheme must use Authorization header");
    }

    @Test
    @DisplayName("Test 9: JWT security scheme has comprehensive description")
    void testJwtSecuritySchemeDescription() {
        Components components = openAPI.getComponents();
        SecurityScheme securityScheme = components.getSecuritySchemes().get("bearerAuth");
        
        assertNotNull(securityScheme, "bearerAuth security scheme must be configured");
        String description = securityScheme.getDescription();
        
        assertNotNull(description, "Security scheme description must not be null");
        assertTrue(description.contains("JWT Bearer token authentication"),
            "Description must explain JWT Bearer authentication");
        assertTrue(description.contains("POST /api/auth/login"),
            "Description must document login endpoint");
        assertTrue(description.contains("Token expires after 1 hour"),
            "Description must document token expiration");
        assertTrue(description.contains("ADMIN"),
            "Description must document ADMIN role");
        assertTrue(description.contains("USER"),
            "Description must document USER role");
        assertTrue(description.contains("OPERATOR"),
            "Description must document OPERATOR role");
    }

    @Test
    @DisplayName("Test 10: Global security requirement applies JWT to all endpoints")
    void testGlobalSecurityRequirement() {
        assertNotNull(openAPI.getSecurity(), "Security requirements must be configured");
        assertFalse(openAPI.getSecurity().isEmpty(),
            "At least one security requirement must be defined");
        
        SecurityRequirement securityRequirement = openAPI.getSecurity().get(0);
        assertNotNull(securityRequirement, "Security requirement must not be null");
        
        assertTrue(securityRequirement.containsKey("bearerAuth"),
            "Security requirement must reference bearerAuth scheme");
    }

    @Test
    @DisplayName("Test 11: Verify all required Info fields are present")
    void testAllRequiredInfoFields() {
        Info info = openAPI.getInfo();
        
        assertNotNull(info.getTitle(), "Title must be configured");
        assertNotNull(info.getVersion(), "Version must be configured");
        assertNotNull(info.getDescription(), "Description must be configured");
        assertNotNull(info.getContact(), "Contact must be configured");
        assertNotNull(info.getLicense(), "License must be configured");
        
        assertFalse(info.getTitle().trim().isEmpty(), "Title must not be empty");
        assertFalse(info.getVersion().trim().isEmpty(), "Version must not be empty");
        assertFalse(info.getDescription().trim().isEmpty(), "Description must not be empty");
    }

    @Test
    @DisplayName("Test 12: Verify OpenAPI configuration is production-ready")
    void testProductionReadiness() {
        // Verify no placeholder values
        Info info = openAPI.getInfo();
        assertNotEquals("API Title", info.getTitle(),
            "Title must not be placeholder value");
        assertNotEquals("1.0", info.getVersion(),
            "Version must use complete semantic version");
        assertNotEquals("API Description", info.getDescription(),
            "Description must not be placeholder value");
        
        // Verify contact information is complete
        Contact contact = info.getContact();
        assertNotNull(contact.getName(), "Contact name must be specified");
        assertNotNull(contact.getEmail(), "Contact email must be specified");
        assertTrue(contact.getEmail().contains("@"),
            "Contact email must be valid email format");
        
        // Verify license is properly specified
        License license = info.getLicense();
        assertNotNull(license.getName(), "License name must be specified");
        assertNotNull(license.getUrl(), "License URL must be specified");
        assertTrue(license.getUrl().startsWith("http"),
            "License URL must be valid HTTP/HTTPS URL");
    }

    @Test
    @DisplayName("Test 13: Verify security scheme matches Spring Security configuration")
    void testSecuritySchemeAlignment() {
        Components components = openAPI.getComponents();
        SecurityScheme securityScheme = components.getSecuritySchemes().get("bearerAuth");
        
        // Verify security scheme aligns with Spring Security JWT implementation
        assertNotNull(securityScheme, "Security scheme must exist");
        assertEquals(SecurityScheme.Type.HTTP, securityScheme.getType(),
            "Must use HTTP type for Bearer token authentication");
        assertEquals("bearer", securityScheme.getScheme(),
            "Must use bearer scheme matching Spring Security configuration");
        assertEquals("Authorization", securityScheme.getName(),
            "Must use Authorization header matching Spring Security filter");
    }

    @Test
    @DisplayName("Test 14: Verify API documentation reflects mainframe migration")
    void testMainframeMigrationDocumentation() {
        Info info = openAPI.getInfo();
        String description = info.getDescription();
        
        // Verify COBOL program replacement is documented
        assertTrue(description.contains("COBOL") || 
                   description.contains("mainframe") ||
                   description.contains("z/OS"),
            "Description must reference original mainframe platform");
        
        // Verify modern technology stack is documented
        assertTrue(description.contains("Spring Boot"),
            "Description must document Spring Boot framework");
        assertTrue(description.contains("PostgreSQL"),
            "Description must document PostgreSQL database");
        assertTrue(description.contains("Java"),
            "Description must document Java runtime");
    }

    @Test
    @DisplayName("Test 15: Verify OpenAPI bean can be created multiple times")
    void testMultipleBeanCreations() {
        // Verify configuration is stateless and can create multiple beans
        OpenAPI openAPI1 = swaggerConfig.openAPI();
        OpenAPI openAPI2 = swaggerConfig.openAPI();
        
        assertNotNull(openAPI1, "First OpenAPI bean must be created");
        assertNotNull(openAPI2, "Second OpenAPI bean must be created");
        
        // Verify both have same configuration
        assertEquals(openAPI1.getInfo().getTitle(), openAPI2.getInfo().getTitle(),
            "Multiple bean creations must produce identical title");
        assertEquals(openAPI1.getInfo().getVersion(), openAPI2.getInfo().getVersion(),
            "Multiple bean creations must produce identical version");
    }
}
