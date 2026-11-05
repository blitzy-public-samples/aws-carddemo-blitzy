/*
 * ============================================================================
 * JpaConfig.java — JPA/Hibernate Configuration
 * ============================================================================
 * Centralizes all JPA-related Spring annotations for the CardDemo application.
 *
 * Source: VSAM catalog metadata from app/catlg/LISTCAT.txt
 *
 * This configuration class replaces the VSAM KSDS (Key Sequenced Data Set)
 * file infrastructure from the original COBOL/CICS mainframe application with
 * JPA/Hibernate-backed PostgreSQL 16+ persistence.
 *
 * VSAM-to-PostgreSQL Mapping (from LISTCAT.txt):
 * -----------------------------------------------
 * | VSAM Dataset | KEYLEN | RECLN | PostgreSQL Table          | Entity Class              |
 * |--------------|--------|-------|---------------------------|---------------------------|
 * | ACCTDATA     | 11     | 300   | account                   | Account.java              |
 * | CARDDATA     | 16     | 150   | card                      | Card.java                 |
 * | CARDXREF     | 16     | 50    | card_xref                 | CardXref.java             |
 * | CUSTDATA     | 9      | 500   | customer                  | Customer.java             |
 * | TRANSACT     | 16     | 350   | card_transaction          | Transaction.java          |
 * | DALYTRAN     | N/A    | ~350  | daily_transaction         | DailyTransaction.java     |
 * | USRSEC       | 8      | 80    | user_security             | UserSecurity.java         |
 * | TRANTYPE     | 2      | ~50   | transaction_type_ref      | TransactionTypeRef.java   |
 * | TRANCATG     | 4      | ~50   | transaction_category_ref  | TransactionCategoryRef.java|
 * | DISCGRP      | N/A    | 50    | discount_group            | DiscountGroup.java        |
 * | TCATBALF     | comp.  | 50    | category_balance          | CategoryBalance.java      |
 *
 * VSAM Alternate Indexes (AIX) become PostgreSQL secondary indexes:
 * - CARDDATA AIX on CARD-ACCT-ID  -> idx_card_acct_id on card.account_id
 * - CARDXREF AIX on XREF-ACCT-ID  -> idx_cardxref_acct_id on card_xref.account_id
 * - TRANSACT AIX on TRAN-ORIG-TS  -> idx_transaction_orig_ts on card_transaction.orig_timestamp
 *
 * JPA/Hibernate Configuration Strategy:
 * - DDL is managed by Flyway migrations (V1__create_schema.sql, V2__create_indexes.sql)
 * - Hibernate ddl-auto is set to 'validate' in application.yml (validates entities against Flyway schema)
 * - open-in-view is disabled for performance (forces explicit fetch strategies)
 * - CamelCaseToUnderscoresNamingStrategy maps Java camelCase to SQL snake_case:
 *     acctId -> acct_id (COBOL: ACCT-ID)
 *     currBal -> curr_bal (COBOL: ACCT-CURR-BAL)
 *     creditLimit -> credit_limit (COBOL: ACCT-CREDIT-LIMIT)
 *     cardNum -> card_num (COBOL: CARD-NUM)
 *     origTimestamp -> orig_timestamp (COBOL: TRAN-ORIG-TS)
 *
 * Transaction Management:
 * - @EnableTransactionManagement enables @Transactional on service methods
 * - Replaces CICS READ UPDATE -> REWRITE optimistic locking with JPA @Version
 * - Affected entities: Account, Card, UserSecurity (online update programs
 *   COACTUPC.cbl, COCRDUPC.cbl, COUSR02C.cbl)
 *
 * No hardcoded credentials — datasource configuration is externalized via:
 *   spring.datasource.url      = ${DB_URL}
 *   spring.datasource.username = ${DB_USERNAME}
 *   spring.datasource.password = ${DB_PASSWORD}
 *
 * @see com.cardemo.entity — 11 JPA entity classes scanned by @EntityScan
 * @see com.cardemo.repository — 11 Spring Data JPA repositories scanned by @EnableJpaRepositories
 * ============================================================================
 */
package com.cardemo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA/Hibernate configuration for the CardDemo application.
 *
 * <p>This configuration class centralizes three critical JPA-related annotations
 * that enable Spring Data JPA repository scanning, JPA entity discovery, and
 * annotation-driven transaction management for the PostgreSQL database that
 * replaces 10 VSAM KSDS datasets from the original COBOL mainframe application.</p>
 *
 * <p>Most JPA/Hibernate settings (dialect, DDL mode, naming strategy, open-in-view)
 * are configured in {@code application.yml} properties rather than Java code,
 * following Spring Boot's externalized configuration best practice.</p>
 *
 * <h3>Repository Scanning</h3>
 * <p>{@code @EnableJpaRepositories} scans {@code com.cardemo.repository} for all 11
 * repository interfaces that replace VSAM keyed access patterns (READ, WRITE,
 * REWRITE, DELETE, STARTBR, READNEXT) with JPA-based data access methods.</p>
 *
 * <h3>Entity Scanning</h3>
 * <p>{@code @EntityScan} scans {@code com.cardemo.entity} for all 11 JPA entity
 * classes that map VSAM KSDS datasets to PostgreSQL tables.</p>
 *
 * <h3>Transaction Management</h3>
 * <p>{@code @EnableTransactionManagement} enables {@code @Transactional} support on
 * service methods, replacing the CICS READ UPDATE to REWRITE optimistic locking
 * pattern with JPA {@code @Version}-based optimistic locking.</p>
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.cardemo.repository")
@EntityScan(basePackages = "com.cardemo.entity")
@EnableTransactionManagement
public class JpaConfig {
    // Configuration is annotation-driven. JPA/Hibernate properties are
    // externalized in application.yml:
    //
    //   spring.jpa.hibernate.ddl-auto=validate
    //   spring.jpa.open-in-view=false
    //   spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
    //   spring.jpa.properties.hibernate.physical_naming_strategy=
    //       org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
    //
    // Spring Boot auto-configures DataSource, EntityManagerFactory, and
    // TransactionManager from these properties. No additional bean definitions
    // are required for the standard VSAM-to-PostgreSQL migration.

    // =========================================================================
    // Jackson BigDecimal Serialization — COBOL PIC V99 scale preservation
    // =========================================================================

    /**
     * Customizes the Jackson {@code ObjectMapper} to serialize all
     * {@link BigDecimal} values with exactly 2 decimal places, matching the
     * COBOL {@code PIC S9(n)V99} format.
     *
     * <p>By default, Jackson's JSON number serialization may strip trailing
     * zeros (e.g., 194.00 becomes 194.0 or 0.10 becomes 0.1). This
     * customizer ensures all monetary {@code BigDecimal} fields are rendered
     * with a fixed scale of 2, preserving the COBOL V99 two-decimal-place
     * contract for downstream consumers.</p>
     *
     * <p>Works in conjunction with
     * {@code spring.jackson.generator.write-bigdecimal-as-plain=true} in
     * {@code application.yml} which prevents scientific notation.</p>
     *
     * @return a Jackson customizer that registers the fixed-scale serializer
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalScaleCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("BigDecimalScale2Module");
            module.addSerializer(BigDecimal.class, new BigDecimalFixedScaleSerializer());
            builder.modulesToInstall(module);
        };
    }

    /**
     * Custom Jackson serializer that renders every {@link BigDecimal} as a
     * JSON number with exactly 2 decimal places.
     *
     * <p>Ensures COBOL {@code PIC S9(n)V99} semantic parity: monetary
     * values always display with two decimal digits (e.g., {@code 194.00},
     * {@code 0.10}, {@code 50.00}) rather than the default Jackson
     * behaviour of stripping trailing zeros.</p>
     *
     * <p>Uses {@link RoundingMode#HALF_UP} matching the COBOL default
     * rounding mode, as mandated by AAP Section 0.7.4.</p>
     */
    static final class BigDecimalFixedScaleSerializer
            extends StdSerializer<BigDecimal> {

        private static final long serialVersionUID = 1L;

        BigDecimalFixedScaleSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            if (value != null) {
                // Force 2 decimal places to match COBOL PIC V99.
                // Use writeNumber(String) overload so that the raw decimal
                // text "0.10" is emitted verbatim as a JSON number — the
                // writeNumber(BigDecimal) overload delegates to JSON number
                // formatting which strips trailing zeros.
                BigDecimal scaled =
                        value.setScale(2, RoundingMode.HALF_UP);
                gen.writeNumber(scaled.toPlainString());
            } else {
                gen.writeNull();
            }
        }
    }
}
