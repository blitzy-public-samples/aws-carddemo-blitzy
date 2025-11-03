package com.carddemo.config;

import com.carddemo.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security Configuration for CardDemo Application
 * 
 * Implements Spring Security 6.x configuration for:
 * - JWT token-based authentication
 * - Method-level security with @PreAuthorize
 * - Password encryption using BCrypt
 * - Role-based access control (ROLE_USER, ROLE_ADMIN)
 * 
 * Migration from: RACF security on mainframe (USRSEC file-based authentication)
 * Target: Spring Security with JWT tokens and role-based authorization
 * 
 * Security Requirements per Section 0.9:
 * - JWT token expiration: 24 hours
 * - Password encryption: BCrypt with strength 12
 * - Role mapping: 'R' (Regular) → ROLE_USER, 'A' (Admin) → ROLE_ADMIN
 * - HTTPS: TLS 1.3 minimum (configured at infrastructure level)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Password encoder bean using BCrypt with strength 12
     * 
     * Per Section 0.9 Security Configuration:
     * "Password encryption: BCrypt with strength 12"
     * 
     * @return BCryptPasswordEncoder configured with strength 12
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Security filter chain configuration
     * 
     * Configures:
     * - Stateless session management (for REST API)
     * - CSRF disabled (stateless API with JWT)
     * - Authorization rules for endpoints
     * - Method security enabled via @EnableMethodSecurity
     * 
     * @param http HttpSecurity configuration object
     * @return SecurityFilterChain configured for the application
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Disable CSRF for stateless REST API
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Stateless sessions with JWT
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/auth/**").permitAll() // Allow authentication endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll() // Allow health checks
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll() // Allow API documentation
                .anyRequest().authenticated() // All other requests require authentication
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class); // Add JWT filter

        return http.build();
    }
}
