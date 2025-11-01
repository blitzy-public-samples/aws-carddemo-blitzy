package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo Spring Boot Application - Main Entry Point
 * 
 * <p>This class serves as the bootstrap entry point for the CardDemo credit card management
 * system, replacing the IBM CICS Transaction Server region initialization from the legacy
 * mainframe environment.</p>
 * 
 * <h2>Migration Context</h2>
 * <p>This application represents a complete technology stack migration from:</p>
 * <ul>
 *   <li><b>Source Platform:</b> IBM z/OS Mainframe with CICS Transaction Server</li>
 *   <li><b>Target Platform:</b> Cloud-Native Java Spring Boot 3.2.1 with Java 21 LTS</li>
 *   <li><b>Legacy Components:</b> 28 COBOL programs, 17 BMS 3270 screens, 5 VSAM KSDS files</li>
 *   <li><b>Modern Components:</b> Spring Boot microservices, React 18 UI, PostgreSQL 15 database</li>
 * </ul>
 * 
 * <h2>Enabled Spring Boot Features</h2>
 * <p>The @SpringBootApplication annotation enables the following capabilities:</p>
 * <ul>
 *   <li><b>Auto-Configuration:</b> Automatic setup of Spring Data JPA, Spring Security,
 *       Spring Batch, Redis session management, and Spring Boot Actuator</li>
 *   <li><b>Component Scanning:</b> Automatic discovery and registration of all Spring
 *       components (@Controller, @Service, @Repository, @Configuration) in com.carddemo
 *       package and sub-packages</li>
 *   <li><b>Configuration Properties:</b> Loading of application.yml and profile-specific
 *       configuration files (application-dev.yml, application-prod.yml)</li>
 * </ul>
 * 
 * <h2>Application Architecture</h2>
 * <p>Initialized components include:</p>
 * <ul>
 *   <li><b>Web Layer:</b> Embedded Apache Tomcat server on port 8080 (configurable)</li>
 *   <li><b>Controller Layer:</b> REST API endpoints replacing CICS transaction IDs</li>
 *   <li><b>Service Layer:</b> Business logic services migrated from COBOL programs</li>
 *   <li><b>Data Access Layer:</b> Spring Data JPA repositories replacing VSAM file I/O</li>
 *   <li><b>Batch Processing:</b> Spring Batch jobs replacing JCL-scheduled batch programs</li>
 *   <li><b>Security:</b> Spring Security with JWT authentication replacing RACF/USRSEC</li>
 *   <li><b>Session Management:</b> Redis-backed sessions replacing CICS COMMAREA state</li>
 * </ul>
 * 
 * <h2>Monitoring and Management Endpoints</h2>
 * <ul>
 *   <li><b>API Documentation:</b> Swagger UI available at /swagger-ui.html</li>
 *   <li><b>Health Check:</b> /actuator/health for Kubernetes liveness/readiness probes</li>
 *   <li><b>Metrics:</b> /actuator/prometheus for Prometheus monitoring integration</li>
 *   <li><b>Application Info:</b> /actuator/info for version and build information</li>
 * </ul>
 * 
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><b>Target Response Time:</b> &lt;200ms at 95th percentile for REST APIs</li>
 *   <li><b>Concurrent Users:</b> Minimum 150 concurrent users supported</li>
 *   <li><b>Peak Throughput:</b> 10,000 transactions per second (TPS) capacity</li>
 *   <li><b>Batch Processing Window:</b> &lt;4 hours for daily batch job completion</li>
 * </ul>
 * 
 * <h2>Deployment Environment</h2>
 * <ul>
 *   <li><b>Container Runtime:</b> Docker 24.x with multi-stage builds</li>
 *   <li><b>Orchestration:</b> Kubernetes 1.28+ with Helm charts</li>
 *   <li><b>Database:</b> PostgreSQL 15+ with HikariCP connection pooling</li>
 *   <li><b>Cache/Session Store:</b> Redis 7.x for distributed session management</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024
 * @see org.springframework.boot.SpringApplication
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Main entry point for the CardDemo Spring Boot application.
     * 
     * <p>This method initializes the Spring ApplicationContext, starts the embedded
     * Tomcat server, and bootstraps all Spring-managed components including controllers,
     * services, repositories, batch jobs, and security configurations.</p>
     * 
     * <p>The SpringApplication.run() method performs the following initialization sequence:</p>
     * <ol>
     *   <li>Creates a SpringApplication instance from the CardDemoApplication class</li>
     *   <li>Loads application.yml and profile-specific configuration properties</li>
     *   <li>Initializes the Spring ApplicationContext with all @Configuration classes</li>
     *   <li>Performs component scanning to discover and register Spring beans</li>
     *   <li>Applies Spring Boot auto-configuration based on classpath dependencies</li>
     *   <li>Initializes database connection pool and runs Flyway migrations</li>
     *   <li>Configures Spring Security authentication and authorization</li>
     *   <li>Starts the embedded Tomcat servlet container</li>
     *   <li>Registers Spring Batch jobs with the JobRepository</li>
     *   <li>Enables Spring Boot Actuator management endpoints</li>
     * </ol>
     * 
     * <p><b>Functional Equivalence to CICS:</b></p>
     * <p>This method replaces the following mainframe initialization components:</p>
     * <ul>
     *   <li>CICS region startup and initialization (DFHSIT parameters)</li>
     *   <li>COBOL program load module resolution (DFHRPL library search)</li>
     *   <li>CICS transaction definition activation (CSD resource definitions)</li>
     *   <li>VSAM file allocation and buffer pool initialization</li>
     * </ul>
     * 
     * @param args Command-line arguments passed to the application. Supports Spring Boot
     *             standard arguments including:
     *             <ul>
     *               <li>--spring.profiles.active=prod (activate production profile)</li>
     *               <li>--server.port=9090 (override default port 8080)</li>
     *               <li>--spring.datasource.url=... (override database connection)</li>
     *             </ul>
     * @throws IllegalArgumentException if required configuration properties are missing
     * @throws IllegalStateException if the application context fails to initialize
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
