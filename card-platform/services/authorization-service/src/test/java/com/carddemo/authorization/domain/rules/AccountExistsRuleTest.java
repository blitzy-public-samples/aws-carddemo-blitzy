package com.carddemo.authorization.domain.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.events.DeclineReason;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.Order;

/**
 * Unit tests for {@link AccountExistsRule}, which assigns reject code {@code 0101}.
 *
 * <p>Subject paragraph {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422}, whose
 * opening ten lines are quoted below. {@code app/cbl/CBTRN02C.cbl:L394} moves the account
 * identifier into the key field and {@code app/cbl/CBTRN02C.cbl:L395} reads the account dataset.
 * The {@code INVALID KEY} limb at {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns the code and its
 * text.
 *
 * <pre>{@code
 *       1500-B-LOOKUP-ACCT.
 *           MOVE XREF-ACCT-ID TO FD-ACCT-ID
 *           READ ACCOUNT-FILE INTO ACCOUNT-RECORD
 *              INVALID KEY
 *                MOVE 101 TO WS-VALIDATION-FAIL-REASON
 *                MOVE 'ACCOUNT RECORD NOT FOUND'
 *                  TO WS-VALIDATION-FAIL-REASON-DESC
 *              NOT INVALID KEY
 *      *         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT
 *      *         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT
 * }</pre>
 *
 * <p>Both commented lines above are reproduced nowhere, and this rule writes no log line.
 *
 * <p>The lookup key arrives on the cross-reference row the preceding rule resolved.
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and {@code ACCT-ID PIC 9(11)}
 * at {@code app/cpy/CVACT01Y.cpy:L5} share one width, which {@code KEYS(11 0)} at
 * {@code app/jcl/ACCTFILE.jcl:L40} repeats. That member declares no alternate index, so one
 * identifier matches at most one row. The eleven characters reach the lookup untouched, leading
 * zeros and all.
 *
 * <p>A keyed read that finds no row answers with a reject reason and raises nothing.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with return code 4 once any record was
 * rejected, so a reject is ordinary traffic. The abend routine at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} serves file and write failures, and no reject reaches it.
 *
 * <p>Reject code {@code 0101} is reached by none of the 300 records of
 * {@code app/data/ASCII/dailytran.txt}. Every cross-reference account resolves in
 * {@code app/data/ASCII/acctdata.txt}, whose fifty rows run {@code 00000000001} through
 * {@code 00000000050}, so each scenario below builds its own input. Fixture values are sliced at
 * the copybook offsets, never read off as a gloss of the digits.
 *
 * <p>Three deliberate non-additions carry an assertion. No account-status test:
 * {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} exists, no program reads
 * it before posting, and the snapshot row holds no such column. No credit-limit or expiry test,
 * which {@code app/cbl/CBTRN02C.cbl:L403-L420} places after this read and the two later rules own.
 * No customer read: {@code XREF-CUST-ID} at {@code app/cpy/CVACT03Y.cpy:L6} arrives on the resolved
 * row.
 *
 * <p>Two more carry none. No card-status test, since {@code app/jcl/POSTTRAN.jcl} allocates no card
 * dataset. No card-number format test, since this rule reads no card number.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class AccountExistsRuleTest {

    /**
     * The card number record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 263-278. Row 21 of {@code app/data/ASCII/cardxref.txt} resolves it.
     */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /**
     * The customer identifier row 21 of {@code app/data/ASCII/cardxref.txt} carries at positions
     * 17-25, the width {@code XREF-CUST-ID PIC 9(09)} declares at {@code app/cpy/CVACT03Y.cpy:L6}.
     */
    private static final String FIXTURE_CUSTOMER_ID = "000000007";

    /**
     * The account identifier row 21 of {@code app/data/ASCII/cardxref.txt} carries at positions
     * 26-36, the width {@code XREF-ACCT-ID PIC 9(11)} declares at {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String FIXTURE_ACCOUNT_ID = "00000000007";

    /**
     * The account identifier row 1 of {@code app/data/ASCII/cardxref.txt} carries, which belongs to
     * a different card number. No digit of {@link #FIXTURE_CARD_NUMBER} yields it.
     */
    private static final String OTHER_ACCOUNT_ID = "00000000050";

    /**
     * Eleven digits no record of {@code app/data/ASCII/acctdata.txt} carries. Its fifty rows run
     * {@code 00000000001} through {@code 00000000050}.
     */
    private static final String UNRESOLVED_ACCOUNT_ID = "00000000099";

    /** The leading eleven characters of {@link #FIXTURE_CARD_NUMBER}. */
    private static final String CARD_NUMBER_LEADING_ELEVEN = "48594526128";

    /** The trailing eleven characters of {@link #FIXTURE_CARD_NUMBER}. */
    private static final String CARD_NUMBER_TRAILING_ELEVEN = "52612877065";

    /**
     * The credit limit account 7 of {@code app/data/ASCII/acctdata.txt} carries at positions 25-36,
     * where the trailing overpunch reads a positive zero, so
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8} reads 2065.00.
     */
    private static final BigDecimal FIXTURE_CREDIT_LIMIT = new BigDecimal("2065.00");

    /**
     * The expiry date account 7 of {@code app/data/ASCII/acctdata.txt} carries at positions 59-68,
     * the width {@code ACCT-EXPIRAION-DATE PIC X(10)} declares at
     * {@code app/cpy/CVACT01Y.cpy:L11}.
     */
    private static final String FIXTURE_EXPIRY_DATE = "2024-12-13";

    /**
     * The value both accumulators of all fifty records of {@code app/data/ASCII/acctdata.txt}
     * carry, at positions 79-90 and 91-102 for {@code app/cpy/CVACT01Y.cpy:L13} and {@code :L14}.
     */
    private static final BigDecimal ZERO_ACCUMULATOR = new BigDecimal("0.00");

    /** A credit limit below every amount the 300 fixture records carry. */
    private static final BigDecimal NARROW_CREDIT_LIMIT = new BigDecimal("0.01");

    /** An expiry date preceding every capture timestamp of {@code app/data/ASCII/dailytran.txt}. */
    private static final String LONG_PAST_EXPIRY_DATE = "2000-01-01";

    /**
     * The amount record one of {@code app/data/ASCII/dailytran.txt} carries at positions 133-143.
     * The field holds {@code 0000005047G}, whose trailing overpunch carries a positive seven, so
     * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10} reads 504.77.
     */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("504.77");

    /**
     * The capture timestamp all 300 records of {@code app/data/ASCII/dailytran.txt} carry at
     * positions 279-304, the width {@code DALYTRAN-ORIG-TS PIC X(26)} declares at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. Position 11 is a space.
     */
    private static final String FIXTURE_ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The account-identifier width {@code app/cpy/CVACT01Y.cpy:L5} declares, which
     * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40} repeats.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** The measured length of the text {@code app/cbl/CBTRN02C.cbl:L398} assigns. */
    private static final int REASON_TEXT_LENGTH = 24;

    /**
     * The widest text {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    private static final int REASON_TEXT_WIDTH = 76;

    /** The position in the chain this rule declares. */
    private static final int CHAIN_POSITION = 20;

    /** The only collaborator the rule takes. */
    private AccountCreditSnapshotRepository accountCreditSnapshots;

    /** The rule under test. */
    private AccountExistsRule rule;

    /** Builds the collaborator and the rule before each test. */
    @BeforeEach
    void prepareRule() {
        accountCreditSnapshots = mock(AccountCreditSnapshotRepository.class);
        rule = new AccountExistsRule(accountCreditSnapshots);
    }

    /**
     * The move at {@code app/cbl/CBTRN02C.cbl:L394}, which keys the read on the identifier the
     * resolved cross-reference row carries.
     */
    @Nested
    @DisplayName("The lookup key")
    class LookupKey {

        @Test
        @DisplayName("is the identifier the resolved cross-reference row carries")
        void comesFromTheResolvedRow() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(FIXTURE_ACCOUNT_ID));

            rule.evaluate(context);

            ArgumentCaptor<String> keyed = ArgumentCaptor.forClass(String.class);
            verify(accountCreditSnapshots).findByAccountId(keyed.capture());
            assertAll(
                    () -> assertEquals(context.getResolvedAccountId(), keyed.getValue(),
                            "app/cbl/CBTRN02C.cbl:L394 moves XREF-ACCT-ID into the key field"),
                    () -> assertEquals(FIXTURE_ACCOUNT_ID, keyed.getValue(),
                            "row 21 of app/data/ASCII/cardxref.txt carries this identifier"),
                    () -> assertEquals(ACCOUNT_ID_WIDTH, keyed.getValue().length(),
                            "all eleven characters of app/cpy/CVACT03Y.cpy:L7 must arrive"));
        }

        @Test
        @DisplayName("follows the row when the row names another account")
        void followsTheRowAndNotTheCardNumber() {
            AccountCreditSnapshotEntity row = snapshotFor(OTHER_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(OTHER_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(OTHER_ACCOUNT_ID));

            rule.evaluate(context);

            ArgumentCaptor<String> keyed = ArgumentCaptor.forClass(String.class);
            verify(accountCreditSnapshots).findByAccountId(keyed.capture());
            assertAll(
                    () -> assertEquals(OTHER_ACCOUNT_ID, keyed.getValue(),
                            "the identifier the seated row carries, and no other"),
                    () -> assertNotEquals(CARD_NUMBER_LEADING_ELEVEN, keyed.getValue(),
                            "no eleven characters of the card number reach the key field"),
                    () -> assertNotEquals(CARD_NUMBER_TRAILING_ELEVEN, keyed.getValue(),
                            "no eleven characters of the card number reach the key field"),
                    () -> assertEquals(FIXTURE_CARD_NUMBER, context.getCardNumber(),
                            "the call still presents the fixture card number"));
        }

        @ParameterizedTest(name = "[{index}] {0} reaches the repository unedited")
        @ValueSource(strings = {FIXTURE_ACCOUNT_ID, OTHER_ACCOUNT_ID, UNRESOLVED_ACCOUNT_ID})
        @DisplayName("keeps every leading zero the row holds")
        void keepsEveryLeadingZero(String accountId) {
            when(accountCreditSnapshots.findByAccountId(accountId)).thenReturn(Optional.empty());

            rule.evaluate(contextWith(resolvedCard(accountId)));

            ArgumentCaptor<String> keyed = ArgumentCaptor.forClass(String.class);
            verify(accountCreditSnapshots).findByAccountId(keyed.capture());
            assertAll(
                    () -> assertEquals(accountId, keyed.getValue(),
                            "column account_id holds text, so the rule pads nothing, trims nothing"
                                    + " and reformats nothing"),
                    () -> assertEquals(ACCOUNT_ID_WIDTH, keyed.getValue().length(),
                            "KEYS(11 0) at app/jcl/ACCTFILE.jcl:L40 fixes the width"));
        }
    }

    /** The {@code INVALID KEY} limb at {@code app/cbl/CBTRN02C.cbl:L396-L399}. */
    @Nested
    @DisplayName("An account miss")
    class AccountMiss {

        @Test
        @DisplayName("answers with reject code 0101 and raises nothing")
        void answersWithRejectCode() {
            when(accountCreditSnapshots.findByAccountId(UNRESOLVED_ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            DeclineRule.Context context = contextWith(resolvedCard(UNRESOLVED_ACCOUNT_ID));

            Optional<DeclineReason> outcome = assertDoesNotThrow(
                    () -> rule.evaluate(context),
                    "an identifier no row carries must answer with a reject reason, and the rule"
                            + " must raise nothing");

            assertAll(
                    () -> assertTrue(outcome.isPresent(),
                            "the answer must carry the reject reason"
                                    + " app/cbl/CBTRN02C.cbl:L397 assigns"),
                    () -> assertEquals(DeclineReason.ACCOUNT_NOT_FOUND, outcome.orElse(null),
                            "a keyed read that finds no row is a decline and never a failure"));
        }

        @Test
        @DisplayName("seats no snapshot on the context")
        void seatsNothing() {
            when(accountCreditSnapshots.findByAccountId(UNRESOLVED_ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            DeclineRule.Context context = contextWith(resolvedCard(UNRESOLVED_ACCOUNT_ID));

            rule.evaluate(context);

            assertNull(context.getAccountCreditSnapshot(),
                    "no row resolved, so the area app/cbl/CBTRN02C.cbl:L395 reads into stays"
                            + " empty");
        }
    }

    /** The {@code NOT INVALID KEY} limb at {@code app/cbl/CBTRN02C.cbl:L400}. */
    @Nested
    @DisplayName("An account hit")
    class AccountHit {

        @Test
        @DisplayName("declines nothing and seats the resolved row on the context")
        void seatsResolvedRow() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(FIXTURE_ACCOUNT_ID));

            Optional<DeclineReason> outcome = rule.evaluate(context);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "an identifier a row carries must decline nothing"),
                    () -> assertSame(row, context.getAccountCreditSnapshot(),
                            "the context must carry the row the repository returned"),
                    () -> assertEquals(FIXTURE_CREDIT_LIMIT,
                            context.getAccountCreditSnapshot().getCreditLimit(),
                            "app/cbl/CBTRN02C.cbl:L407 reads the credit limit off this row"),
                    () -> assertEquals(FIXTURE_EXPIRY_DATE,
                            context.getAccountCreditSnapshot().getAccountExpirationDate(),
                            "app/cbl/CBTRN02C.cbl:L414 reads the expiry date off this row"));
        }
    }

    /** The code and text {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns. */
    @Nested
    @DisplayName("The reject reason")
    class RejectReasonShape {

        @Test
        @DisplayName("carries 0101 as four zero-padded digits and 101 as a number")
        void carriesBothFormsOfTheCode() {
            DeclineReason answer = rejectReasonFromMiss();

            assertAll(
                    () -> assertEquals("0101", answer.code(),
                            "WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181"
                                    + " carries four digits, so the wire form is padded"),
                    () -> assertEquals(101, answer.numericCode(),
                            "app/cbl/CBTRN02C.cbl:L397 assigns 101, and the account rewrite limb"
                                    + " at app/cbl/CBTRN02C.cbl:L554-L559 reuses this text under"
                                    + " a code no decline carries"));
        }

        @Test
        @DisplayName("carries the text app/cbl/CBTRN02C.cbl:L398 assigns, unedited")
        void carriesTheSourceText() {
            String text = rejectReasonFromMiss().description();

            assertAll(
                    () -> assertEquals(REASON_TEXT_LENGTH, text.length(),
                            "the measured length of the source literal"),
                    () -> assertTrue(text.length() <= REASON_TEXT_WIDTH,
                            "the text must fit WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at"
                                    + " app/cbl/CBTRN02C.cbl:L182"),
                    () -> assertEquals(text.strip(), text,
                            "the source literal carries no leading or trailing space"),
                    () -> assertEquals(text.toUpperCase(Locale.ROOT), text,
                            "the source literal is upper case throughout"));
        }

        /**
         * Reads the reject reason the rule answers with for an identifier no row carries.
         *
         * <p>The code and the text are read from the answer, so this file restates neither.
         *
         * @return the reject reason the rule assigned
         */
        private DeclineReason rejectReasonFromMiss() {
            when(accountCreditSnapshots.findByAccountId(UNRESOLVED_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            DeclineReason answer = rule
                    .evaluate(contextWith(resolvedCard(UNRESOLVED_ACCOUNT_ID)))
                    .orElse(null);

            assertNotNull(answer,
                    "an identifier no row carries must answer with a reject reason");
            return answer;
        }
    }

    /**
     * The order {@code app/cbl/CBTRN02C.cbl:L394} imposes: the account read follows the
     * cross-reference read and takes its key from it.
     */
    @Nested
    @DisplayName("A context carrying no cross-reference row")
    class MissingCrossReferenceRow {

        @Test
        @DisplayName("raises, and answers with neither an approval nor reject code 0101")
        void raisesAndReadsNothing() {
            DeclineRule.Context context = contextFor(FIXTURE_CARD_NUMBER);

            assertThrows(NullPointerException.class, () -> rule.evaluate(context),
                    "app/cbl/CBTRN02C.cbl:L394 takes the key off a row the preceding read"
                            + " supplied, so a call arriving without one is a wiring fault and"
                            + " carries no business answer at all");

            assertAll(
                    () -> assertNull(context.getCardCrossReference(),
                            "the call presented no resolved row"),
                    () -> assertNull(context.getAccountCreditSnapshot(),
                            "no read ran, so no snapshot reached the context"));
            verifyNoInteractions(accountCreditSnapshots);
        }
    }

    /**
     * Where this rule sits, which is what the gate at {@code app/cbl/CBTRN02C.cbl:L372} decides.
     *
     * <p>What the chain then does with several rules is asserted in the parent package.
     */
    @Nested
    @DisplayName("Chain placement")
    class ChainPlacement {

        @Test
        @DisplayName("declares the segment that ends the chain at the first decline")
        void declaresTheStoppingSegment() {
            assertEquals(DeclineRule.Segment.STOP_ON_FIRST_DECLINE, rule.segment(),
                    "app/cbl/CBTRN02C.cbl:L372 runs this paragraph only while the reject reason"
                            + " holds zero, and app/cbl/CBTRN02C.cbl:L403 reads a row this rule"
                            + " must have resolved");
        }

        @Test
        @DisplayName("declares position 20, which leaves room for a rule on either side")
        void declaresItsPosition() {
            Order declared = AccountExistsRule.class.getAnnotation(Order.class);

            assertNotNull(declared, "the class must declare its position in the chain");
            assertEquals(CHAIN_POSITION, declared.value(),
                    "the account read runs second, as app/cbl/CBTRN02C.cbl:L373 performs it after"
                            + " the card lookup");
        }
    }

    /** The single keyed read at {@code app/cbl/CBTRN02C.cbl:L395}. */
    @Nested
    @DisplayName("Repository reach")
    class RepositoryReach {

        @Test
        @DisplayName("reads the account finder once, writes nothing and reaches no other method")
        void readsOneFinderOnceAndWritesNothing() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));

            rule.evaluate(contextWith(resolvedCard(FIXTURE_ACCOUNT_ID)));

            verify(accountCreditSnapshots, times(1)).findByAccountId(FIXTURE_ACCOUNT_ID);
            verify(accountCreditSnapshots, never()).save(any());
            verifyNoMoreInteractions(accountCreditSnapshots);
        }

        @Test
        @DisplayName("reads the account finder once when nothing resolves either")
        void readsOneFinderOnceOnAMiss() {
            when(accountCreditSnapshots.findByAccountId(UNRESOLVED_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            rule.evaluate(contextWith(resolvedCard(UNRESOLVED_ACCOUNT_ID)));

            verify(accountCreditSnapshots, times(1)).findByAccountId(UNRESOLVED_ACCOUNT_ID);
            verify(accountCreditSnapshots, never()).save(any());
            verifyNoMoreInteractions(accountCreditSnapshots);
        }
    }

    /**
     * The shape of the rule itself. {@code app/cbl/CBTRN02C.cbl:L408} and
     * {@code app/cbl/CBTRN02C.cbl:L415} put {@code CONTINUE} on the approve side, so the source
     * approves unless a reject reason was assigned.
     */
    @Nested
    @DisplayName("The rule surface")
    class RuleSurface {

        @Test
        @DisplayName("exposes evaluate and segment, and neither answers with a flag")
        void exposesTwoMethods() throws NoSuchMethodException {
            Set<String> exposed = new TreeSet<>();
            for (Method method : AccountExistsRule.class.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                exposed.add(method.getName());
                assertNotEquals(boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
                assertNotEquals(Boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
            }

            assertEquals(Set.of("evaluate", "segment"), exposed,
                    "the public surface must be the two methods DeclineRule declares, so no"
                            + " differently named entry point can grow beside them");

            Method evaluation =
                    AccountExistsRule.class.getMethod("evaluate", DeclineRule.Context.class);
            assertEquals("java.util.Optional<com.carddemo.events.DeclineReason>",
                    evaluation.getGenericReturnType().getTypeName(),
                    "an absent answer is an approval and a present answer is the reject reason");
        }

        @Test
        @DisplayName("takes one collaborator through one unannotated constructor")
        void takesOneCollaborator() {
            Constructor<?>[] constructors = AccountExistsRule.class.getConstructors();

            assertEquals(1, constructors.length, "the rule must offer one way to build it");
            assertArrayEquals(new Class<?>[] {AccountCreditSnapshotRepository.class},
                    constructors[0].getParameterTypes(),
                    "one repository and nothing else, so no second table is reachable from here");
            assertEquals(0, constructors[0].getAnnotations().length,
                    "a sole constructor needs no injection annotation");
        }
    }

    /** Behaviour a reader may expect that the source does not have. */
    @Nested
    @DisplayName("Deliberate non-additions")
    class DeliberateNonAdditions {

        @Test
        @DisplayName("a closed account still authorizes")
        void authorizesAClosedAccount() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(FIXTURE_ACCOUNT_ID));

            Optional<DeclineReason> outcome = rule.evaluate(context);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "no paragraph of app/cbl/CBTRN02C.cbl reads ACCT-ACTIVE-STATUS at"
                                    + " app/cpy/CVACT01Y.cpy:L6 before posting"),
                    () -> assertEquals(Set.of(), statusAccessorsOfTheSnapshotRow(),
                            "the snapshot row carries no status column, so the rule cannot reach"
                                    + " one"),
                    () -> assertSame(row, context.getAccountCreditSnapshot(),
                            "the row resolved, so the context must carry it"));
        }

        @Test
        @DisplayName("neither the credit limit nor the expiry date decides this rule")
        void readsNeitherTheLimitNorTheExpiry() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID,
                    NARROW_CREDIT_LIMIT, LONG_PAST_EXPIRY_DATE);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(FIXTURE_ACCOUNT_ID));

            Optional<DeclineReason> outcome = rule.evaluate(context);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L403-L420 places both tests after this read,"
                                    + " and the two later rules own them"),
                    () -> assertSame(row, context.getAccountCreditSnapshot(),
                            "those two rules read the values off the row this one seats"));
        }

        @Test
        @DisplayName("the customer identifier arrives on the resolved row and is not looked up")
        void carriesTheCustomerIdentifierOnTheRow() {
            AccountCreditSnapshotEntity row = snapshotFor(FIXTURE_ACCOUNT_ID);
            when(accountCreditSnapshots.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextWith(resolvedCard(FIXTURE_ACCOUNT_ID));

            rule.evaluate(context);

            assertEquals(FIXTURE_CUSTOMER_ID, context.getCardCrossReference().getCustomerId(),
                    "XREF-CUST-ID at app/cpy/CVACT03Y.cpy:L6 arrives on the row");
            verify(accountCreditSnapshots).findByAccountId(FIXTURE_ACCOUNT_ID);
            verifyNoMoreInteractions(accountCreditSnapshots);
        }

        /**
         * Collects every public accessor of the snapshot row whose name names a status.
         *
         * <p>{@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} holds no
         * column on that row, so the set is empty.
         *
         * @return the names of any status accessors the snapshot row exposes
         */
        private Set<String> statusAccessorsOfTheSnapshotRow() {
            Set<String> named = new TreeSet<>();
            for (Method method : AccountCreditSnapshotEntity.class.getMethods()) {
                if (method.getName().toLowerCase(Locale.ROOT).contains("status")) {
                    named.add(method.getName());
                }
            }
            return named;
        }
    }

    /**
     * Builds the values one authorization call carries, for one card number.
     *
     * <p>The amount and the capture timestamp hold steady across every scenario. This rule reads
     * neither.
     *
     * @param cardNumber the card number, already padded to sixteen characters
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextFor(String cardNumber) {
        return new DeclineRule.Context(cardNumber, FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);
    }

    /**
     * Builds the values one authorization call carries, with a cross-reference row already seated.
     *
     * <p>The preceding rule seats that row, mirroring the working-storage area
     * {@code app/cbl/CBTRN02C.cbl:L383} reads into.
     *
     * @param resolvedCard the row a cross-reference read resolved
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextWith(CardCrossReferenceEntity resolvedCard) {
        DeclineRule.Context context = contextFor(FIXTURE_CARD_NUMBER);
        context.setCardCrossReference(resolvedCard);
        return context;
    }

    /**
     * Builds the cross-reference row a keyed read resolves to, naming one account.
     *
     * <p>The row is a stub. Its own width and digit-class contract is asserted under
     * {@code src/test/java/com/carddemo/authorization/entity}.
     *
     * @param accountId the account identifier the row carries, eleven digit characters
     * @return the resolved row
     */
    private static CardCrossReferenceEntity resolvedCard(String accountId) {
        CardCrossReferenceEntity row = mock(CardCrossReferenceEntity.class);
        when(row.getCardNumber()).thenReturn(FIXTURE_CARD_NUMBER);
        when(row.getCustomerId()).thenReturn(FIXTURE_CUSTOMER_ID);
        when(row.getAccountId()).thenReturn(accountId);
        return row;
    }

    /**
     * Builds the snapshot row a keyed read resolves to, carrying the values account 7 of
     * {@code app/data/ASCII/acctdata.txt} holds.
     *
     * @param accountId the account identifier the row carries, eleven digit characters
     * @return the resolved row
     */
    private static AccountCreditSnapshotEntity snapshotFor(String accountId) {
        return snapshotFor(accountId, FIXTURE_CREDIT_LIMIT, FIXTURE_EXPIRY_DATE);
    }

    /**
     * Builds the snapshot row a keyed read resolves to, carrying one credit limit and one expiry
     * date.
     *
     * <p>The row is a stub. Both accumulators hold the value all fifty fixture accounts carry, and
     * this rule reads neither.
     *
     * @param accountId      the account identifier the row carries, eleven digit characters
     * @param creditLimit    the credit limit at two digits after the decimal point
     * @param expirationDate the expiry date, ten characters held as text
     * @return the resolved row
     */
    private static AccountCreditSnapshotEntity snapshotFor(String accountId,
            BigDecimal creditLimit, String expirationDate) {
        AccountCreditSnapshotEntity row = mock(AccountCreditSnapshotEntity.class);
        when(row.getAccountId()).thenReturn(accountId);
        when(row.getCreditLimit()).thenReturn(creditLimit);
        when(row.getAccountExpirationDate()).thenReturn(expirationDate);
        when(row.getCurrentCycleCredit()).thenReturn(ZERO_ACCUMULATOR);
        when(row.getCurrentCycleDebit()).thenReturn(ZERO_ACCUMULATOR);
        return row;
    }
}
