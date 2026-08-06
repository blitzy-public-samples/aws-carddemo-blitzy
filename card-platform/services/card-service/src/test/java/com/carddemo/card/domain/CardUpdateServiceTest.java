package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
    private static final String CARD_VERIFICATION_VALUE = "123";

    /** Embossed name the stored row carries, before padding. */
    private static final String STORED_NAME = "ALEXANDER J MORGAN";

    /** Expiry date the stored row carries. Its day is the one every update carries through. */
    private static final LocalDate STORED_EXPIRY = LocalDate.of(2028, 11, 30);

    /** The two-character day slice of {@link #STORED_EXPIRY}. */
    private static final String STORED_DAY = "30";

    private static ValidatorFactory validatorFactory;

    private CardRepository cards;
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
        cardQueries = mock(CardQueryService.class);
        outboxWriter = mock(OutboxWriter.class);
        registry = new SimpleMeterRegistry();

        ObservabilityConfig meters = new ObservabilityConfig();
        CardLatencyTimers timers = meters.cardLatencyTimers(registry);
        service = new CardUpdateService(cards, cardQueries, outboxWriter, validator,
                meters.cardUpdatesAppliedCounter(registry),
                meters.cardUpdateConflictCounter(registry),
                meters.cardInfrastructureFailureCounter(registry), timers, selfProvider());
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
            CardEntity padded = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE,
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
         *
         * <p>The stored date is the submitted year and month joined with the stored day, which is
         * what {@code app/cbl/COCRDUPC.cbl:L1467-L1474} joins once the protected day field has
         * echoed back.
         */
        @Test
        void anAppliedUpdateWritesTheRowAndOneEvent() {
            CardEntity stored = storedCard();
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(stored));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", STORED_DAY, "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(), "the row was rewritten");
            assertNull(answer.message(), "UPDATED carries no message");
            assertEquals("MORGAN" + " ".repeat(44), locked.getEmbossedName(),
                    "the name is stored at the width the Picture clause declares");
            assertEquals(LocalDate.of(2029, 12, 30), locked.getExpirationDate(),
                    "the submitted year and month join the stored day");
            assertEquals("N", locked.getActiveStatus(), "the status is stored as submitted");
            verify(cards).save(locked);
            verify(outboxWriter).writeCardUpdated(locked);
            assertEquals(1.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED),
                    "the applied counter moved once");
        }

        /**
         * Asserts a submitted expiry day never reaches the column, whatever the caller sends.
         *
         * <p>{@code app/bms/COCRDUP.bms:L142} declares {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)}
         * while the four editable fields at L107, L117, L127 and L135 declare {@code UNPROT}, so a
         * 3270 operator cannot type a day at all. {@code app/cbl/COCRDUPC.cbl:L1110},
         * {@code :L1123} and {@code :L1127} send {@code CCUP-OLD-EXPDAY} to that field on every
         * path, and the move of {@code CCUP-NEW-EXPDAY} at {@code :L1122} is commented out. The
         * absence of a 1270 paragraph follows from the field being protected.
         */
        @Test
        void aSubmittedDayNeverReachesTheStoredDate() {
            CardEntity locked = storedCard();
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(locked));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", "07", "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(), "the row was rewritten");
            assertEquals(LocalDate.of(2029, 12, 30), locked.getExpirationDate(),
                    "the stored day survives a caller that submitted another one");
        }

        /**
         * Asserts a change of the day alone is no change, since the effective day is the stored one.
         *
         * <p>A 3270 operator cannot reach this state. A Representational State Transfer (REST)
         * caller can send any day, and the protected field of the map is reproduced by substituting
         * the stored day before the comparison at {@code app/cbl/COCRDUPC.cbl:L680-L683}.
         */
        @Test
        void aChangeOfTheDayAloneIsNoChange() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, STORED_NAME,
                    "2028", "11", "07", "Y"));

            assertEquals(UpdateOutcome.NO_CHANGE_DETECTED, answer.outcome(),
                    "the submitted day is replaced by the stored day before the comparison");
            verify(cards, never()).findForUpdateByCardNumber(any());
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
            CardEntity changed = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE, "SOMEBODY ELSE",
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

        /**
         * Asserts a stored name that changed only in letter case is no concurrent change.
         *
         * <p>The fold sits on both sides. {@code 9000-READ-DATA.} folds the fetched name at
         * {@code app/cbl/COCRDUPC.cbl:L1356-L1358} before storing it at
         * {@code app/cbl/COCRDUPC.cbl:L1360}, and {@code 9300-CHECK-CHANGE-IN-REC.} folds the
         * re-read name at {@code app/cbl/COCRDUPC.cbl:L1499-L1501} before comparing it at
         * {@code app/cbl/COCRDUPC.cbl:L1504}. Folding one side alone reports a race for any
         * mixed-case name, and {@code app/data/ASCII/carddata.txt} carries names such as
         * {@code Aniya Von}.
         */
        @Test
        void aStoredNameThatChangedOnlyInCaseIsNoConcurrentChange() {
            CardEntity refolded = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE,
                    STORED_NAME.toLowerCase(Locale.ROOT), STORED_EXPIRY, "Y");
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(refolded));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", STORED_DAY, "N"));

            assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                    "both sides of the comparison are folded, so the case change is no race");
            assertEquals(0.0, counter(ObservabilityConfig.METRIC_CARD_UPDATE_CONFLICTS),
                    "the conflict counter did not move");
        }

        /**
         * Asserts a refreshed snapshot carries the stored name folded, as the source refreshes it.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1513} moves the already-folded
         * {@code CARD-EMBOSSED-NAME} into the saved field.
         */
        @Test
        void aRefreshedSnapshotCarriesTheFoldedName() {
            CardEntity changed = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE,
                    "Somebody Else", LocalDate.of(2030, 1, 2), "N");
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
            when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(changed));

            CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN",
                    "2029", "12", STORED_DAY, "N"));

            assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, answer.outcome(), "the race is lost");
            assertEquals("SOMEBODY ELSE" + " ".repeat(37), answer.refreshedCard().embossedName(),
                    "the refreshed name is folded and padded to the declared width");
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
                    () -> new CardUpdateService(null, cardQueries, outboxWriter,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            new ObservabilityConfig().cardLatencyTimers(registry), selfProvider()),
                    "the card repository is required");
            assertThrows(NullPointerException.class,
                    () -> new CardUpdateService(cards, null, outboxWriter,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            new ObservabilityConfig().cardLatencyTimers(registry), selfProvider()),
                    "the read side is required");
            assertThrows(NullPointerException.class,
                    () -> new CardUpdateService(cards, cardQueries, null,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            new ObservabilityConfig().cardLatencyTimers(registry), selfProvider()),
                    "the outbox writer is required");
            assertThrows(NullPointerException.class,
                    () -> new CardUpdateService(cards, cardQueries, outboxWriter,
                            validatorFactory.getValidator(),
                            new ObservabilityConfig().cardUpdatesAppliedCounter(registry),
                            new ObservabilityConfig().cardUpdateConflictCounter(registry),
                            new ObservabilityConfig().cardInfrastructureFailureCounter(registry),
                            null, selfProvider()),
                    "the latency timers are required");
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
     * Asserts the request constraints still carry the ranges the source condition names declare.
     *
     * <p>{@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95} and
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99} are the only
     * statements of those ranges. {@link CardValidationMessages#CARD_EXPIRY_YEAR_NOT_VALID} names no
     * bound, so a constraint narrowed by mistake would answer the same text and pass unnoticed. This
     * assertion is what fails instead.
     */
    @Test
    @DisplayName("the request constraints carry the measured source ranges")
    void theRequestConstraintsCarryTheMeasuredRanges() {
        assertEquals(1, CardUpdateService.EXPIRY_MONTH_LOWER_BOUND, "app/cbl/COCRDUPC.cbl:L95");
        assertEquals(12, CardUpdateService.EXPIRY_MONTH_UPPER_BOUND, "app/cbl/COCRDUPC.cbl:L95");
        assertEquals(1950, CardUpdateService.EXPIRY_YEAR_LOWER_BOUND, "app/cbl/COCRDUPC.cbl:L99");
        assertEquals(2099, CardUpdateService.EXPIRY_YEAR_UPPER_BOUND, "app/cbl/COCRDUPC.cbl:L99");
        assertEquals(List.of("Y", "N"), CardUpdateService.ACTIVE_STATUS_FLAGS,
                "88 FLG-YES-NO-VALID VALUES 'Y', 'N'. at app/cbl/COCRDUPC.cbl:L91");
        assertEquals(16, PicClause.CARD_NUM_WIDTH,
                "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5");
        assertEquals(11, CardUpdateService.ACCOUNT_ID_WIDTH,
                "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6");
        assertEquals(3, CardUpdateService.CARD_VERIFICATION_VALUE_WIDTH,
                "CARD-CARD_VERIFICATION_VALUE-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7");

        when(cardQueries.findByCardNumber(CARD_NUMBER))
                .thenReturn(Optional.of(storedCard()), Optional.of(storedCard()),
                        Optional.of(storedCard()), Optional.of(storedCard()));

        assertEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "00", STORED_DAY, "N"))
                        .message(), "month zero is below the lower bound");
        assertEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "13", STORED_DAY, "N"))
                        .message(), "month thirteen is above the upper bound");
        assertEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "1949", "12", STORED_DAY, "N"))
                        .message(), "1949 is below the lower bound");
        assertEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "2100", "12", STORED_DAY, "N"))
                        .message(), "2100 is above the upper bound");
    }

    /**
     * Asserts the boundary values of both ranges are admitted and a lower-case flag is not.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L91} tests two upper-case literals, and no edit of the program
     * folds the case of that field.
     */
    @Test
    @DisplayName("the boundary values pass and a lower-case status flag does not")
    void theBoundaryValuesPassAndALowerCaseFlagDoesNot() {
        when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
        when(cards.findForUpdateByCardNumber(CARD_NUMBER))
                .thenAnswer(invocation -> Optional.of(storedCard()));

        assertEquals(UpdateOutcome.UPDATED,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "1950", "01", STORED_DAY, "N"))
                        .outcome(), "the lowest month and the lowest year are admitted");
        assertEquals(UpdateOutcome.UPDATED,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "2099", "12", STORED_DAY, "N"))
                        .outcome(), "the highest month and the highest year are admitted");
        assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12", STORED_DAY, "y"))
                        .message(), "a lower-case flag fails");
    }

    /**
     * Asserts the embossed-name edit admits letters and spaces and nothing else.
     *
     * <p>{@code 1230-EDIT-NAME.} at {@code app/cbl/COCRDUPC.cbl:L806-L840} converts every character
     * of {@code LIT-ALL-ALPHA-FROM} at {@code app/cbl/COCRDUPC.cbl:L255-L257} to a space and then
     * requires the field to trim to nothing. The blank test at
     * {@code app/cbl/COCRDUPC.cbl:L811-L813} runs first, so a name of spaces answers
     * {@code 'Card name not provided'} at {@code app/cbl/COCRDUPC.cbl:L182} and never reaches the
     * conversion.
     */
    @Test
    @DisplayName("the embossed-name edit admits letters and spaces only")
    void theEmbossedNameEditAdmitsLettersAndSpacesOnly() {
        when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
        when(cards.findForUpdateByCardNumber(CARD_NUMBER))
                .thenAnswer(invocation -> Optional.of(storedCard()));

        assertEquals(UpdateOutcome.UPDATED,
                service.updateCard(request(CARD_NUMBER, "Aniya Von", "2029", "12", STORED_DAY, "N"))
                        .outcome(), "letters and one space are admitted");
        assertEquals(CardValidationMessages.NAME_MUST_BE_ALPHA,
                service.updateCard(request(CARD_NUMBER, "Aniya-Von", "2029", "12", STORED_DAY, "N"))
                        .message(), "a hyphen is outside LIT-ALL-ALPHA-FROM");
        assertEquals(CardValidationMessages.PROMPT_FOR_NAME,
                service.updateCard(request(CARD_NUMBER, "   ", "2029", "12", STORED_DAY, "N"))
                        .message(), "the blank test runs ahead of the conversion test");
    }

    /**
     * Asserts no outcome carries an unmasked card number or the card verification value.
     *
     * <p>{@code CARD-CARD_VERIFICATION_VALUE-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored in the clear and
     * emitted nowhere. The masked rendering is what {@link PanMasker#maskCardNumber(String)}
     * produces, twelve mask characters and the last four digits.
     */
    @Test
    @DisplayName("no outcome carries an unmasked card number or the card verification value")
    void noOutcomeCarriesCardholderSecrets() {
        CardEntity changed = new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE,
                "SOMEBODY ELSE", LocalDate.of(2030, 1, 2), "N");
        when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
        when(cards.findForUpdateByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(changed));

        CardUpdateResponse answer = service.updateCard(request(CARD_NUMBER, "MORGAN", "2029", "12",
                STORED_DAY, "N"));

        String rendered = answer.toString() + answer.refreshedCard().toString();
        assertFalse(rendered.contains(CARD_NUMBER), "no rendering carries the full card number");
        assertFalse(rendered.contains(CARD_VERIFICATION_VALUE),
                "no rendering carries the card verification value");
        assertEquals("************1150", PanMasker.maskCardNumber(CARD_NUMBER),
                "the masked rendering the log lines carry");
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
        return new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE, STORED_NAME, STORED_EXPIRY, "Y");
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
