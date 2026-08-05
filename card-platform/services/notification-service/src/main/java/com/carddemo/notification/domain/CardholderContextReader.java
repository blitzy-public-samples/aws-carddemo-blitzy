package com.carddemo.notification.domain;

import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.repository.CardholderContextRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Reads the ten cardholder fields one alert reports, and refuses to render without them.
 *
 * <p>ADDITIVE, with no COBOL ancestor as a component. {@code 5000-CREATE-STATEMENT} at
 * {@code app/cbl/CBSTM03A.CBL:L458-L504} reads the customer record directly, through the copybook
 * {@code app/cbl/CBSTM03A.CBL:L55} copies, because one program held every dataset. This service owns
 * no customer record, so it keeps a projection of the ten fields that paragraph reads and nothing
 * else.
 *
 * <p>WHY A MISSING ROW IS A FAULT. Each listener previously answered a missing row with an all-blank
 * set of fields, so an alert rendered with no name, no address and no credit score, and nothing
 * recorded that it had. The factory that produced those blanks has been removed, so no listener can
 * reach for it again. The failure was silent and it was on the cardholder-facing
 * path: the alert went out looking like a rendering with nothing to say rather than like an error.
 * Reporting the gap is the only way it can be noticed.
 *
 * <p>WHY RETRYABLE AND NOT PERMANENT. A missing row is a legitimate transient state. The account
 * service owns the customer record and publishes {@code CustomerContextChanged}, and the two events
 * race: a transaction can be authorized and posted before the context for a newly opened account has
 * arrived. The exception below is therefore left under the retryable default of
 * {@code config/KafkaConsumerConfig}, which registers only a refused deserialization as permanent. A
 * delivery that finds no context is taken again, and only a record whose attempts run out reaches the
 * dead-letter topic, where the absence is visible instead of rendered as spaces.
 *
 * <p>{@code db/migration/V2__seed.sql} bootstraps the projection for the fifty accounts of
 * {@code app/data/ASCII/custdata.txt}, so the shipped demo renders complete alerts from its first
 * event and this exception reports a genuine gap rather than an unseeded table.
 */
@Component
public class CardholderContextReader {

    /** Store of the account-keyed cardholder projection. */
    private final CardholderContextRepository cardholderContexts;

    /**
     * Takes the projection store.
     *
     * @param cardholderContexts store of the account-keyed cardholder projection
     * @throws NullPointerException if {@code cardholderContexts} is {@code null}
     */
    public CardholderContextReader(CardholderContextRepository cardholderContexts) {
        this.cardholderContexts =
                Objects.requireNonNull(cardholderContexts, "cardholderContexts is required");
    }

    /**
     * Reads the ten cardholder fields one account carries, refusing when the projection holds none.
     *
     * @param accountId the account the event names
     * @return the ten fields, each at the width its source field declares
     * @throws NullPointerException            if {@code accountId} is {@code null}
     * @throws CardholderContextMissingException when the projection holds no row for the account
     */
    public CardholderDetails require(String accountId) {
        Objects.requireNonNull(accountId, "accountId is required");
        return cardholderContexts.findById(accountId)
                .map(CardholderContextReader::detailsOf)
                .orElseThrow(() -> new CardholderContextMissingException(accountId));
    }

    /**
     * Maps one projection row onto the ten fields an alert reports.
     *
     * <p>The order matches {@code app/cbl/CBSTM03A.CBL:L462-L485}: the name, the three address lines,
     * the state and country codes, the mail code and the credit score.
     *
     * @param context one row of the account-keyed cardholder projection
     * @return the ten fields, each at the width its source field declares
     */
    private static CardholderDetails detailsOf(CardholderContextEntity context) {
        return new CardholderDetails(context.getFirstName(), context.getMiddleName(),
                context.getLastName(), context.getAddressLine1(), context.getAddressLine2(),
                context.getAddressLine3(), context.getStateCode(), context.getCountryCode(),
                context.getZipCode(), context.getFicoScore());
    }

    /**
     * Raised when no cardholder projection row carries the account an event names.
     *
     * <p>Carries no cardholder field and no card number, because every component of the projection is
     * personal data and this exception's message reaches a log line and the dead-letter diagnostic.
     * The account identifier is masked for the same reason.
     */
    public static final class CardholderContextMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Names the gap without naming the account.
         *
         * @param accountId the account whose projection row is absent
         */
        public CardholderContextMissingException(String accountId) {
            super("No cardholder_context row carries the account this event names, so an alert for"
                    + " it would render with no name, no address and no credit score. The account"
                    + " identifier is " + "*".repeat(accountId.length()) + " characters wide. The"
                    + " account service publishes CustomerContextChanged for it, and"
                    + " db/migration/V2__seed.sql bootstraps the fifty accounts of the customer"
                    + " fixture.");
        }
    }
}
