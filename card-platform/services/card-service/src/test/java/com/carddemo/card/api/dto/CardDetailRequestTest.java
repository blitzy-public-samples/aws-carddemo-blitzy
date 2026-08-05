package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.api.CardController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link CardDetailRequest}.
 *
 * <p>Two things are asserted. The constraints admit exactly what
 * {@code app/cbl/COCRDSLC.cbl} admits, and the texts they declare are the texts
 * {@code api/CardController} answers with.
 *
 * <p>The second half is what keeps the record and the controller from drifting. The controller applies
 * the four edits in source order because a validator reports an unordered set and one rule spans both
 * fields, so the two hold the same texts in two places and a test has to say so.
 */
@DisplayName("the card detail request contract")
class CardDetailRequestTest {

    /** An account identifier of the width the column holds. */
    private static final String ACCOUNT_ID = "00000000050";

    /** A card number of the width the column holds. */
    private static final String CARD_NUMBER = "4111111111111150";

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    /** Opens one validator for the class. */
    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes it. */
    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /** What the constraints admit. */
    @Nested
    @DisplayName("what the constraints admit")
    class WhatTheConstraintsAdmit {

        /** Asserts a well-formed pair passes every constraint. */
        @Test
        void aWellFormedPairPasses() {
            assertEquals(Set.of(), messagesOf(new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER)),
                    "eleven digits and sixteen digits pass");
        }

        /**
         * Asserts an account shorter than eleven digits is refused.
         *
         * <p>{@code IS NOT NUMERIC} at {@code app/cbl/COCRDSLC.cbl:L665} reads an eleven-character
         * alphanumeric field, so a shorter value arrives space-padded and fails. Column
         * {@code account_id} is fixed-width too, so a shorter value would match no row either.
         */
        @Test
        void anAccountShorterThanElevenDigitsIsRefused() {
            assertTrue(messagesOf(new CardDetailRequest("50", CARD_NUMBER))
                            .contains(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC),
                    "the eleven-digit wording answers");
        }

        /** Asserts a card number shorter than sixteen digits is refused. */
        @Test
        void aCardShorterThanSixteenDigitsIsRefused() {
            assertTrue(messagesOf(new CardDetailRequest(ACCOUNT_ID, "4111"))
                            .contains(CardValidationMessages.CARD_FILTER_NOT_NUMERIC),
                    "the sixteen-digit wording answers");
        }

        /** Asserts a non-digit character is refused in either component. */
        @Test
        void aNonDigitIsRefusedInEitherComponent() {
            assertTrue(messagesOf(new CardDetailRequest("0000000005X", CARD_NUMBER))
                            .contains(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC),
                    "the account is a numeric display field");
            assertTrue(messagesOf(new CardDetailRequest(ACCOUNT_ID, "411111111111115X"))
                            .contains(CardValidationMessages.CARD_FILTER_NOT_NUMERIC),
                    "and so is the card number");
        }

        /** Asserts an absent value takes the prompt text of its own field. */
        @Test
        void anAbsentValueTakesItsOwnPrompt() {
            assertTrue(messagesOf(new CardDetailRequest(null, CARD_NUMBER))
                            .contains(CardValidationMessages.PROMPT_FOR_ACCT),
                    "the account prompt");
            assertTrue(messagesOf(new CardDetailRequest(ACCOUNT_ID, null))
                            .contains(CardValidationMessages.PROMPT_FOR_CARD),
                    "the card prompt");
        }

        /**
         * Asserts an all-zero value passes the constraints, because absence is not a field rule here.
         *
         * <p>The third condition of each absence test in {@code app/cbl/COCRDSLC.cbl} reads the
         * numeric redefine against zero, which no width or character-class constraint can express.
         * {@code api/CardController} carries that rule, and this assertion records that the record
         * deliberately does not.
         */
        @Test
        void anAllZeroValuePassesTheFieldConstraints() {
            assertEquals(Set.of(),
                    messagesOf(new CardDetailRequest("0".repeat(11), "0".repeat(16))),
                    "the record admits it and the controller refuses it");
            assertEquals(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                    CardController.firstSearchFailure(
                            new CardDetailRequest("0".repeat(11), "0".repeat(16))),
                    "the controller applies the rule the constraints cannot express");
        }

        /** Asserts no checksum rule is added. */
        @Test
        void noChecksumRuleIsAdded() {
            assertEquals(Set.of(), messagesOf(new CardDetailRequest(ACCOUNT_ID, "1".repeat(16))),
                    "app/cbl/COCRDSLC.cbl:L706 tests sixteen digits and nothing else, so a number "
                            + "no checksum would accept is accepted here");
        }
    }

    /** Agreement between the record and the controller. */
    @Nested
    @DisplayName("agreement with the controller")
    class AgreementWithTheController {

        /**
         * Asserts every text the controller can answer with is a text this record declares, or the one
         * cross-field text no field declares.
         */
        @Test
        void everyControllerTextIsDeclaredSomewhere() {
            Set<String> declared = new TreeSet<>(declaredMessages());
            declared.add(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED);

            for (CardDetailRequest refused : List.of(
                    new CardDetailRequest("", ""),
                    new CardDetailRequest(null, CARD_NUMBER),
                    new CardDetailRequest("50", CARD_NUMBER),
                    new CardDetailRequest(ACCOUNT_ID, ""),
                    new CardDetailRequest(ACCOUNT_ID, "411111111111115X"))) {
                String answered = CardController.firstSearchFailure(refused);
                assertTrue(declared.contains(answered),
                        "the controller answered a text no constraint declares: " + answered);
            }
        }

        /** Asserts the record declares exactly the four texts the controller can answer. */
        @Test
        void theRecordDeclaresTheFourFieldTexts() {
            assertEquals(new TreeSet<>(List.of(CardValidationMessages.PROMPT_FOR_ACCT,
                            CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC,
                            CardValidationMessages.PROMPT_FOR_CARD,
                            CardValidationMessages.CARD_FILTER_NOT_NUMERIC)),
                    new TreeSet<>(declaredMessages()),
                    "two fields, each with an absence text and a character-class text");
        }

        /** Asserts the two components appear in the order the source edits them. */
        @Test
        void theComponentsAppearInSourceEditOrder() {
            List<String> declared = java.util.Arrays
                    .stream(CardDetailRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertEquals(List.of("accountId", "cardNumber"), declared,
                    "2210-EDIT-ACCOUNT runs before 2220-EDIT-CARD at "
                            + "app/cbl/COCRDSLC.cbl:L630-L634");
        }
    }

    /** What the rendering discloses. */
    @Nested
    @DisplayName("the rendering")
    class TheRendering {

        /** Asserts neither value reaches the rendering. */
        @Test
        void neitherValueReachesTheRendering() {
            String rendered = new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER).toString();

            assertFalse(rendered.contains(CARD_NUMBER), "no card number is printed");
            assertFalse(rendered.contains(ACCOUNT_ID), "and no account identifier either");
            assertTrue(rendered.contains("CardDetailRequest"), "the class is named");
        }

        /** Asserts the rendering separates an absent component from a present one. */
        @Test
        void theRenderingSeparatesAbsentFromPresent() {
            assertTrue(new CardDetailRequest(null, CARD_NUMBER).toString()
                            .contains("accountId=absent"),
                    "an absent component is reported as absent");
            assertFalse(new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER).toString()
                            .contains("absent"),
                    "and a complete request reports none");
        }
    }

    /**
     * Reads every message the record's own constraints declare.
     *
     * @return the declared texts
     */
    private static Set<String> declaredMessages() {
        Set<String> texts = new TreeSet<>();
        for (CardDetailRequest refused : List.of(new CardDetailRequest(null, null),
                new CardDetailRequest("x", "y"))) {
            texts.addAll(messagesOf(refused));
        }
        return texts;
    }

    /**
     * Validates one request and returns the texts it failed with.
     *
     * @param request the request to validate
     * @return the reported texts
     */
    private static Set<String> messagesOf(CardDetailRequest request) {
        Set<String> texts = new TreeSet<>();
        for (ConstraintViolation<CardDetailRequest> violation : validator.validate(request)) {
            texts.add(violation.getMessage());
        }
        return texts;
    }
}
