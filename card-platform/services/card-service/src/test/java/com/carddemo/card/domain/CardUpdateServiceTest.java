package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.ObservabilityConfig;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.CardCrossReferenceEntity;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.card.repository.CardCrossReferenceRepository;
import com.carddemo.card.repository.CardRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Behaviour tests for {@link CardUpdateService}, driving the orchestration directly.
 *
 * <p>The subject is the card update program {@code app/cbl/COCRDUPC.cbl}. Seven answers leave that
 * program and the order of its checks is what decides which one, so these tests drive each of the
 * seven and also drive the two orderings that a differently arranged implementation would get wrong:
 * the read ahead of the field edits, and the no-change comparison ahead of them too.
 *
 * <p>The repository, the read side and the outbox writer are stubbed. The validator is real, because
 * the constraints under test are the ones {@link CardUpdateRequest} declares and a stubbed validator
 * would test nothing. The meter registry is real for the same reason: the counters are part of the
 * observability contract and a stub would not show them moving.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the card update orchestration")
class CardUpdateServiceTest {

    /** Card number of the row under test, sixteen digits. */
    private static final String CARD_NUMBER = "4111111111111150";

    /** The account the card belongs to, eleven digits. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The card verification value the row stores and no response returns. */
    private static final String CVV = "123";

    /** The customer the cross-reference replica names for the card, nine digits. */
    private static final String CUSTOMER_ID = "000000050";

    /** When the replica row was last observed. A seeded row carries the seed instant. */
    private static final Instant OBSERVED_AT = Instant.parse("2024-01-01T00:00:00Z");

    /** Embossed name the stored row carries, before padding. */
    private static final String STORED_NAME = "ALEXANDER J MORGAN";

    /** Expiry date the stored row carries. */
    private static final LocalDate STORED_EXPIRY = LocalDate.of(2028, 11, 30);

    private static ValidatorFactory validatorFactory;

    private CardRepository cards;
    private CardCrossReferenceRepository crossReferences;
    private CardQueryService cardQueries;
    private OutboxWriter outboxWriter;
    private MeterRegistry registry;
    private CardUpdateService service;

    /** Builds the subject over stubbed collaborators and real meters before each test. */
    @BeforeEach
    void buildService() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        Validator validator = validatorFactory.getValidator();

        cards = mock(CardRepository.class);
        crossReferences = mock(CardCrossReferenceRepository.class);
        cardQueries = mock(CardQueryService.class);
        outboxWriter = mock(OutboxWriter.class);
        registry = new SimpleMeterRegistry();

        // The replica agrees with the card row unless a test says otherwise, so the divergence
        // counter stays at zero for every test that is about something else.
        when(crossReferences.findByCardNumber(CARD_NUMBER))
                .thenReturn(Optional.of(new CardCrossReferenceEntity(CARD_NUMBER, CUSTOMER_ID,
                        ACCOUNT_ID, OBSERVED_AT)));

        ObservabilityConfig meters = new ObservabilityConfig();
        CardLatencyTimers timers = meters.cardLatencyTimers(registry);
        service = new CardUpdateService(cards, crossReferences, cardQueries, outboxWriter, validator,
                meters.cardUpdatesAppliedCounter(registry),
                meters.cardUpdateConflictCounter(registry),
                meters.cardInfrastructureFailureCounter(registry),
                meters.cardCrossReferenceDivergenceCounter(registry), timers, selfProvider());
    }

    /** Closes the validator factory the test opened. */
    @AfterEach
    void closeValidatorFactory() {
        validatorFactory.close();
    }

    /** The order in which the checks run, which decides which of the seven answers arrives. */
    @Nested
    @DisplayName("the order of the checks")
    class OrderOfTheChecks {

        /**
         * Asserts a request with no card number answers the missing-value text and reads nothing.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L768-L774} tests the search key before anything is read, so
         * a request with no key never reaches the database.
         */
        @Test
        void aRequestWithNoCardNumberIsRefusedBeforeAnyRead() {
            CardUpdateResponse answer = service.updateCard(request("", STORED_NAME, "2028", "11",
                    "30", "Y"));

            assertEquals(UpdateOutcome.VALIDATION_REJECTED, answer.outcome(), "an edit refused it");
            assertEquals(CardValidationMessages.PROMPT_FOR_CARD, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L774 sets");
            verify(cardQueries, never()).findByCardNumber(any());
        }

        /**
         * Asserts sixteen zeros count as no card number rather than as a malformed one.
         *
         * <p>{@code CC-CARD-NUM-N EQUAL ZEROS} at {@code app/cbl/COCRDUPC.cbl:L770} is the third
         * condition of the absence test, so an all-zero key takes the missing-value text and not the
         * character-class text.
         */
        @Test
        void sixteenZerosCountAsNoCardNumber() {
            CardUpdateResponse answer = service.updateCard(request("0".repeat(16), STORED_NAME,
                    "2028", "11", "30", "Y"));

            assertEquals(CardValidationMessages.PROMPT_FOR_CARD, answer.message(),
                    "the absence test reaches the all-zero value");
            verify(cardQueries, never()).findByCardNumber(any());
        }

        /** Asserts a malformed card number answers the character-class text. */
        @Test
        void aMalformedCardNumberAnswersTheCharacterClassText() {
            CardUpdateResponse answer = service.updateCard(request("411111111111115X",
                    STORED_NAME, "2028", "11", "30", "Y"));

            assertEquals(CardValidationMessages.CARD_FILTER_NOT_NUMERIC, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L789 moves into the message field");
        }

        /**
         * Asserts the read runs before the field edits.
         *
         * <p>This is the ordering a boundary validation would get wrong. A request with a bad month
         * for a card number that names no row answers the not-found text, because
         * {@code 9000-READ-DATA} reaches its read at {@code app/cbl/COCRDUPC.cbl:L1394} while the
         * field edits sit behind {@code app/cbl/COCRDUPC.cbl:L698-L708}, which the not-fetched branch
         * at {@code app/cbl/COCRDUPC.cbl:L646-L663} never reaches.
         */
        @Test
        void anAbsentRowAnswersBeforeAnyFieldEditRuns() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, STORED_NAME,
                    "2028", "99", "30", "Y"));

            assertEquals(UpdateOutcome.CARD_NOT_FOUND, answer.outcome(),
                    "the absent row answers, and the month edit does not");
            assertEquals(CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L1400 sets");
            verify(cards, never()).findForUpdateByCardNumber(any());
        }

        /**
         * Asserts the no-change comparison runs before the field edits.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L685-L693} sets every field flag valid and jumps past the
         * edit chain once the comparison holds, so a submission that matches the stored values
         * receives no field message whatever those values are.
         */
        @Test
        void anUnchangedSubmissionAnswersBeforeAnyFieldEditRuns() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, STORED_NAME,
                    "2028", "11", "30", "Y"));

            assertEquals(UpdateOutcome.NO_CHANGE_DETECTED, answer.outcome(),
                    "the submitted group equals the stored group");
            assertEquals(CardValidationMessages.NO_CHANGES_DETECTED, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L682 sets");
            verify(cards, never()).findForUpdateByCardNumber(any());
        }

        /**
         * Asserts a change of letter case alone counts as no change.
         *
         * <p>Both sides of the comparison at {@code app/cbl/COCRDUPC.cbl:L680-L681} pass through
         * {@code FUNCTION UPPER-CASE}, so the fold is part of the rule and not an accident of the
         * data.
         */
        @Test
        void aChangeOfCaseAloneCountsAsNoChange() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER,
                    STORED_NAME.toLowerCase(java.util.Locale.ROOT), "2028", "11", "30", "Y"));

            assertEquals(UpdateOutcome.NO_CHANGE_DETECTED, answer.outcome(),
                    "the comparison folds both sides to upper case");
        }

        /**
         * Asserts a name shorter than the stored field still matches it.
         *
         * <p>Column {@code embossed_name} is fixed-width character storage, so a stored value returns
         * padded to fifty characters, and {@code CCUP-NEW-CRDNAME PIC X(50)} is padded too. Comparing
         * the two unpadded would report a change where the source reports none.
         */
        @Test
        void aShorterNameStillMatchesTheStoredPaddedField() {
            CardEntity padded = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CVV,
                    STORED_NAME + " ".repeat(50 - STORED_NAME.length()), STORED_EXPIRY, "Y");
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(padded));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, STORED_NAME,
                    "2028", "11", "30", "Y"));

            assertEquals(UpdateOutcome.NO_CHANGE_DETECTED, answer.outcome(),
                    "both sides are padded to the width the Picture clause declares");
        }

        /**
         * Asserts the field edits answer in the order the source performs them.
         *
         * <p>All four values are wrong at once. The name edit runs first at
         * {@code app/cbl/COCRDUPC.cbl:L698}, so the name text answers and the other three do not.
         */
        @Test
        void theFirstFailingEditInSourceOrderOwnsTheAnswer() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN 9",
                    "1800", "99", "30", "X"));

            assertEquals(CardValidationMessages.NAME_MUST_BE_ALPHA, answer.message(),
                    "1230-EDIT-NAME runs ahead of the status, month and year edits");
        }

        /** Asserts the status edit answers ahead of the two expiry edits. */
        @Test
        void theStatusEditAnswersAheadOfTheExpiryEdits() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "1800", "99", "30", "X"));

            assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO, answer.message(),
                    "1240-EDIT-CARDSTATUS runs ahead of 1250 and 1260");
        }

        /** Asserts the month edit answers ahead of the year edit. */
        @Test
        void theMonthEditAnswersAheadOfTheYearEdit() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "1800", "99", "30", "Y"));

            assertEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID, answer.message(),
                    "1250-EDIT-EXPIRY-MON runs ahead of 1260-EDIT-EXPIRY-YEAR");
        }

        /** Asserts a year outside the source range answers the year text. */
        @Test
        void aYearOutsideTheSourceRangeAnswersTheYearText() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "1800", "11", "30", "Y"));

            assertEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID, answer.message(),
                    "88 VALID-YEAR VALUES 1950 THRU 2099 at app/cbl/COCRDUPC.cbl:L99");
        }

        /**
         * Asserts a day that is not on the calendar is refused, which is the one divergence of this
         * path.
         *
         * <p>The source joins the three parts into ten characters of text at
         * {@code app/cbl/COCRDUPC.cbl:L1467-L1474} and would store {@code 2028-02-31}. Column
         * {@code expiration_date} is a {@code DATE}, so this platform refuses it, and the additive
         * text says so rather than borrowing a text the source wrote for something else.
         */
        @Test
        void aDayThatIsNotOnTheCalendarIsRefused() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2028", "02", "31", "Y"));

            assertEquals(UpdateOutcome.VALIDATION_REJECTED, answer.outcome(), "the date is refused");
            assertEquals(CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE,
                    answer.message(), "the additive text names the calendar and not the width");
            verify(cards, never()).findForUpdateByCardNumber(any());
        }

        /** Asserts a day of three characters is refused by the width edit, not by the calendar. */
        @Test
        void aDayWiderThanTheSourceFieldIsRefusedByTheWidthEdit() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2028", "11", "030", "Y"));

            assertEquals(CardValidationMessages.ADDITIVE_CARD_EXPIRY_DAY_WIDTH, answer.message(),
                    "a 3270 field two characters wide cannot deliver a third");
        }
    }

    /** The write, and the two ways it can be refused once the row has been read. */
    @Nested
    @DisplayName("the write")
    class TheWrite {

        /**
         * Asserts an applied update rewrites the row, writes one event and moves the applied
         * counter.
         */
        @Test
        void anAppliedUpdateWritesTheRowAndOneEvent() {
            CardEntity stored = storedCard();
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(stored));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", "31", "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(), "the row was rewritten");
            assertNull(answer.message(), "UPDATED carries no message");
            assertEquals("MORGAN" + " ".repeat(44), locked.getEmbossedName(),
                    "the name is stored at the width the Picture clause declares");
            assertEquals(LocalDate.of(2029, 12, 31), locked.getExpirationDate(),
                    "the three parts are joined into one date");
            assertEquals("N", locked.getActiveStatus(), "the status is stored as submitted");
            verify(cards).save(locked);
            verify(outboxWriter).writeCardUpdated(locked);
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED),
                    "the applied counter moved once");
        }

        /** Asserts the account identifier and the verification value are left as they were. */
        @Test
        void theAccountAndTheVerificationValueAreNeverWritten() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N"));

            assertEquals(ACCOUNT_ID, locked.getAccountId(),
                    "app/bms/COCRDUP.bms declares no account field");
            assertEquals(CARD_NUMBER, locked.getCardNumber(), "the key is the search key");
        }

        /**
         * Asserts a row another writer changed first is refused, with the row as it now stands.
         *
         * <p>The locked row carries a different status from the row the caller read, which is what
         * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} compares and
         * {@code app/cbl/COCRDUPC.cbl:L1511-L1517} reports.
         */
        @Test
        void aRowAnotherWriterChangedFirstIsRefusedWithTheCurrentValues() {
            CardEntity changed = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CVV, "SOMEBODY ELSE",
                    LocalDate.of(2030, 1, 2), "N");
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(changed));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", "31", "N"));

            assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, answer.outcome(), "the race is lost");
            assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L1511 sets");
            RefreshedCard refreshed = answer.refreshedCard();
            assertNotNull(refreshed, "the outcome carries the row as it now stands");
            assertEquals("2030", refreshed.expiryYear(), "the refreshed year slice");
            assertEquals("01", refreshed.expiryMonth(), "the refreshed month slice, zero-padded");
            assertEquals("02", refreshed.expiryDay(), "the refreshed day slice, zero-padded");
            assertEquals("N", refreshed.activeStatus(), "the refreshed status");
            verify(cards, never()).save(any());
            verify(outboxWriter, never()).writeCardUpdated(any());
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_CONFLICTS),
                    "the conflict counter moved once");
        }

        /** Asserts a row that could not be locked is refused with the source's lock text. */
        @Test
        void aRowThatCouldNotBeLockedIsRefused() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", "31", "N"));

            assertEquals(UpdateOutcome.LOCK_NOT_ACQUIRED, answer.outcome(),
                    "the read for update returned nothing");
            assertEquals(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L1446 sets");
            verify(cards, never()).save(any());
        }

        /**
         * Asserts a failing event write refuses the update rather than committing the row alone.
         *
         * <p>The two rows commit together or neither commits, so a failure of the event write has to
         * refuse the update. The service reports it by throwing out of the transactional method,
         * which is what rolls the row back, and answers the source's post-lock failure text.
         */
        @Test
        void aFailingEventWriteRefusesTheUpdate() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));
            when(outboxWriter.writeCardUpdated(locked))
                    .thenThrow(new IllegalStateException("the broker row could not be stored"));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", "31", "N"));

            assertEquals(UpdateOutcome.UPDATE_FAILED_AFTER_LOCK, answer.outcome(),
                    "the write failed once the row was held");
            assertEquals(CardValidationMessages.LOCKED_BUT_UPDATE_FAILED, answer.message(),
                    "the text app/cbl/COCRDUPC.cbl:L1491 sets");
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_FAILURES),
                    "the failure counter moved once");
            assertEquals(0.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED),
                    "the applied counter did not move");
        }

        /**
         * Asserts the transactional method throws rather than returning, so the transaction rolls
         * back.
         *
         * <p>A method that returned an outcome would commit whatever the failing statement had
         * already written, which for this path is a rewritten card row with no event beside it.
         */
        @Test
        void theTransactionalMethodThrowsSoTheTransactionRollsBack() {
            CardEntity locked = storedCard();
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));
            when(outboxWriter.writeCardUpdated(locked))
                    .thenThrow(new IllegalStateException("the broker row could not be stored"));

            assertThrows(CardUpdateService.UpdateFailedAfterLock.class,
                    () -> service.applyUpdate(request(CARD_NUMBER, "MORGAN", "2029", "12", "31",
                            "N"), snapshotOfStored(), LocalDate.of(2029, 12, 31)),
                    "the failure leaves the transactional method by throwing");
        }
    }

    /**
     * The comparison against the {@code card_xref} replica, which reports and decides nothing.
     *
     * <p>This service is the only one holding both the account of the card row and the account the
     * replica names for the same card. A disagreement puts the published event on one account's
     * partition while every authorization decision about that card reads another, and nothing else
     * in the platform can see it.
     */
    @Nested
    @DisplayName("the cross-reference comparison")
    class TheCrossReferenceComparison {

        /** Asserts an agreeing replica leaves the divergence counter alone. */
        @Test
        void anAgreeingReplicaMovesNoCounter() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N"));

            assertEquals(0.0, counter(ObservabilityConfig.METRIC_CARD_XREF_DIVERGENCE),
                    "both copies are seeded from app/data/ASCII/cardxref.txt and no operation in "
                            + "scope changes a cross-reference, so agreement is the expected case");
        }

        /**
         * Asserts the comparison reads the replica keyed on the card number of the locked row.
         *
         * <p>Reading it on the submitted value would compare against whatever the caller sent, and
         * the point of the comparison is the two values that were true at the moment of the write.
         */
        @Test
        void theComparisonKeysOnTheLockedRow() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N"));

            verify(crossReferences).findByCardNumber(CARD_NUMBER);
        }

        /**
         * Asserts a replica naming another account is counted, and that the update still commits.
         *
         * <p>The source performs no such comparison. {@code app/cbl/COCRDUPC.cbl:L356} reads
         * {@code *COPY CVACT03Y.} commented out and its update path never opens the cross-reference
         * dataset, so refusing the update would answer a text no card program writes.
         */
        @Test
        void aReplicaNamingAnotherAccountIsCountedAndTheUpdateStillCommits() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));
            when(crossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(
                    new CardCrossReferenceEntity(CARD_NUMBER, CUSTOMER_ID, "00000000099",
                            OBSERVED_AT)));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN", "2029",
                    "12", "31", "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                    "the comparison reports and decides nothing, so the outcome is unchanged");
            verify(cards).save(locked);
            verify(outboxWriter).writeCardUpdated(locked);
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_XREF_DIVERGENCE),
                    "the disagreement is counted once");
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED),
                    "and the applied counter still moved");
        }

        /**
         * Asserts an absent replica row is counted, and that no row is written to repair it.
         *
         * <p>A card with no cross-reference is a card every authorization declines with reason 100 at
         * {@code app/cbl/CBTRN02C.cbl:L385-L387} while this service serves it as active. This service
         * cannot create the missing row: {@code XREF-CUST-ID PIC 9(09)} at
         * {@code app/cpy/CVACT03Y.cpy:L6} is a column the card record does not carry.
         */
        @Test
        void anAbsentReplicaRowIsCountedAndNothingIsWrittenToRepairIt() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));
            when(crossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN", "2029",
                    "12", "31", "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(), "the outcome is unchanged");
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_XREF_DIVERGENCE),
                    "an absent row is a disagreement and is counted");
            verify(crossReferences, never()).save(any());
            verify(crossReferences, never()).saveAll(any());
        }

        /**
         * Asserts a refused update makes no comparison at all.
         *
         * <p>The comparison sits after the concurrency check, so an update that never reaches the
         * write does not read the replica and does not report a disagreement about a row it left
         * alone.
         */
        @Test
        void aRefusedUpdateMakesNoComparison() {
            CardEntity changed = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CVV, "SOMEBODY ELSE",
                    LocalDate.of(2030, 1, 2), "N");
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(changed));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN", "2029",
                    "12", "31", "N"));

            assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, answer.outcome(),
                    "the race was lost before the comparison");
            verify(crossReferences, never()).findByCardNumber(any());
            assertEquals(0.0, counter(ObservabilityConfig.METRIC_CARD_XREF_DIVERGENCE),
                    "no disagreement is reported about a row this call did not write");
        }
    }

    /** The latency timer, which records whichever answer the call reached. */
    @Nested
    @DisplayName("the latency timer")
    class TheLatencyTimer {

        /** Asserts a refused update is timed as well as an applied one. */
        @Test
        void aRefusedUpdateIsTimedToo() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N"));

            assertEquals(1L, registry.find(ObservabilityConfig.METRIC_CARD_UPDATE_LATENCY)
                    .timer().count(), "one recording, for the answer the call reached");
        }
    }

    /** The two arguments the transactional method requires. */
    @Nested
    @DisplayName("the transactional method's own guards")
    class TransactionalGuards {

        /** Asserts each of the three arguments is required. */
        @Test
        void everyArgumentIsRequired() {
            CardUpdateRequest submitted = request(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N");
            RefreshedCard fetched = snapshotOfStored();
            LocalDate expiry = LocalDate.of(2029, 12, 31);

            assertThrows(NullPointerException.class,
                    () -> service.applyUpdate(null, fetched, expiry), "the request is required");
            assertThrows(NullPointerException.class,
                    () -> service.applyUpdate(submitted, null, expiry), "the snapshot is required");
            assertThrows(NullPointerException.class,
                    () -> service.applyUpdate(submitted, fetched, null), "the date is required");
        }

        /** Asserts the constructor refuses every absent collaborator. */
        @Test
        void theConstructorRefusesAnAbsentCollaborator() {
            assertThrows(NullPointerException.class,
                    () -> new CardUpdateService(null, crossReferences, cardQueries, outboxWriter,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            new ObservabilityConfig()
                                    .cardCrossReferenceDivergenceCounter(registry),
                            new ObservabilityConfig().cardLatencyTimers(registry), selfProvider()),
                    "the card repository is required");
            assertThrows(NullPointerException.class,
                    () -> new CardUpdateService(cards, null, cardQueries, outboxWriter,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            new ObservabilityConfig()
                                    .cardCrossReferenceDivergenceCounter(registry),
                            new ObservabilityConfig().cardLatencyTimers(registry), selfProvider()),
                    "the cross-reference repository is required");
        }
    }

    /**
     * Asserts the ordered property list covers every component the request declares.
     *
     * <p>A component added to {@link CardUpdateRequest} with no entry in the order would be validated
     * by nothing, and the request would be accepted with a value its own annotation refuses. This
     * assertion is what makes the two lists a complete cover rather than a partial one.
     */
    @Test
    @DisplayName("the ordered property lists cover every component of the request")
    void theOrderedPropertyListsCoverEveryComponent() {
        List<String> ordered = new java.util.ArrayList<>(CardUpdateService.SEARCH_KEY_PROPERTIES);
        ordered.addAll(CardUpdateService.DATA_PROPERTIES);

        List<String> declared = java.util.Arrays.stream(
                        CardUpdateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertEquals(new java.util.TreeSet<>(declared), new java.util.TreeSet<>(ordered),
                "every component of the request is validated by one of the two ordered lists");
        assertEquals(declared.size(), ordered.size(), "no component is validated twice");
        assertEquals(List.of("cardNumber"), CardUpdateService.SEARCH_KEY_PROPERTIES,
                "the search key is the card number, which app/cbl/COCRDUPC.cbl:L1427 keys on");
        assertEquals(List.of("embossedName", "activeStatus", "expiryMonth", "expiryYear",
                        "expiryDay"), CardUpdateService.DATA_PROPERTIES,
                "the order app/cbl/COCRDUPC.cbl:L698-L708 performs, with the unedited day last");
    }

    /**
     * Builds one submitted update.
     *
     * @param cardNumber   the card number
     * @param embossedName the embossed name
     * @param expiryYear   the four-character year
     * @param expiryMonth  the two-character month
     * @param expiryDay    the two-character day
     * @param activeStatus the one-character status
     * @return the request
     */
    private static CardUpdateRequest request(String cardNumber, String embossedName,
            String expiryYear, String expiryMonth, String expiryDay, String activeStatus) {
        return new CardUpdateRequest(cardNumber, embossedName, expiryYear, expiryMonth, expiryDay,
                activeStatus);
    }

    /**
     * Builds the stored card row every test reads.
     *
     * @return the row
     */
    private static CardEntity storedCard() {
        return new CardEntity(CARD_NUMBER, ACCOUNT_ID, CVV, STORED_NAME, STORED_EXPIRY, "Y");
    }

    /**
     * Builds the snapshot of the stored row, padded the way the service pads it.
     *
     * @return the snapshot
     */
    private static RefreshedCard snapshotOfStored() {
        return new RefreshedCard(STORED_NAME + " ".repeat(50 - STORED_NAME.length()), "2028", "11",
                "30", "Y");
    }

    /**
     * Reads one counter of the registry this test built.
     *
     * @param name the meter name
     * @return the count
     */
    private double counter(String name) {
        return registry.find(name).counter().count();
    }

    /**
     * Supplies the subject through a provider, standing in for the transactional proxy.
     *
     * <p>The provider hands back the same instance, so {@code applyUpdate} runs with no transaction
     * here. That is the right shape for a unit test: the transaction boundary is a property of the
     * proxy and {@code repository/CardRepositoryIT} exercises the real one.
     *
     * @return the provider
     */
    private ObjectProvider<CardUpdateService> selfProvider() {
        return new ObjectProvider<>() {
            @Override
            public CardUpdateService getObject() {
                return service;
            }

            @Override
            public CardUpdateService getObject(Object... arguments) {
                return service;
            }

            @Override
            public CardUpdateService getIfAvailable() {
                return service;
            }

            @Override
            public CardUpdateService getIfUnique() {
                return service;
            }
        };
    }
}
