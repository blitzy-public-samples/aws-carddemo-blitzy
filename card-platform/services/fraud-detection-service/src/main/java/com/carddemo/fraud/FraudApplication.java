package com.carddemo.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the fraud detection service.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk}, {@code scoring} and {@code luhn} matches zero of its 28 programs.
 *
 * <p>The service reads {@code TransactionAuthorized} from topic {@code transaction.authorized} in
 * consumer group {@code fraud-detection}, and writes {@code FraudFlagged} and {@code FraudCleared}
 * to topic {@code fraud.assessed}. It never sits in the authorization response path.
 */
@SpringBootApplication
@EnableScheduling
public class FraudApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudApplication.class, args);
    }
}
