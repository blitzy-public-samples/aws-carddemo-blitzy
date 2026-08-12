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
 * <p>A MISSING ROW IS A FAULT. Answering a missing row with an all-blank set of fields would render
 * an alert with no name, no address and no credit score, and record nothing about it. No factory for
 * such a set exists, so no listener can reach for one. The failure would be silent and on the
 * cardholder-facing path: the alert would look like a rendering with nothing to say rather than like
 * an error. Reporting the gap is the only way it can be noticed.
 *
 * <p>A missing row is a transient state: the account service owns the customer record and publishes
 * {@code CustomerContextChanged}, so a transaction can be authorized before that context arrives. The
 * exception below stays under the retryable default of {@code config/KafkaConsumerConfig}, which
 * registers only a refused deserialization as permanent, so a delivery that finds no context is taken
 * again and only a record whose attempts run out reaches the dead-letter topic.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
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
