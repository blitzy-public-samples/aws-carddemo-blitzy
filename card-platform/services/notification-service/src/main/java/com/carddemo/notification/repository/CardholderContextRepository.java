package com.carddemo.notification.repository;

import com.carddemo.notification.entity.CardholderContextEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the account-keyed cardholder projection this service renders an alert from, held
 * in the table {@code cardholder_context}.
 *
 * <p>{@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} read the same ten
 * fields from the customer record directly, through the copybook {@code app/cbl/CBSTM03A.CBL:L55}
 * copies. This service owns no customer record, so {@code messaging/CustomerContextChangedConsumer}
 * applies one {@code CustomerContextChanged} event to one row here and
 * {@code messaging/TransactionPostedConsumer} reads the row by account.
 *
 * <p>The one-interface-per-aggregate shape comes from the generic parameter area
 * {@code 01 LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * dispatches every read and write behind one subroutine.
 *
 * <p>Extending {@link Repository} holds the surface to the two operations below, so no
 * {@code save} and no {@code delete} is reachable: every write travels through
 * {@link #applyContextChange}, which carries the staleness comparison in the statement itself.
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md}, and the source-to-target
 * mapping in {@code card-platform/docs/traceability-matrix.md}.
 */
public interface CardholderContextRepository
        extends Repository<CardholderContextEntity, String> {

    /** The count {@link #applyContextChange} returns when the stored row is not older. */
    int NO_ROW_WRITTEN = 0;

    /**
     * Reads the cardholder context of one account.
     *
     * @param accountId the account identifier as the column holds it, eleven digit characters
     * @return the row, and empty when no event has arrived for the account
     */
    Optional<CardholderContextEntity> findById(String accountId);

    /**
     * Applies one {@code CustomerContextChanged} event to one row, and applies nothing older.
     *
     * <p>One statement inserts the row or raises it. The {@code WHERE} clause on the conflict arm
     * compares {@code source_occurred_at} against the arriving value, so a redelivered or reordered
     * event writes nothing and the caller reads {@value #NO_ROW_WRITTEN}.</p>
     *
     * @param accountId the account this context belongs to
     * @param firstName {@code CUST-FIRST-NAME} at {@code app/cpy/CVCUS01Y.cpy:L6}
     * @param middleName {@code CUST-MIDDLE-NAME} at {@code app/cpy/CVCUS01Y.cpy:L7}
     * @param lastName {@code CUST-LAST-NAME} at {@code app/cpy/CVCUS01Y.cpy:L8}
     * @param addressLine1 {@code CUST-ADDR-LINE-1} at {@code app/cpy/CVCUS01Y.cpy:L9}
     * @param addressLine2 {@code CUST-ADDR-LINE-2} at {@code app/cpy/CVCUS01Y.cpy:L10}
     * @param addressLine3 {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy:L11}
     * @param stateCode {@code CUST-ADDR-STATE-CD} at {@code app/cpy/CVCUS01Y.cpy:L12}
     * @param countryCode {@code CUST-ADDR-COUNTRY-CD} at {@code app/cpy/CVCUS01Y.cpy:L13}
     * @param zipCode {@code CUST-ADDR-ZIP} at {@code app/cpy/CVCUS01Y.cpy:L14}
     * @param ficoScore {@code CUST-FICO-CREDIT-SCORE} at {@code app/cpy/CVCUS01Y.cpy:L22}
     * @param sourceOccurredAt when the event occurred, from its envelope
     * @param observedAt when this service applied it
     * @return 1 when the row was written, and {@value #NO_ROW_WRITTEN} when a change at least as
     *         recent was already recorded
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO cardholder_context (account_id, first_name, middle_name, last_name,
                                            address_line_1, address_line_2, address_line_3,
                                            state_code, country_code, zip_code, fico_score,
                                            source_occurred_at, observed_at)
            VALUES (:accountId, :firstName, :middleName, :lastName,
                    :addressLine1, :addressLine2, :addressLine3,
                    :stateCode, :countryCode, :zipCode, :ficoScore,
                    :sourceOccurredAt, :observedAt)
            ON CONFLICT (account_id) DO UPDATE SET
                first_name         = EXCLUDED.first_name,
                middle_name        = EXCLUDED.middle_name,
                last_name          = EXCLUDED.last_name,
                address_line_1     = EXCLUDED.address_line_1,
                address_line_2     = EXCLUDED.address_line_2,
                address_line_3     = EXCLUDED.address_line_3,
                state_code         = EXCLUDED.state_code,
                country_code       = EXCLUDED.country_code,
                zip_code           = EXCLUDED.zip_code,
                fico_score         = EXCLUDED.fico_score,
                source_occurred_at = EXCLUDED.source_occurred_at,
                observed_at        = EXCLUDED.observed_at
            WHERE cardholder_context.source_occurred_at < EXCLUDED.source_occurred_at
            """, nativeQuery = true)
    int applyContextChange(@Param("accountId") String accountId,
            @Param("firstName") String firstName,
            @Param("middleName") String middleName,
            @Param("lastName") String lastName,
            @Param("addressLine1") String addressLine1,
            @Param("addressLine2") String addressLine2,
            @Param("addressLine3") String addressLine3,
            @Param("stateCode") String stateCode,
            @Param("countryCode") String countryCode,
            @Param("zipCode") String zipCode,
            @Param("ficoScore") String ficoScore,
            @Param("sourceOccurredAt") Instant sourceOccurredAt,
            @Param("observedAt") Instant observedAt);
}
