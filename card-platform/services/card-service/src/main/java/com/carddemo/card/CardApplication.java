package com.carddemo.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the card service.
 *
 * <p>The behaviour of this service comes from the three Customer Information Control System (CICS)
 * transactions defined at {@code app/csd/CARDDEMO.CSD:L347-L369}: {@code CCDL} dispatched into
 * {@code COCRDSLC} for card detail, {@code CCLI} into {@code COCRDLIC} for card list, and
 * {@code CCUP} into {@code COCRDUPC} for card update.
 */
@SpringBootApplication
@EnableScheduling
public class CardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
