package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CardDemoFixtureLoader}, covering its two key forms, the width tolerance of one
 * fixture, and the failure text of every check.
 *
 * <p>The key-form tests supply values this class chose, so they compare the loader against an
 * independently stated contract rather than against a fixture. The load tests read the nine fixtures
 * under {@code app/data/ASCII}, because the record count and the record width of each are the
 * contract the loader exists to enforce.</p>
 *
 * <p>{@link CardDemoFixtureLoader#cardNumberKey} refuses a card number shorter than
 * {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}. Two tests below hold that
 * refusal. Zeros in the leading positions form the key of a different card.</p>
 *
 * <p>No assertion message and no failure this class provokes carries a card number, an account
 * identifier or any other fixture value. Three tests hold that property against the loader's own
 * failure text.</p>
 */
class CardDemoFixtureLoaderTest {

    /** {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_KEY_WIDTH = 16;

    /** {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width {@code app/data/ASCII/cardxref.txt} delivers. */
    private static final int CROSS_REFERENCE_DELIVERED_WIDTH = 36;

    /** Width {@code app/jcl/XREFFILE.jcl:L44} declares as {@code RECORDSIZE(50 50)}. */
    private static final int CROSS_REFERENCE_DECLARED_WIDTH = 50;

    /** Records {@code app/data/ASCII/cardxref.txt} holds. */
    private static final int CROSS_REFERENCE_RECORD_COUNT = 50;

    /** A card number of exactly the key width, chosen here and not read from a fixture. */
    private static final String KEY_WIDTH_CARD_NUMBER = "4111222233334444";

    /** A card number one digit short of the key width. */
    private static final String SHORT_CARD_NUMBER = "411122223333444";

    /** A card number one digit past the key width. */
    private static final String LONG_CARD_NUMBER = "41112222333344445";

    // cardNumberKey. The key is text and only a full-width card number can match a row.

    @Test
    void aCardNumberAtTheKeyWidthPassesThroughUnchanged() {
        assertEquals(KEY_WIDTH_CARD_NUMBER,
                CardDemoFixtureLoader.cardNumberKey(KEY_WIDTH_CARD_NUMBER),
                "a card number of width " + CARD_NUMBER_KEY_WIDTH + " is already the key");
        assertEquals(KEY_WIDTH_CARD_NUMBER,
                CardDemoFixtureLoader.cardNumberKey("  " + KEY_WIDTH_CARD_NUMBER + "   "),
                "surrounding space padding is removed and nothing else changes");
        assertEquals(CARD_NUMBER_KEY_WIDTH,
                CardDemoFixtureLoader.cardNumberKey(KEY_WIDTH_CARD_NUMBER).length(),
                "the key holds exactly " + CARD_NUMBER_KEY_WIDTH + " characters");
    }

    @Test
    void aShortCardNumberIsRefusedAndNoKeyIsSynthesised() {
        assertEquals(CARD_NUMBER_KEY_WIDTH - 1, SHORT_CARD_NUMBER.length(),
                "the case below supplies a card number one digit short of the key");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.cardNumberKey(SHORT_CARD_NUMBER),
                "card_xref.card_number is compared as text, so a short card number is no key");
        assertEquals("XREF-CARD-NUM holds " + (CARD_NUMBER_KEY_WIDTH - 1)
                        + " digits and the key holds exactly " + CARD_NUMBER_KEY_WIDTH,
                refusal.getMessage(),
                "the refusal names the field and the two widths, and carries no value");

        String zeroPadded = "0".repeat(CARD_NUMBER_KEY_WIDTH - SHORT_CARD_NUMBER.length())
                + SHORT_CARD_NUMBER;
        assertEquals(CARD_NUMBER_KEY_WIDTH, zeroPadded.length(),
                "the key a zero-padding loader would have built holds the full width");
        assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.cardNumberKey(SHORT_CARD_NUMBER),
                "the loader builds no key for a short card number, zero-padded or otherwise");
    }

    @Test
    void everyWidthBelowTheKeyWidthIsRefused() {
        for (int width = 1; width < CARD_NUMBER_KEY_WIDTH; width++) {
            String candidate = "4".repeat(width);
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> CardDemoFixtureLoader.cardNumberKey(candidate),
                    "a card number of width " + width + " is no key");
            assertTrue(refusal.getMessage().startsWith("XREF-CARD-NUM holds " + width + " "),
                    "the refusal for width " + width + " names that width");
        }
    }

    @Test
    void aCardNumberPastTheKeyWidthIsRefused() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.cardNumberKey(LONG_CARD_NUMBER),
                "a card number of width " + LONG_CARD_NUMBER.length() + " is no key");
        assertEquals("XREF-CARD-NUM holds " + (CARD_NUMBER_KEY_WIDTH + 1)
                        + " digits and the key holds exactly " + CARD_NUMBER_KEY_WIDTH,
                refusal.getMessage(),
                "the refusal names the field and the two widths, and carries no value");
    }

    @Test
    void aCardNumberThatIsNotSixteenDigitsIsRefusedWithoutEchoingItsValue() {
        assertEquals("XREF-CARD-NUM holds no digits",
                assertThrows(IllegalArgumentException.class,
                        () -> CardDemoFixtureLoader.cardNumberKey(""),
                        "an empty card number is no key").getMessage(),
                "the refusal names the field alone");
        assertEquals("XREF-CARD-NUM holds no digits",
                assertThrows(IllegalArgumentException.class,
                        () -> CardDemoFixtureLoader.cardNumberKey("     "),
                        "a card number of only spaces is no key").getMessage(),
                "a value of only spaces trims to nothing and reports the same refusal");

        String withLetter = "411122223333444X";
        IllegalArgumentException nonDigit = assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.cardNumberKey(withLetter),
                "a card number holding a letter is no key");
        assertEquals("XREF-CARD-NUM holds a character other than a digit at position 16 of 16",
                nonDigit.getMessage(),
                "the refusal names the field, the one-based position and the width");
        assertFalse(nonDigit.getMessage().contains(withLetter),
                "the refusal carries no card number");

        assertThrows(NullPointerException.class,
                () -> CardDemoFixtureLoader.cardNumberKey(null),
                "a null card number is refused before any check runs");
    }

    // accountIdentifier. The account key is numeric, matching a NUMERIC(11,0) column.

    @Test
    void anAccountIdentifierReadsAsAValueAtScaleZero() {
        BigDecimal padded = CardDemoFixtureLoader.accountIdentifier("00000000077");
        BigDecimal bare = CardDemoFixtureLoader.accountIdentifier("77");

        assertEquals(0, padded.scale(),
                "the identifier carries scale 0, the scale a NUMERIC(11,0) column compares");
        assertEquals(new BigDecimal("77"), padded,
                "leading zeros make no difference to the value of ACCT-ID");
        assertEquals(padded, bare,
                "the same number with and without leading zeros reads as one value");
        assertEquals(new BigDecimal("77"),
                CardDemoFixtureLoader.accountIdentifier("  00000000077  "),
                "surrounding space padding is removed before the value is read");
    }

    @Test
    void anAccountIdentifierPastTheFieldWidthIsRefusedWithoutEchoingItsValue() {
        String atWidth = "9".repeat(ACCOUNT_ID_WIDTH);
        String pastWidth = "9".repeat(ACCOUNT_ID_WIDTH + 1);

        assertEquals(new BigDecimal(atWidth), CardDemoFixtureLoader.accountIdentifier(atWidth),
                "an identifier of width " + ACCOUNT_ID_WIDTH + " is accepted");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.accountIdentifier(pastWidth),
                "an identifier of width " + (ACCOUNT_ID_WIDTH + 1) + " runs past ACCT-ID");
        assertEquals("ACCT-ID holds " + (ACCOUNT_ID_WIDTH + 1)
                        + " digits and the field holds at most " + ACCOUNT_ID_WIDTH,
                refusal.getMessage(),
                "the refusal names the field and the two widths, and carries no value");
        assertFalse(refusal.getMessage().contains(pastWidth),
                "the refusal carries no account identifier");

        IllegalArgumentException nonDigit = assertThrows(IllegalArgumentException.class,
                () -> CardDemoFixtureLoader.accountIdentifier("0000000X077"),
                "an identifier holding a letter is refused");
        assertEquals("ACCT-ID holds a character other than a digit at position 8 of 11",
                nonDigit.getMessage(),
                "the refusal names the field, the one-based position and the width");

        assertThrows(NullPointerException.class,
                () -> CardDemoFixtureLoader.accountIdentifier(null),
                "a null identifier is refused before any check runs");
    }

    // Loads. Every fixture is read once and checked against its inventory.

    /**
     * Asserts the record count of each of the nine fixtures under {@code app/data/ASCII}. The
     * counts are typed here from the inventory in the plan, so a fixture gaining or losing a record
     * fails.
     */
    @Test
    void eachFixtureHoldsTheNumberOfRecordsTheInventoryNames() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("acctdata.txt", 50);
        expected.put("carddata.txt", 50);
        expected.put("cardxref.txt", 50);
        expected.put("custdata.txt", 50);
        expected.put("dailytran.txt", 300);
        expected.put("discgrp.txt", 51);
        expected.put("tcatbal.txt", 50);
        expected.put("trancatg.txt", 18);
        expected.put("trantype.txt", 7);

        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("acctdata.txt", CardDemoFixtureLoader.loadAccounts().size());
        actual.put("carddata.txt", CardDemoFixtureLoader.loadCards().size());
        actual.put("cardxref.txt", CardDemoFixtureLoader.loadCardCrossReferences().size());
        actual.put("custdata.txt", CardDemoFixtureLoader.loadCustomers().size());
        actual.put("dailytran.txt", CardDemoFixtureLoader.loadDailyTransactions().size());
        actual.put("discgrp.txt", CardDemoFixtureLoader.loadDisclosureGroups().size());
        actual.put("tcatbal.txt", CardDemoFixtureLoader.loadTransactionCategoryBalances().size());
        actual.put("trancatg.txt", CardDemoFixtureLoader.loadTransactionCategories().size());
        actual.put("trantype.txt", CardDemoFixtureLoader.loadTransactionTypes().size());

        assertEquals(expected.size(), actual.size(),
                "the nine fixtures under app/data/ASCII are each read once");
        assertEquals(expected, actual,
                "each fixture holds the number of records the inventory names");
    }

    /**
     * Asserts the width tolerance of {@code app/data/ASCII/cardxref.txt}, the one fixture whose
     * delivered width differs from the width its dataset definition declares.
     */
    @Test
    void theCrossReferenceFixtureIsReadAtBothOfItsWidths() {
        List<String> atDeclaredWidth = CardDemoFixtureLoader.cardCrossReferenceRecordsAtDeclaredWidth();

        assertEquals(CROSS_REFERENCE_RECORD_COUNT, atDeclaredWidth.size(),
                "the fixture holds " + CROSS_REFERENCE_RECORD_COUNT + " records at either width");
        for (int position = 0; position < atDeclaredWidth.size(); position++) {
            assertEquals(CROSS_REFERENCE_DECLARED_WIDTH, atDeclaredWidth.get(position).length(),
                    "record " + (position + 1) + " reaches the "
                            + CROSS_REFERENCE_DECLARED_WIDTH
                            + " bytes app/jcl/XREFFILE.jcl:L44 declares");
        }
        assertEquals(CROSS_REFERENCE_DECLARED_WIDTH - CROSS_REFERENCE_DELIVERED_WIDTH,
                atDeclaredWidth.get(0).length() - CROSS_REFERENCE_DELIVERED_WIDTH,
                "the app/cpy/CVACT03Y.cpy:L8 filler accounts for every byte the fixture omits");
        assertEquals(CROSS_REFERENCE_RECORD_COUNT,
                CardDemoFixtureLoader.loadCardCrossReferences().size(),
                "the same fixture parses at the width it delivers");
    }

    @Test
    void everyReturnedCollectionRefusesEveryChange() {
        List<CopybookRecordParser.CardRecord> cards = CardDemoFixtureLoader.loadCards();
        assertThrows(UnsupportedOperationException.class, () -> cards.remove(0),
                "the card list refuses a removal");
        assertThrows(UnsupportedOperationException.class, cards::clear,
                "the card list refuses a clear");

        Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        assertThrows(UnsupportedOperationException.class, byCardNumber::clear,
                "the cross-reference index refuses a clear");

        Map<String, CopybookRecordParser.AccountRecord> byAccountId =
                CardDemoFixtureLoader.accountsByAccountId();
        assertThrows(UnsupportedOperationException.class, byAccountId::clear,
                "the account index refuses a clear");

        Map<BigDecimal, CopybookRecordParser.AccountRecord> byIdentifier =
                CardDemoFixtureLoader.accountsByAccountIdentifier();
        assertThrows(UnsupportedOperationException.class, byIdentifier::clear,
                "the numeric account index refuses a clear");
    }

    @Test
    void eachIndexHoldsOneEntryPerRecordAndEachKeyReachesItsOwnRecord() {
        List<CopybookRecordParser.CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.loadCardCrossReferences();
        Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();

        assertEquals(crossReferences.size(), byCardNumber.size(),
                "the cross-reference index holds one entry per record, so every key is distinct");
        for (CopybookRecordParser.CardCrossReferenceRecord crossReference : crossReferences) {
            assertEquals(crossReference, byCardNumber.get(crossReference.cardNumber()),
                    "each cross-reference key reaches the record it came from");
        }

        List<CopybookRecordParser.AccountRecord> accounts = CardDemoFixtureLoader.loadAccounts();
        Map<String, CopybookRecordParser.AccountRecord> byAccountId =
                CardDemoFixtureLoader.accountsByAccountId();
        Map<BigDecimal, CopybookRecordParser.AccountRecord> byIdentifier =
                CardDemoFixtureLoader.accountsByAccountIdentifier();

        assertEquals(accounts.size(), byAccountId.size(),
                "the text account index holds one entry per record");
        assertEquals(accounts.size(), byIdentifier.size(),
                "the numeric account index holds one entry per record");
        for (CopybookRecordParser.AccountRecord account : accounts) {
            assertEquals(account, byAccountId.get(account.accountId()),
                    "each text account key reaches the record it came from");
            assertEquals(account,
                    byIdentifier.get(CardDemoFixtureLoader.accountIdentifier(account.accountId())),
                    "each numeric account key reaches the record it came from");
        }
    }

    @Test
    void everyCrossReferenceKeyIsAlreadyAtTheKeyWidth() {
        Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();

        assertFalse(byCardNumber.isEmpty(), "the cross-reference index holds at least one entry");
        for (String key : byCardNumber.keySet()) {
            assertEquals(CARD_NUMBER_KEY_WIDTH, key.length(),
                    "every key of the cross-reference index holds " + CARD_NUMBER_KEY_WIDTH
                            + " characters");
            assertEquals(key, CardDemoFixtureLoader.cardNumberKey(key),
                    "cardNumberKey accepts every key the fixture delivers and changes none");
        }
    }

    // Failure text. No message may carry a fixture value.

    @Test
    void noKeyFormFailureCarriesAFixtureValue() {
        List<String> values = new ArrayList<>();
        for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                : CardDemoFixtureLoader.loadCardCrossReferences()) {
            values.add(crossReference.cardNumber());
        }
        for (CopybookRecordParser.AccountRecord account : CardDemoFixtureLoader.loadAccounts()) {
            values.add(account.accountId());
        }
        assertFalse(values.isEmpty(), "the fixtures supply at least one value to check");

        for (String value : values) {
            String tooLong = value + "0";
            String message = assertThrows(IllegalArgumentException.class,
                    () -> CardDemoFixtureLoader.cardNumberKey(tooLong),
                    "a value one digit past the key width is refused").getMessage();
            assertFalse(message.contains(value),
                    "a cardNumberKey refusal carries a fixture value of width " + value.length());

            String withLetter = value.substring(0, value.length() - 1) + "X";
            String nonDigit = assertThrows(IllegalArgumentException.class,
                    () -> CardDemoFixtureLoader.cardNumberKey(withLetter),
                    "a value holding a letter is refused").getMessage();
            assertFalse(nonDigit.contains(withLetter),
                    "a cardNumberKey refusal carries the value supplied");
        }
    }

    @Test
    void noRenderedFixtureRecordCarriesARedactedValue() {
        for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
            String rendered = card.toString();
            assertFalse(rendered.contains(card.cardNumber()),
                    "a rendered card record carries its full card number");
            assertFalse(rendered.contains(card.cardVerificationValue()),
                    "a rendered card record carries its verification value");
        }
        for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                : CardDemoFixtureLoader.loadCardCrossReferences()) {
            assertFalse(crossReference.toString().contains(crossReference.cardNumber()),
                    "a rendered cross-reference carries its full card number");
        }
        for (CopybookRecordParser.CustomerRecord customer : CardDemoFixtureLoader.loadCustomers()) {
            String rendered = customer.toString();
            assertFalse(rendered.contains(customer.socialSecurityNumber()),
                    "a rendered customer record carries its social security number");
            assertFalse(rendered.contains(customer.governmentIssuedId()),
                    "a rendered customer record carries its government-issued identifier");
            assertFalse(rendered.contains(customer.eftAccountId()),
                    "a rendered customer record carries its electronic funds transfer identifier");
        }
        for (CopybookRecordParser.DailyTransactionRecord transaction
                : CardDemoFixtureLoader.loadDailyTransactions()) {
            assertFalse(transaction.toString().contains(transaction.cardNumber()),
                    "a rendered daily transaction carries its full card number");
        }
    }

    @Test
    void theFixtureDirectoryResolvesToAnExistingAbsolutePath() {
        java.nio.file.Path directory = CardDemoFixtureLoader.fixtureDirectory();

        assertNotNull(directory, "the fixture directory resolves");
        assertTrue(directory.isAbsolute(), "the fixture directory is absolute");
        assertTrue(java.nio.file.Files.isDirectory(directory),
                "the resolved fixture path is a directory");
        assertTrue(directory.endsWith(java.nio.file.Path.of("app", "data", "ASCII")),
                "the resolved fixture path ends in app/data/ASCII");
    }
}
