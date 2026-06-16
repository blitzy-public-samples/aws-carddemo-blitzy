package com.carddemo.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link MessageService}.
 *
 * <p><strong>Purpose &mdash; parity pinning.</strong> {@code MessageService} is the Java
 * replacement for the legacy COBOL message copybooks {@code app/cpy/CSMSG01Y.cpy} (common
 * error/info messages) and {@code app/cpy/CSMSG02Y.cpy} (the {@code CABENDD} ABEND-DATA block,
 * which has no REST analog), plus the many inline {@code MOVE '...' TO WS-MESSAGE} literals
 * scattered across the online programs ({@code COSGN00C}, {@code COBIL00C}, {@code COACTUPC},
 * {@code COTRN01C}/{@code COTRN02C}, {@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C}). The
 * entire value of this test is to <em>catch any drift</em> in user-facing message text versus
 * the legacy 3270 application, thereby preserving the AAP's mandated
 * <strong>100% functional parity</strong> (AAP &sect;0.3.2, &sect;0.4.1.3, &sect;0.7.1).</p>
 *
 * <p><strong>Intentional legacy quirks &mdash; do NOT "clean up".</strong> Two strings carry
 * deliberate spacing anomalies inherited verbatim from the COBOL source; they are asserted
 * explicitly here so a well-meaning refactor cannot silently normalize them:</p>
 * <ul>
 *   <li>{@link MessageService#CHANGES_VALIDATED} &mdash; has <em>no space</em> after the period
 *       ({@code "Changes validated.Press F5 to save"}).</li>
 *   <li>{@link MessageService#TRANSACTION_ADDED_SUFFIX} &mdash; has a <em>trailing space</em>
 *       ({@code "Transaction added successfully. "}).</li>
 * </ul>
 *
 * <p><strong>Test character.</strong> This is a pure POJO/static unit test: no
 * {@code @SpringBootTest}, no {@code @ExtendWith}, no mocks, no Spring context, and no database.
 * {@code MessageService} has no collaborators &mdash; it exposes {@code public static final String}
 * constants and three string-formatting helper methods ({@code userAdded}, {@code userUpdated},
 * {@code userDeleted}). Because this test lives in the same package
 * ({@code com.carddemo.service}) as the class under test, no import of {@code MessageService} is
 * required.</p>
 *
 * <p>No personally identifiable information (PII) appears in any test data &mdash; no CVV, SSN, or
 * password values are used (AAP &sect;0.6.8, &sect;0.7.1).</p>
 */
class MessageServiceTest {

    // =================================================================================
    // Constant value parity assertions
    // ---------------------------------------------------------------------------------
    // Each assertion pins the EXACT, byte-for-byte text the legacy COBOL produced so the
    // migrated REST API remains message-compatible with the retired 3270 screens.
    // =================================================================================

    @Test
    @DisplayName("Signon / authentication prompts match the legacy COSGN00C literals")
    void signonMessagesMatchLegacyText() {
        // Source-of-truth: app/cbl/COSGN00C.cbl signon prompts and authentication failures.
        assertThat(MessageService.ENTER_USER_ID).isEqualTo("Please enter User ID ...");
        assertThat(MessageService.ENTER_PASSWORD).isEqualTo("Please enter Password ...");
        assertThat(MessageService.WRONG_PASSWORD).isEqualTo("Wrong Password. Try again ...");
        assertThat(MessageService.USER_NOT_FOUND).isEqualTo("User not found. Try again ...");
        assertThat(MessageService.UNABLE_TO_VERIFY_USER).isEqualTo("Unable to verify the User ...");
    }

    @Test
    @DisplayName("Confirmation / Yes-No validation messages match the legacy literals")
    void confirmationAndYesNoMessagesMatchLegacyText() {
        // Source-of-truth: app/cbl/COBIL00C.cbl, app/cbl/COTRN02C.cbl.
        assertThat(MessageService.INVALID_YN_VALUE).isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(MessageService.NOTHING_TO_PAY).isEqualTo("You have nothing to pay...");
    }

    @Test
    @DisplayName("User CRUD field-required validation messages match the legacy literals")
    void userCrudValidationMessagesMatchLegacyText() {
        // Source-of-truth: app/cbl/COUSR01C.cbl, COUSR02C.cbl, COUSR03C.cbl.
        assertThat(MessageService.FIRST_NAME_REQUIRED).isEqualTo("First Name can NOT be empty...");
        assertThat(MessageService.LAST_NAME_REQUIRED).isEqualTo("Last Name can NOT be empty...");
        assertThat(MessageService.USER_ID_REQUIRED).isEqualTo("User ID can NOT be empty...");
        assertThat(MessageService.PASSWORD_REQUIRED).isEqualTo("Password can NOT be empty...");
        assertThat(MessageService.USER_TYPE_REQUIRED).isEqualTo("User Type can NOT be empty...");
        assertThat(MessageService.USER_ALREADY_EXISTS).isEqualTo("User ID already exist...");
    }

    @Test
    @DisplayName("User CRUD confirmation suffixes match the legacy STRING concatenation tails")
    void userCrudConfirmationSuffixesMatchLegacyText() {
        // Each suffix has a LEADING space so that "<userId>" + suffix reads naturally, e.g.
        // "USER0001 has been added ...". Source: COUSR01C/COUSR02C/COUSR03C STRING statements.
        assertThat(MessageService.USER_ADDED_SUFFIX).isEqualTo(" has been added ...");
        assertThat(MessageService.USER_UPDATED_SUFFIX).isEqualTo(" has been updated ...");
        assertThat(MessageService.USER_DELETED_SUFFIX).isEqualTo(" has been deleted ...");
        // The leading space is structural to the concatenation contract; pin it explicitly.
        assertThat(MessageService.USER_ADDED_SUFFIX).startsWith(" ");
        assertThat(MessageService.USER_UPDATED_SUFFIX).startsWith(" ");
        assertThat(MessageService.USER_DELETED_SUFFIX).startsWith(" ");
    }

    @Test
    @DisplayName("Account update / optimistic-lock messages match the legacy COACTUPC literals")
    void accountUpdateAndOptimisticLockMessagesMatchLegacyText() {
        // Source-of-truth: app/cbl/COACTUPC.cbl. RECORD_CHANGED_BY_OTHER is surfaced as HTTP 409
        // (AAP §0.6.6); note the legacy two-word "some one" spelling is preserved verbatim.
        assertThat(MessageService.RECORD_CHANGED_BY_OTHER)
                .isEqualTo("Record changed by some one else. Please review");
        assertThat(MessageService.CHANGES_COMMITTED).isEqualTo("Changes committed to database");
        assertThat(MessageService.CHANGES_UNSUCCESSFUL).isEqualTo("Changes unsuccessful. Please try again");
        assertThat(MessageService.COULD_NOT_LOCK_ACCOUNT).isEqualTo("Could not lock account record for update");
        assertThat(MessageService.COULD_NOT_LOCK_CUSTOMER).isEqualTo("Could not lock customer record for update");
    }

    @Test
    @DisplayName("QUIRK: CHANGES_VALIDATED has NO space after the period (legacy COACTUPC verbatim)")
    void changesValidatedPreservesMissingSpaceQuirk() {
        // The legacy literal is "Changes validated.Press F5 to save" — the period is immediately
        // followed by "Press" with NO intervening space. This anomaly is deliberate parity.
        assertThat(MessageService.CHANGES_VALIDATED).isEqualTo("Changes validated.Press F5 to save");
        // Guard against a "helpful" normalization that inserts the missing space.
        assertThat(MessageService.CHANGES_VALIDATED).doesNotContain(". Press");
        // Positively assert the quirky no-space form is present.
        assertThat(MessageService.CHANGES_VALIDATED).contains(".Press");
    }

    @Test
    @DisplayName("Transaction add field-required validation messages match the legacy literals")
    void transactionFieldValidationMessagesMatchLegacyText() {
        // Source-of-truth: app/cbl/COTRN02C.cbl (add) and app/cbl/COTRN01C.cbl (view/select).
        assertThat(MessageService.TYPE_CD_REQUIRED).isEqualTo("Type CD can NOT be empty...");
        assertThat(MessageService.CATEGORY_CD_REQUIRED).isEqualTo("Category CD can NOT be empty...");
        assertThat(MessageService.SOURCE_REQUIRED).isEqualTo("Source can NOT be empty...");
        assertThat(MessageService.DESCRIPTION_REQUIRED).isEqualTo("Description can NOT be empty...");
        assertThat(MessageService.AMOUNT_REQUIRED).isEqualTo("Amount can NOT be empty...");
        assertThat(MessageService.ORIG_DATE_REQUIRED).isEqualTo("Orig Date can NOT be empty...");
        assertThat(MessageService.PROC_DATE_REQUIRED).isEqualTo("Proc Date can NOT be empty...");
        assertThat(MessageService.MERCHANT_ID_REQUIRED).isEqualTo("Merchant ID can NOT be empty...");
        assertThat(MessageService.MERCHANT_NAME_REQUIRED).isEqualTo("Merchant Name can NOT be empty...");
        assertThat(MessageService.MERCHANT_CITY_REQUIRED).isEqualTo("Merchant City can NOT be empty...");
        assertThat(MessageService.MERCHANT_ZIP_REQUIRED).isEqualTo("Merchant Zip can NOT be empty...");
        assertThat(MessageService.TRAN_ID_REQUIRED).isEqualTo("Tran ID can NOT be empty...");
    }

    @Test
    @DisplayName("QUIRK: TRANSACTION_ADDED_SUFFIX preserves its trailing space (legacy COTRN02C verbatim)")
    void transactionAddedSuffixPreservesTrailingSpaceQuirk() {
        // The legacy literal is "Transaction added successfully. " — there is a deliberate
        // trailing space (the COBOL STRING statement appended further text after it).
        assertThat(MessageService.TRANSACTION_ADDED_SUFFIX).isEqualTo("Transaction added successfully. ");
        // The trailing space is parity-critical: assert it is present and survives.
        assertThat(MessageService.TRANSACTION_ADDED_SUFFIX).endsWith(" ");
        // And the meaningful (trimmed) text is exactly the sentence without the trailing space.
        assertThat(MessageService.TRANSACTION_ADDED_SUFFIX.trim()).isEqualTo("Transaction added successfully.");
    }

    @Test
    @DisplayName("Common CSMSG01Y messages match the trimmed PIC X(50) copybook values")
    void commonCopybookMessagesMatchLegacyText() {
        // Source-of-truth: app/cpy/CSMSG01Y.cpy. Both fields are PIC X(50), space-padded in the
        // copybook; the Java constants hold only the meaningful (trimmed) text.
        assertThat(MessageService.THANK_YOU).isEqualTo("Thank you for using CardDemo application...");
        assertThat(MessageService.INVALID_KEY).isEqualTo("Invalid key pressed. Please see below...");
    }

    @Test
    @DisplayName("Neutral 404 not-found messages are stable")
    void neutralNotFoundMessagesMatchExpectedText() {
        // The legacy online programs used screen-specific not-found text; the REST controllers
        // surface a uniform HTTP 404, so neutral messages are used (AAP §0.4.1.3 permits neutral
        // text where the legacy text varied per screen).
        assertThat(MessageService.ACCOUNT_NOT_FOUND).isEqualTo("Account not found");
        assertThat(MessageService.CARD_NOT_FOUND).isEqualTo("Card not found");
        assertThat(MessageService.TRANSACTION_NOT_FOUND).isEqualTo("Transaction not found");
        assertThat(MessageService.USER_NOT_FOUND_BY_ID).isEqualTo("User not found");
        assertThat(MessageService.CUSTOMER_NOT_FOUND).isEqualTo("Customer not found");
    }

    @Test
    @DisplayName("CSMSG02Y/CABENDD has no REST analog; INTERNAL_ERROR provides the generic 500 text")
    void internalErrorMessageMatchesExpectedText() {
        // app/cpy/CSMSG02Y.cpy resolves to CABENDD.CPY (ABEND-DATA: code/culprit/reason/msg), a
        // mainframe abend control block with no REST analog. Catastrophic failures map to HTTP 500
        // via GlobalExceptionHandler, for which this generic constant supplies the text.
        assertThat(MessageService.INTERNAL_ERROR).isEqualTo("An unexpected error occurred");
    }


    // =================================================================================
    // Helper method assertions
    // ---------------------------------------------------------------------------------
    // The three static helpers reproduce the legacy COUSR0xC "STRING <userId> DELIMITED BY
    // SPACE <suffix>" concatenations that build the add/update/delete confirmation lines.
    // =================================================================================

    @Test
    @DisplayName("userAdded(id) builds the legacy 'added' confirmation containing the id")
    void userAddedBuildsConfirmationContainingId() {
        String message = MessageService.userAdded("USER0001");
        // Full exact string parity (id + suffix, suffix carries its own leading space).
        assertThat(message).isEqualTo("USER0001 has been added ...");
        // The supplied id must be embedded, and the legacy "added" wording preserved.
        assertThat(message).contains("USER0001");
        assertThat(message).contains("added");
        // Bind the helper to its backing constant so the two cannot drift apart.
        assertThat(message).isEqualTo("USER0001" + MessageService.USER_ADDED_SUFFIX);
        // The helper must echo whatever id it is given (no hard-coding of a single id).
        assertThat(MessageService.userAdded("ADMIN001")).contains("ADMIN001");
    }

    @Test
    @DisplayName("userUpdated(id) builds the legacy 'updated' confirmation containing the id")
    void userUpdatedBuildsConfirmationContainingId() {
        String message = MessageService.userUpdated("USER0001");
        assertThat(message).isEqualTo("USER0001 has been updated ...");
        assertThat(message).contains("USER0001");
        assertThat(message).contains("updated");
        assertThat(message).isEqualTo("USER0001" + MessageService.USER_UPDATED_SUFFIX);
        assertThat(MessageService.userUpdated("ADMIN001")).contains("ADMIN001");
    }

    @Test
    @DisplayName("userDeleted(id) builds the legacy 'deleted' confirmation containing the id")
    void userDeletedBuildsConfirmationContainingId() {
        String message = MessageService.userDeleted("USER0001");
        assertThat(message).isEqualTo("USER0001 has been deleted ...");
        assertThat(message).contains("USER0001");
        assertThat(message).contains("deleted");
        assertThat(message).isEqualTo("USER0001" + MessageService.USER_DELETED_SUFFIX);
        assertThat(MessageService.userDeleted("ADMIN001")).contains("ADMIN001");
    }

    // =================================================================================
    // Robustness assertions
    // =================================================================================

    @Test
    @DisplayName("Key user-facing constants are never null or blank")
    void keyConstantsAreNotBlank() {
        // A representative set spanning every logical group; a blank here would mean an empty
        // message reached the user, which the legacy application never did.
        assertThat(MessageService.ENTER_USER_ID).isNotBlank();
        assertThat(MessageService.ENTER_PASSWORD).isNotBlank();
        assertThat(MessageService.WRONG_PASSWORD).isNotBlank();
        assertThat(MessageService.USER_NOT_FOUND).isNotBlank();
        assertThat(MessageService.INVALID_YN_VALUE).isNotBlank();
        assertThat(MessageService.NOTHING_TO_PAY).isNotBlank();
        assertThat(MessageService.USER_ALREADY_EXISTS).isNotBlank();
        assertThat(MessageService.RECORD_CHANGED_BY_OTHER).isNotBlank();
        assertThat(MessageService.CHANGES_VALIDATED).isNotBlank();
        assertThat(MessageService.TRANSACTION_ADDED_SUFFIX).isNotBlank();
        assertThat(MessageService.THANK_YOU).isNotBlank();
        assertThat(MessageService.INVALID_KEY).isNotBlank();
        assertThat(MessageService.INTERNAL_ERROR).isNotBlank();
    }

    @Test
    @DisplayName("Every public static final String constant is non-null and non-blank (reflection sweep)")
    void allPublicStaticFinalStringConstantsAreNonNullAndNonBlank() throws IllegalAccessException {
        int stringConstantCount = 0;
        for (Field field : MessageService.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            boolean isPublicStaticFinal = Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods);
            if (isPublicStaticFinal && field.getType() == String.class) {
                stringConstantCount++;
                Object value = field.get(null);
                assertThat(value)
                        .as("constant %s must not be null", field.getName())
                        .isNotNull();
                assertThat((String) value)
                        .as("constant %s must not be blank", field.getName())
                        .isNotBlank();
            }
        }
        // Non-brittle lower bound: the class is expected to expose at least one such constant.
        // (Kept deliberately loose so renaming/adding constants never breaks this sweep.)
        assertThat(stringConstantCount)
                .as("MessageService should expose public String message constants")
                .isGreaterThan(0);
    }
}
