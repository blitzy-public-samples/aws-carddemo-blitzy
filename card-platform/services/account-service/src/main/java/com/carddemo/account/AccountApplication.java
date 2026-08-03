package com.carddemo.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boots the account service, which reads and updates accounts and customers and closes the
 * billing cycle for one account.
 *
 * <p>Also enables scheduling for the outbox relay in {@code com.carddemo.account.outbox}.
 *
 * <p>Decisions behind this module: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootApplication
@EnableScheduling
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
