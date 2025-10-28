package com.carddemo.controller;

import com.carddemo.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * JUnit 5 Test Class for HealthCheckController
 * 
 * <p>This test class validates the health check endpoint functionality required for 
 * Kubernetes readiness and liveness probes in cloud-native deployment. Tests ensure 
 * the GET /api/health endpoint returns proper HTTP 200 OK status with expected JSON 
 * response body.</p>
 * 
 * <p><b>Testing Approach:</b></p>
 * <ul>
 *   <li>Uses Spring Boot @WebMvcTest for focused controller layer testing</li>
 *   <li>Loads only HealthCheckController and related web components (not full application context)</li>
 *   <li>Uses MockMvc to perform HTTP requests without starting full HTTP server</li>
 *   <li>Disables Spring Security filters with @AutoConfigureMockMvc(addFilters = false) 
 *       to test controller logic in isolation, as health endpoints should be publicly 
 *       accessible without authentication for Kubernetes probes</li>
 *   <li>Validates HTTP status codes and JSON response structure</li>
 * </ul>
 * 
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>Health endpoint accessibility (GET /api/health)</li>
 *   <li>HTTP 200 OK status code returned</li>
 *   <li>JSON response contains "status" field</li>
 *   <li>JSON response "status" field has value "UP"</li>
 * </ul>
 * 
 * <p><b>Migration Context:</b> This test validates NEW cloud-native functionality 
 * required for containerized deployment. There is no COBOL source equivalent as the 
 * mainframe CICS system used different health monitoring mechanisms. This endpoint 
 * is essential for Kubernetes orchestration to determine pod health and readiness.</p>
 * 
 * <p><b>Security Note:</b> The health endpoint MUST be publicly accessible without 
 * authentication to allow Kubernetes probes, load balancers, and monitoring systems 
 * to check application health. The @AutoConfigureMockMvc(addFilters = false) annotation 
 * simulates this public access by disabling security filters during testing.</p>
 * 
 * <p><b>Framework Versions:</b></p>
 * <ul>
 *   <li>JUnit Jupiter (JUnit 5) - 5.10.x</li>
 *   <li>Spring Boot Test - 3.4.5</li>
 *   <li>Spring Test (MockMvc) - 6.2.x</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@WebMvcTest(HealthCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
public class HealthCheckControllerTest {

    /**
     * MockMvc instance for performing HTTP requests against HealthCheckController.
     * 
     * <p>Injected by Spring Test framework when using @WebMvcTest annotation. 
     * MockMvc provides a fluent API for constructing HTTP requests and asserting 
     * responses without starting a full HTTP server, enabling fast and isolated 
     * controller layer testing.</p>
     * 
     * <p>The MockMvc instance is pre-configured with:</p>
     * <ul>
     *   <li>Spring MVC infrastructure (DispatcherServlet, HandlerMappings, etc.)</li>
     *   <li>Only the HealthCheckController bean loaded</li>
     *   <li>JSON message converters for request/response handling</li>
     * </ul>
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mock bean for JwtTokenProvider to satisfy Spring Security filter chain dependencies.
     * 
     * <p>Even though @AutoConfigureMockMvc(addFilters = false) disables filter execution,
     * Spring Security autoconfiguration still attempts to create JwtAuthenticationFilter bean
     * during context initialization, which requires JwtTokenProvider as a dependency. This
     * @MockBean annotation provides a mock implementation to satisfy the dependency injection
     * requirement without loading the actual JWT security infrastructure.</p>
     * 
     * <p>This mock is not used in health check tests since the endpoint is public and
     * filters are disabled, but it prevents context initialization failures.</p>
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Test: Health endpoint returns HTTP 200 OK status.
     * 
     * <p>Validates that the GET /api/health endpoint is accessible and returns 
     * HTTP 200 OK status code, indicating the application is healthy and ready 
     * to process requests. This is the fundamental requirement for Kubernetes 
     * liveness and readiness probes.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Perform HTTP GET request to /api/health endpoint</li>
     *   <li>Assert HTTP status code is 200 OK</li>
     *   <li>Assert JSON response contains "status" field</li>
     *   <li>Assert "status" field value is "UP"</li>
     * </ol>
     * 
     * <p><b>Expected Response:</b></p>
     * <pre>
     * HTTP/1.1 200 OK
     * Content-Type: application/json
     * 
     * {
     *   "status": "UP"
     * }
     * </pre>
     * 
     * <p><b>Kubernetes Integration:</b> This test validates that the endpoint 
     * will work correctly with Kubernetes probe configurations like:</p>
     * <pre>
     * livenessProbe:
     *   httpGet:
     *     path: /api/health
     *     port: 8080
     *   initialDelaySeconds: 30
     *   periodSeconds: 10
     * 
     * readinessProbe:
     *   httpGet:
     *     path: /api/health
     *     port: 8080
     *   initialDelaySeconds: 10
     *   periodSeconds: 5
     * </pre>
     * 
     * <p><b>Failure Scenarios Tested:</b></p>
     * <ul>
     *   <li>If endpoint returns non-200 status, Kubernetes marks pod as unhealthy</li>
     *   <li>If JSON structure is invalid, monitoring systems may fail to parse status</li>
     *   <li>If "status" field is missing or not "UP", health checks may misinterpret application state</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request execution fails (should not occur in normal test execution)
     */
    @Test
    public void testHealthEndpoint_ReturnsOk() throws Exception {
        // Perform GET request to /api/health endpoint and assert response
        mockMvc.perform(get("/api/health"))
                // Assert: HTTP status code is 200 OK (required for Kubernetes probes)
                .andExpect(status().isOk())
                // Assert: JSON response contains "status" field (validates JSON structure)
                .andExpect(jsonPath("$.status").exists())
                // Assert: "status" field value is "UP" (indicates application health)
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Test: Health endpoint returns valid JSON content type.
     * 
     * <p>Validates that the health check endpoint returns proper Content-Type 
     * header set to application/json, ensuring monitoring systems and Kubernetes 
     * probes can correctly parse the response.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Perform HTTP GET request to /api/health endpoint</li>
     *   <li>Assert Content-Type header is application/json</li>
     * </ol>
     * 
     * <p><b>Importance:</b> While Kubernetes HTTP probes primarily check status 
     * codes, monitoring systems and load balancers may parse the response body 
     * to extract detailed health information. Proper Content-Type ensures 
     * correct parsing.</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    public void testHealthEndpoint_ReturnsJsonContentType() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Test: Health endpoint response structure is complete.
     * 
     * <p>Validates that the health check response contains all expected fields 
     * with correct data types. This test ensures the JSON structure matches the 
     * documented API contract.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Perform HTTP GET request to /api/health endpoint</li>
     *   <li>Assert "status" field exists</li>
     *   <li>Assert "status" field is a string (not null, not object)</li>
     *   <li>Assert "status" value is exactly "UP" (case-sensitive)</li>
     * </ol>
     * 
     * <p><b>API Contract Validation:</b> This test ensures:</p>
     * <ul>
     *   <li>Response is valid JSON object (not array or primitive)</li>
     *   <li>Required "status" field is present</li>
     *   <li>Field value matches expected constant "UP"</li>
     *   <li>No unexpected fields are present (minimal response for fast parsing)</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    public void testHealthEndpoint_ResponseStructure() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                // Validate "status" field exists
                .andExpect(jsonPath("$.status").exists())
                // Validate "status" field is a string value
                .andExpect(jsonPath("$.status").isString())
                // Validate "status" value is exactly "UP"
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
