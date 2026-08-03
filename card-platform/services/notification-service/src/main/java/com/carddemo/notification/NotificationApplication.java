package com.carddemo.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The notification service consumes {@code TransactionAuthorized}, {@code TransactionPosted} and
 * {@code FraudFlagged}, maintains a read model keyed by card number, and renders a cardholder
 * alert. The service publishes no event and calls no other service.
 *
 * <p>Alert content comes from {@code app/cbl/CBSTM03A.CBL}, the batch program that
 * {@code app/jcl/CREASTMT.JCL} invokes at line 79 as {@code //STEP040 EXEC PGM=CBSTM03A}. Spring
 * Boot startup replaces that program's prologue at {@code app/cbl/CBSTM03A.CBL:L262-L294}, which
 * opened its two output files, and the {@code 0000-START} dispatch at
 * {@code app/cbl/CBSTM03A.CBL:L296-L314}.
 *
 * <p>See {@code card-platform/docs/decision-log.md} for the decisions behind this module.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
