package com.carddemo.account.domain.validation;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EditResult}, the verdict every field edit in this package returns.
 *
 * <p> {@code EditResult} carries one message slot and one guard on that slot. The slot is
 * {@code WS-RETURN-MSG}, declared {@code PIC X(75)} at app/cbl/COACTUPC.cbl:L479. The guard is the
 * condition name {@code WS-RETURN-MSG-OFF VALUE SPACES} at app/cbl/COACTUPC.cbl:L480.
 * app/cbl/COACTUPC.cbl:L876 sets {@code WS-RETURN-MSG-OFF} to true once per validation pass, and
 * one slot with one guard holds one message for that pass.</p>
 *
 * <p>Ordering messages across several fields is orchestration and sits outside this class.</p>
 */
@DisplayName("EditResult, the verdict returned by every field edit")
class EditResultTest {

    /** Declared width of {@code WS-RETURN-MSG} at app/cbl/COACTUPC.cbl:L479. */
    private static final int SOURCE_MESSAGE_WIDTH = 75;

    /** Fragment the mandatory field edit appends at app/cbl/COACTUPC.cbl:L1841, opening with a space. */
    private static final String MANDATORY_FRAGMENT = " must be supplied.";

    /** Fragment the credit score edit appends at app/cbl/COACTUPC.cbl:L2523, opening with a colon. */
    private static final String CREDIT_SCORE_FRAGMENT = ": should be between 300 and 850";

    /** Message that opens with two spaces, carries a colon, and closes with two spaces. */
    private static final String PADDED_MESSAGE = "  FICO Score: should be between 300 and 850  ";

    /** Message holding only spaces, the slot state {@code WS-RETURN-MSG-OFF} names. */
    private static final String SPACES_ONLY_MESSAGE = "   ";

    /** Character the width tests repeat to build a message of a known length. */
    private static final String FILL_CHARACTER = "M";

    @Test
    @DisplayName("ok() returns a passing verdict and leaves the message slot empty")
    void okReturnsPassingVerdictWithEmptySlot() {
        EditResult result = EditResult.ok();

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("failure(String) returns a failing verdict and the supplied message character for character")
    void failureReturnsSuppliedMessageUnchanged() {
        EditResult result = EditResult.failure(PADDED_MESSAGE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(PADDED_MESSAGE);
        assertThat(result.message()).startsWith("  ").endsWith("  ").contains(":");

        assertThat(EditResult.failure(MANDATORY_FRAGMENT).message()).isEqualTo(MANDATORY_FRAGMENT);
        assertThat(EditResult.failure(CREDIT_SCORE_FRAGMENT).message()).isEqualTo(CREDIT_SCORE_FRAGMENT);
    }

    @Test
    @DisplayName("hasMessage() reports slot content, and a slot of only spaces counts as empty")
    void hasMessageReportsSlotContent() {
        assertThat(EditResult.failure(CREDIT_SCORE_FRAGMENT).hasMessage()).isTrue();
        assertThat(EditResult.ok().hasMessage()).isFalse();

        EditResult spacesOnly = EditResult.failure(SPACES_ONLY_MESSAGE);

        assertThat(spacesOnly.valid()).isFalse();
        assertThat(spacesOnly.message()).isEqualTo(SPACES_ONLY_MESSAGE);
        assertThat(spacesOnly.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("EditResult declares exactly two record components, the verdict and the message")
    void recordDeclaresExactlyTwoComponents() {
        RecordComponent[] components = EditResult.class.getRecordComponents();

        // A third component, a removed component, or a renamed component fails these assertions.
        assertThat(components.length).isEqualTo(2);
        assertThat(components[0].getName()).isEqualTo("valid");
        assertThat(components[0].getType()).isEqualTo(boolean.class);
        assertThat(components[1].getName()).isEqualTo("message");
        assertThat(components[1].getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("EditResult is a record")
    void editResultIsARecord() {
        assertThat(EditResult.class.isRecord()).isTrue();
    }

    @Test
    @DisplayName("Two results holding the same verdict and the same message are equal")
    void resultsWithSameVerdictAndMessageAreEqual() {
        assertThat(EditResult.ok()).isEqualTo(EditResult.ok());
        assertThat(EditResult.ok()).hasSameHashCodeAs(EditResult.ok());

        assertThat(EditResult.failure(CREDIT_SCORE_FRAGMENT))
                .isEqualTo(EditResult.failure(CREDIT_SCORE_FRAGMENT));
        assertThat(EditResult.failure(CREDIT_SCORE_FRAGMENT))
                .hasSameHashCodeAs(EditResult.failure(CREDIT_SCORE_FRAGMENT));

        assertThat(EditResult.failure(CREDIT_SCORE_FRAGMENT)).isNotEqualTo(EditResult.ok());
        assertThat(EditResult.failure(MANDATORY_FRAGMENT))
                .isNotEqualTo(EditResult.failure(CREDIT_SCORE_FRAGMENT));
    }

    @Test
    @DisplayName("A message of the source width and a longer message both reach the caller intact")
    void messagesAtAndBeyondSourceWidthSurviveIntact() {
        String atSourceWidth = FILL_CHARACTER.repeat(SOURCE_MESSAGE_WIDTH);
        String beyondSourceWidth = FILL_CHARACTER.repeat(SOURCE_MESSAGE_WIDTH + 45);

        assertThat(EditResult.failure(atSourceWidth).message())
                .isEqualTo(atSourceWidth)
                .hasSize(SOURCE_MESSAGE_WIDTH);
        assertThat(EditResult.failure(beyondSourceWidth).message())
                .isEqualTo(beyondSourceWidth)
                .hasSize(SOURCE_MESSAGE_WIDTH + 45);
    }
}
