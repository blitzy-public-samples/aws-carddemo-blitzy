package com.carddemo.authorization.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mapping and construction tests for {@link AuthorizationDecisionEntity}.
 *
 * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl:L424-L444} posts an approved transaction into three files
 * and {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a 430-byte reject record for a declined one, so
 * the source keeps the two outcomes in different places and stores neither as a decision.
 *
 * <p>What these tests hold is the shape the migration declares, the insert-only answer, the two
 * outcome combinations the table admits, and the rendering that withholds the account identifier and
 * the amount.
 */
final class AuthorizationDecisionEntityTest {

    /** The account the approval below names, eleven digits with leading zeros. */
    private static final String ACCOUNT_ID = "00000000077";

    /**
     * The authenticated request identity every row records, at the width
     * {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18} declares.
     */
    private static final String ACTOR = "OPERATR1";

    /** The identifier every row below is keyed on. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The card of fixture record one, from {@code app/data/ASCII/dailytran.txt}. */
    private static final String CARD_NUMBER = "4859452612877065";

    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The moment every row below records. */
    private static final Instant DECIDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * The processing moment the caller declared, at the width {@code TRAN-PROC-TS PIC X(26)} holds.
     * The value is the one record one of {@code app/data/ASCII/dailytran.txt} carries.
     */
    private static final String DECLARED_PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.410000";

    /**
     * The mapped table, its columns, and the one index a reader actually uses.
     *
     * <p>This assertion counted three indexes until {@code V18__authorization_decision_index_pruning.sql}
     * withdrew two of them. Both were written for questions no code asked: the repository declared four
     * decision-read methods and no production caller reached any of them, so every authorization on the
     * hot write path maintained two index entries nothing ever read. What remains is
     * {@code ix_authorization_decision_decided_at}, which the bounded retention delete of
     * {@code AuthorizationDecisionRepository#deleteDecidedBefore} ranges over.
     *
     * <p>The count is asserted rather than the presence of one name, so restoring an index without the
     * query that reads it fails here. {@code card-platform/docs/suggested-next-tasks.md} carries the
     * bounded operator reads the two withdrawn indexes were built for, and the index belongs in the same
     * change as the query.
     */
    @Test
    @DisplayName("the entity maps the table, columns, and the one index a reader uses")
    void mapsTheTableAndItsColumns() {
        Table table = AuthorizationDecisionEntity.class.getAnnotation(Table.class);

        assertEquals("authorization_decision", table.name(), "the table V5 creates");
        assertEquals(1, table.indexes().length,
                "only the retention index has a reader; V16 dropped the account and actor composites");
        assertEquals("decided_at", table.indexes()[0].columnList(),
                "the retention sweep ranges over decision time");
        assertEquals("transaction_id", columnNameOf("transactionId"), "the primary key");
        assertEquals("account_id", columnNameOf("accountId"), "nullable for reject code 0100");
        assertEquals("masked_card_number", columnNameOf("maskedCardNumber"), "never the full one");
        assertEquals("card_token", columnNameOf("cardToken"), "the deterministic digest");
        assertEquals("event_id", columnNameOf("eventId"), "the outbox row this published through");
    }

    @Test
    @DisplayName("every store is an insert, so a repeated identifier raises rather than replacing")
    void everyStoreIsAnInsert() {
        AuthorizationDecisionEntity decision = approval();

        assertTrue(decision.isNew(), "an assigned key would otherwise be read and then updated");
        assertEquals(TRANSACTION_ID, decision.getId(), "the identifier is the primary key");
    }

    @Test
    @DisplayName("an approval names its account and carries no reject reason")
    void anApprovalNamesItsAccountAndNoRejectReason() {
        AuthorizationDecisionEntity decision = approval();

        assertTrue(decision.isApproved(), "every rule accepted");
        assertEquals(ACCOUNT_ID, decision.getAccountId(), "the account the cross-reference named");
        assertNull(decision.getDeclineReasonCode(), "reject reason zero carries no code");
        assertNull(decision.getDeclineReasonDescription(), "and no text");
        assertEquals(PanMasker.maskCardNumber(CARD_NUMBER), decision.getMaskedCardNumber(),
                "twelve mask characters then the last four digits");
        assertEquals(PanMasker.tokenOf(CARD_NUMBER), decision.getCardToken(), "the card token");
        assertEquals(AMOUNT, decision.getAmount(), "the amount at two digits after the point");
        assertEquals(DECIDED_AT, decision.getDecidedAt(), "the moment the decision was taken");
    }

    /**
     * Proves the row records which card-token key its stored token belongs to.
     *
     * <p>A card-token key change re-derives {@code card.card_token} in the card service. This table
     * holds no card number, so it cannot re-derive its own rows, and without the version a stale
     * diagnostic is indistinguishable from a current one. No caller supplies the value: a caller naming a version
     * would be naming a key it does not hold, so the row reads the configured version at the moment
     * it is written.
     */
    @Test
    @DisplayName("a decision records the card-token version its token was taken under")
    void aDecisionRecordsTheCardTokenVersionOfItsToken() {
        AuthorizationDecisionEntity approved = approval();
        AuthorizationDecisionEntity declined = decline(ACCOUNT_ID,
                DeclineReason.OVER_CREDIT_LIMIT);

        assertEquals(PanMasker.cardTokenVersion(), approved.getCardTokenVersion(),
                "the row must name the version the configured key derives under, which is the only"
                        + " way a rotation can tell a reached row from an unreached one");
        assertEquals(PanMasker.cardTokenVersion(), declined.getCardTokenVersion(),
                "a decline carries a token on the same terms as an approval");
        assertEquals(AuthorizationDecisionEntity.CARD_TOKEN_VERSION_LENGTH,
                "999".length(),
                "the column holds one to three digits, the shape PanMasker holds a configured"
                        + " version to");
    }

    @Test
    @DisplayName("an approval naming no account cannot be built")
    void anApprovalNamingNoAccountCannotBeBuilt() {
        assertThrows(NullPointerException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, null,
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, DECIDED_AT, UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP),
                "the credit-limit and expiry rules both ran against a resolved account");
    }

    @Test
    @DisplayName("a decline carries the reject code and its verbatim source text")
    void aDeclineCarriesTheRejectCodeAndItsText() {
        AuthorizationDecisionEntity decision = decline(ACCOUNT_ID,
                DeclineReason.OVER_CREDIT_LIMIT);

        assertFalse(decision.isApproved(), "one rule declined");
        assertEquals(DeclineReason.OVER_CREDIT_LIMIT.code(), decision.getDeclineReasonCode(),
                "the code of app/cbl/CBTRN02C.cbl:L410-L412");
        assertEquals(DeclineReason.OVER_CREDIT_LIMIT.description(),
                decision.getDeclineReasonDescription(), "the verbatim source text");
    }

    /**
     * Reject code {@code 0100} resolves no account, so the row names none. It still names the event it
     * published through: AAP transformation rule T4 gives one authorization call one event, and the
     * outcome publishes {@code schemas/transaction-declined-v2.json} keyed on its transaction
     * identifier. {@code ck_authorization_decision_event} in
     * {@code V15__unresolved_decline_is_published.sql} holds every row to that.
     */
    @Test
    @DisplayName("the decline that resolved no account names no account and still names its event")
    void theDeclineThatResolvedNoAccountRecordsNone() {
        UUID published = UUID.randomUUID();
        AuthorizationDecisionEntity decision = AuthorizationDecisionEntity.declined(TRANSACTION_ID,
                ACTOR, null, PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                AMOUNT, DeclineReason.INVALID_CARD_NUMBER.code(),
                DeclineReason.INVALID_CARD_NUMBER.description(), DECIDED_AT, published,
                DECLARED_PROCESSING_TIMESTAMP);

        assertNull(decision.getAccountId(),
                "reject code 0100 follows a cross-reference read that resolved no account");
        assertEquals(published, decision.getEventId(),
                "and it names the transaction-keyed decline event it published through");
        assertEquals(PanMasker.FULLY_MASKED_CARD_NUMBER,
                AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, null,
                        PanMasker.maskCardNumber(null), PanMasker.tokenOf(null), AMOUNT,
                        DeclineReason.INVALID_CARD_NUMBER.code(),
                        DeclineReason.INVALID_CARD_NUMBER.description(), DECIDED_AT,
                        UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP).getMaskedCardNumber(),
                "a request naming no card number stores the fully masked form");
    }

    @Test
    @DisplayName("a decision that names no event cannot be built, whether or not it resolved an account")
    void aDecisionRefusesToNameAnEventItDidNotPublish() {
        assertThrows(IllegalArgumentException.class,
                () -> declineWithoutEvent(null, DeclineReason.INVALID_CARD_NUMBER),
                "the outcome that resolved no account still publishes one event and must name it");
        assertThrows(IllegalArgumentException.class,
                () -> declineWithoutEvent(ACCOUNT_ID, DeclineReason.OVER_CREDIT_LIMIT),
                "including the three declines that resolved an account");
    }

    @Test
    @DisplayName("a decline naming no reject reason cannot be built")
    void aDeclineNamingNoRejectReasonCannotBeBuilt() {
        assertThrows(NullPointerException.class,
                () -> AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, ACCOUNT_ID,
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, null, null, DECIDED_AT, UUID.randomUUID(),
                        DECLARED_PROCESSING_TIMESTAMP),
                "a decline names one of the four reject codes");
    }

    @Test
    @DisplayName("an unmasked card number is refused")
    void anUnmaskedCardNumberIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, ACCOUNT_ID, CARD_NUMBER,
                        PanMasker.tokenOf(CARD_NUMBER), AMOUNT, DECIDED_AT, UUID.randomUUID(),
                        DECLARED_PROCESSING_TIMESTAMP),
                "the full Primary Account Number reaches no column of this table");
    }

    @Test
    @DisplayName("an account identifier of the wrong width is refused")
    void anAccountIdentifierOfTheWrongWidthIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, "77",
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, DECIDED_AT, UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP),
                "the column holds eleven digits, from XREF-ACCT-ID PIC 9(11)");
    }

    @Test
    @DisplayName("the rendering withholds the account identifier and the amount")
    void theRenderingWithholdsTheAccountIdentifierAndTheAmount() {
        String rendered = approval().toString();

        assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD),
                "a stable identity is withheld from a rendering a caller may log");
        assertFalse(rendered.contains(ACCOUNT_ID), "no account identifier reaches the rendering");
        assertFalse(rendered.contains(AMOUNT.toPlainString()), "no amount reaches it either");
        assertFalse(rendered.contains(CARD_NUMBER), "and no card number");
        assertTrue(rendered.contains(TRANSACTION_ID), "the identifier under diagnosis stays");
    }

    @Test
    @DisplayName("two rows are equal when they carry one transaction identifier")
    void twoRowsAreEqualWhenTheyCarryOneIdentifier() {
        AuthorizationDecisionEntity approval = approval();
        AuthorizationDecisionEntity decline = decline(ACCOUNT_ID, DeclineReason.OVER_CREDIT_LIMIT);

        assertEquals(approval, decline, "the primary key is what identifies a row");
        assertEquals(approval.hashCode(), decline.hashCode(), "and what it hashes on");
    }

    /**
     * Builds one approved row.
     *
     * @return the row
     */
    private static AuthorizationDecisionEntity approval() {
        return AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, ACCOUNT_ID,
                PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER), AMOUNT,
                DECIDED_AT, UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one declined row.
     *
     * @param accountId the account the cross-reference named, or {@code null}
     * @param reason    the reject reason that stands
     * @return the row
     */
    private static AuthorizationDecisionEntity decline(String accountId, DeclineReason reason) {
        return AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, accountId,
                PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER), AMOUNT,
                reason.code(), reason.description(), DECIDED_AT, UUID.randomUUID(),
                DECLARED_PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one declined row naming no event, which {@code ck_authorization_decision_event} refuses
     * for every outcome.
     *
     * @param accountId the account the cross-reference named, or {@code null}
     * @param reason    the reject reason that stands
     * @return the row
     */
    private static AuthorizationDecisionEntity declineWithoutEvent(String accountId,
            DeclineReason reason) {
        return AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, accountId,
                PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER), AMOUNT,
                reason.code(), reason.description(), DECIDED_AT, null,
                DECLARED_PROCESSING_TIMESTAMP);
    }

    /**
     * Reads the column name one field maps to.
     *
     * @param fieldName the declared field
     * @return the column name the mapping declares
     */
    private static String columnNameOf(String fieldName) {
        try {
            Field field = AuthorizationDecisionEntity.class.getDeclaredField(fieldName);
            return field.getAnnotation(Column.class).name();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError(fieldName + " is not a field of the entity", absent);
        }
    }
}
