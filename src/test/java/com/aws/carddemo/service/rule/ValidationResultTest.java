/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationResult}, verifying the valid/invalid factory
 * contracts, the non-null-message invariant of an invalid result, the cached
 * valid singleton, value-based equality/hash, and the log-safe {@code toString}.
 */
class ValidationResultTest {

    @Test
    @DisplayName("valid() is valid, not invalid, and carries an empty (never null) message")
    void valid_hasEmptyMessageAndPasses() {
        ValidationResult result = ValidationResult.valid();

        assertTrue(result.isValid());
        assertFalse(result.isInvalid());
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("invalid(msg) is invalid, not valid, and carries the supplied message")
    void invalid_carriesMessageAndFails() {
        ValidationResult result = ValidationResult.invalid("x");

        assertFalse(result.isValid());
        assertTrue(result.isInvalid());
        assertEquals("x", result.message());
    }

    @Test
    @DisplayName("invalid(null) throws NullPointerException (an invalid result must carry a message)")
    void invalid_nullMessage_throws() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> ValidationResult.invalid(null));
        assertEquals("message", ex.getMessage());
    }

    @Test
    @DisplayName("valid() returns the same cached singleton on every call")
    void valid_returnsCachedSingleton() {
        assertSame(ValidationResult.valid(), ValidationResult.valid());
    }

    @Test
    @DisplayName("equal results are equal and share a hash code; unequal results differ")
    void equalsAndHashCode_areConsistent() {
        ValidationResult a = ValidationResult.invalid("boom");
        ValidationResult b = ValidationResult.invalid("boom");

        // Reflexive and value-based equality with a consistent hash code.
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());

        // Two valid results are equal (same cached instance) and share a hash code.
        assertEquals(ValidationResult.valid(), ValidationResult.valid());
        assertEquals(ValidationResult.valid().hashCode(), ValidationResult.valid().hashCode());

        // Different message, or different validity, breaks equality.
        assertNotEquals(a, ValidationResult.invalid("other"));
        assertNotEquals(ValidationResult.valid(), a);

        // Not equal to null or to an unrelated type.
        assertFalse(a.equals(null));
        assertFalse(a.equals("boom"));
    }

    @Test
    @DisplayName("toString is log-safe and reflects the flag and message")
    void toString_reflectsState() {
        assertEquals("ValidationResult{valid=true, message=''}", ValidationResult.valid().toString());
        assertEquals(
                "ValidationResult{valid=false, message='bad field'}",
                ValidationResult.invalid("bad field").toString());
    }
}
