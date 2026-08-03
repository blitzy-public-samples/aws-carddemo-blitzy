package com.carddemo.authorization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the authorization service, the only synchronous entry point on the card platform.
 *
 * <p>A client sends one Representational State Transfer (REST) request. The service resolves the
 * card to an account, applies four decline rules, and writes the authorization decision and one
 * domain event in a single local transaction. A scheduled relay reads the outbox table afterwards
 * and publishes that event.
 *
 * <p>The service publishes {@code transaction.authorized} and {@code transaction.declined}. It
 * consumes no topic.
 *
 * <p>No single program is the ancestor of this service. Three CardDemo programs supply its
 * behaviour:
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} supplies the four decline rules. Paragraph
 *       {@code 1500-A-LOOKUP-XREF} at lines 380 to 392 sets reason code 100. Paragraph
 *       {@code 1500-B-LOOKUP-ACCT} at lines 393 to 422 sets reason codes 101, 102 and 103.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} supplies the synchronous request contract.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} supplies authentication, which stays outside the
 *       authorization path.</li>
 * </ul>
 *
 * <p>Program {@code COPAUA0C} and Customer Information Control System (CICS) transaction
 * {@code CP00}, both named in the brief, are absent from this repository. A recursive
 * case-insensitive search for {@code COPAUA0C} under {@code app/} matches zero files, and
 * {@code CP00} appears zero times in {@code app/csd/CARDDEMO.CSD}.
 *
 * <p>The component scan covers this package and the eight packages beneath it.
 * {@code @EnableScheduling} activates the {@code @Scheduled} poll on {@code OutboxRelay}.
 *
 * <p>Rationale for every design choice lives in {@code card-platform/docs/decision-log.md}.
 */
@SpringBootApplication
@EnableScheduling
public class AuthorizationApplication {

    /**
     * Boots the Spring application context for this service.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthorizationApplication.class, args);
    }
}
