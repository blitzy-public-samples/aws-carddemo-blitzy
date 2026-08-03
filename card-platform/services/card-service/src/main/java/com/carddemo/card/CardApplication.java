package com.carddemo.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the card service.
 *
 * <p>The service exposes card list, card detail and card update endpoints over Representational
 * State Transfer (REST), and replaces three Customer Information Control System (CICS) transactions
 * defined at {@code app/csd/CARDDEMO.CSD:L347-L369}. {@code CCDL} dispatched into
 * {@code COCRDSLC} for card detail, {@code CCLI} into {@code COCRDLIC} for card list, and
 * {@code CCUP} into {@code COCRDUPC} for card update.
 *
 * <p>{@code @EnableScheduling} lets the outbox relay run its fixed-delay poll, which publishes the
 * card state-change event.
 *
 * <p>Design decisions for this module are recorded in {@code card-platform/docs/decision-log.md}.
 */
@SpringBootApplication
@EnableScheduling
public class CardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
