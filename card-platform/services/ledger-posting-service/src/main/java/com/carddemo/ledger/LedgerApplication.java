package com.carddemo.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the ledger posting service.
 *
 * <p>The posting behaviour of this service comes from {@code app/cbl/CBTRN02C.cbl}, the batch
 * program that {@code app/jcl/POSTTRAN.jcl} invokes at line 23 as
 * {@code //STEP15 EXEC PGM=CBTRN02C}.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
