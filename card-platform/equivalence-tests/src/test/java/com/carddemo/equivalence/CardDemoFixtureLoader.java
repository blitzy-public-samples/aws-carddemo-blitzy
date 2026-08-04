package com.carddemo.equivalence;

import com.carddemo.cobol.PicClause;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads the nine CardDemo sample fixtures under {@code app/data/ASCII} into typed records.
 *
 * <p>Every fixture is fixed width and line feed delimited. Each load validates the record width and
 * the record count against the constants {@link PicClause} publishes, then hands each record to the
 * matching {@link CopybookRecordParser} operation. Returned lists and maps are immutable, and no
 * fixture is opened for anything other than reading.</p>
 *
 * <p>ADDITIVE: {@code app/data/ASCII/cardxref.txt} delivers
 * {@link PicClause#CARDXREF_FIXTURE_RECORD_WIDTH} bytes where {@code app/jcl/XREFFILE.jcl:L44}
 * declares {@code RECORDSIZE(50 50)}. {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:L7}
 * closes on the last delivered byte and the {@code app/cpy/CVACT03Y.cpy:L8} filler is absent. That
 * fixture is the only width mismatch among the nine, and the tolerance is recorded in
 * {@code card-platform/docs/decision-log.md} (planned).</p>
 *
 * <p>Every failure this class reports names the fixture file, the field, the one-based record
 * ordinal, the position inside a field, and the widths and counts involved. None carries the text
 * of a field, so a failing load discloses no card number, no account identifier and no other
 * fixture value.</p>
 */
public final class CardDemoFixtureLoader {

    /**
     * System property that names the fixture directory. A value set here skips the search for an
     * ancestor directory and is used as given.
     */
    public static final String FIXTURE_DIRECTORY_PROPERTY = "carddemo.fixture.directory";

    /** First path segment below the repository root that leads to the fixtures. */
    private static final String APP_DIRECTORY_NAME = "app";

    /** Second path segment below the repository root that leads to the fixtures. */
    private static final String DATA_DIRECTORY_NAME = "data";

    /** Third path segment below the repository root, which holds the nine text fixtures. */
    private static final String ASCII_DIRECTORY_NAME = "ASCII";

    // Fixture file names. Every fixture is named here and nowhere else, so no operation below
    // holds a file name of its own and no operation lists or matches a directory. Each constant
    // names its layout copybook and the PicClause constants that carry its count and width.

    /**
     * Account fixture, laid out by {@code app/cpy/CVACT01Y.cpy}, counted by
     * {@link PicClause#ACCTDATA_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#ACCTDATA_FIXTURE_RECORD_WIDTH}. {@code app/jcl/ACCTFILE.jcl:L41} declares
     * {@code RECORDSIZE(300 300)}.
     */
    public static final String ACCTDATA_FIXTURE_FILE_NAME = "acctdata.txt";

    /**
     * Card fixture, laid out by {@code app/cpy/CVACT02Y.cpy}, counted by
     * {@link PicClause#CARDDATA_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#CARDDATA_FIXTURE_RECORD_WIDTH}. {@code app/jcl/CARDFILE.jcl:L55} declares
     * {@code RECORDSIZE(150 150)}.
     */
    public static final String CARDDATA_FIXTURE_FILE_NAME = "carddata.txt";

    /**
     * Card cross-reference fixture, laid out by {@code app/cpy/CVACT03Y.cpy}, counted by
     * {@link PicClause#CARDXREF_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#CARDXREF_FIXTURE_RECORD_WIDTH}. {@code app/jcl/XREFFILE.jcl:L44} declares
     * {@code RECORDSIZE(50 50)}, restored by {@link #cardCrossReferenceRecordsAtDeclaredWidth}.
     */
    public static final String CARDXREF_FIXTURE_FILE_NAME = "cardxref.txt";

    /**
     * Customer fixture, laid out by {@code app/cpy/CVCUS01Y.cpy}, counted by
     * {@link PicClause#CUSTDATA_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#CUSTDATA_FIXTURE_RECORD_WIDTH}. {@code app/jcl/CUSTFILE.jcl:L51} declares
     * {@code RECORDSIZE(500 500)}.
     */
    public static final String CUSTDATA_FIXTURE_FILE_NAME = "custdata.txt";

    /**
     * Daily transaction fixture, laid out by {@code app/cpy/CVTRA06Y.cpy}, counted by
     * {@link PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#DAILYTRAN_FIXTURE_RECORD_WIDTH}. {@code app/jcl/TRANFILE.jcl:L54} declares
     * {@code RECORDSIZE(350 350)}.
     */
    public static final String DAILYTRAN_FIXTURE_FILE_NAME = "dailytran.txt";

    /**
     * Disclosure group fixture, laid out by {@code app/cpy/CVTRA02Y.cpy}, counted by
     * {@link PicClause#DISCGRP_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#DISCGRP_FIXTURE_RECORD_WIDTH}. {@code app/jcl/DISCGRP.jcl:L41} declares
     * {@code RECORDSIZE(50 50)}.
     */
    public static final String DISCGRP_FIXTURE_FILE_NAME = "discgrp.txt";

    /**
     * Transaction category balance fixture, laid out by {@code app/cpy/CVTRA01Y.cpy}, counted by
     * {@link PicClause#TCATBAL_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#TCATBAL_FIXTURE_RECORD_WIDTH}. {@code app/jcl/TCATBALF.jcl:L41} declares
     * {@code RECORDSIZE(50 50)}.
     */
    public static final String TCATBAL_FIXTURE_FILE_NAME = "tcatbal.txt";

    /**
     * Transaction category fixture, laid out by {@code app/cpy/CVTRA04Y.cpy}, counted by
     * {@link PicClause#TRANCATG_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#TRANCATG_FIXTURE_RECORD_WIDTH}. {@code app/jcl/TRANCATG.jcl:L41} declares
     * {@code RECORDSIZE(60 60)}.
     */
    public static final String TRANCATG_FIXTURE_FILE_NAME = "trancatg.txt";

    /**
     * Transaction type fixture, laid out by {@code app/cpy/CVTRA03Y.cpy}, counted by
     * {@link PicClause#TRANTYPE_FIXTURE_RECORD_COUNT} and measured by
     * {@link PicClause#TRANTYPE_FIXTURE_RECORD_WIDTH}. {@code app/jcl/TRANTYPE.jcl:L41} declares
     * {@code RECORDSIZE(60 60)}.
     */
    public static final String TRANTYPE_FIXTURE_FILE_NAME = "trantype.txt";

    /** Character that closes every record in all nine fixtures. */
    private static final char RECORD_SEPARATOR = '\n';

    /**
     * The nine fixture files this loader reads, in the order the constants above declare them.
     *
     * <p>{@link #requireFixtureDirectory(Path)} checks every one, so a directory chosen through
     * {@link #FIXTURE_DIRECTORY_PROPERTY} has to be a CardDemo fixture directory.</p>
     */
    private static final List<String> FIXTURE_FILE_NAMES = List.of(
            ACCTDATA_FIXTURE_FILE_NAME,
            CARDDATA_FIXTURE_FILE_NAME,
            CARDXREF_FIXTURE_FILE_NAME,
            CUSTDATA_FIXTURE_FILE_NAME,
            DAILYTRAN_FIXTURE_FILE_NAME,
            DISCGRP_FIXTURE_FILE_NAME,
            TCATBAL_FIXTURE_FILE_NAME,
            TRANCATG_FIXTURE_FILE_NAME,
            TRANTYPE_FIXTURE_FILE_NAME);

    /** Characters the record separator occupies, which a scan steps over to reach the next record. */
    private static final int RECORD_SEPARATOR_WIDTH = 1;

    /** Character COBOL pads a {@code PIC X(n)} field with on the right. */
    private static final String COBOL_TEXT_PAD = " ";

    /** {@code XREF-CARD-NUM} at {@code app/cpy/CVACT03Y.cpy:L5}, reported when a key read fails. */
    private static final String XREF_CARD_NUM_FIELD = "XREF-CARD-NUM";

    /** {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy:L5}, reported when a key read fails. */
    private static final String ACCT_ID_FIELD = "ACCT-ID";

    /** Lowest character a {@code PIC 9(n)} field accepts. */
    private static final char DIGIT_LOWER_BOUND = '0';

    /** Highest character a {@code PIC 9(n)} field accepts. */
    private static final char DIGIT_UPPER_BOUND = '9';

    /**
     * Decimal places an account identifier carries. {@code ACCT-ID} is {@code PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}, so the value holds digits and no fraction.
     */
    private static final int ACCOUNT_IDENTIFIER_SCALE = 0;

    /** Amount added to a zero-based position to report it as a one-based ordinal. */
    private static final int FIRST_ORDINAL = 1;

    private CardDemoFixtureLoader() {
        throw new AssertionError(CardDemoFixtureLoader.class.getName() + " holds static operations only");
    }

    /**
     * Parses one fixture record of a single layout.
     */
    private interface RecordParser<T> {

        /**
         * Reads one fixed-width record.
         *
         * @param record one fixture record, padding included
         * @return the typed fields of the record
         */
        T parse(String record);
    }

    // Loaders. One per fixture, listed in the order the fixture table names them.

    /**
     * Loads {@code app/data/ASCII/acctdata.txt}.
     *
     * @return {@link PicClause#ACCTDATA_FIXTURE_RECORD_COUNT} immutable account records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.AccountRecord> loadAccounts() {
        return load(ACCTDATA_FIXTURE_FILE_NAME,
                PicClause.ACCTDATA_FIXTURE_RECORD_WIDTH,
                PicClause.ACCTDATA_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseAccount);
    }

    /**
     * Loads {@code app/data/ASCII/carddata.txt}.
     *
     * @return {@link PicClause#CARDDATA_FIXTURE_RECORD_COUNT} immutable card records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.CardRecord> loadCards() {
        return load(CARDDATA_FIXTURE_FILE_NAME,
                PicClause.CARDDATA_FIXTURE_RECORD_WIDTH,
                PicClause.CARDDATA_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseCard);
    }

    /**
     * Loads {@code app/data/ASCII/cardxref.txt} at the width the file delivers.
     *
     * @return {@link PicClause#CARDXREF_FIXTURE_RECORD_COUNT} immutable cross-reference records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.CardCrossReferenceRecord> loadCardCrossReferences() {
        return load(CARDXREF_FIXTURE_FILE_NAME,
                PicClause.CARDXREF_FIXTURE_RECORD_WIDTH,
                PicClause.CARDXREF_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseCardCrossReference);
    }

    /**
     * Loads {@code app/data/ASCII/custdata.txt}.
     *
     * @return {@link PicClause#CUSTDATA_FIXTURE_RECORD_COUNT} immutable customer records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.CustomerRecord> loadCustomers() {
        return load(CUSTDATA_FIXTURE_FILE_NAME,
                PicClause.CUSTDATA_FIXTURE_RECORD_WIDTH,
                PicClause.CUSTDATA_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseCustomer);
    }

    /**
     * Loads {@code app/data/ASCII/dailytran.txt}, the fixture the posting suite drives end to end.
     *
     * @return {@link PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} immutable daily transaction records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.DailyTransactionRecord> loadDailyTransactions() {
        return load(DAILYTRAN_FIXTURE_FILE_NAME,
                PicClause.DAILYTRAN_FIXTURE_RECORD_WIDTH,
                PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseDailyTransaction);
    }

    /**
     * Loads {@code app/data/ASCII/discgrp.txt}.
     *
     * @return {@link PicClause#DISCGRP_FIXTURE_RECORD_COUNT} immutable disclosure group records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.DisclosureGroupRecord> loadDisclosureGroups() {
        return load(DISCGRP_FIXTURE_FILE_NAME,
                PicClause.DISCGRP_FIXTURE_RECORD_WIDTH,
                PicClause.DISCGRP_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseDisclosureGroup);
    }

    /**
     * Loads {@code app/data/ASCII/tcatbal.txt}.
     *
     * @return {@link PicClause#TCATBAL_FIXTURE_RECORD_COUNT} immutable category balance records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.TransactionCategoryBalanceRecord>
            loadTransactionCategoryBalances() {
        return load(TCATBAL_FIXTURE_FILE_NAME,
                PicClause.TCATBAL_FIXTURE_RECORD_WIDTH,
                PicClause.TCATBAL_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseTransactionCategoryBalance);
    }

    /**
     * Loads {@code app/data/ASCII/trancatg.txt}.
     *
     * @return {@link PicClause#TRANCATG_FIXTURE_RECORD_COUNT} immutable transaction category
     *         records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.TransactionCategoryRecord> loadTransactionCategories() {
        return load(TRANCATG_FIXTURE_FILE_NAME,
                PicClause.TRANCATG_FIXTURE_RECORD_WIDTH,
                PicClause.TRANCATG_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseTransactionCategory);
    }

    /**
     * Loads {@code app/data/ASCII/trantype.txt}.
     *
     * @return {@link PicClause#TRANTYPE_FIXTURE_RECORD_COUNT} immutable transaction type records
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<CopybookRecordParser.TransactionTypeRecord> loadTransactionTypes() {
        return load(TRANTYPE_FIXTURE_FILE_NAME,
                PicClause.TRANTYPE_FIXTURE_RECORD_WIDTH,
                PicClause.TRANTYPE_FIXTURE_RECORD_COUNT,
                CopybookRecordParser::parseTransactionType);
    }

    // Declared layout. The cross-reference fixture is the one file narrower than its dataset
    // definition, and this operation restores the declared width.
    // ADDITIVE, recorded in card-platform/docs/decision-log.md (planned).

    /**
     * Reads {@code app/data/ASCII/cardxref.txt} and pads each record to the declared layout.
     *
     * <p>ADDITIVE. Each record gains the {@link PicClause#CARD_XREF_RECORD_FILLER_WIDTH} trailing
     * spaces that {@code app/cpy/CVACT03Y.cpy:L8} declares and the file omits, reaching the
     * {@link PicClause#CARD_XREF_RECORD_LENGTH} bytes of {@code app/jcl/XREFFILE.jcl:L44}.</p>
     *
     * @return {@link PicClause#CARDXREF_FIXTURE_RECORD_COUNT} immutable records, each
     *         {@link PicClause#CARD_XREF_RECORD_LENGTH} characters long
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    public static List<String> cardCrossReferenceRecordsAtDeclaredWidth() {
        List<String> delivered = readRecords(CARDXREF_FIXTURE_FILE_NAME,
                PicClause.CARDXREF_FIXTURE_RECORD_WIDTH,
                PicClause.CARDXREF_FIXTURE_RECORD_COUNT);
        List<String> padded = new ArrayList<>(delivered.size());
        for (String record : delivered) {
            padded.add(record + COBOL_TEXT_PAD.repeat(PicClause.CARD_XREF_RECORD_FILLER_WIDTH));
        }
        return List.copyOf(padded);
    }

    // Lookup projections. The cross-reference key is text and the account key is numeric, which
    // matches how card_xref.card_number and account_credit_snapshot.account_id compare.

    /**
     * Indexes {@code app/data/ASCII/cardxref.txt} by {@code XREF-CARD-NUM}, the dataset key
     * {@code app/jcl/XREFFILE.jcl:L43} declares as {@code KEYS(16 0)}.
     *
     * <p>Each key holds the {@link PicClause#XREF_CARD_NUM_WIDTH} characters the fixture delivers.
     * {@link #cardNumberKey} reads a card number into that same form and refuses a shorter one,
     * because a shorter Primary Account Number (PAN) misses the row it belongs to.</p>
     *
     * @return an immutable map in fixture order, keyed by card number
     * @throws IllegalStateException when the fixture is absent, holds an unexpected record width
     *         or record count, or repeats a card number
     */
    public static Map<String, CopybookRecordParser.CardCrossReferenceRecord>
            cardCrossReferencesByCardNumber() {
        Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                new LinkedHashMap<>();
        List<CopybookRecordParser.CardCrossReferenceRecord> crossReferences =
                loadCardCrossReferences();
        for (int position = 0; position < crossReferences.size(); position++) {
            CopybookRecordParser.CardCrossReferenceRecord crossReference =
                    crossReferences.get(position);
            requireFirstOccurrence(byCardNumber.put(crossReference.cardNumber(), crossReference),
                    CARDXREF_FIXTURE_FILE_NAME, XREF_CARD_NUM_FIELD, position + FIRST_ORDINAL);
        }
        return Collections.unmodifiableMap(byCardNumber);
    }

    /**
     * Indexes {@code app/data/ASCII/acctdata.txt} by the {@code ACCT-ID} text the fixture holds,
     * which keeps the leading zeros of the {@link PicClause#ACCT_ID_WIDTH} character field.
     *
     * @return an immutable map in fixture order, keyed by the account identifier as text
     * @throws IllegalStateException when the fixture is absent, holds an unexpected record width
     *         or record count, or repeats an account identifier
     */
    public static Map<String, CopybookRecordParser.AccountRecord> accountsByAccountId() {
        Map<String, CopybookRecordParser.AccountRecord> byAccountId = new LinkedHashMap<>();
        List<CopybookRecordParser.AccountRecord> accounts = loadAccounts();
        for (int position = 0; position < accounts.size(); position++) {
            CopybookRecordParser.AccountRecord account = accounts.get(position);
            requireFirstOccurrence(byAccountId.put(account.accountId(), account),
                    ACCTDATA_FIXTURE_FILE_NAME, ACCT_ID_FIELD, position + FIRST_ORDINAL);
        }
        return Collections.unmodifiableMap(byAccountId);
    }

    /**
     * Indexes {@code app/data/ASCII/acctdata.txt} by the numeric value of {@code ACCT-ID}, the
     * form {@code account_credit_snapshot.account_id} compares as {@code NUMERIC(11,0)}.
     *
     * <p>Every key comes from {@link #accountIdentifier}, so a key carries no scale artifact and
     * no sign artifact.</p>
     *
     * @return an immutable map in fixture order, keyed by the account identifier as a value
     * @throws IllegalStateException when the fixture is absent, holds an unexpected record width
     *         or record count, or repeats an account identifier
     */
    public static Map<BigDecimal, CopybookRecordParser.AccountRecord> accountsByAccountIdentifier() {
        Map<BigDecimal, CopybookRecordParser.AccountRecord> byIdentifier = new LinkedHashMap<>();
        List<CopybookRecordParser.AccountRecord> accounts = loadAccounts();
        for (int position = 0; position < accounts.size(); position++) {
            CopybookRecordParser.AccountRecord account = accounts.get(position);
            BigDecimal identifier = accountIdentifier(account.accountId());
            requireFirstOccurrence(byIdentifier.put(identifier, account),
                    ACCTDATA_FIXTURE_FILE_NAME, ACCT_ID_FIELD, position + FIRST_ORDINAL);
        }
        return Collections.unmodifiableMap(byIdentifier);
    }

    /**
     * Reads a card number as the key {@code card_xref.card_number} compares.
     *
     * <p>{@code card_xref.card_number} is a {@code VARCHAR(16)} column compared as text, so only a
     * card number of exactly {@link PicClause#XREF_CARD_NUM_WIDTH} digits can match a row. A
     * shorter value is refused rather than brought up to width. Zeros in the leading positions
     * form the key of a different card.</p>
     *
     * <p>Surrounding space padding is removed, because the fixture delivers a fixed-width field and
     * {@code app/cpy/CVACT03Y.cpy:L5} declares {@code XREF-CARD-NUM PIC X(16)}.</p>
     *
     * @param cardNumber a card number, with or without surrounding space padding
     * @return the card number as the key, unchanged apart from that padding
     * @throws NullPointerException     when {@code cardNumber} is null
     * @throws IllegalArgumentException when {@code cardNumber} holds no digits, holds a character
     *         other than a digit, or does not hold exactly
     *         {@link PicClause#XREF_CARD_NUM_WIDTH} digits. The failure names the field and the two
     *         widths, and never the value
     */
    public static String cardNumberKey(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        String digits = requireDigits(cardNumber.trim(), XREF_CARD_NUM_FIELD);
        if (digits.length() != PicClause.XREF_CARD_NUM_WIDTH) {
            throw new IllegalArgumentException(XREF_CARD_NUM_FIELD + " holds " + digits.length()
                    + " digits and the key holds exactly " + PicClause.XREF_CARD_NUM_WIDTH);
        }
        return digits;
    }

    /**
     * Reads an {@code ACCT-ID} field as the value a {@code NUMERIC(11,0)} column compares.
     *
     * <p>The result carries {@link #ACCOUNT_IDENTIFIER_SCALE} decimal places, so a fixture value
     * and a column value of the same account match under {@link BigDecimal#equals}.</p>
     *
     * @param accountId an account identifier as text, with or without leading zeros
     * @return the account identifier as a value at scale zero
     * @throws NullPointerException     when {@code accountId} is null
     * @throws IllegalArgumentException when {@code accountId} holds no digits, holds a character
     *         other than a digit, or is longer than the field
     */
    public static BigDecimal accountIdentifier(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        String digits = requireDigits(accountId.trim(), ACCT_ID_FIELD);
        if (digits.length() > PicClause.ACCT_ID_WIDTH) {
            throw new IllegalArgumentException(ACCT_ID_FIELD + " holds " + digits.length()
                    + " digits and the field holds at most " + PicClause.ACCT_ID_WIDTH);
        }
        return new BigDecimal(digits).setScale(ACCOUNT_IDENTIFIER_SCALE);
    }

    // Fixture location. The fixtures stay where the repository holds them, so the suite reads the
    // one copy the COBOL programs were delivered with.

    /**
     * Resolves the directory holding the nine fixtures.
     *
     * <p>A value in {@link #FIXTURE_DIRECTORY_PROPERTY} is read first. Otherwise the search starts
     * at the working directory and walks up to the ancestor holding {@code app/data/ASCII}. A module
     * directory and the repository root both resolve.</p>
     *
     * <p>Whichever path is chosen, it is resolved to its real path and then checked: the directory
     * has to hold all nine fixture files as regular files. A system property therefore cannot point
     * this loader at an unrelated tree, and a symbolic link cannot point one check at one directory
     * and the read at another.</p>
     *
     * @return the real, absolute, normalised fixture directory
     * @throws IllegalStateException when any of these checks fails:
     *         <ul>
     *           <li>the configured directory exists</li>
     *           <li>it resolves to a real path</li>
     *           <li>it holds all nine fixtures</li>
     *           <li>an ancestor of the working directory holds the fixture path</li>
     *         </ul>
     */
    public static Path fixtureDirectory() {
        String configured = System.getProperty(FIXTURE_DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path directory = Path.of(configured.trim());
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("system property '" + FIXTURE_DIRECTORY_PROPERTY
                        + "' names '" + configured + "' and no directory sits there");
            }
            return requireFixtureDirectory(canonicalPath(directory));
        }
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path fixtures = fixturePathUnder(candidate);
            if (Files.isDirectory(fixtures)) {
                return requireFixtureDirectory(canonicalPath(fixtures));
            }
        }
        throw new IllegalStateException("no ancestor of '" + start + "' holds '"
                + fixturePathUnder(Path.of("")) + "'; set the system property '"
                + FIXTURE_DIRECTORY_PROPERTY + "' to the fixture directory");
    }

    /**
     * Resolves a directory to its real path, following every symbolic link once and for all.
     *
     * <p>The real path is what every later check reads, so a link cannot point one check at one
     * directory and the read at another.</p>
     *
     * @param directory the directory to resolve
     * @return the real, absolute, normalised path
     * @throws IllegalStateException when the path does not resolve
     */
    private static Path canonicalPath(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("fixture directory '" + directory
                    + "' does not resolve to a real path", failure);
        }
    }

    /**
     * Checks that a resolved directory is the CardDemo fixture directory and nothing else.
     *
     * <p>The directory must hold all nine fixture files this class reads. A directory chosen
     * through {@link #FIXTURE_DIRECTORY_PROPERTY} therefore cannot point this loader at an
     * unrelated tree, and a partial or substituted directory fails before any file is read.</p>
     *
     * @param directory the resolved candidate directory
     * @return the same directory
     * @throws IllegalStateException when a fixture file is missing from the directory
     */
    private static Path requireFixtureDirectory(Path directory) {
        for (String fileName : FIXTURE_FILE_NAMES) {
            if (!Files.isRegularFile(directory.resolve(fileName))) {
                throw new IllegalStateException("directory '" + directory + "' is not the CardDemo "
                        + "fixture directory: it does not hold the regular file '" + fileName
                        + "'. The nine fixtures this loader reads are " + FIXTURE_FILE_NAMES + ".");
            }
        }
        return directory;
    }

    private static Path fixturePathUnder(Path root) {
        return root.resolve(APP_DIRECTORY_NAME)
                .resolve(DATA_DIRECTORY_NAME)
                .resolve(ASCII_DIRECTORY_NAME);
    }

    // Reading. Every fixture arrives through this one path, so the width check, the count check
    // and the single-byte decode apply to all nine.

    /**
     * Reads one fixture and parses every record.
     *
     * @param <T>         record type the parser produces
     * @param fileName    fixture file name, reported when a check fails
     * @param recordWidth expected character width of each record
     * @param recordCount expected number of records
     * @param parser      applied to each record, in file order
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     */
    private static <T> List<T> load(String fileName, int recordWidth, int recordCount,
            RecordParser<T> parser) {
        List<String> records = readRecords(fileName, recordWidth, recordCount);
        List<T> parsed = new ArrayList<>(records.size());
        for (String record : records) {
            parsed.add(parser.parse(record));
        }
        return List.copyOf(parsed);
    }

    /**
     * Reads one fixture into its records and checks the shape of what arrived.
     *
     * <p>The file is decoded with a single-byte character set, so one byte becomes one character
     * and every field offset holds. Sign overpunch characters are single bytes and survive
     * unchanged.</p>
     *
     * @param fileName    fixture file name, reported when a check fails
     * @param recordWidth expected character width of each record
     * @param recordCount expected number of records
     * @return the records, immutable and in file order, with the separator removed
     * @throws IllegalStateException when the fixture is absent, or holds an unexpected record
     *         width or record count
     * @throws UncheckedIOException  when the fixture does not read
     */
    private static List<String> readRecords(String fileName, int recordWidth, int recordCount) {
        Path directory = fixtureDirectory();
        Path fixture = directory.resolve(fileName);
        if (!Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("fixture '" + fileName + "' is absent from '"
                    + directory + "', or is not a regular file. A symbolic link is refused: the "
                    + "loader reads the nine files of the fixture directory and nothing else.");
        }
        byte[] content;
        try (SeekableByteChannel channel = Files.newByteChannel(fixture, StandardOpenOption.READ)) {
            long declaredSize = channel.size();
            long permittedSize = permittedSize(recordWidth, recordCount);
            if (declaredSize > permittedSize) {
                throw new IllegalStateException("fixture '" + fileName + "' holds " + declaredSize
                        + " bytes and " + permittedSize + " is the most " + recordCount
                        + " records of " + recordWidth + " characters can occupy");
            }
            content = new byte[(int) declaredSize];
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                continue;
            }
            if (buffer.hasRemaining()) {
                throw new IllegalStateException("fixture '" + fileName + "' holds "
                        + (declaredSize - buffer.remaining()) + " readable bytes and " + declaredSize
                        + " were declared");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture '" + fixture + "' did not read", failure);
        }
        List<String> records = splitRecords(new String(content, StandardCharsets.ISO_8859_1));
        requireRecordWidth(fileName, records, recordWidth);
        requireRecordCount(fileName, records, recordCount);
        return List.copyOf(records);
    }

    /**
     * Returns the largest byte count a fixture of the stated shape can occupy.
     *
     * <p>Every record carries its declared width plus at most one separator byte, and the file may
     * close with one separator. The cap is therefore exact rather than generous: the largest
     * fixture, {@code dailytran.txt} at three hundred records of three hundred and fifty
     * characters, is bounded at {@code 300 * 351} bytes. Reading a file no larger than its declared
     * shape keeps a substituted or truncated file from consuming memory before the width and count
     * checks run.</p>
     *
     * @param recordWidth expected character width of each record
     * @param recordCount expected number of records
     * @return the largest permitted byte count
     */
    private static long permittedSize(int recordWidth, int recordCount) {
        return (long) recordCount * (recordWidth + RECORD_SEPARATOR_WIDTH);
    }

    /**
     * Splits fixture content on the record separator.
     *
     * <p>A separator closing the final record adds no empty record, and a final record without one
     * is kept whole.</p>
     *
     * @return the records, separator removed
     */
    private static List<String> splitRecords(String content) {
        List<String> records = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int separator = content.indexOf(RECORD_SEPARATOR, start);
            if (separator < 0) {
                records.add(content.substring(start));
                return records;
            }
            records.add(content.substring(start, separator));
            start = separator + RECORD_SEPARATOR_WIDTH;
        }
        return records;
    }

    /**
     * Checks that every record carries the width its layout reads.
     *
     * @param fileName    fixture file name, reported when a record is the wrong width
     * @param records     records read from the fixture
     * @param recordWidth expected character width of each record
     * @throws IllegalStateException when a record is not exactly {@code recordWidth} characters
     */
    private static void requireRecordWidth(String fileName, List<String> records, int recordWidth) {
        for (int position = 0; position < records.size(); position++) {
            int width = records.get(position).length();
            if (width != recordWidth) {
                throw new IllegalStateException("fixture '" + fileName + "' record "
                        + (position + FIRST_ORDINAL) + " holds " + width
                        + " characters and the layout reads " + recordWidth);
            }
        }
    }

    /**
     * Checks that the fixture holds the number of records the inventory names.
     *
     * @param fileName    fixture file name, reported when the count differs
     * @param records     records read from the fixture
     * @param recordCount number of records the inventory names
     * @throws IllegalStateException when the fixture holds a different number of records
     */
    private static void requireRecordCount(String fileName, List<String> records, int recordCount) {
        if (records.size() != recordCount) {
            throw new IllegalStateException("fixture '" + fileName + "' holds " + records.size()
                    + " records and the inventory names " + recordCount);
        }
    }

    /**
     * Checks that a key had not already been indexed.
     *
     * <p>The failure names the fixture, the key field and the one-based ordinal of the record that
     * carried the repeat. It never carries the key itself, because a key is a card number or an
     * account identifier.</p>
     *
     * @param displaced the value a map returned when the key was added
     * @param fileName  fixture file name, reported when the key repeats
     * @param field     COBOL field name of the key, reported when the key repeats
     * @param ordinal   one-based position of the record that carried the repeat
     * @throws IllegalStateException when {@code displaced} shows the key was already present
     */
    private static void requireFirstOccurrence(Object displaced, String fileName, String field,
            int ordinal) {
        if (displaced != null) {
            throw new IllegalStateException("fixture '" + fileName + "' repeats its " + field
                    + " at record " + ordinal + " and the key identifies one record");
        }
    }

    /**
     * Checks that a key field holds at least one character and only digits.
     *
     * @param value characters to check
     * @param field COBOL field name, reported when the check fails
     * @throws IllegalArgumentException when {@code value} is empty or holds a character other than
     *         a digit. The message names the field, the offending position, and the field width,
     *         and never the value
     */
    private static String requireDigits(String value, String field) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " holds no digits");
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < DIGIT_LOWER_BOUND || character > DIGIT_UPPER_BOUND) {
                throw new IllegalArgumentException(field + " holds a character other than a "
                        + "digit at position " + (position + FIRST_ORDINAL) + " of "
                        + value.length());
            }
        }
        return value;
    }
}
