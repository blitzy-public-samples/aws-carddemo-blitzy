package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.DisclosureGroupEntity;
import com.carddemo.account.entity.DisclosureGroupEntity.DisclosureGroupId;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.equivalence.CopybookRecordParser.DisclosureGroupRecord;
import com.carddemo.equivalence.CopybookRecordParser.TransactionCategoryBalanceRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the interest rate rules of {@code app/cbl/CBACT04C.cbl:L415-L460} against
 * {@code app/data/ASCII/discgrp.txt}, and the cycle-accumulator reset of
 * {@code app/cbl/CBACT04C.cbl:L350-L358} against {@link BillingCycleService}.
 *
 * <p>Resolution C8 verifies the rate rules and migrates no interest computation. No interest
 * service exists in the target. The divide at {@code app/cbl/CBACT04C.cbl:L462-L470} runs inside
 * this class to show truncation and the narrowed working precision of
 * {@code app/cbl/CBACT04C.cbl:L168-L169}. {@code app/cbl/CBACT04C.cbl:L518-L520} is an
 * unimplemented fee paragraph, and this class asserts no fee.</p>
 *
 * <p>Maven Failsafe runs this class at {@code integration-test} and {@code verify}. Surefire
 * excludes the {@code *EquivalenceTest} name pattern. {@code mvn test} runs none of it.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Interest rate parity from CBACT04C L350-L470")
class InterestCalculationEquivalenceTest {

    /** The literal {@code app/cbl/CBACT04C.cbl:L437} moves into the group identifier. */
    private static final String DEFAULT_GROUP_LITERAL = "DEFAULT";

    /** The group identifier {@code app/data/ASCII/acctdata.txt} carries in every row. */
    private static final String BLANK_GROUP = " ".repeat(PicClause.ACCT_GROUP_ID_WIDTH);

    /** Reads performed when the keyed read at {@code app/cbl/CBACT04C.cbl:L416} finds its row. */
    private static final int LOOKUPS_ON_A_MATCH = 1;

    /** Reads performed when {@code app/cbl/CBACT04C.cbl:L444} re-reads once after a miss. */
    private static final int LOOKUPS_WITH_THE_DEFAULT_RETRY = LOOKUPS_ON_A_MATCH + 1;

    /** Step between one {@code DIS-TRAN-CAT-CD} value and the next. */
    private static final int NEXT_CATEGORY_CODE_STEP = 1;

    /** Padding digit of a {@code PIC 9(04)} category code. */
    private static final String CODE_PAD_DIGIT = "0";

    /** {@code DIS-TRAN-TYPE-CD} of the group {@code app/data/ASCII/discgrp.txt} rates apart. */
    private static final String DISCRIMINATING_TYPE = "07";

    /** {@code DIS-TRAN-CAT-CD} paired with {@link #DISCRIMINATING_TYPE}. */
    private static final String DISCRIMINATING_CATEGORY = "0001";

    /** {@code DIS-TRAN-TYPE-CD} of the row every negative fixture transaction reaches. */
    private static final String ZERO_RATED_TYPE = "03";

    /** {@code DIS-TRAN-CAT-CD} paired with {@link #ZERO_RATED_TYPE}. */
    private static final String ZERO_RATED_CATEGORY = "0001";

    /** {@code DIS-TRAN-TYPE-CD} of the first row of the {@code DEFAULT} group. */
    private static final String LEADING_TYPE = "01";

    /** {@code DIS-TRAN-CAT-CD} of the first row of the {@code DEFAULT} group. */
    private static final String LEADING_CATEGORY = "0001";

    /** {@code DIS-TRAN-CAT-CD} of the second row of the {@code DEFAULT} group. */
    private static final String SECOND_CATEGORY = "0002";

    /** The rate {@code app/data/ASCII/discgrp.txt} carries on its zero-rated rows. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /** The rate {@code app/data/ASCII/discgrp.txt} carries on {@code DEFAULT 01/0001}. */
    private static final BigDecimal STANDARD_RATE = new BigDecimal("15.00");

    /** The rate {@code app/data/ASCII/discgrp.txt} carries on {@code DEFAULT 01/0002}. */
    private static final BigDecimal HIGH_RATE = new BigDecimal("25.00");

    /** A {@code TRAN-CAT-BAL} the seeded fixture does not carry, shared with the sibling suite. */
    private static final BigDecimal SAMPLE_CATEGORY_BALANCE = new BigDecimal("1287.09");

    /** {@link #SAMPLE_CATEGORY_BALANCE} at {@link #STANDARD_RATE}, truncated toward zero. */
    private static final BigDecimal SAMPLE_MONTHLY_INTEREST = new BigDecimal("16.08");

    /** A {@code ACCT-CURR-CYC-CREDIT} the cycle-close operation clears. */
    private static final BigDecimal ADDITIVE_CYCLE_CREDIT = new BigDecimal("123.45");

    /** A {@code ACCT-CURR-CYC-DEBIT} the cycle-close operation clears. */
    private static final BigDecimal ADDITIVE_CYCLE_DEBIT = new BigDecimal("-67.89");

    /** {@code KEYS(length offset)} of the cluster definition at {@code app/jcl/DISCGRP.jcl:L40}. */
    private static final Pattern KEYS_PARAMETER =
            Pattern.compile("KEYS\\((\\d+)\\s+(\\d+)\\)");

    /** {@code RECORDSIZE(average maximum)} at {@code app/jcl/DISCGRP.jcl:L41}. */
    private static final Pattern RECORDSIZE_PARAMETER =
            Pattern.compile("RECORDSIZE\\((\\d+)\\s+(\\d+)\\)");

    /** Offset the cluster definition names for a key that starts at the first byte. */
    private static final int KEY_OFFSET_AT_RECORD_START = 0;

    /** Group of the first captured value of {@link #KEYS_PARAMETER}. */
    private static final int FIRST_CAPTURE = 1;

    /** Group of the second captured value of {@link #KEYS_PARAMETER}. */
    private static final int SECOND_CAPTURE = FIRST_CAPTURE + 1;

    /** The Job Control Language member that defines the disclosure-group cluster. */
    private static final String DISCGRP_JOB_MEMBER = "app/jcl/DISCGRP.jcl";

    /** The directory holding the six service modules of the target platform. */
    private static final String SERVICE_MODULE_DIRECTORY = "card-platform/services";

    private static final String JAVA_SOURCE_SUFFIX = ".java";

    /** Word a migrated interest type would carry in its name. */
    private static final String INTEREST_TYPE_WORD = "Interest";

    /** Word a migrated type for {@code 1400-COMPUTE-FEES} would carry in its name. */
    private static final String FEE_TYPE_WORD = "Fee";

    /** Every row of {@code app/data/ASCII/acctdata.txt}, parsed at its declared width. */
    private static final List<AccountRecord> ACCOUNTS = CardDemoFixtureLoader.loadAccounts();

    /** Every row of {@code app/data/ASCII/discgrp.txt}, parsed at its declared width. */
    private static final List<DisclosureGroupRecord> DISCLOSURE_GROUPS =
            CardDemoFixtureLoader.loadDisclosureGroups();

    /** The fixture rows indexed by the three key parts of {@code app/cpy/CVTRA02Y.cpy:L6-L8}. */
    private static final Map<RateKey, BigDecimal> RATES = rateTable();

    /** The distinct {@code DIS-ACCT-GROUP-ID} values the fixture carries, in fixture order. */
    private static final Set<String> GROUP_IDENTIFIERS = groupIdentifiers();

    /** {@link #DEFAULT_GROUP_LITERAL} padded to the width of {@code app/cpy/CVTRA02Y.cpy:L6}. */
    private static final String DEFAULT_GROUP = paddedGroupIdentifier(DEFAULT_GROUP_LITERAL);

    /**
     * A fixture group whose rate at {@link #DISCRIMINATING_TYPE} and
     * {@link #DISCRIMINATING_CATEGORY} differs from the rate {@code DEFAULT} carries there.
     */
    private static final String MATCHED_GROUP = groupThatRatesDifferentlyFromDefault();

    /** The checked-in rate expectations this class is the declared consumer of. */
    private static final String EXPECTED_RATE_FILE = "discgrp-interest-rates.csv";

    /** Every row of {@link #EXPECTED_RATE_FILE}, parsed once for the whole class. */
    private static final ExpectedOutcomes EXPECTED_RATES_TABLE =
            ExpectedOutcomes.load(EXPECTED_RATE_FILE);

    /** The interest program the carve-out of resolution C8 verifies without migrating. */
    private static final String INTEREST_PROGRAM = "app/cbl/CBACT04C.cbl";

    /** The primary rate lookup, which accepts a missed read and substitutes the default group. */
    private static final String RATE_PARAGRAPH = "1200-GET-INTEREST-RATE";

    /** The substituted re-read, which accepts the normal status alone and abends otherwise. */
    private static final String DEFAULT_RATE_PARAGRAPH = "1200-A-GET-DEFAULT-INT-RATE";

    /** The accrual paragraph, whose single divide is the formula the expectations name. */
    private static final String INTEREST_PARAGRAPH = "1300-COMPUTE-INTEREST";

    /** The cycle-close paragraph, of which this platform reproduces the two zeroing moves only. */
    private static final String CYCLE_PARAGRAPH = "1050-UPDATE-ACCOUNT";

    /** The fixture-level entity key of {@link #EXPECTED_RATE_FILE}. */
    private static final String DISCGRP_FIXTURE_KEY = "discgrp";

    /** The fallback-level entity key of {@link #EXPECTED_RATE_FILE}. */
    private static final String FALLBACK_KEY = "default_group_fallback";

    /** The file-description prefix a COBOL record name carries inside a file section. */
    private static final String RECORD_DESCRIPTION_PREFIX = "FD-";

    /** The separator {@link #EXPECTED_RATE_FILE} writes between the three key parts. */
    private static final String KEY_PART_SEPARATOR = "||";

    /** The separator {@link #EXPECTED_RATE_FILE} writes between listed values. */
    private static final String VALUE_SEPARATOR = ",";

    /** Offset of {@code DIS-TRAN-TYPE-CD} in the fixture record of {@code CVTRA02Y.cpy:L7}. */
    private static final int TYPE_CODE_OFFSET = PicClause.DIS_ACCT_GROUP_ID_WIDTH;

    /** Offset of {@code DIS-INT-RATE} in the fixture record of {@code CVTRA02Y.cpy:L9}. */
    private static final int RATE_OFFSET = TYPE_CODE_OFFSET + PicClause.DIS_TRAN_TYPE_CD_WIDTH
            + PicClause.DIS_TRAN_CAT_CD_WIDTH;

    /** Offset of the trailing {@code FILLER} of {@code CVTRA02Y.cpy:L10}. */
    private static final int FILLER_OFFSET = RATE_OFFSET + PicClause.DIS_INT_RATE_WIDTH;

    /** The character the trailing filler of every fixture row is made of. */
    private static final char FILLER_CHARACTER = '0';

    /** How {@link #EXPECTED_RATE_FILE} names {@link #FILLER_CHARACTER}. */
    private static final String FILLER_CHARACTER_NAME = "ASCII zero";

    /** The checked-in seeded-accrual expectations this class is the declared consumer of. */
    private static final String EXPECTED_ACCRUAL_FILE = "tcatbal-interest-accrual.csv";

    /** Every row of {@link #EXPECTED_ACCRUAL_FILE}, parsed once for the whole class. */
    private static final ExpectedOutcomes EXPECTED_ACCRUAL =
            ExpectedOutcomes.load(EXPECTED_ACCRUAL_FILE);

    /** The sequential read of the category-balance file that drives the accrual loop. */
    private static final String READ_PARAGRAPH = "1000-TCATBALF-GET-NEXT";

    /** The paragraph that builds and writes one interest transaction. */
    private static final String WRITE_PARAGRAPH = "1300-B-WRITE-TX";

    /** The fee paragraph, which carries no statement other than its exit. */
    private static final String FEE_PARAGRAPH = "1400-COMPUTE-FEES";

    /** The job that runs the interest program, and the only place its parameter is written. */
    private static final String INTEREST_JOB_MEMBER = "app/jcl/INTCALC.jcl";

    /** The program name the job names, without its member extension. */
    private static final String INTEREST_PROGRAM_NAME = "CBACT04C";

    /** The COBOL file status that means the operation succeeded. */
    private static final String NORMAL_FILE_STATUS = "00";

    /** The read clause a keyed read carries and the substituted re-read deliberately does not. */
    private static final String INVALID_KEY = "INVALID KEY";

    /** The key part the default-group substitution writes into. */
    private static final String GROUP_ID_KEY_COMPONENT = "DIS-ACCT-GROUP-ID";

    /** The separator {@link #EXPECTED_ACCRUAL_FILE} writes between the three key parts. */
    private static final String SEED_KEY_SEPARATOR = "|";

    /** Where the event schemas of {@code libs/event-contracts} live below the repository root. */
    private static final String EVENT_SCHEMA_DIRECTORY =
            "card-platform/libs/event-contracts/src/main/resources/schemas";

    /** Where the Kafka listeners of the account service live below the repository root. */
    private static final String ACCOUNT_MESSAGING_DIRECTORY =
            "card-platform/services/account-service/src/main/java/com/carddemo/account/messaging";

    /** The operation a consumer would have to name in order to drive the carve-out. */
    private static final String CYCLE_CLOSE_METHOD = "closeBillingCycle";

    /** The separator {@link #EXPECTED_RATE_FILE} writes inside a field name. */
    private static final char FIELD_NAME_SEPARATOR = '_';

    @Nested
    @DisplayName("1200-GET-INTEREST-RATE at CBACT04C L415-L440")
    class RateResolution {

        @Test
        @DisplayName("ADDITIVE: a matched primary read at L416 returns its own rate")
        void aMatchedGroupReturnsItsOwnRateAndReachesNoSubstitution() {
            BigDecimal matchedRate =
                    RATES.get(new RateKey(MATCHED_GROUP, DISCRIMINATING_TYPE,
                            DISCRIMINATING_CATEGORY));
            BigDecimal defaultRate =
                    RATES.get(new RateKey(DEFAULT_GROUP, DISCRIMINATING_TYPE,
                            DISCRIMINATING_CATEGORY));

            ResolvedRate resolved = resolveRate(MATCHED_GROUP, DISCRIMINATING_TYPE,
                    DISCRIMINATING_CATEGORY);

            assertEquals(matchedRate, resolved.rate(),
                    "equals: group " + MATCHED_GROUP.trim() + " type " + DISCRIMINATING_TYPE
                            + " category " + DISCRIMINATING_CATEGORY + " carries " + matchedRate
                            + " in discgrp.txt");
            assertFalse(resolved.fallbackUsed(),
                    "a matched read at CBACT04C L416 leaves status 00, and L436 tests for 23");
            assertEquals(LOOKUPS_ON_A_MATCH, resolved.lookupCount(),
                    "CBACT04C L416 performs the only read when the key matches");
            assertTrue(matchedRate.compareTo(defaultRate) != 0,
                    "compareTo: group " + MATCHED_GROUP.trim() + " carries " + matchedRate
                            + " and DEFAULT carries " + defaultRate + " for type "
                            + DISCRIMINATING_TYPE + " category " + DISCRIMINATING_CATEGORY);
        }

        @Test
        @DisplayName("L437 replaces DIS-ACCT-GROUP-ID and holds the other two key parts")
        void theSubstitutionReplacesTheFirstKeyPartAlone() {
            String beforeSubstitution = compositeKey(BLANK_GROUP, DISCRIMINATING_TYPE,
                    DISCRIMINATING_CATEGORY);
            String afterSubstitution = compositeKey(DEFAULT_GROUP, DISCRIMINATING_TYPE,
                    DISCRIMINATING_CATEGORY);

            assertEquals(PicClause.DIS_GROUP_KEY_WIDTH, beforeSubstitution.length(),
                    "equals: DIS-GROUP-KEY at CVTRA02Y L5 spans "
                            + PicClause.DIS_GROUP_KEY_WIDTH + " bytes");
            assertEquals(beforeSubstitution.substring(PicClause.DIS_ACCT_GROUP_ID_WIDTH),
                    afterSubstitution.substring(PicClause.DIS_ACCT_GROUP_ID_WIDTH),
                    "equals: type " + DISCRIMINATING_TYPE + " and category "
                            + DISCRIMINATING_CATEGORY + " carry over past byte "
                            + PicClause.DIS_ACCT_GROUP_ID_WIDTH);
            assertEquals(DEFAULT_GROUP,
                    afterSubstitution.substring(0, PicClause.DIS_ACCT_GROUP_ID_WIDTH),
                    "equals: the first key part holds the L437 literal padded to its width");
        }

        @Test
        @DisplayName("The L437 literal pads to the DIS-ACCT-GROUP-ID width of CVTRA02Y L6")
        void theSubstitutedLiteralPadsToTheKeyPartWidth() {
            assertEquals(PicClause.DIS_ACCT_GROUP_ID_WIDTH, DEFAULT_GROUP.length(),
                    "equals: the padded literal fills DIS-ACCT-GROUP-ID");
            assertTrue(DEFAULT_GROUP.startsWith(DEFAULT_GROUP_LITERAL),
                    "the padded identifier opens with the L437 literal " + DEFAULT_GROUP_LITERAL);
            assertTrue(GROUP_IDENTIFIERS.contains(DEFAULT_GROUP),
                    "discgrp.txt carries rows under the padded identifier "
                            + DEFAULT_GROUP_LITERAL);
        }

        @Test
        @DisplayName("DISCGRP.jcl L40-L41 sizes the key and the record the copybook declares")
        void theClusterDefinitionMatchesTheCopybookKeyAndRecordLength() {
            String definition = jobControlLanguageMember(DISCGRP_JOB_MEMBER);
            Matcher keys = requireMatch(KEYS_PARAMETER, definition, "KEYS");
            Matcher recordSize = requireMatch(RECORDSIZE_PARAMETER, definition, "RECORDSIZE");

            assertEquals(PicClause.DIS_GROUP_KEY_WIDTH,
                    Integer.parseInt(keys.group(FIRST_CAPTURE)),
                    "equals: DISCGRP.jcl L40 sizes the key as DIS-ACCT-GROUP-ID plus "
                            + "DIS-TRAN-TYPE-CD plus DIS-TRAN-CAT-CD");
            assertEquals(KEY_OFFSET_AT_RECORD_START,
                    Integer.parseInt(keys.group(SECOND_CAPTURE)),
                    "equals: DISCGRP.jcl L40 starts the key at the first byte of the record");
            assertEquals(PicClause.DIS_GROUP_RECORD_LENGTH,
                    Integer.parseInt(recordSize.group(FIRST_CAPTURE)),
                    "equals: DISCGRP.jcl L41 sizes the record as CVTRA02Y L2 states");
            assertEquals(Integer.parseInt(recordSize.group(FIRST_CAPTURE)),
                    Integer.parseInt(recordSize.group(SECOND_CAPTURE)),
                    "equals: DISCGRP.jcl L41 fixes the record at one length");
        }
    }

    @Nested
    @DisplayName("1200-A-GET-DEFAULT-INT-RATE at CBACT04C L443-L460")
    class DefaultGroupFallback {

        @Test
        @DisplayName("ACCT-GROUP-ID at CVACT01Y L16 is blank on every acctdata.txt row")
        void everyFixtureAccountCarriesTheBlankPrimaryGroup() {
            for (AccountRecord account : ACCOUNTS) {
                assertEquals(BLANK_GROUP, account.groupId(),
                        "equals: account " + account.accountId() + " carries "
                                + PicClause.ACCT_GROUP_ID_WIDTH
                                + " spaces in ACCT-GROUP-ID at bytes 113 to 122");
                assertFalse(GROUP_IDENTIFIERS.contains(account.groupId()),
                        "discgrp.txt carries no rows under the blank group identifier");
            }

            assertEquals(PicClause.ACCTDATA_FIXTURE_RECORD_COUNT, ACCOUNTS.size(),
                    "equals: acctdata.txt delivers its declared record count");
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP at CVACT01Y L15 names a group the fixture carries")
        void theAdjacentFieldNamesARealDisclosureGroup() {
            for (AccountRecord account : ACCOUNTS) {
                String adjacent = paddedGroupIdentifier(account.addressZip());

                assertTrue(GROUP_IDENTIFIERS.contains(adjacent),
                        "account " + account.accountId() + " holds " + adjacent.trim()
                                + " in ACCT-ADDR-ZIP at bytes 103 to 112, and discgrp.txt carries "
                                + GROUP_IDENTIFIERS.size() + " group identifiers");
                assertEquals(PicClause.ACCT_ADDR_ZIP_WIDTH, adjacent.length(),
                        "equals: the adjacent field spans the width CVACT01Y L15 declares");
            }
        }

        @Test
        @DisplayName("A miss at L436 substitutes DEFAULT and holds the type and category")
        void aMissResolvesTheDefaultRateForTheSameTypeAndCategory() {
            List<DisclosureGroupRecord> defaultRows = rowsOf(DEFAULT_GROUP);

            for (DisclosureGroupRecord row : defaultRows) {
                ResolvedRate resolved = resolveRate(BLANK_GROUP, row.transactionTypeCode(),
                        row.transactionCategoryCode());

                assertEquals(row.interestRate(), resolved.rate(),
                        "equals: group " + DEFAULT_GROUP_LITERAL + " type "
                                + row.transactionTypeCode() + " category "
                                + row.transactionCategoryCode() + " carries " + row.interestRate()
                                + " in discgrp.txt");
                assertTrue(resolved.fallbackUsed(),
                        "CBACT04C L436 to L438 substitutes DEFAULT for type "
                                + row.transactionTypeCode() + " category "
                                + row.transactionCategoryCode());
            }

            assertFalse(defaultRows.isEmpty(),
                    "discgrp.txt carries rows under " + DEFAULT_GROUP_LITERAL);
        }

        @Test
        @DisplayName("L444 re-reads once, and the retry holds the category the caller supplied")
        void theRetryIsOneReadAndKeepsTheCallerCategory() {
            BigDecimal secondRowRate =
                    RATES.get(new RateKey(DEFAULT_GROUP, LEADING_TYPE, SECOND_CATEGORY));
            BigDecimal firstRowRate =
                    RATES.get(new RateKey(DEFAULT_GROUP, LEADING_TYPE, LEADING_CATEGORY));

            ResolvedRate resolved = resolveRate(BLANK_GROUP, LEADING_TYPE, SECOND_CATEGORY);

            assertEquals(LOOKUPS_WITH_THE_DEFAULT_RETRY, resolved.lookupCount(),
                    "equals: one missed read at CBACT04C L416 and one re-read at L444");
            assertEquals(secondRowRate, resolved.rate(),
                    "equals: group " + DEFAULT_GROUP_LITERAL + " type " + LEADING_TYPE
                            + " category " + SECOND_CATEGORY + " carries " + secondRowRate);
            assertTrue(resolved.rate().compareTo(firstRowRate) != 0,
                    "compareTo: category " + SECOND_CATEGORY + " carries " + secondRowRate
                            + " and category " + LEADING_CATEGORY + " carries " + firstRowRate);
        }

        @Test
        @DisplayName("L446 accepts status 00 alone, and a second miss reaches L458")
        void aSecondMissFailsAndReturnsNoRate() {
            RateCode absent = codePairAbsentFromEveryGroup();

            assertFalse(RATES.containsKey(new RateKey(DEFAULT_GROUP, absent.typeCode(),
                            absent.categoryCode())),
                    "group " + DEFAULT_GROUP_LITERAL + " carries no row for type "
                            + absent.typeCode() + " category " + absent.categoryCode());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> resolveRate(BLANK_GROUP, absent.typeCode(), absent.categoryCode()),
                    "CBACT04C L455 to L458 reports the failed read and abends");

            assertTrue(failure.getMessage().contains(DEFAULT_GROUP_LITERAL),
                    "the failure names group " + DEFAULT_GROUP_LITERAL + ", type "
                            + absent.typeCode() + " and category " + absent.categoryCode());
        }

        @Test
        @DisplayName("Every category row the fixture run reaches resolves through the substitution")
        void theFallbackFiresOnEveryCategoryRowTheFixtureRunReaches() {
            Set<CategoryKey> reached = categoryKeysAfterPosting();
            long throughTheFallback = 0L;

            for (CategoryKey key : reached) {
                ResolvedRate resolved =
                        resolveRate(BLANK_GROUP, key.typeCode(), key.categoryCode());
                assertTrue(resolved.fallbackUsed(),
                        "account " + key.accountId() + " type " + key.typeCode() + " category "
                                + key.categoryCode() + " resolves through "
                                + DEFAULT_GROUP_LITERAL);
                throughTheFallback++;
            }

            assertEquals(reached.size(), throughTheFallback,
                    "equals: the substitution fires on every category row the run reaches");
            assertTrue(reached.size() > PicClause.TCATBAL_FIXTURE_RECORD_COUNT,
                    "the run adds category rows beyond the " + PicClause.TCATBAL_FIXTURE_RECORD_COUNT
                            + " tcatbal.txt seeds");
        }
    }

    @Nested
    @DisplayName("DIS-GROUP-RECORD rows at CVTRA02Y L4-L10")
    class DisclosureGroupRateTable {

        @Test
        @DisplayName("discgrp.txt divides its declared record count evenly across its groups")
        void everyGroupCarriesTheSameKeySetAndRowCount() {
            Map<String, Set<RateCode>> keysByGroup = new LinkedHashMap<>();
            for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
                keysByGroup.computeIfAbsent(row.accountGroupId(), group -> new LinkedHashSet<>())
                        .add(new RateCode(row.transactionTypeCode(),
                                row.transactionCategoryCode()));
            }

            assertEquals(PicClause.DISCGRP_FIXTURE_RECORD_COUNT, DISCLOSURE_GROUPS.size(),
                    "equals: discgrp.txt delivers its declared record count");
            assertEquals(GROUP_IDENTIFIERS, keysByGroup.keySet(),
                    "equals: every fixture row falls under a known group identifier");

            int rowsPerGroup = DISCLOSURE_GROUPS.size() / keysByGroup.size();
            Set<RateCode> defaultKeys = keysByGroup.get(DEFAULT_GROUP);
            for (Map.Entry<String, Set<RateCode>> group : keysByGroup.entrySet()) {
                assertEquals(defaultKeys, group.getValue(),
                        "equals: group " + group.getKey().trim() + " carries the key set group "
                                + DEFAULT_GROUP_LITERAL + " carries");
                assertEquals(rowsPerGroup, group.getValue().size(),
                        "equals: group " + group.getKey().trim() + " carries " + rowsPerGroup
                                + " type and category rows");
            }
        }

        @Test
        @DisplayName("The DEFAULT table carries the three rate values discgrp.txt encodes")
        void theDefaultTableCarriesTheThreeMeasuredRates() {
            Set<BigDecimal> rates = new LinkedHashSet<>();
            for (DisclosureGroupRecord row : rowsOf(DEFAULT_GROUP)) {
                rates.add(row.interestRate());
                assertEquals(PicClause.DIS_INT_RATE_SCALE, row.interestRate().scale(),
                        "equals: group " + DEFAULT_GROUP_LITERAL + " type "
                                + row.transactionTypeCode() + " category "
                                + row.transactionCategoryCode()
                                + " keeps the DIS-INT-RATE scale of CVTRA02Y L9");
            }

            assertEquals(Set.of(ZERO_RATE, STANDARD_RATE, HIGH_RATE), rates,
                    "equals, so scale matches: group " + DEFAULT_GROUP_LITERAL + " carries "
                            + ZERO_RATE + ", " + STANDARD_RATE + " and " + HIGH_RATE);
        }

        @Test
        @DisplayName("DEFAULT 03/0001 resolves to zero, which the L214 gate reads")
        void theZeroRatedRowResolvesToNumericZero() {
            ResolvedRate resolved =
                    resolveRate(BLANK_GROUP, ZERO_RATED_TYPE, ZERO_RATED_CATEGORY);

            assertEquals(0, resolved.rate().compareTo(BigDecimal.ZERO),
                    "compareTo: group " + DEFAULT_GROUP_LITERAL + " type " + ZERO_RATED_TYPE
                            + " category " + ZERO_RATED_CATEGORY + " carries " + ZERO_RATE);
            assertEquals(ZERO_RATE, resolved.rate(),
                    "equals, so scale matches: the zero rate keeps the DIS-INT-RATE scale of "
                            + PicClause.DIS_INT_RATE_SCALE);
            assertEquals(PicClause.DIS_INT_RATE_SCALE, resolved.rate().scale(),
                    "equals: CVTRA02Y L9 fixes " + PicClause.DIS_INT_RATE_SCALE
                            + " digits after the point");
            assertTrue(resolved.fallbackUsed(),
                    "the blank fixture group reaches this row through group "
                            + DEFAULT_GROUP_LITERAL);
        }

        @Test
        @DisplayName("Every fixture row maps onto the target composite key and rate column")
        void everyFixtureRowMapsToTheTargetRow() {
            for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
                DisclosureGroupEntity entity = new DisclosureGroupEntity(
                        new DisclosureGroupId(row.accountGroupId(), row.transactionTypeCode(),
                                row.transactionCategoryCode()),
                        row.interestRate());

                assertEquals(row.accountGroupId(), entity.getId().getAccountGroupId(),
                        "equals: DIS-ACCT-GROUP-ID at CVTRA02Y L6 maps to the first key column");
                assertEquals(row.transactionTypeCode(),
                        entity.getId().getTransactionTypeCode(),
                        "equals: DIS-TRAN-TYPE-CD at CVTRA02Y L7 maps to the second key column");
                assertEquals(row.transactionCategoryCode(),
                        entity.getId().getTransactionCategoryCode(),
                        "equals: DIS-TRAN-CAT-CD at CVTRA02Y L8 maps to the third key column");
                assertEquals(row.interestRate(), entity.getInterestRate(),
                        "equals, so scale matches: DIS-INT-RATE at CVTRA02Y L9 maps to the rate"
                                + " column for group " + row.accountGroupId().trim() + " type "
                                + row.transactionTypeCode() + " category "
                                + row.transactionCategoryCode());
            }
        }
    }

    /**
     * Exercises the divide of {@code app/cbl/CBACT04C.cbl:L462-L470} and asserts no fee.
     *
     * <p>{@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} sit in the working-storage block at
     * {@code app/cbl/CBACT04C.cbl:L143-L169}. The same block declares the timestamp components
     * ending {@code DB2-MIL} at {@code app/cbl/CBACT04C.cbl:L164} and {@code DB2-REST} at
     * {@code app/cbl/CBACT04C.cbl:L165}, which this class does not read.</p>
     */
    @Nested
    @DisplayName("1300-COMPUTE-INTEREST at CBACT04C L462-L470 and 1400-COMPUTE-FEES at L518-L520")
    class VerifiedAndNotMigrated {

        @Test
        @DisplayName("L464-L465 divides by the L465 constant and truncates toward zero")
        void theDivideTruncatesTowardZeroAtTheWorkingScale() {
            BigDecimal rate = resolveRate(BLANK_GROUP, LEADING_TYPE, LEADING_CATEGORY).rate();

            BigDecimal monthlyInterest = monthlyInterest(SAMPLE_CATEGORY_BALANCE, rate);

            assertEquals(STANDARD_RATE, rate,
                    "equals, so scale matches: group " + DEFAULT_GROUP_LITERAL + " type "
                            + LEADING_TYPE + " category " + LEADING_CATEGORY + " carries "
                            + STANDARD_RATE);
            assertEquals(SAMPLE_MONTHLY_INTEREST, monthlyInterest,
                    "equals, so scale matches: balance " + SAMPLE_CATEGORY_BALANCE + " at rate "
                            + rate + " over " + CobolDecimal.INTEREST_DIVISOR + " truncates to "
                            + SAMPLE_MONTHLY_INTEREST);
            assertEquals(PicClause.WS_MONTHLY_INT_SCALE, monthlyInterest.scale(),
                    "equals: WS-MONTHLY-INT at CBACT04C L168 holds "
                            + PicClause.WS_MONTHLY_INT_SCALE + " digits after the point");
        }

        @Test
        @DisplayName("ADDITIVE: a store into WS-MONTHLY-INT at L168 drops the high-order digit")
        void theStoreReproducesTheNarrowedWorkingPrecision() {
            BigDecimal fieldModulus = pictureFieldModulus(PicClause.WS_MONTHLY_INT_PRECISION,
                    PicClause.WS_MONTHLY_INT_SCALE);
            BigDecimal beyondTheField = fieldModulus.add(SAMPLE_MONTHLY_INTEREST);

            BigDecimal stored = CobolDecimal.truncateToPictureField(beyondTheField,
                    PicClause.WS_MONTHLY_INT_PRECISION, PicClause.WS_MONTHLY_INT_SCALE);

            assertEquals(SAMPLE_MONTHLY_INTEREST, stored,
                    "equals, so scale matches: WS-MONTHLY-INT holds "
                            + (PicClause.WS_MONTHLY_INT_PRECISION
                                    - PicClause.WS_MONTHLY_INT_SCALE)
                            + " integer digits, and " + beyondTheField + " stores as " + stored);
            assertTrue(beyondTheField.compareTo(stored) != 0,
                    "compareTo: the store at CBACT04C L464 narrows " + beyondTheField
                            + " into a PIC S9(09)V99 field");
        }

        @Test
        @DisplayName("L467 accumulates into WS-TOTAL-INT at L169 under the same narrowing")
        void theAccumulateHoldsTheTotalWorkingPrecision() {
            BigDecimal fieldModulus = pictureFieldModulus(PicClause.WS_TOTAL_INT_PRECISION,
                    PicClause.WS_TOTAL_INT_SCALE);
            BigDecimal runningTotal = CobolDecimal.add(SAMPLE_MONTHLY_INTEREST,
                    SAMPLE_MONTHLY_INTEREST, PicClause.WS_TOTAL_INT_SCALE);

            BigDecimal stored = CobolDecimal.truncateToPictureField(runningTotal,
                    PicClause.WS_TOTAL_INT_PRECISION, PicClause.WS_TOTAL_INT_SCALE);
            BigDecimal beyondTheField = CobolDecimal.truncateToPictureField(
                    fieldModulus.add(runningTotal), PicClause.WS_TOTAL_INT_PRECISION,
                    PicClause.WS_TOTAL_INT_SCALE);

            assertEquals(runningTotal, stored,
                    "equals, so scale matches: WS-TOTAL-INT holds " + runningTotal
                            + " without narrowing");
            assertEquals(runningTotal, beyondTheField,
                    "equals, so scale matches: WS-TOTAL-INT at CBACT04C L169 holds "
                            + (PicClause.WS_TOTAL_INT_PRECISION - PicClause.WS_TOTAL_INT_SCALE)
                            + " integer digits");
            assertEquals(PicClause.WS_MONTHLY_INT_PRECISION, PicClause.WS_TOTAL_INT_PRECISION,
                    "equals: CBACT04C L168 and L169 declare one Picture clause twice");
        }

        @Test
        @DisplayName("L214 leaves the divide unreached for the zero-rated DEFAULT rows")
        void aZeroRateLeavesTheDivideUnreached() {
            for (DisclosureGroupRecord row : rowsOf(DEFAULT_GROUP)) {
                BigDecimal rate = row.interestRate();
                boolean gatePasses = carriesInterest(rate);

                assertEquals(rate.signum() != 0, gatePasses,
                        "the CBACT04C L214 gate reads rate " + rate + " for group "
                                + DEFAULT_GROUP_LITERAL + " type " + row.transactionTypeCode()
                                + " category " + row.transactionCategoryCode());
                if (!gatePasses) {
                    assertEquals(0,
                            monthlyInterest(SAMPLE_CATEGORY_BALANCE, rate)
                                    .compareTo(BigDecimal.ZERO),
                            "compareTo: rate " + rate + " for type " + row.transactionTypeCode()
                                    + " category " + row.transactionCategoryCode()
                                    + " yields no interest");
                }
            }

            assertFalse(carriesInterest(
                            resolveRate(BLANK_GROUP, ZERO_RATED_TYPE, ZERO_RATED_CATEGORY).rate()),
                    "group " + DEFAULT_GROUP_LITERAL + " type " + ZERO_RATED_TYPE + " category "
                            + ZERO_RATED_CATEGORY + " fails the CBACT04C L214 gate");
        }

        @Test
        @DisplayName("Every tcatbal.txt TRAN-CAT-BAL at CVTRA01Y L9 is zero as seeded")
        void theSeededCategoryBalancesYieldNoInterest() {
            List<TransactionCategoryBalanceRecord> seeded =
                    CardDemoFixtureLoader.loadTransactionCategoryBalances();

            for (TransactionCategoryBalanceRecord row : seeded) {
                BigDecimal rate = resolveRate(BLANK_GROUP, row.typeCode(),
                        row.categoryCode()).rate();

                assertEquals(0, row.balance().compareTo(BigDecimal.ZERO),
                        "compareTo: account " + row.accountId() + " type " + row.typeCode()
                                + " category " + row.categoryCode()
                                + " carries no balance in tcatbal.txt");
                assertEquals(0, monthlyInterest(row.balance(), rate).compareTo(BigDecimal.ZERO),
                        "compareTo: balance " + row.balance() + " at rate " + rate
                                + " yields no interest");
            }

            assertEquals(PicClause.TCATBAL_FIXTURE_RECORD_COUNT, seeded.size(),
                    "equals: tcatbal.txt delivers its declared record count");
        }

        @Test
        @DisplayName("No service module declares an interest service or a fee type")
        void noServiceModuleDeclaresAnInterestOrFeeType() {
            List<String> declared = serviceTypesNamedForInterestOrFees();

            assertEquals(List.of(), declared,
                    "resolution C8 verifies the rate rules and migrates no computation, and these "
                            + "service types carry an interest or fee name: " + declared);
        }
    }

    @Nested
    @DisplayName("1050-UPDATE-ACCOUNT at CBACT04C L350-L358")
    class CycleCloseCarveOut {

        @Test
        @DisplayName("L353 and L354 zero both accumulators, and L352 stays unreproduced")
        void theCloseZeroesBothAccumulatorsAndHoldsTheBalance() {
            AccountEntity account = accountEntity(ACCOUNTS.getFirst());
            account.setCurrentCycleCredit(ADDITIVE_CYCLE_CREDIT);
            account.setCurrentCycleDebit(ADDITIVE_CYCLE_DEBIT);
            BigDecimal openingBalance = account.getCurrentBalance();
            AccountRepository repository = mock(AccountRepository.class);
            when(repository.findForUpdateByAccountId(account.getAccountId()))
                    .thenReturn(Optional.of(account));
            when(repository.save(account)).thenReturn(account);
            OutboxWriter outbox = mock(OutboxWriter.class);
            BillingCycleService service = new BillingCycleService(repository, outbox,
                    immediateTransactions(),
                    new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()));

            AccountEntity closed = service.closeBillingCycle(account.getAccountId()).orElseThrow();

            assertEquals(0, closed.getCurrentCycleCredit().compareTo(BigDecimal.ZERO),
                    "compareTo: account " + closed.getAccountId()
                            + " opened the close with cycle credit " + ADDITIVE_CYCLE_CREDIT
                            + ", and CBACT04C L353 zeroes ACCT-CURR-CYC-CREDIT of CVACT01Y L13");
            assertEquals(0, closed.getCurrentCycleDebit().compareTo(BigDecimal.ZERO),
                    "compareTo: account " + closed.getAccountId()
                            + " opened the close with cycle debit " + ADDITIVE_CYCLE_DEBIT
                            + ", and CBACT04C L354 zeroes ACCT-CURR-CYC-DEBIT of CVACT01Y L14");
            assertEquals(openingBalance, closed.getCurrentBalance(),
                    "equals, so scale matches: ACCT-CURR-BAL holds " + openingBalance
                            + ", and CBACT04C L352 is not reproduced");
            assertEquals(PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                    closed.getCurrentCycleCredit().scale(),
                    "equals: CVACT01Y L13 fixes " + PicClause.ACCT_CURR_CYC_CREDIT_SCALE
                            + " digits after the point");
            assertEquals(PicClause.ACCT_CURR_CYC_DEBIT_SCALE,
                    closed.getCurrentCycleDebit().scale(),
                    "equals: CVACT01Y L14 fixes " + PicClause.ACCT_CURR_CYC_DEBIT_SCALE
                            + " digits after the point");
            assertEquals(PicClause.ACCT_CURR_BAL_SCALE, closed.getCurrentBalance().scale(),
                    "equals: CVACT01Y L7 fixes " + PicClause.ACCT_CURR_BAL_SCALE
                            + " digits after the point");
            verify(outbox).write(any());
        }
    }

    @Nested
    @DisplayName(EXPECTED_RATE_FILE + " bound row by row")
    class CheckedInRateExpectations {

        @Test
        @DisplayName("every row matches the fixture, the program or the migrated close")
        void everyRowOfTheRateExpectationsMatches() {
            for (ExpectedOutcomes.Row row : EXPECTED_RATES_TABLE.rows()) {
                String expected = EXPECTED_RATES_TABLE.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());

                assertEquals(expected, actualRateValue(row),
                        EXPECTED_RATE_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertTrue(EXPECTED_RATES_TABLE.unconsumedRows().isEmpty(),
                    EXPECTED_RATES_TABLE.unconsumedDescription());
        }
    }

    @Nested
    @DisplayName(EXPECTED_ACCRUAL_FILE + " bound row by row")
    class CheckedInAccrualExpectations {

        @Test
        @DisplayName("every row matches the fixture, the program, the job or the migrated close")
        void everyRowOfTheAccrualExpectationsMatches() {
            for (ExpectedOutcomes.Row row : EXPECTED_ACCRUAL.rows()) {
                String expected = EXPECTED_ACCRUAL.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());

                assertEquals(expected, actualAccrualValue(row),
                        EXPECTED_ACCRUAL_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertTrue(EXPECTED_ACCRUAL.unconsumedRows().isEmpty(),
                    EXPECTED_ACCRUAL.unconsumedDescription());
        }
    }

    /**
     * Resolves what the fixture, the program, the job or this platform holds for one accrual row.
     *
     * <p>The file carries eleven rows for each of the fifty seeded category-balance keys, then
     * seven mechanics sections whose {@code record_seq} names the section rather than an ordinal.
     * No branch reads {@code expected_value}.</p>
     *
     * @param row the expectation to resolve
     * @return the value the row must equal
     * @throws IllegalStateException when the row names a field this method does not resolve
     */
    private static String actualAccrualValue(ExpectedOutcomes.Row row) {
        return switch (row.recordSequence()) {
            case "RATE-RESOLUTION" -> rateResolutionValue(row.expectedField());
            case "ACCRUAL-DRIVER" -> accrualDriverValue(row.expectedField());
            case "INTEREST-TXN" -> interestTransactionValue(row.expectedField());
            case "CYCLE-CLOSE" -> cycleCloseValue(row.expectedField());
            case "SUMMARY" -> accrualSummaryValue(row.expectedField());
            case "SCOPE" -> carveOutScopeValue(row.expectedField());
            case "PROHIBITION" -> carveOutProhibitionValue(row.expectedField());
            default -> seededKeyValue(row);
        };
    }

    /** Resolves one {@code RATE-RESOLUTION} mechanics expectation. */
    private static String rateResolutionValue(String field) {
        return switch (field) {
            case "first_read_accepted_statuses" ->
                    CobolSourceEvidence.acceptedStatusesOf(INTEREST_PROGRAM, RATE_PARAGRAPH);
            case "first_read_invalid_key_message_1" -> displayedLiteral(RATE_PARAGRAPH, 1);
            case "first_read_invalid_key_message_2" -> displayedLiteral(RATE_PARAGRAPH, 2);
            case "fallback_trigger_status" -> fallbackTriggerStatus();
            case "fallback_substitutes_group_id_only" ->
                    yesOrNo(GROUP_ID_KEY_COMPONENT.equals(substitutedKeyComponent())
                            && theSubstitutionTouchesNeitherCode());
            case "fallback_preserves_type_and_category" ->
                    yesOrNo(theSubstitutionTouchesNeitherCode());
            case "retry_count_before_abend" ->
                    Integer.toString(LOOKUPS_WITH_THE_DEFAULT_RETRY - LOOKUPS_ON_A_MATCH);
            case "retry_read_accepted_statuses" -> CobolSourceEvidence
                    .acceptedStatusesOf(INTEREST_PROGRAM, DEFAULT_RATE_PARAGRAPH);
            case "retry_read_has_invalid_key_clause" -> yesOrNo(CobolSourceEvidence
                    .paragraphContains(INTEREST_PROGRAM, DEFAULT_RATE_PARAGRAPH, INVALID_KEY));
            case "second_miss_abend_message" ->
                    displayedLiteral(DEFAULT_RATE_PARAGRAPH, 1);
            case "second_miss_abends" -> yesOrNo(
                    CobolSourceEvidence.abendsFrom(INTEREST_PROGRAM, DEFAULT_RATE_PARAGRAPH));
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved rate field " + field);
        };
    }

    /** Resolves one {@code ACCRUAL-DRIVER} mechanics expectation. */
    private static String accrualDriverValue(String field) {
        return switch (field) {
            case "sequential_read_eof_status" -> endOfFileStatus();
            case "account_break_condition_field" -> accountBreakField();
            case "ws_total_int_reset_on_account_break" -> yesOrNo(
                    CobolSourceEvidence.contains(INTEREST_PROGRAM, "MOVE 0 TO WS-TOTAL-INT"));
            case "account_data_read_only_on_break" -> yesOrNo(CobolSourceEvidence
                    .occurrences(INTEREST_PROGRAM, "PERFORM 1100-GET-ACCT-DATA") == 1L);
            case "xref_data_read_only_on_break" -> yesOrNo(CobolSourceEvidence
                    .occurrences(INTEREST_PROGRAM, "PERFORM 1110-GET-XREF-DATA") == 1L);
            case "first_account_suppresses_update" -> yesOrNo(
                    CobolSourceEvidence.contains(INTEREST_PROGRAM, "IF WS-FIRST-TIME NOT = 'Y'")
                            && CobolSourceEvidence.contains(INTEREST_PROGRAM,
                                    "MOVE 'N' TO WS-FIRST-TIME"));
            case "eof_flushes_final_account_update" -> yesOrNo(CobolSourceEvidence
                    .occurrences(INTEREST_PROGRAM, "PERFORM 1050-UPDATE-ACCOUNT") > 1L);
            case "account_updates_performed" -> Integer.toString(seededAccountIds().size());
            case "records_read" -> Integer.toString(seededCategoryBalances().size());
            case "rate_zero_gates_out_compute_and_fees" -> yesOrNo(
                    CobolSourceEvidence.contains(INTEREST_PROGRAM, "IF DIS-INT-RATE NOT = 0"));
            case "commented_out_key_diagnostics_present" -> yesOrNo(commentedKeyDiagnostics());
            case "fee_paragraph_is_unimplemented_stub" -> yesOrNo(
                    CobolSourceEvidence.isUnimplementedStub(INTEREST_PROGRAM, FEE_PARAGRAPH));
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved driver field " + field);
        };
    }

    /** Resolves one {@code INTEREST-TXN} mechanics expectation. */
    private static String interestTransactionValue(String field) {
        return switch (field) {
            case "tran_type_cd" -> movedLiteralInto("TRAN-TYPE-CD");
            case "tran_cat_cd" -> zeroPadded(movedLiteralInto("TRAN-CAT-CD"),
                    PicClause.TRAN_CAT_CD_WIDTH);
            case "tran_source" -> movedLiteralInto("TRAN-SOURCE");
            case "tran_desc_prefix" -> descriptionPrefix();
            case "tran_amt_source_field" -> movedFieldInto("TRAN-AMT");
            case "tran_merchant_id" -> zeroPadded(movedLiteralInto("TRAN-MERCHANT-ID"),
                    PicClause.TRAN_MERCHANT_ID_WIDTH);
            case "tran_merchant_name_is_spaces" -> yesOrNo(spacesAreMovedInto("TRAN-MERCHANT-NAME"));
            case "tran_merchant_city_is_spaces" -> yesOrNo(spacesAreMovedInto("TRAN-MERCHANT-CITY"));
            case "tran_merchant_zip_is_spaces" -> yesOrNo(spacesAreMovedInto("TRAN-MERCHANT-ZIP"));
            case "tran_card_num_source_field" -> movedFieldInto("TRAN-CARD-NUM");
            case "one_timestamp_into_both_ts_fields" -> yesOrNo(
                    movedFieldInto("TRAN-ORIG-TS").equals(movedFieldInto("TRAN-PROC-TS")));
            case "tran_id_parm_date_component" -> jobParameter();
            case "tran_id_suffix_starts_at" -> zeroPadded(suffixIncrement(), suffixWidth());
            case "write_accepted_status" ->
                    CobolSourceEvidence.acceptedStatusesOf(INTEREST_PROGRAM, WRITE_PARAGRAPH);
            case "write_failure_abends" ->
                    yesOrNo(CobolSourceEvidence.abendsFrom(INTEREST_PROGRAM, WRITE_PARAGRAPH));
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved transaction field " + field);
        };
    }

    /** Resolves one {@code CYCLE-CLOSE} mechanics expectation. */
    private static String cycleCloseValue(String field) {
        return switch (field) {
            case "interest_added_to_balance_in_source" -> yesOrNo(CobolSourceEvidence
                    .paragraphContains(INTEREST_PROGRAM, CYCLE_PARAGRAPH,
                            "ADD WS-TOTAL-INT TO ACCT-CURR-BAL"));
            case "interest_add_reproduced_by_cycle_close" ->
                    yesOrNo(!migratedCloseHoldsTheBalance());
            case "cyc_credit_zeroed" ->
                    closedCycleAccount().getCurrentCycleCredit().toPlainString();
            case "cyc_debit_zeroed" -> closedCycleAccount().getCurrentCycleDebit().toPlainString();
            case "account_rewrite_accepted_status" ->
                    CobolSourceEvidence.acceptedStatusesOf(INTEREST_PROGRAM, CYCLE_PARAGRAPH);
            case "cycle_close_is_only_reproduced_statement_pair" -> yesOrNo(
                    migratedCloseZeroesBothAccumulators() && migratedCloseHoldsTheBalance());
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved cycle field " + field);
        };
    }

    /** Resolves one {@code SUMMARY} expectation of the seeded fixture. */
    private static String accrualSummaryValue(String field) {
        return switch (field) {
            case "seeded_category_balance_keys" ->
                    Integer.toString(seededCategoryBalances().size());
            case "seeded_record_width_bytes" ->
                    Integer.toString(PicClause.TCATBAL_FIXTURE_RECORD_WIDTH);
            case "distinct_seeded_balances" -> Integer.toString(distinctSeeded(
                    seed -> seed.balance().stripTrailingZeros().toPlainString()));
            case "distinct_seeded_type_codes" ->
                    Integer.toString(distinctSeeded(TransactionCategoryBalanceRecord::typeCode));
            case "distinct_seeded_category_codes" -> Integer.toString(
                    distinctSeeded(TransactionCategoryBalanceRecord::categoryCode));
            case "fallback_fires_on_seeded_key_count" ->
                    Long.toString(seededKeysThroughTheFallback());
            case "fallback_fires_on_model_b_closing_key_count" ->
                    Long.toString(keysThroughTheFallback());
            case "accounts_with_ten_space_group_id" -> Long.toString(ACCOUNTS.stream()
                    .filter(account -> BLANK_GROUP.equals(account.groupId())).count());
            case "acct_addr_zip_holds_a_real_group_id" -> onlyAdjacentGroupIdentifier();
            case "divide_is_unobservable_on_seeded_data" -> yesOrNo(everySeededAccrualIsZero());
            case "interest_transactions_written" -> Long.toString(seededKeysThatAccrue());
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved summary field " + field);
        };
    }

    /** Resolves one {@code SCOPE} expectation of resolution C8. */
    private static String carveOutScopeValue(String field) {
        return switch (field) {
            case "interest_rate_rules_equivalence_verified" -> yesOrNo(!RATES.isEmpty());
            case "interest_computation_migrated_to_a_service" ->
                    yesOrNo(!serviceTypesNamedForInterestOrFees().isEmpty());
            case "interest_remains_a_batch_process" -> yesOrNo(jobControlLanguageMember(
                    INTEREST_JOB_MEMBER).contains("PGM=" + INTEREST_PROGRAM_NAME));
            case "interest_publishes_no_event", "interest_consumes_no_event" ->
                    yesOrNo(eventSchemasNamedForInterestOrFees().isEmpty());
            case "cycle_close_endpoint_is_operational_not_event_driven" ->
                    yesOrNo(noConsumerDrivesTheCycleClose());
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved scope field " + field);
        };
    }

    /** Resolves one {@code PROHIBITION} expectation of resolution C8. */
    private static String carveOutProhibitionValue(String field) {
        return switch (field) {
            case "no_interest_service_exists" ->
                    yesOrNo(serviceTypesNamedForInterestOrFees().isEmpty());
            case "no_interest_event_schema_exists" ->
                    yesOrNo(eventSchemasNamedForInterestOrFees().isEmpty());
            case "fee_computation_not_implemented" -> yesOrNo(
                    CobolSourceEvidence.isUnimplementedStub(INTEREST_PROGRAM, FEE_PARAGRAPH));
            case "interest_add_to_balance_not_reproduced" ->
                    yesOrNo(migratedCloseHoldsTheBalance());
            case "rounding_mode_down_applies_to_the_divide" -> yesOrNo(theDivideTruncates());
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved prohibition field " + field);
        };
    }

    /** Resolves one of the eleven expectations of one seeded category-balance key. */
    private static String seededKeyValue(ExpectedOutcomes.Row row) {
        int ordinal = Integer.parseInt(row.recordSequence());
        TransactionCategoryBalanceRecord seed = seededCategoryBalances().get(ordinal - 1);
        AccountRecord account = CardDemoFixtureLoader.accountsByAccountId().get(seed.accountId());
        String key = seed.accountId() + SEED_KEY_SEPARATOR + seed.typeCode() + SEED_KEY_SEPARATOR
                + seed.categoryCode();

        assertEquals(row.entityKey(), key, EXPECTED_ACCRUAL_FILE + " row " + row.key()
                + " names a key the fixture does not carry at that sequence");

        ResolvedRate resolved = resolveRate(account.groupId(), seed.typeCode(),
                seed.categoryCode());
        BigDecimal accrued = monthlyInterest(seed.balance(), resolved.rate());

        return switch (row.expectedField()) {
            case "tran_cat_bal_seeded" -> CobolDecimal
                    .truncateToScale(seed.balance(), PicClause.TRAN_CAT_BAL_SCALE)
                    .toPlainString();
            case "acct_group_id_on_account_record" -> account.groupId();
            case "discgrp_key_first_read" ->
                    account.groupId() + seed.typeCode() + seed.categoryCode();
            case "discgrp_status_first_read" ->
                    resolved.fallbackUsed() ? fallbackTriggerStatus() : NORMAL_FILE_STATUS;
            case "dis_acct_group_id_after_fallback" ->
                    resolved.fallbackUsed() ? DEFAULT_GROUP : account.groupId();
            case "dis_int_rate_resolved" -> CobolDecimal
                    .truncateToScale(resolved.rate(), PicClause.DIS_INT_RATE_SCALE)
                    .toPlainString();
            case "interest_compute_gate_opens" ->
                    yesOrNo(resolved.rate().compareTo(BigDecimal.ZERO) != 0);
            case "ws_monthly_int_accrued", "ws_total_int_at_account_break" ->
                    accrued.toPlainString();
            case "interest_transaction_written" ->
                    yesOrNo(resolved.rate().compareTo(BigDecimal.ZERO) != 0);
            case "interest_transaction_id" ->
                    jobParameter() + zeroPadded(Integer.toString(ordinal), suffixWidth());
            default -> throw new IllegalStateException(
                    EXPECTED_ACCRUAL_FILE + " names unresolved seed field " + row.expectedField());
        };
    }

    /**
     * Resolves what the fixture, the program or the migrated close really holds for one row.
     *
     * <p>No branch reads {@code expected_value}. A per-row expectation resolves against the parsed
     * fixture row its sequence names, a fixture-level expectation against the whole fixture, and a
     * fallback expectation against {@link #INTEREST_PROGRAM} on disk or against
     * {@link BillingCycleService} as this platform ships it.</p>
     *
     * @param row the expectation to resolve
     * @return the value the row must equal
     * @throws IllegalStateException when the row names a field this method does not resolve
     */
    private static String actualRateValue(ExpectedOutcomes.Row row) {
        return switch (row.entityKey()) {
            case DISCGRP_FIXTURE_KEY -> fixtureLevelRateValue(row.expectedField());
            case FALLBACK_KEY -> fallbackValue(row.expectedField());
            default -> perRowRateValue(row);
        };
    }

    /** Resolves one whole-fixture expectation of {@link #EXPECTED_RATE_FILE}. */
    private static String fixtureLevelRateValue(String field) {
        return switch (field) {
            case "record_count" -> Integer.toString(DISCLOSURE_GROUPS.size());
            case "record_length" -> Integer.toString(PicClause.DISCGRP_FIXTURE_RECORD_WIDTH);
            case "filler_character" -> fillerCharacterName();
            case "overpunch_position_count" -> Long.toString(overpunchPositionCount());
            case "distinct_group_count" -> Integer.toString(GROUP_IDENTIFIERS.size());
            case "rows_per_group" -> Integer.toString(uniformRowsPerGroup());
            case "group_id_values" -> trimmedGroupIdentifiers();
            case "cluster_key_width" ->
                    CobolSourceEvidence.datasetKeyParameter(DISCGRP_JOB_MEMBER, FIRST_CAPTURE);
            case "cluster_key_offset" ->
                    CobolSourceEvidence.datasetKeyParameter(DISCGRP_JOB_MEMBER, SECOND_CAPTURE);
            case "cluster_record_size" ->
                    CobolSourceEvidence.datasetRecordSize(DISCGRP_JOB_MEMBER);
            case "zeroapr_all_rates_zero" ->
                    yesOrNo(everyRateIsZeroInTheGroupNamedBy("zeroapr_all_rates_zero"));
            case "a000000000_vs_default_divergent_row_count" ->
                    Long.toString(divergentKeys().size());
            case "a000000000_vs_default_divergent_key" -> onlyDivergentKey();
            case "default_zero_rated_key" -> onlyZeroRatedDefaultKey();
            default -> throw new IllegalStateException(
                    EXPECTED_RATE_FILE + " names unresolved fixture field " + field);
        };
    }

    /** Resolves one fallback expectation of {@link #EXPECTED_RATE_FILE}. */
    private static String fallbackValue(String field) {
        return switch (field) {
            case "fallback_fires_on_key_count" -> Long.toString(keysThroughTheFallback());
            case "fallback_fires_on_key_total" ->
                    Integer.toString(categoryKeysPostedRecordsReach().size());
            case "account_group_id_value" -> ACCOUNTS.getFirst().groupId();
            case "account_group_id_present_in_discgrp_keys" ->
                    yesOrNo(GROUP_IDENTIFIERS.contains(BLANK_GROUP));
            case "addr_zip_is_a_real_group_id" -> yesOrNo(everyAdjacentZipNamesAGroup());
            case "substituted_key_component" -> substitutedKeyComponent();
            case "type_and_category_carry_over_unchanged" ->
                    yesOrNo(theSubstitutionTouchesNeitherCode());
            case "first_read_accepted_statuses" ->
                    CobolSourceEvidence.acceptedStatusesOf(INTEREST_PROGRAM, RATE_PARAGRAPH);
            case "retry_read_accepted_statuses" -> CobolSourceEvidence
                    .acceptedStatusesOf(INTEREST_PROGRAM, DEFAULT_RATE_PARAGRAPH);
            case "read_count_on_matched_key" -> Integer.toString(LOOKUPS_ON_A_MATCH);
            case "read_count_on_missed_key" -> Integer.toString(LOOKUPS_WITH_THE_DEFAULT_RETRY);
            case "second_miss_outcome" -> CobolSourceEvidence
                    .abendsFrom(INTEREST_PROGRAM, DEFAULT_RATE_PARAGRAPH) ? "abend" : "continue";
            case "second_miss_abend_code" -> CobolSourceEvidence.abendCode(INTEREST_PROGRAM);
            case "interest_formula" -> accrualFormula();
            case "cycle_credit_accumulator_field" -> zeroedAccumulatorField(FIRST_CAPTURE);
            case "cycle_debit_accumulator_field" -> zeroedAccumulatorField(SECOND_CAPTURE);
            case "cycle_close_zeroes_both_accumulators" ->
                    yesOrNo(migratedCloseZeroesBothAccumulators());
            case "cycle_close_omits_interest_add" -> yesOrNo(migratedCloseHoldsTheBalance());
            default -> throw new IllegalStateException(
                    EXPECTED_RATE_FILE + " names unresolved fallback field " + field);
        };
    }

    /** Resolves one per-row expectation against the fixture row its sequence names. */
    private static String perRowRateValue(ExpectedOutcomes.Row row) {
        DisclosureGroupRecord fixtureRow =
                DISCLOSURE_GROUPS.get(Integer.parseInt(row.recordSequence()) - 1);
        String key = fixtureRow.accountGroupId().strip() + KEY_PART_SEPARATOR
                + fixtureRow.transactionTypeCode() + KEY_PART_SEPARATOR
                + fixtureRow.transactionCategoryCode();

        assertEquals(row.entityKey(), key, EXPECTED_RATE_FILE + " row " + row.key()
                + " names a key the fixture does not carry at that sequence");

        return switch (row.expectedField()) {
            case "dis_acct_group_id" -> fixtureRow.accountGroupId();
            case "dis_tran_type_cd" -> fixtureRow.transactionTypeCode();
            case "dis_tran_cat_cd" -> fixtureRow.transactionCategoryCode();
            case "dis_int_rate" -> CobolDecimal
                    .truncateToScale(fixtureRow.interestRate(), PicClause.DIS_INT_RATE_SCALE)
                    .toPlainString();
            default -> throw new IllegalStateException(
                    EXPECTED_RATE_FILE + " names unresolved row field " + row.expectedField());
        };
    }

    /** Names the character every fixture row's trailing filler is made of. */
    private static String fillerCharacterName() {
        for (String record : disclosureGroupRecordText()) {
            for (char character : record.substring(FILLER_OFFSET).toCharArray()) {
                if (character != FILLER_CHARACTER) {
                    throw new IllegalStateException(
                            "the trailing filler carries " + character + " as well");
                }
            }
        }
        return FILLER_CHARACTER_NAME;
    }

    /** Counts the fixture rows whose rate ends in a zoned sign overpunch rather than a digit. */
    private static long overpunchPositionCount() {
        return disclosureGroupRecordText().stream()
                .map(record -> record.charAt(FILLER_OFFSET - 1))
                .filter(last -> !Character.isDigit(last))
                .count();
    }

    /** Answers the row count every group carries, refusing an uneven table. */
    private static int uniformRowsPerGroup() {
        Set<Integer> counts = new LinkedHashSet<>();
        for (String groupId : GROUP_IDENTIFIERS) {
            counts.add(rowsOf(groupId).size());
        }
        if (counts.size() != 1) {
            throw new IllegalStateException("the groups carry uneven row counts " + counts);
        }
        return counts.iterator().next();
    }

    /** Lists the group identifiers the way {@link #EXPECTED_RATE_FILE} writes them. */
    private static String trimmedGroupIdentifiers() {
        return GROUP_IDENTIFIERS.stream().map(String::strip).sorted()
                .collect(Collectors.joining(VALUE_SEPARATOR));
    }

    /**
     * Reports whether every row of the group an expectation names carries a zero rate.
     *
     * <p>The group is taken from the field name rather than written down, so the assertion stays
     * bound to the identifier {@link #EXPECTED_RATE_FILE} names. The check matters because
     * {@code DEFAULT} also carries a zero-rated row, so "every zero-rated row belongs to one
     * group" would be false while this property is true.</p>
     *
     * @param field the expectation's field name, whose leading part names the group
     * @return {@code true} when every fixture row of that group carries a zero rate
     * @throws IllegalStateException when the fixture carries no such group
     */
    private static boolean everyRateIsZeroInTheGroupNamedBy(String field) {
        String groupId = paddedGroupIdentifier(
                field.substring(0, field.indexOf(FIELD_NAME_SEPARATOR)).toUpperCase(Locale.ROOT));
        List<DisclosureGroupRecord> rows = rowsOf(groupId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "the fixture carries no group " + groupId.strip());
        }
        return rows.stream().allMatch(row -> row.interestRate().compareTo(BigDecimal.ZERO) == 0);
    }

    /** Answers the reference codes where the leading group and {@code DEFAULT} rate differently. */
    private static List<RateCode> divergentKeys() {
        List<RateCode> divergent = new ArrayList<>();
        for (DisclosureGroupRecord row : rowsOf(MATCHED_GROUP)) {
            BigDecimal fromDefault = RATES.get(new RateKey(DEFAULT_GROUP,
                    row.transactionTypeCode(), row.transactionCategoryCode()));
            if (fromDefault != null && fromDefault.compareTo(row.interestRate()) != 0) {
                divergent.add(new RateCode(row.transactionTypeCode(),
                        row.transactionCategoryCode()));
            }
        }
        return List.copyOf(divergent);
    }

    /** Names the single divergent key, refusing to answer when there is more than one. */
    private static String onlyDivergentKey() {
        List<RateCode> divergent = divergentKeys();
        if (divergent.size() != 1) {
            throw new IllegalStateException("the fixture carries " + divergent.size()
                    + " divergent keys, so no single key names the divergence");
        }
        RateCode only = divergent.getFirst();
        return only.typeCode() + KEY_PART_SEPARATOR + only.categoryCode();
    }

    /**
     * Names the one reference code the fixture run reaches for which {@code DEFAULT} rates at zero.
     *
     * <p>{@code DEFAULT} carries seven zero-rated rows, so "the zero-rated row" is not a property
     * of the rate table alone. It becomes one when the run is taken into account: of the reference
     * codes {@code app/data/ASCII/dailytran.txt} and {@code app/data/ASCII/tcatbal.txt} between
     * them reach, exactly one resolves to a zero rate, and that is the code at which
     * {@code app/cbl/CBACT04C.cbl:L464} leaves its divide unreached.</p>
     *
     * @return the type and category, joined the way {@link #EXPECTED_RATE_FILE} joins them
     * @throws IllegalStateException when the reached codes hold no single zero-rated one
     */
    private static String onlyZeroRatedDefaultKey() {
        Set<RateCode> zeroRated = new LinkedHashSet<>();
        for (CategoryKey reached : categoryKeysAfterPosting()) {
            BigDecimal rate = resolveRate(BLANK_GROUP, reached.typeCode(), reached.categoryCode())
                    .rate();
            if (rate.compareTo(BigDecimal.ZERO) == 0) {
                zeroRated.add(new RateCode(reached.typeCode(), reached.categoryCode()));
            }
        }
        if (zeroRated.size() != 1) {
            throw new IllegalStateException("the run reaches " + zeroRated.size()
                    + " zero-rated reference codes, so no single code names one");
        }
        RateCode only = zeroRated.iterator().next();
        return only.typeCode() + KEY_PART_SEPARATOR + only.categoryCode();
    }

    /** Counts the keys a posted record reaches whose rate resolves through the substitution. */
    private static long keysThroughTheFallback() {
        return categoryKeysPostedRecordsReach().stream()
                .filter(key -> resolveRate(BLANK_GROUP, key.typeCode(), key.categoryCode())
                        .fallbackUsed())
                .count();
    }

    /**
     * Answers the composite keys of {@code CVTRA01Y.cpy:L5-L8} that a posted record really touches.
     *
     * <p>This is deliberately narrower than {@link #categoryKeysAfterPosting()}, which unions the
     * {@code tcatbal.txt} seeds in whether the run touches them or not. A rate is resolved only
     * where an amount is applied, and one seeded key the fixture carries is never reached, so the
     * two counts differ by one. {@code dailytran-category-balances-model-b.csv} carries the same
     * narrower set, which is the corroboration.</p>
     *
     * @return the keys, in the order the run reaches them
     */
    private static Set<CategoryKey> categoryKeysPostedRecordsReach() {
        Map<String, CycleAccumulators> accounts = cycleAccumulatorsByAccountId();
        Map<String, CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Set<CategoryKey> reached = new LinkedHashSet<>();

        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            CardCrossReferenceRecord crossReference =
                    crossReferences.get(transaction.cardNumber());
            CycleAccumulators account = crossReference == null
                    ? null
                    : accounts.get(crossReference.accountId());
            if (account == null || account.declines(transaction)) {
                continue;
            }
            reached.add(new CategoryKey(crossReference.accountId(), transaction.typeCode(),
                    transaction.categoryCode()));
            account.apply(transaction.amount());
        }
        return Set.copyOf(reached);
    }

    /** Reports whether every account's adjacent postal field names a group the fixture carries. */
    private static boolean everyAdjacentZipNamesAGroup() {
        return ACCOUNTS.stream()
                .allMatch(account -> GROUP_IDENTIFIERS.contains(
                        paddedGroupIdentifier(account.addressZip().strip())));
    }

    /** Names the key part the substitution writes into, without its file-section prefix. */
    private static String substitutedKeyComponent() {
        Pattern substitution = Pattern.compile(
                "MOVE '" + DEFAULT_GROUP_LITERAL + "' TO ([A-Z0-9-]+)");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM, RATE_PARAGRAPH)) {
            Matcher matcher = substitution.matcher(line);
            if (matcher.find()) {
                String field = matcher.group(FIRST_CAPTURE);
                return field.startsWith(RECORD_DESCRIPTION_PREFIX)
                        ? field.substring(RECORD_DESCRIPTION_PREFIX.length())
                        : field;
            }
        }
        throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + RATE_PARAGRAPH
                + " substitutes no " + DEFAULT_GROUP_LITERAL + " literal");
    }

    /** Reports whether the substitution leaves the type and category key parts alone. */
    private static boolean theSubstitutionTouchesNeitherCode() {
        return CobolSourceEvidence.paragraph(INTEREST_PROGRAM, RATE_PARAGRAPH).stream()
                .noneMatch(line -> line.contains("TO FD-DIS-TRAN-TYPE-CD")
                        || line.contains("TO FD-DIS-TRAN-CAT-CD")
                        || line.contains("TO DIS-TRAN-TYPE-CD")
                        || line.contains("TO DIS-TRAN-CAT-CD"));
    }

    /** Answers the accrual expression the way a reader would write it. */
    private static String accrualFormula() {
        List<String> body = CobolSourceEvidence.paragraph(INTEREST_PROGRAM, INTEREST_PARAGRAPH);
        for (int index = 0; index < body.size(); index++) {
            if (!body.get(index).startsWith("COMPUTE ")) {
                continue;
            }
            String continuation = index + 1 < body.size() ? body.get(index + 1) : "";
            String expression = continuation.startsWith("=")
                    ? continuation.substring(1)
                    : body.get(index).substring(body.get(index).indexOf('=') + 1);
            return expression.strip().replace("( ", "(").replace(" )", ")");
        }
        throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + INTEREST_PARAGRAPH
                + " carries no COMPUTE statement");
    }

    /** Names one of the two accumulators the cycle-close paragraph zeroes, in source order. */
    private static String zeroedAccumulatorField(int ordinal) {
        List<String> zeroed = new ArrayList<>();
        Pattern move = Pattern.compile("MOVE 0 TO ([A-Z0-9-]+)");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM, CYCLE_PARAGRAPH)) {
            Matcher matcher = move.matcher(line);
            if (matcher.find()) {
                zeroed.add(matcher.group(FIRST_CAPTURE));
            }
        }
        if (zeroed.size() < ordinal) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + CYCLE_PARAGRAPH
                    + " zeroes only " + zeroed.size() + " fields");
        }
        return zeroed.get(ordinal - 1);
    }

    /** Reports whether the migrated close really zeroes both accumulators. */
    private static boolean migratedCloseZeroesBothAccumulators() {
        AccountEntity closed = closedCycleAccount();
        return closed.getCurrentCycleCredit().compareTo(BigDecimal.ZERO) == 0
                && closed.getCurrentCycleDebit().compareTo(BigDecimal.ZERO) == 0;
    }

    /** Reports whether the migrated close leaves the balance alone, omitting the accrual add. */
    private static boolean migratedCloseHoldsTheBalance() {
        AccountEntity opened = accountEntity(ACCOUNTS.getFirst());
        return closedCycleAccount().getCurrentBalance().compareTo(opened.getCurrentBalance()) == 0;
    }

    /**
     * Runs the shipped {@link BillingCycleService} over one seeded account and answers the result.
     *
     * <p>Both accumulators open non-zero so that a close which did nothing would be visible, and
     * the balance opens at whatever the fixture row carries so that reproducing
     * {@code app/cbl/CBACT04C.cbl:L352} would be visible too.</p>
     *
     * @return the account as the close leaves it
     */
    private static AccountEntity closedCycleAccount() {
        AccountEntity account = accountEntity(ACCOUNTS.getFirst());
        account.setCurrentCycleCredit(ADDITIVE_CYCLE_CREDIT);
        account.setCurrentCycleDebit(ADDITIVE_CYCLE_DEBIT);
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findForUpdateByAccountId(account.getAccountId()))
                .thenReturn(Optional.of(account));
        when(repository.save(account)).thenReturn(account);
        BillingCycleService service = new BillingCycleService(repository, mock(OutboxWriter.class),
                immediateTransactions(),
                new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()));
        return service.closeBillingCycle(account.getAccountId()).orElseThrow();
    }

    /** Writes a boolean the way {@link #EXPECTED_RATE_FILE} writes one. */
    private static String yesOrNo(boolean value) {
        return value ? ExpectedOutcomes.YES : ExpectedOutcomes.NO;
    }

    /** Answers the literals a paragraph displays, one-based, in source order. */
    private static String displayedLiteral(String label, int ordinal) {
        List<String> displayed = new ArrayList<>();
        Pattern display = Pattern.compile("DISPLAY '([^']*)'");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM, label)) {
            Matcher matcher = display.matcher(line);
            while (matcher.find()) {
                displayed.add(matcher.group(FIRST_CAPTURE));
            }
        }
        if (displayed.size() < ordinal) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + label
                    + " displays only " + displayed.size() + " literals");
        }
        return displayed.get(ordinal - 1);
    }

    /** Answers the file status that triggers the default-group substitution. */
    private static String fallbackTriggerStatus() {
        Pattern single = Pattern.compile("IF DISCGRP-STATUS = '(\\d{2})'$");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM, RATE_PARAGRAPH)) {
            Matcher matcher = single.matcher(line);
            if (matcher.find()) {
                return matcher.group(FIRST_CAPTURE);
            }
        }
        throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + RATE_PARAGRAPH
                + " tests no single file status");
    }

    /** Answers the status the sequential read treats as end of file. */
    private static String endOfFileStatus() {
        Set<String> tested = new LinkedHashSet<>();
        Pattern status = Pattern.compile("IF TCATBALF-STATUS = '(\\d{2})'");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM, READ_PARAGRAPH)) {
            Matcher matcher = status.matcher(line);
            if (matcher.find()) {
                tested.add(matcher.group(FIRST_CAPTURE));
            }
        }
        tested.remove(NORMAL_FILE_STATUS);
        if (tested.size() != 1) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + READ_PARAGRAPH
                    + " tests " + tested.size() + " statuses beyond the normal one");
        }
        return tested.iterator().next();
    }

    /** Names the field the main loop compares against the remembered account number. */
    private static String accountBreakField() {
        Matcher matcher = Pattern.compile("IF ([A-Z0-9-]+) NOT= WS-LAST-ACCT-NUM")
                .matcher(String.join(" ", CobolSourceEvidence.lines(INTEREST_PROGRAM)));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_PROGRAM + " carries no account-break test");
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Reports whether the commented-out key diagnostics still sit in the main loop. */
    private static boolean commentedKeyDiagnostics() {
        String program = CobolSourceEvidence.file(INTEREST_PROGRAM);
        return program.contains("*              DISPLAY 'ACCT-GROUP-ID: '")
                && program.contains("*              DISPLAY 'TRANCAT-CD: '")
                && program.contains("*              DISPLAY 'TRANCAT-TYPE-CD: '");
    }

    /** Answers the literal the write paragraph moves into one transaction field. */
    private static String movedLiteralInto(String field) {
        Matcher matcher = Pattern
                .compile("MOVE '?([A-Za-z0-9]+)'? +TO " + field + "$", Pattern.MULTILINE)
                .matcher(String.join("\n", CobolSourceEvidence
                        .paragraph(INTEREST_PROGRAM, WRITE_PARAGRAPH)));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + WRITE_PARAGRAPH
                    + " moves no literal into " + field);
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Names the field the write paragraph moves into one transaction field. */
    private static String movedFieldInto(String field) {
        Matcher matcher = Pattern
                .compile("MOVE ([A-Z0-9-]+) +TO " + field + "$", Pattern.MULTILINE)
                .matcher(String.join("\n", CobolSourceEvidence
                        .paragraph(INTEREST_PROGRAM, WRITE_PARAGRAPH)));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + WRITE_PARAGRAPH
                    + " moves no field into " + field);
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Reports whether the write paragraph blanks one transaction field. */
    private static boolean spacesAreMovedInto(String field) {
        return CobolSourceEvidence.paragraph(INTEREST_PROGRAM, WRITE_PARAGRAPH).stream()
                .anyMatch(line -> line.matches("MOVE SPACES +TO " + field));
    }

    /** Answers the literal prefix the write paragraph strings in front of the account number. */
    private static String descriptionPrefix() {
        Matcher matcher = Pattern.compile("STRING '([^']*)' ,?").matcher(String.join("\n",
                CobolSourceEvidence.paragraph(INTEREST_PROGRAM, WRITE_PARAGRAPH)));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + WRITE_PARAGRAPH
                    + " strings no description prefix");
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Answers the hard-coded parameter the interest job passes the program. */
    private static String jobParameter() {
        Matcher matcher = Pattern.compile("PARM='([^']*)'")
                .matcher(jobControlLanguageMember(INTEREST_JOB_MEMBER));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_JOB_MEMBER + " passes no PARM");
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Answers the amount the write paragraph adds to the identifier suffix. */
    private static String suffixIncrement() {
        Matcher matcher = Pattern.compile("ADD (\\d+) TO WS-TRANID-SUFFIX").matcher(String.join(
                "\n", CobolSourceEvidence.paragraph(INTEREST_PROGRAM, WRITE_PARAGRAPH)));
        if (!matcher.find()) {
            throw new IllegalStateException(INTEREST_PROGRAM + " paragraph " + WRITE_PARAGRAPH
                    + " does not advance WS-TRANID-SUFFIX");
        }
        return matcher.group(FIRST_CAPTURE);
    }

    /** Answers how many digits the identifier leaves for the suffix after the job parameter. */
    private static int suffixWidth() {
        return PicClause.TRAN_ID_WIDTH - jobParameter().length();
    }

    /** Left-pads a numeric string to one width with the digit the Picture clause implies. */
    private static String zeroPadded(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(value + " exceeds " + width + " digits");
        }
        return CODE_PAD_DIGIT.repeat(width - value.length()) + value;
    }

    /** The fifty seeded rows of {@code app/data/ASCII/tcatbal.txt}, in fixture order. */
    private static List<TransactionCategoryBalanceRecord> seededCategoryBalances() {
        return CardDemoFixtureLoader.loadTransactionCategoryBalances();
    }

    /** The account identifiers the seeded rows name, without repetition. */
    private static Set<String> seededAccountIds() {
        Set<String> accountIds = new LinkedHashSet<>();
        for (TransactionCategoryBalanceRecord seed : seededCategoryBalances()) {
            accountIds.add(seed.accountId());
        }
        return Set.copyOf(accountIds);
    }

    /** Counts the distinct values one projection takes across the seeded rows. */
    private static int distinctSeeded(
            java.util.function.Function<TransactionCategoryBalanceRecord, String> projection) {
        Set<String> values = new LinkedHashSet<>();
        for (TransactionCategoryBalanceRecord seed : seededCategoryBalances()) {
            values.add(projection.apply(seed));
        }
        return values.size();
    }

    /** Counts the seeded keys whose rate resolves through the default-group substitution. */
    private static long seededKeysThroughTheFallback() {
        return seededCategoryBalances().stream()
                .filter(seed -> resolveRate(accountGroupOf(seed), seed.typeCode(),
                        seed.categoryCode()).fallbackUsed())
                .count();
    }

    /** Counts the seeded keys whose resolved rate opens the accrual gate. */
    private static long seededKeysThatAccrue() {
        return seededCategoryBalances().stream()
                .filter(seed -> resolveRate(accountGroupOf(seed), seed.typeCode(),
                        seed.categoryCode()).rate().compareTo(BigDecimal.ZERO) != 0)
                .count();
    }

    /** Reports whether every seeded key accrues nothing, which is what a zero balance means. */
    private static boolean everySeededAccrualIsZero() {
        return seededCategoryBalances().stream().allMatch(seed -> monthlyInterest(
                seed.balance(),
                resolveRate(accountGroupOf(seed), seed.typeCode(), seed.categoryCode()).rate())
                .compareTo(BigDecimal.ZERO) == 0);
    }

    /** Answers the group identifier the account record of one seeded row carries. */
    private static String accountGroupOf(TransactionCategoryBalanceRecord seed) {
        return CardDemoFixtureLoader.accountsByAccountId().get(seed.accountId()).groupId();
    }

    /** Answers the single adjacent postal value every account carries, refusing more than one. */
    private static String onlyAdjacentGroupIdentifier() {
        Set<String> values = new LinkedHashSet<>();
        for (AccountRecord account : ACCOUNTS) {
            values.add(account.addressZip());
        }
        if (values.size() != 1) {
            throw new IllegalStateException("the accounts carry " + values.size()
                    + " distinct ACCT-ADDR-ZIP values, so no single value names a group");
        }
        return values.iterator().next();
    }

    /**
     * Reports whether the shared divide really truncates rather than rounding half up.
     *
     * <p>The case is chosen so the two modes disagree. A balance of {@code 100.00} at a rate of
     * {@code 20.00} divides to {@code 1.6666...}, which truncates to {@code 1.66} and rounds half
     * up to {@code 1.67}. Asserting the disagreement as well as the answer is what makes a future
     * change of rounding mode fail here rather than pass silently.</p>
     *
     * @return {@code true} when the shared helper answers the truncated value and the two modes
     *         really do disagree on it
     */
    private static boolean theDivideTruncates() {
        BigDecimal balance = new BigDecimal("100.00");
        BigDecimal rate = new BigDecimal("20.00");
        BigDecimal product = balance.multiply(rate);
        BigDecimal truncated = product.divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.DOWN);
        BigDecimal roundedHalfUp = product.divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.HALF_UP);
        return truncated.compareTo(roundedHalfUp) != 0
                && monthlyInterest(balance, rate).compareTo(truncated) == 0;
    }

    /** Names the event schemas whose file name carries an interest or fee word, in walk order. */
    private static List<String> eventSchemasNamedForInterestOrFees() {
        Path schemas = repositoryRoot().resolve(EVENT_SCHEMA_DIRECTORY);
        if (!Files.isDirectory(schemas)) {
            throw new IllegalStateException("no schema directory at " + schemas);
        }
        try (Stream<Path> files = Files.list(schemas)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(InterestCalculationEquivalenceTest::namedForInterestOrFees)
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + schemas, unreadable);
        }
    }

    /**
     * Reports whether no Kafka listener of the account service drives the cycle close.
     *
     * <p>The cycle close is an operational endpoint, so a consumer that invoked it would put the
     * carve-out back inside the event model, which resolution C8 forbids.</p>
     *
     * @return {@code true} when no consumer source mentions the close
     * @throws UncheckedIOException when the messaging package cannot be walked
     */
    private static boolean noConsumerDrivesTheCycleClose() {
        Path messaging = repositoryRoot().resolve(ACCOUNT_MESSAGING_DIRECTORY);
        if (!Files.isDirectory(messaging)) {
            throw new IllegalStateException("no messaging package at " + messaging);
        }
        try (Stream<Path> files = Files.list(messaging)) {
            return files.filter(path -> path.getFileName().toString().endsWith(JAVA_SOURCE_SUFFIX))
                    .noneMatch(InterestCalculationEquivalenceTest::mentionsTheCycleClose);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + messaging, unreadable);
        }
    }

    /** Reports whether one source file mentions the cycle-close operation. */
    private static boolean mentionsTheCycleClose(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8).contains(CYCLE_CLOSE_METHOD);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + source, unreadable);
        }
    }

    /** Reads the fixture of {@code app/data/ASCII/discgrp.txt} as its declared-width records. */
    private static List<String> disclosureGroupRecordText() {
        Path path = CardDemoFixtureLoader.fixtureDirectory().resolve("discgrp.txt");
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /**
     * Resolves one rate the way {@code app/cbl/CBACT04C.cbl:L415-L460} resolves it.
     *
     * <p>A matched primary read answers with its own row. A missed read substitutes the
     * {@code app/cbl/CBACT04C.cbl:L437} literal into the first key part and re-reads once. A
     * missed re-read reaches {@code app/cbl/CBACT04C.cbl:L458}.</p>
     *
     * @param groupId      the {@code DIS-ACCT-GROUP-ID} the caller supplies
     * @param typeCode     the {@code DIS-TRAN-TYPE-CD} the caller supplies
     * @param categoryCode the {@code DIS-TRAN-CAT-CD} the caller supplies
     * @return the resolved rate, whether the substitution fired, and the read count
     * @throws IllegalStateException when the substituted re-read finds no row
     */
    private static ResolvedRate resolveRate(String groupId, String typeCode, String categoryCode) {
        BigDecimal primary = RATES.get(new RateKey(groupId, typeCode, categoryCode));
        if (primary != null) {
            return new ResolvedRate(primary, false, LOOKUPS_ON_A_MATCH);
        }

        BigDecimal substituted = RATES.get(new RateKey(DEFAULT_GROUP, typeCode, categoryCode));
        if (substituted == null) {
            throw new IllegalStateException("group " + DEFAULT_GROUP_LITERAL
                    + " carries no row for type " + typeCode + " category " + categoryCode);
        }
        return new ResolvedRate(substituted, true, LOOKUPS_WITH_THE_DEFAULT_RETRY);
    }

    /**
     * Divides as {@code app/cbl/CBACT04C.cbl:L464-L465} divides, then stores the result.
     *
     * @param categoryBalance the {@code TRAN-CAT-BAL} of {@code app/cpy/CVTRA01Y.cpy:L9}
     * @param rate            the {@code DIS-INT-RATE} of {@code app/cpy/CVTRA02Y.cpy:L9}
     * @return the value {@code WS-MONTHLY-INT} at {@code app/cbl/CBACT04C.cbl:L168} would hold
     */
    private static BigDecimal monthlyInterest(BigDecimal categoryBalance, BigDecimal rate) {
        BigDecimal quotient = CobolDecimal.multiplyThenDivide(categoryBalance, rate,
                CobolDecimal.INTEREST_DIVISOR, PicClause.WS_MONTHLY_INT_SCALE);
        return CobolDecimal.truncateToPictureField(quotient, PicClause.WS_MONTHLY_INT_PRECISION,
                PicClause.WS_MONTHLY_INT_SCALE);
    }

    /**
     * Reports what the gate at {@code app/cbl/CBACT04C.cbl:L214} reports for one rate.
     *
     * @param rate the {@code DIS-INT-RATE} the resolution answered with
     * @return {@code true} when the rate is not zero
     */
    private static boolean carriesInterest(BigDecimal rate) {
        return rate.signum() != 0;
    }

    /**
     * Returns the magnitude one Picture field cannot hold.
     *
     * @param precision total digits of the target field, from {@link PicClause}
     * @param scale     digits after the decimal point, from {@link PicClause}
     * @return ten raised to the count of integer digits the field holds
     */
    private static BigDecimal pictureFieldModulus(int precision, int scale) {
        return BigDecimal.TEN.pow(precision - scale).setScale(scale);
    }

    /** Indexes every fixture row by the three key parts of {@code app/cpy/CVTRA02Y.cpy:L6-L8}. */
    private static Map<RateKey, BigDecimal> rateTable() {
        Map<RateKey, BigDecimal> rates = new LinkedHashMap<>();
        for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
            RateKey key = new RateKey(row.accountGroupId(), row.transactionTypeCode(),
                    row.transactionCategoryCode());
            if (rates.put(key, row.interestRate()) != null) {
                throw new IllegalStateException("discgrp.txt repeats group "
                        + row.accountGroupId().trim() + " type " + row.transactionTypeCode()
                        + " category " + row.transactionCategoryCode());
            }
        }
        return Map.copyOf(rates);
    }

    /** Collects the distinct {@code DIS-ACCT-GROUP-ID} values, in fixture order. */
    private static Set<String> groupIdentifiers() {
        Set<String> identifiers = new LinkedHashSet<>();
        for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
            identifiers.add(row.accountGroupId());
        }
        return Set.copyOf(identifiers);
    }

    /** Returns the fixture rows of one group, in fixture order. */
    private static List<DisclosureGroupRecord> rowsOf(String groupId) {
        return DISCLOSURE_GROUPS.stream()
                .filter(row -> row.accountGroupId().equals(groupId))
                .toList();
    }

    /**
     * Finds a fixture group whose discriminating rate differs from the one {@code DEFAULT} carries.
     *
     * @return the group identifier, padded to the width of {@code app/cpy/CVTRA02Y.cpy:L6}
     * @throws IllegalStateException when every group carries the same discriminating rate
     */
    private static String groupThatRatesDifferentlyFromDefault() {
        BigDecimal defaultRate = RATES.get(new RateKey(DEFAULT_GROUP, DISCRIMINATING_TYPE,
                DISCRIMINATING_CATEGORY));
        if (defaultRate == null) {
            throw new IllegalStateException("group " + DEFAULT_GROUP_LITERAL
                    + " carries no row for type " + DISCRIMINATING_TYPE + " category "
                    + DISCRIMINATING_CATEGORY);
        }

        for (String identifier : GROUP_IDENTIFIERS) {
            BigDecimal candidate = RATES.get(new RateKey(identifier, DISCRIMINATING_TYPE,
                    DISCRIMINATING_CATEGORY));
            if (candidate != null && candidate.compareTo(defaultRate) != 0) {
                return identifier;
            }
        }
        throw new IllegalStateException("every discgrp.txt group carries " + defaultRate
                + " for type " + DISCRIMINATING_TYPE + " category " + DISCRIMINATING_CATEGORY);
    }

    /**
     * Finds a type and category pair no fixture group carries.
     *
     * @return a pair whose category code follows a present one and is itself absent
     * @throws IllegalStateException when every successor category code is present
     */
    private static RateCode codePairAbsentFromEveryGroup() {
        Set<RateCode> present = new LinkedHashSet<>();
        for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
            present.add(new RateCode(row.transactionTypeCode(), row.transactionCategoryCode()));
        }

        for (RateCode carried : present) {
            RateCode successor = new RateCode(carried.typeCode(),
                    nextCategoryCode(carried.categoryCode()));
            if (!present.contains(successor)) {
                return successor;
            }
        }
        throw new IllegalStateException("discgrp.txt carries every successor category code");
    }

    /**
     * Returns the category code that follows one code, padded to its declared width.
     *
     * @param categoryCode a {@code DIS-TRAN-CAT-CD} the fixture carries
     * @return the next code at the width of {@code app/cpy/CVTRA02Y.cpy:L8}
     * @throws IllegalStateException when the next code exceeds that width
     */
    private static String nextCategoryCode(String categoryCode) {
        String next = Integer.toString(Integer.parseInt(categoryCode) + NEXT_CATEGORY_CODE_STEP);
        if (next.length() > PicClause.DIS_TRAN_CAT_CD_WIDTH) {
            throw new IllegalStateException("the code after " + categoryCode + " exceeds the "
                    + PicClause.DIS_TRAN_CAT_CD_WIDTH + " digits DIS-TRAN-CAT-CD holds");
        }
        return CODE_PAD_DIGIT.repeat(PicClause.DIS_TRAN_CAT_CD_WIDTH - next.length()) + next;
    }

    /**
     * Pads a group identifier to the width of {@code app/cpy/CVTRA02Y.cpy:L6}.
     *
     * @param value a group identifier or the adjacent {@code ACCT-ADDR-ZIP} value
     * @return the value followed by the spaces the field pads with
     * @throws IllegalArgumentException when the value exceeds the declared width
     */
    private static String paddedGroupIdentifier(String value) {
        if (value.length() > PicClause.DIS_ACCT_GROUP_ID_WIDTH) {
            throw new IllegalArgumentException(value + " exceeds the "
                    + PicClause.DIS_ACCT_GROUP_ID_WIDTH + " bytes DIS-ACCT-GROUP-ID holds");
        }
        return value + " ".repeat(PicClause.DIS_ACCT_GROUP_ID_WIDTH - value.length());
    }

    /** Joins the three key parts into the {@code DIS-GROUP-KEY} of {@code CVTRA02Y.cpy:L5}. */
    private static String compositeKey(String groupId, String typeCode, String categoryCode) {
        return paddedGroupIdentifier(groupId) + typeCode + categoryCode;
    }

    /**
     * Collects the category keys the fixture run reaches, seeds first and posted rows after.
     *
     * <p>The seeds come from {@code app/data/ASCII/tcatbal.txt}. Each posted row of
     * {@code app/data/ASCII/dailytran.txt} adds the key its type and category name, and
     * {@code app/cbl/CBTRN02C.cbl:L403-L420} holds the rest back.</p>
     *
     * @return the reached keys, in the order the run reaches them
     */
    private static Set<CategoryKey> categoryKeysAfterPosting() {
        Map<String, CycleAccumulators> accounts = cycleAccumulatorsByAccountId();
        Map<String, CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Set<CategoryKey> reached = new LinkedHashSet<>();

        for (TransactionCategoryBalanceRecord seed
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            reached.add(new CategoryKey(seed.accountId(), seed.typeCode(), seed.categoryCode()));
        }

        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            CardCrossReferenceRecord crossReference =
                    crossReferences.get(transaction.cardNumber());
            CycleAccumulators account = crossReference == null
                    ? null
                    : accounts.get(crossReference.accountId());
            if (account == null || account.declines(transaction)) {
                continue;
            }
            reached.add(new CategoryKey(crossReference.accountId(), transaction.typeCode(),
                    transaction.categoryCode()));
            account.apply(transaction.amount());
        }
        return Set.copyOf(reached);
    }

    /** Indexes the fields the overlimit and expiration tests read, keyed by account identifier. */
    private static Map<String, CycleAccumulators> cycleAccumulatorsByAccountId() {
        Map<String, CycleAccumulators> accounts = new LinkedHashMap<>();
        for (AccountRecord account : ACCOUNTS) {
            accounts.put(account.accountId(), new CycleAccumulators(account.creditLimit(),
                    account.expirationDate(), account.currentCycleCredit(),
                    account.currentCycleDebit()));
        }
        return accounts;
    }

    /**
     * Names every service type whose file name carries an interest or a fee word.
     *
     * @return the file names found under {@code card-platform/services}, in walk order
     * @throws UncheckedIOException when the service tree cannot be read
     */
    private static List<String> serviceTypesNamedForInterestOrFees() {
        Path services = repositoryRoot().resolve(SERVICE_MODULE_DIRECTORY);
        List<String> named = new ArrayList<>();
        try (Stream<Path> files = Files.walk(services)) {
            files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(JAVA_SOURCE_SUFFIX))
                    .filter(InterestCalculationEquivalenceTest::namedForInterestOrFees)
                    .forEach(named::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + services, unreadable);
        }
        return List.copyOf(named);
    }

    /** Reports whether one source file name carries an interest or a fee word. */
    private static boolean namedForInterestOrFees(String fileName) {
        return fileName.contains(INTEREST_TYPE_WORD) || fileName.contains(FEE_TYPE_WORD);
    }

    /**
     * Reads one Job Control Language member of the read-only source tree.
     *
     * @param member the repository-relative path of the member
     * @return the whole member as text
     * @throws UncheckedIOException when the member cannot be read
     */
    private static String jobControlLanguageMember(String member) {
        Path path = repositoryRoot().resolve(member);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /**
     * Applies one pattern to one member and answers with the match.
     *
     * @param pattern   the parameter pattern
     * @param text      the member text
     * @param parameter the parameter name, reported when the match fails
     * @return the match, positioned on its captures
     * @throws IllegalStateException when the member carries no such parameter
     */
    private static Matcher requireMatch(Pattern pattern, String text, String parameter) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException(DISCGRP_JOB_MEMBER + " carries no " + parameter
                    + " parameter");
        }
        return matcher;
    }

    /** Resolves the repository root from the fixture directory the loader reports. */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }

    /** Copies one fixture account row into the entity the cycle-close operation stores. */
    private static AccountEntity accountEntity(AccountRecord source) {
        AccountEntity account = new AccountEntity();
        account.setAccountId(source.accountId());
        account.setActiveStatus(source.activeStatus());
        account.setCurrentBalance(source.currentBalance());
        account.setCreditLimit(source.creditLimit());
        account.setCashCreditLimit(source.cashCreditLimit());
        account.setOpenDate(source.openDate());
        account.setExpirationDate(source.expirationDate());
        account.setReissueDate(source.reissueDate());
        account.setCurrentCycleCredit(source.currentCycleCredit());
        account.setCurrentCycleDebit(source.currentCycleDebit());
        account.setAddressZip(source.addressZip());
        account.setGroupId(source.groupId());
        return account;
    }

    /** Runs the cycle-close callback on the calling thread with no transaction manager. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** The three key parts of {@code app/cpy/CVTRA02Y.cpy:L6-L8}. */
    private record RateKey(String groupId, String typeCode, String categoryCode) { }

    /** The two reference codes of {@code app/cpy/CVTRA02Y.cpy:L7-L8}. */
    private record RateCode(String typeCode, String categoryCode) { }

    /** The composite key of {@code app/cpy/CVTRA01Y.cpy:L5-L8}. */
    private record CategoryKey(String accountId, String typeCode, String categoryCode) { }

    /** One resolution outcome of {@code app/cbl/CBACT04C.cbl:L415-L460}. */
    private record ResolvedRate(BigDecimal rate, boolean fallbackUsed, int lookupCount) { }

    /**
     * Holds the two accumulators of {@code app/cpy/CVACT01Y.cpy:L13-L14} across the fixture run.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L403-L407} reads both into a working balance and compares
     * that balance with the credit limit. {@code app/cbl/CBTRN02C.cbl:L547-L551} routes a posted
     * amount into one of the two on its sign.</p>
     */
    private static final class CycleAccumulators {

        /** {@code ACCT-CREDIT-LIMIT} of {@code app/cpy/CVACT01Y.cpy:L8}. */
        private final BigDecimal creditLimit;

        /** {@code ACCT-EXPIRAION-DATE} of {@code app/cpy/CVACT01Y.cpy:L11}, held as text. */
        private final String expirationDate;

        /** {@code ACCT-CURR-CYC-CREDIT} of {@code app/cpy/CVACT01Y.cpy:L13}. */
        private BigDecimal cycleCredit;

        /** {@code ACCT-CURR-CYC-DEBIT} of {@code app/cpy/CVACT01Y.cpy:L14}. */
        private BigDecimal cycleDebit;

        private CycleAccumulators(BigDecimal creditLimit, String expirationDate,
                BigDecimal cycleCredit, BigDecimal cycleDebit) {
            this.creditLimit = creditLimit;
            this.expirationDate = expirationDate;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        /** Applies the overlimit test of L403-L413 and the expiration test of L414-L420. */
        private boolean declines(DailyTransactionRecord transaction) {
            BigDecimal difference = CobolDecimal.subtract(cycleCredit, cycleDebit,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal computed = CobolDecimal.add(difference, transaction.amount(),
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal working = CobolDecimal.truncateToPictureField(computed,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);
            boolean overLimit = creditLimit.compareTo(working) < 0;
            boolean expired = expirationDate.compareTo(
                    CopybookRecordParser.timestampDatePart(transaction.originTimestamp())) < 0;
            return overLimit || expired;
        }

        /** Routes a posted amount into one accumulator on its sign, per L548-L551. */
        private void apply(BigDecimal amount) {
            if (amount.signum() < 0) {
                cycleDebit = CobolDecimal.add(cycleDebit, amount,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
                return;
            }
            cycleCredit = CobolDecimal.add(cycleCredit, amount,
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        }
    }
}
