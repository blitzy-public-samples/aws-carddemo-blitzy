package com.carddemo.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the fraud detection service.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk}, {@code scoring} and {@code luhn} matches zero of its 28 programs.
 *
 * <p>The service is planned to read {@code TransactionAuthorized} from topic
 * {@code transaction.authorized} in consumer group {@code fraud-detection}, and to write
 * {@code FraudFlagged} and {@code FraudCleared} to topic {@code fraud.assessed}. It is to stay out
 * of the authorization response path. No listener and no endpoint is authored yet, so this
 * application starts, exposes the actuator endpoints, and consumes nothing.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FraudApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudApplication.class, args);
    }
}
