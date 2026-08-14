package com.carddemo.authorization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the authorization service.
 *
 * <p>No single CardDemo program is the ancestor of this service. {@code app/cbl/CBTRN02C.cbl}
 * supplies its decline rules, {@code app/cbl/COTRN02C.cbl} its synchronous request contract, and
 * {@code app/cbl/COSGN00C.cbl} its authentication.
 *
 * <p>Program {@code COPAUA0C} and Customer Information Control System (CICS) transaction
 * {@code CP00} are both named in the brief and are absent from this repository. A recursive
 * case-insensitive search for {@code COPAUA0C} under {@code app/} matches zero files.
 * {@code CP00} appears zero times in {@code app/csd/CARDDEMO.CSD}.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AuthorizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthorizationApplication.class, args);
    }
}
