package com.carddemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring Boot Web MVC Configuration
 * 
 * This configuration class implements WebMvcConfigurer to provide comprehensive
 * web layer configuration for the CardDemo application, replacing mainframe
 * CICS web infrastructure with modern Spring Boot capabilities.
 * 
 * Key responsibilities:
 * - CORS configuration for cross-origin frontend access
 * - Jackson JSON message converters with JSR-310 date/time support
 * - Request/response interceptors for logging and performance monitoring
 * - Static resource handling
 * 
 * This configuration ensures that:
 * - Frontend React application can communicate with backend APIs
 * - Date/time fields from COBOL (CEEDAYS format) serialize properly to JSON
 * - All HTTP requests are logged for observability
 * - Slow requests are identified for performance optimization
 *
 * @author CardDemo Migration Team
 * @version 1.0.0
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * CORS allowed origins from application.properties
     * Supports comma-separated list including:
     * - http://localhost:3000 for local development
     * - Production frontend URLs
     */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    /**
     * CORS max age for preflight request caching in seconds
     * Default: 3600 seconds (1 hour)
     */
    @Value("${cors.max-age:3600}")
    private Long corsMaxAge;

    /**
     * Performance threshold in milliseconds for slow request logging
     * Requests exceeding this threshold are logged as warnings
     * Default: 1000ms (1 second)
     */
    @Value("${performance.slow-request-threshold-ms:1000}")
    private Long slowRequestThresholdMs;

    /**
     * Configures Cross-Origin Resource Sharing (CORS) mappings
     * 
     * CORS configuration enables the React frontend application to make
     * HTTP requests to the Spring Boot backend from different origins.
     * This replaces mainframe terminal-based access with modern web browser access.
     * 
     * Configuration includes:
     * - Allowed origins: Externalized from application.properties for environment-specific URLs
     * - Allowed methods: GET, POST, PUT, DELETE, OPTIONS for full REST API support
     * - Allowed headers: Including Authorization for JWT bearer tokens
     * - Allow credentials: Enables cookie-based session support
     * - Max age: Caching duration for preflight requests
     *
     * @param registry CorsRegistry for registering CORS configuration patterns
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("Configuring CORS with allowed origins: {}", allowedOrigins);
        
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Type")
                .allowCredentials(true)
                .maxAge(corsMaxAge);
        
        log.debug("CORS configuration completed: methods=[GET, POST, PUT, DELETE, OPTIONS], "
                + "maxAge={}s, allowCredentials=true", corsMaxAge);
    }

    /**
     * Extends HTTP message converters to customize JSON serialization
     * 
     * This method modifies the auto-configured Jackson2HttpMessageConverter to ensure
     * proper handling of Java 8 date/time types (LocalDate, LocalDateTime, ZonedDateTime)
     * that replace COBOL date fields (CEEDAYS Lillian format).
     * 
     * By using extendMessageConverters instead of configureMessageConverters, we preserve
     * Spring Boot's auto-configuration including Spring Data Page serialization support
     * while adding our custom date/time formatting requirements.
     * 
     * Key configuration:
     * - JavaTimeModule: Enables JSR-310 date/time API support
     * - ISO-8601 format: Dates serialized as "2024-01-15" instead of Unix timestamps
     * - Human-readable JSON: Ensures date fields are easily readable in API responses
     * - Spring Data support: Preserved from auto-configuration for Page, Slice serialization
     * 
     * This replaces mainframe EBCDIC data encoding with modern JSON UTF-8 encoding.
     *
     * @param converters List of HttpMessageConverter instances to extend
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("Extending Jackson message converters with JSR-310 date/time configuration");
        
        // Find the existing Jackson converter added by Spring Boot auto-configuration
        converters.stream()
                .filter(converter -> converter instanceof MappingJackson2HttpMessageConverter)
                .map(converter -> (MappingJackson2HttpMessageConverter) converter)
                .findFirst()
                .ifPresent(converter -> {
                    ObjectMapper objectMapper = converter.getObjectMapper();
                    
                    // Debug: Log registered modules
                    log.debug("Registered Jackson modules before customization: " + 
                            objectMapper.getRegisteredModuleIds());
                    
                    // Ensure JavaTimeModule is registered
                    objectMapper.registerModule(new JavaTimeModule());
                    
                    // Disable timestamp serialization - use ISO-8601 format instead
                    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                    
                    // Debug: Log registered modules after customization
                    log.debug("Registered Jackson modules after customization: " + 
                            objectMapper.getRegisteredModuleIds());
                    
                    log.debug("Extended existing Jackson converter: ISO-8601 date format configured, "
                            + "Spring Data Page serialization preserved");
                });
    }

    /**
     * Registers HTTP request/response interceptors for logging and monitoring
     * 
     * Interceptors provide cross-cutting concerns for all HTTP requests:
     * - Request logging: Logs incoming request URL, method, and parameters
     * - Performance monitoring: Measures and logs request execution time
     * - Slow request detection: Warns about requests exceeding performance threshold
     * 
     * This replaces mainframe CICS transaction logging with modern HTTP request logging.
     *
     * @param registry InterceptorRegistry for registering HandlerInterceptor implementations
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("Registering HTTP request/response interceptors");
        
        // Add request logging and performance monitoring interceptor
        registry.addInterceptor(new RequestLoggingInterceptor(slowRequestThresholdMs));
        
        log.debug("Interceptors registered: RequestLoggingInterceptor with threshold={}ms",
                slowRequestThresholdMs);
    }

    /**
     * Request Logging and Performance Monitoring Interceptor
     * 
     * This inner class implements HandlerInterceptor to provide comprehensive
     * logging and performance monitoring for all HTTP requests.
     * 
     * Features:
     * - Pre-request logging: Logs incoming request details
     * - Execution time measurement: Calculates request processing duration
     * - Post-request logging: Logs response status and execution time
     * - Slow request detection: Warns about requests exceeding threshold
     * 
     * This provides observability equivalent to mainframe CICS transaction monitoring.
     */
    @Slf4j
    private static class RequestLoggingInterceptor implements HandlerInterceptor {
        
        private static final Logger logger = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
        private static final String START_TIME_ATTRIBUTE = "startTime";
        
        private final Long slowRequestThresholdMs;
        
        /**
         * Constructor with slow request threshold
         *
         * @param slowRequestThresholdMs Threshold in milliseconds for slow request warnings
         */
        public RequestLoggingInterceptor(Long slowRequestThresholdMs) {
            this.slowRequestThresholdMs = slowRequestThresholdMs;
        }
        
        /**
         * Pre-handle method executed before request processing
         * 
         * Records the request start time and logs incoming request details.
         *
         * @param request HttpServletRequest being processed
         * @param response HttpServletResponse being generated
         * @param handler Handler (Controller method) that will process the request
         * @return true to continue processing, false to abort
         * @throws Exception if an error occurs
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                Object handler) throws Exception {
            // Record start time for performance measurement
            long startTime = System.currentTimeMillis();
            request.setAttribute(START_TIME_ATTRIBUTE, startTime);
            
            // Log incoming request
            logger.info("Incoming request: method={}, uri={}, queryString={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString() != null ? request.getQueryString() : "none");
            
            return true;
        }
        
        /**
         * After-completion method executed after request processing completes
         * 
         * Calculates request execution time, logs response details, and
         * warns about slow requests exceeding the configured threshold.
         * 
         * This method is called even if an exception occurred during processing.
         *
         * @param request HttpServletRequest that was processed
         * @param response HttpServletResponse that was generated
         * @param handler Handler (Controller method) that processed the request
         * @param ex Exception thrown during processing, or null if successful
         * @throws Exception if an error occurs
         */
        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                   Object handler, Exception ex) throws Exception {
            // Calculate execution time
            Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
            if (startTime != null) {
                long executionTime = System.currentTimeMillis() - startTime;
                
                // Get response status
                int status = response.getStatus();
                
                // Log completion details
                if (executionTime > slowRequestThresholdMs) {
                    // Warn about slow requests
                    logger.warn("SLOW REQUEST: method={}, uri={}, status={}, executionTime={}ms "
                            + "(threshold={}ms)",
                            request.getMethod(),
                            request.getRequestURI(),
                            status,
                            executionTime,
                            slowRequestThresholdMs);
                } else {
                    // Normal request logging
                    logger.info("Request completed: method={}, uri={}, status={}, executionTime={}ms",
                            request.getMethod(),
                            request.getRequestURI(),
                            status,
                            executionTime);
                }
                
                // Log exception if present
                if (ex != null) {
                    logger.error("Request failed with exception: method={}, uri={}, exception={}",
                            request.getMethod(),
                            request.getRequestURI(),
                            ex.getMessage(),
                            ex);
                }
            }
        }
    }
}
