package com.carddemo.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the fraud detection service.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor. Searching {@code app/cbl/} for
 * {@code fraud}, {@code velocit}, {@code risk}, {@code scoring} and {@code luhn} matches zero of
 * its 28 programs.
 *
 * <p>{@code messaging.TransactionAuthorizedConsumer} reads {@code TransactionAuthorized} from
 * topic {@code transaction.authorized} in consumer group {@code fraud-detection}, scores it against
 * the three rules in {@code domain.rules}, and writes one assessment row and one
 * {@code FraudFlagged} or {@code FraudCleared} outbox row in a single local transaction.
 * {@code outbox.OutboxRelay} publishes those rows to topic {@code fraud.assessed} on the schedule
 * {@link EnableScheduling} starts. {@code api.FraudAssessmentController} answers read-only queries
 * over the stored assessments. Nothing here sits in the authorization response path.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FraudApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudApplication.class, args);
    }
}
