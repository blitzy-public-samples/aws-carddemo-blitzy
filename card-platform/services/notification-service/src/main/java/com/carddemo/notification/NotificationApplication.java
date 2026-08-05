package com.carddemo.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The notification service consumes {@code TransactionPosted} from topic
 * {@code transaction.posted}, maintains a read model keyed by card number, and renders a cardholder
 * alert. That event sits downstream of the authorization event, which the ledger and fraud services
 * consume directly, so this service reads no authorization event of its own. The service also
 * consumes {@code FraudFlagged}, {@code FraudCleared}, and {@code CustomerContextChanged}; it
 * publishes no event and calls no other service.
 *
 * <p>The posted, fraud-assessment, and customer-context listeners advance under separate consumer
 * groups. None calls another consumer, and each commits only after its local effects commit.
 *
 * <p>Alert content comes from {@code app/cbl/CBSTM03A.CBL}, the batch program that
 * {@code app/jcl/CREASTMT.JCL} invokes at line 79 as {@code //STEP040 EXEC PGM=CBSTM03A}. Spring
 * Boot startup replaces that program's prologue at {@code app/cbl/CBSTM03A.CBL:L262-L294}, which
 * opened its two output files, and the {@code 0000-START} dispatch at
 * {@code app/cbl/CBSTM03A.CBL:L296-L314}.
 *
 * <p>Scheduling is enabled because {@code domain/RetentionSweep} bounds the three tables this service
 * grows by one row per consumed event. Without it the horizons declared in
 * {@code src/main/resources/application.yml} would describe an intention rather than a behaviour.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
