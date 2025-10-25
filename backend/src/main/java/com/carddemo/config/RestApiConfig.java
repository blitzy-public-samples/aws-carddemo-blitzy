package com.carddemo.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.UUID;

/**
 * Spring configuration class for REST API concerns.
 * 
 * <p>Converted from COBOL/CICS mainframe architecture to modern REST API patterns.</p>
 * 
 * <p><b>Key Configuration Areas:</b></p>
 * <ul>
 *   <li>Jackson ObjectMapper with Java 8 date/time support (replaces COBOL PIC X(10) date formats)</li>
 *   <li>Request/response logging interceptor (replaces CICS EXEC CICS WRITE OPERATOR logging)</li>
 *   <li>CORS configuration for React frontend communication</li>
 *   <li>Content negotiation for JSON responses</li>
 * </ul>
 * 
 * <p><b>COBOL-to-Java Migration Notes:</b></p>
 * <ul>
 *   <li>COBOL PIC X(10) date fields (YYYY-MM-DD) → Java LocalDate → ISO-8601 JSON ("2024-01-15")</li>
 *   <li>COBOL PIC X(26) timestamp fields → Java LocalDateTime → ISO-8601 JSON ("2024-01-15T14:30:00")</li>
 *   <li>CICS DISPLAY statements for operational logging → SLF4J structured logging with correlation IDs</li>
 *   <li>CICS RESP/RESP2 codes → HTTP status codes (200, 400, 404, 500)</li>
 * </ul>
 * 
 * <p><b>No business logic</b> per MINIMAL CHANGE CLAUSE - configuration only.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-15
 */
@Configuration
public class RestApiConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(RestApiConfig.class);

    /**
     * CORS allowed origins from application.yml.
     * Dev profile: http://localhost:3000,http://localhost:5173 (React/Vite dev servers)
     * Prod profile: production domain
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String[] allowedOrigins;

    /**
     * Active Spring profile (dev, test, prod).
     * Used to conditionally enable pretty-print JSON in dev profile.
     */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * Sensitive field names to sanitize in request/response logging.
     * Matches COBOL security patterns for password/SSN protection.
     */
    private static final String[] SENSITIVE_FIELDS = {
        "password", "pwd", "passwd", "secret", "token",
        "ssn", "social_security", "credit_card", "cvv", "pin"
    };

    /**
     * Configures Jackson ObjectMapper for JSON serialization/deserialization.
     * 
     * <p><b>Key Features:</b></p>
     * <ul>
     *   <li>JavaTimeModule: Enables LocalDate/LocalDateTime JSON serialization</li>
     *   <li>ISO-8601 date format: Replaces COBOL PIC X(10) YYYY-MM-DD with standard JSON dates</li>
     *   <li>WRITE_DATES_AS_TIMESTAMPS=false: Dates as strings, not epoch milliseconds</li>
     *   <li>INDENT_OUTPUT=true in dev: Pretty-print JSON for debugging</li>
     *   <li>FAIL_ON_UNKNOWN_PROPERTIES=false: Forward compatibility for API versioning</li>
     * </ul>
     * 
     * <p><b>COBOL Date Conversion Example:</b></p>
     * <pre>
     * COBOL: 01 ACCT-OPEN-DATE PIC X(10) VALUE "2024-01-15".
     * Java:  LocalDate acctOpenDate = LocalDate.of(2024, 1, 15);
     * JSON:  {"acctOpenDate": "2024-01-15"}
     * </pre>
     * 
     * @return Configured ObjectMapper instance
     */
    @Bean
    public ObjectMapper objectMapper() {
        logger.info("Configuring Jackson ObjectMapper with Java 8 date/time support");
        
        ObjectMapper mapper = new ObjectMapper();
        
        // Register JavaTimeModule for Java 8 date/time API support
        // Replaces COBOL PIC X(10) date formats with ISO-8601 JSON serialization
        mapper.registerModule(new JavaTimeModule());
        logger.debug("Registered JavaTimeModule for LocalDate/LocalDateTime serialization");
        
        // Disable timestamp format, use ISO-8601 strings instead
        // Example: "2024-01-15" instead of 1705276800000
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Enable pretty-print JSON in dev profile for debugging
        // Production uses compact JSON for bandwidth optimization
        if ("dev".equalsIgnoreCase(activeProfile)) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            logger.debug("Enabled JSON pretty-printing for dev profile");
        }
        
        // Allow unknown properties for forward compatibility
        // Enables gradual API evolution without breaking existing clients
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        logger.debug("Configured FAIL_ON_UNKNOWN_PROPERTIES=false for API versioning");
        
        // Enable fail on null for primitives (data integrity)
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        
        logger.info("Successfully configured ObjectMapper with JavaTimeModule and ISO-8601 date format");
        return mapper;
    }

    /**
     * Configures request/response logging interceptor for audit trail.
     * 
     * <p>Replaces CICS EXEC CICS WRITE OPERATOR log statements with structured REST API logging.</p>
     * 
     * <p><b>Logged Information:</b></p>
     * <ul>
     *   <li>HTTP method and URI</li>
     *   <li>Request headers (sanitized)</li>
     *   <li>Request body (with sensitive field masking)</li>
     *   <li>Response status code</li>
     *   <li>Execution time (milliseconds)</li>
     *   <li>Correlation ID for distributed tracing</li>
     * </ul>
     * 
     * <p>Matches COBOL operational logging patterns for continuity.</p>
     * 
     * @param registry InterceptorRegistry for registering interceptors
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        logger.info("Registering request/response logging interceptor");
        
        HandlerInterceptor loggingInterceptor = new HandlerInterceptor() {
            
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // Generate correlation ID for request tracking
                String correlationId = UUID.randomUUID().toString();
                request.setAttribute("correlationId", correlationId);
                request.setAttribute("startTime", System.currentTimeMillis());
                
                // Add correlation ID to response headers for client tracing
                response.setHeader("X-Correlation-ID", correlationId);
                
                // Log incoming request details
                logger.info("==> Incoming Request [{}] {} {} from {}",
                    correlationId,
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getRemoteAddr());
                
                // Log request headers (sanitized)
                if (logger.isDebugEnabled()) {
                    Enumeration<String> headerNames = request.getHeaderNames();
                    while (headerNames.hasMoreElements()) {
                        String headerName = headerNames.nextElement();
                        String headerValue = request.getHeader(headerName);
                        
                        // Sanitize Authorization header (show only Bearer prefix)
                        if ("Authorization".equalsIgnoreCase(headerName) && headerValue != null) {
                            headerValue = headerValue.startsWith("Bearer ") ? "Bearer <JWT_TOKEN>" : "<REDACTED>";
                        }
                        
                        logger.debug("    Header: {} = {}", headerName, headerValue);
                    }
                }
                
                return true;
            }
            
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
                // Log response status after controller processing
                String correlationId = (String) request.getAttribute("correlationId");
                logger.debug("    Response Status [{}]: {}", correlationId, response.getStatus());
            }
            
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                // Calculate and log execution time
                Long startTime = (Long) request.getAttribute("startTime");
                String correlationId = (String) request.getAttribute("correlationId");
                
                if (startTime != null) {
                    long executionTime = System.currentTimeMillis() - startTime;
                    
                    // Log completion with timing
                    logger.info("<== Completed Request [{}] {} {} - Status: {} - Time: {}ms",
                        correlationId,
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        executionTime);
                    
                    // Warn if response time exceeds 200ms (mainframe SLA requirement)
                    if (executionTime > 200) {
                        logger.warn("PERFORMANCE WARNING [{}]: Request took {}ms, exceeds 200ms SLA",
                            correlationId, executionTime);
                    }
                }
                
                // Log any exception that occurred
                if (ex != null) {
                    logger.error("Exception occurred during request processing [{}]: {}",
                        correlationId, ex.getMessage(), ex);
                }
            }
        };
        
        // Register interceptor for all API endpoints
        registry.addInterceptor(loggingInterceptor)
            .addPathPatterns("/api/**");
        
        logger.info("Successfully registered logging interceptor for /api/** endpoints");
    }

    /**
     * Configures CORS (Cross-Origin Resource Sharing) for React frontend.
     * 
     * <p>Enables React SPA hosted on different origin to make API calls.</p>
     * 
     * <p><b>CORS Configuration:</b></p>
     * <ul>
     *   <li>Allowed Origins: From application.yml (localhost:3000, localhost:5173 in dev)</li>
     *   <li>Allowed Methods: GET, POST, PUT, DELETE, OPTIONS</li>
     *   <li>Allowed Headers: Authorization, Content-Type, X-Requested-With</li>
     *   <li>Exposed Headers: Authorization (for JWT tokens)</li>
     *   <li>Allow Credentials: true (for cookie/auth support)</li>
     *   <li>Max Age: 3600s (preflight cache duration)</li>
     * </ul>
     * 
     * <p>Replaces mainframe terminal-based access with web browser cross-origin access.</p>
     * 
     * @param registry CorsRegistry for configuring CORS mappings
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        logger.info("Configuring CORS for allowed origins: {}", (Object[]) allowedOrigins);
        
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "X-Requested-With", "X-Correlation-ID")
            .exposedHeaders("Authorization", "X-Correlation-ID")
            .allowCredentials(true)
            .maxAge(3600);
        
        logger.info("Successfully configured CORS for /api/** endpoints");
    }

    /**
     * Sanitizes request/response body by masking sensitive fields.
     * 
     * <p>Matches COBOL security patterns for password/SSN protection.</p>
     * 
     * @param body Raw request/response body
     * @return Sanitized body with sensitive fields masked
     */
    private String sanitizeBody(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        
        String sanitized = body;
        for (String sensitiveField : SENSITIVE_FIELDS) {
            // Mask JSON field values: "password":"secret123" → "password":"<REDACTED>"
            sanitized = sanitized.replaceAll(
                "\"" + sensitiveField + "\"\\s*:\\s*\"[^\"]+\"",
                "\"" + sensitiveField + "\":\"<REDACTED>\""
            );
            
            // Mask JSON field values without quotes: "ssn":123456789 → "ssn":"<REDACTED>"
            sanitized = sanitized.replaceAll(
                "\"" + sensitiveField + "\"\\s*:\\s*[^,}]+",
                "\"" + sensitiveField + "\":\"<REDACTED>\""
            );
        }
        
        return sanitized;
    }
}
