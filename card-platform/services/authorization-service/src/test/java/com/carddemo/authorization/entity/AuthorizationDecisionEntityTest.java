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

    /** The amount every row below carries. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The moment every row below records. */
    private static final Instant DECIDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("the entity maps the table, columns, and three operational indexes")
    void mapsTheTableAndItsColumns() {
        Table table = AuthorizationDecisionEntity.class.getAnnotation(Table.class);

        assertEquals("authorization_decision", table.name(), "the table V5 creates");
        assertEquals(3, table.indexes().length,
                "the account, retention, and authenticated-actor indexes");
        assertEquals("account_id, decided_at DESC", table.indexes()[0].columnList(),
                "an operator reads one account's recent decisions, newest first");
        assertEquals("decided_at", table.indexes()[1].columnList(),
                "the retention sweep ranges over decision time");
        assertEquals("actor, decided_at DESC", table.indexes()[2].columnList(),
                "an operator reads one authenticated actor's recent decisions");
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

    @Test
    @DisplayName("an approval naming no account cannot be built")
    void anApprovalNamingNoAccountCannotBeBuilt() {
        assertThrows(NullPointerException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, null,
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, DECIDED_AT, UUID.randomUUID()),
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

    @Test
    @DisplayName("the decline that resolved no account records none")
    void theDeclineThatResolvedNoAccountRecordsNone() {
        AuthorizationDecisionEntity decision =
                decline(null, DeclineReason.INVALID_CARD_NUMBER);

        assertNull(decision.getAccountId(),
                "reject code 0100 follows a cross-reference read that resolved no account");
        assertEquals(PanMasker.FULLY_MASKED_CARD_NUMBER,
                AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, null,
                        PanMasker.maskCardNumber(null), PanMasker.tokenOf(null), AMOUNT,
                        DeclineReason.INVALID_CARD_NUMBER.code(),
                        DeclineReason.INVALID_CARD_NUMBER.description(), DECIDED_AT,
                        UUID.randomUUID()).getMaskedCardNumber(),
                "a request naming no card number stores the fully masked form");
    }

    @Test
    @DisplayName("a decline naming no reject reason cannot be built")
    void aDeclineNamingNoRejectReasonCannotBeBuilt() {
        assertThrows(NullPointerException.class,
                () -> AuthorizationDecisionEntity.declined(TRANSACTION_ID, ACTOR, ACCOUNT_ID,
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, null, null, DECIDED_AT, UUID.randomUUID()),
                "a decline names one of the four reject codes");
    }

    @Test
    @DisplayName("an unmasked card number is refused")
    void anUnmaskedCardNumberIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, ACCOUNT_ID, CARD_NUMBER,
                        PanMasker.tokenOf(CARD_NUMBER), AMOUNT, DECIDED_AT, UUID.randomUUID()),
                "the full Primary Account Number reaches no column of this table");
    }

    @Test
    @DisplayName("an account identifier of the wrong width is refused")
    void anAccountIdentifierOfTheWrongWidthIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationDecisionEntity.approved(TRANSACTION_ID, ACTOR, "77",
                        PanMasker.maskCardNumber(CARD_NUMBER), PanMasker.tokenOf(CARD_NUMBER),
                        AMOUNT, DECIDED_AT, UUID.randomUUID()),
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
                DECIDED_AT, UUID.randomUUID());
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
                reason.code(), reason.description(), DECIDED_AT, UUID.randomUUID());
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
