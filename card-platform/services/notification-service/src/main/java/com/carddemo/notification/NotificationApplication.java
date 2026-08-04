package com.carddemo.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The notification service consumes {@code TransactionPosted} and {@code FraudFlagged}, maintains a
 * read model keyed by card number, and renders a cardholder alert. Both events sit downstream of the
 * authorization event, which the ledger and fraud services consume directly, so this service reads
 * no authorization event of its own. The service publishes no event and calls no other service.
 *
 * <p>The {@code fraud.assessed} topic also carries {@code FraudCleared}. The service counts a
 * cleared assessment and raises no alert on one.
 *
 * <p>Alert content comes from {@code app/cbl/CBSTM03A.CBL}, the batch program that
 * {@code app/jcl/CREASTMT.JCL} invokes at line 79 as {@code //STEP040 EXEC PGM=CBSTM03A}. Spring
 * Boot startup replaces that program's prologue at {@code app/cbl/CBSTM03A.CBL:L262-L294}, which
 * opened its two output files, and the {@code 0000-START} dispatch at
 * {@code app/cbl/CBSTM03A.CBL:L296-L314}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
