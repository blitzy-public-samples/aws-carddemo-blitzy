package com.carddemo.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the ledger posting service.
 *
 * <p>The service consumes {@code TransactionAuthorized} from the Kafka topic
 * {@code transaction.authorized} under consumer group {@code ledger-posting}. It publishes
 * {@code TransactionPosted} to {@code transaction.posted} through its transactional outbox. The
 * service also exposes one read-only balance query endpoint and calls no other service.
 *
 * <p>Each consumed event runs the posting arithmetic of {@code app/cbl/CBTRN02C.cbl}, the batch
 * program that {@code app/jcl/POSTTRAN.jcl} invokes at line 23 as
 * {@code //STEP15 EXEC PGM=CBTRN02C}. One consumer invocation covers one iteration of that
 * program's driver loop at {@code app/cbl/CBTRN02C.cbl:L202-L219}. Posting runs once per event,
 * not once per nightly file.
 *
 * <p>Design rationale lives in {@code card-platform/docs/decision-log.md}.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
