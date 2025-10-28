package com.carddemo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health Check REST Controller
 * 
 * <p>Provides system health status endpoints for monitoring and readiness probes 
 * in Kubernetes deployments. This controller is NEW functionality for cloud-native 
 * deployment and has NO direct COBOL source equivalent.</p>
 * 
 * <p>This endpoint is designed to be called by:</p>
 * <ul>
 *   <li>Kubernetes liveness probes - to determine if the pod should be restarted</li>
 *   <li>Kubernetes readiness probes - to determine if the pod is ready to receive traffic</li>
 *   <li>Load balancers - to determine if the instance should receive requests</li>
 *   <li>Monitoring systems - to track application availability</li>
 * </ul>
 * 
 * <p><b>Security:</b> No authentication required for health endpoints to allow 
 * infrastructure components to check health status without credentials.</p>
 * 
 * <p><b>Integration:</b> This simple health check can be supplemented with 
 * Spring Boot Actuator for more detailed health metrics including database 
 * connectivity, disk space, and custom health indicators.</p>
 * 
 * <p><b>Migration Note:</b> This is NEW cloud-native functionality required for 
 * containerized deployment. The mainframe COBOL system did not require health 
 * check endpoints as the CICS region health was monitored through different 
 * mainframe-specific mechanisms.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthCheckController {

    /**
     * Health check endpoint for Kubernetes probes and monitoring systems.
     * 
     * <p>Returns a simple status indicating the application is running and able 
     * to process requests. This endpoint responds quickly (sub-millisecond) to 
     * minimize impact on system resources during frequent probe checks.</p>
     * 
     * <p><b>Kubernetes Configuration Example:</b></p>
     * <pre>
     * livenessProbe:
     *   httpGet:
     *     path: /api/health
     *     port: 8080
     *   initialDelaySeconds: 30
     *   periodSeconds: 10
     *   timeoutSeconds: 5
     *   failureThreshold: 3
     * 
     * readinessProbe:
     *   httpGet:
     *     path: /api/health
     *     port: 8080
     *   initialDelaySeconds: 10
     *   periodSeconds: 5
     *   timeoutSeconds: 3
     *   failureThreshold: 3
     * </pre>
     * 
     * <p><b>Response Format:</b></p>
     * <pre>
     * {
     *   "status": "UP"
     * }
     * </pre>
     * 
     * <p><b>HTTP Status Codes:</b></p>
     * <ul>
     *   <li>200 OK - Application is healthy and ready to process requests</li>
     * </ul>
     * 
     * <p><b>Note:</b> For more detailed health information including database 
     * connectivity status, disk space, and custom metrics, use the Spring Boot 
     * Actuator endpoint at /actuator/health (which requires authentication).</p>
     * 
     * @return ResponseEntity containing health status map with HTTP 200 OK status.
     *         The response body is a JSON object with a single "status" field 
     *         set to "UP" indicating the application is operational.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        log.debug("Health check endpoint accessed");
        
        // Return simple UP status for Kubernetes probes
        // Uses Map.of() for immutable single-entry map (Java 9+)
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
