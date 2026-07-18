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
package com.aws.carddemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link UpperCasePasswordEncoder}, the shared credential-normalization
 * encoder that folds the raw password to upper case ({@code Locale.ROOT}) before
 * delegating to a hashing encoder.
 *
 * <p>These tests lock down the behavioral-parity contract that fixes the credential-case
 * divergence between the CC00 sign-on service and the HTTP&nbsp;Basic gate (finding
 * F-CRIT-1; decision log D26):</p>
 * <ul>
 *   <li><strong>Case-insensitive matching</strong> &mdash; any case of the raw password
 *       verifies against a hash encoded from any other case, reproducing the legacy
 *       {@code FUNCTION UPPER-CASE} password contract of {@code COSGN00C}.</li>
 *   <li><strong>Idempotence</strong> &mdash; because {@code SignonService} and
 *       {@code UserService} already upper-case before invoking the encoder, the extra
 *       fold here is a no-op ({@code UPPER(UPPER(x)) == UPPER(x)}).</li>
 *   <li><strong>Faithful delegation</strong> &mdash; {@code encode}/{@code matches} fold
 *       and then delegate verbatim, and {@code upgradeEncoding} delegates unchanged.</li>
 * </ul>
 *
 * <p>Behavior tests run over a real {@link BCryptPasswordEncoder}; delegation tests use a
 * Mockito mock delegate to assert the folded value is what reaches the delegate.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpperCasePasswordEncoder — case-insensitive credential normalization (COSGN00C parity)")
class UpperCasePasswordEncoderTest {

    /** Mock hashing delegate used by the delegation-verification tests. */
    @Mock
    private PasswordEncoder delegate;

    @Test
    @DisplayName("matches() is case-insensitive across lower, upper, and mixed case")
    void matchesIsCaseInsensitive() {
        PasswordEncoder encoder = new UpperCasePasswordEncoder(new BCryptPasswordEncoder());
        String hash = encoder.encode("password"); // folded to PASSWORD before hashing

        assertThat(encoder.matches("password", hash)).isTrue();
        assertThat(encoder.matches("PASSWORD", hash)).isTrue();
        assertThat(encoder.matches("PaSsWoRd", hash)).isTrue();
    }

    @Test
    @DisplayName("matches() still rejects a genuinely different password")
    void matchesRejectsDifferentPassword() {
        PasswordEncoder encoder = new UpperCasePasswordEncoder(new BCryptPasswordEncoder());
        String hash = encoder.encode("password");

        assertThat(encoder.matches("passw0rd", hash)).isFalse();
        assertThat(encoder.matches("secret", hash)).isFalse();
    }

    @Test
    @DisplayName("encode()/matches() are idempotent for an already upper-cased password")
    void idempotentUnderPreFoldedPassword() {
        // SignonService / UserService already upper-case before calling the encoder,
        // so double-folding must not change the outcome.
        PasswordEncoder encoder = new UpperCasePasswordEncoder(new BCryptPasswordEncoder());
        String hashFromRaw = encoder.encode("password");
        String hashFromUpper = encoder.encode("PASSWORD");

        assertThat(encoder.matches("PASSWORD", hashFromRaw)).isTrue();
        assertThat(encoder.matches("password", hashFromUpper)).isTrue();
    }

    @Test
    @DisplayName("fold uses Locale.ROOT (locale-independent)")
    void foldUsesLocaleRoot() {
        PasswordEncoder encoder = new UpperCasePasswordEncoder(new BCryptPasswordEncoder());
        String hash = encoder.encode("i"); // Locale.ROOT: "i" -> "I"

        assertThat(encoder.matches("I", hash)).isTrue();
    }

    @Test
    @DisplayName("no-arg constructor wraps a BCrypt delegate (case-insensitive end-to-end)")
    void noArgConstructorWrapsBcrypt() {
        PasswordEncoder encoder = new UpperCasePasswordEncoder();
        String hash = encoder.encode("password");

        assertThat(hash).startsWith("$2"); // BCrypt hash marker
        assertThat(encoder.matches("PASSWORD", hash)).isTrue();
    }

    @Test
    @DisplayName("encode() folds the raw password to upper case before delegating")
    void encodeFoldsBeforeDelegating() {
        when(delegate.encode("PASSWORD")).thenReturn("HASH");
        PasswordEncoder encoder = new UpperCasePasswordEncoder(delegate);

        assertThat(encoder.encode("password")).isEqualTo("HASH");
        verify(delegate).encode("PASSWORD");
    }

    @Test
    @DisplayName("matches() folds the raw password to upper case before delegating")
    void matchesFoldsBeforeDelegating() {
        when(delegate.matches("PASSWORD", "HASH")).thenReturn(true);
        PasswordEncoder encoder = new UpperCasePasswordEncoder(delegate);

        assertThat(encoder.matches("password", "HASH")).isTrue();
        verify(delegate).matches("PASSWORD", "HASH");
    }

    @Test
    @DisplayName("upgradeEncoding() delegates unchanged")
    void upgradeEncodingDelegates() {
        when(delegate.upgradeEncoding("HASH")).thenReturn(true);
        PasswordEncoder encoder = new UpperCasePasswordEncoder(delegate);

        assertThat(encoder.upgradeEncoding("HASH")).isTrue();
        verify(delegate).upgradeEncoding("HASH");
    }

    @Test
    @DisplayName("null delegate is rejected")
    void nullDelegateRejected() {
        assertThatThrownBy(() -> new UpperCasePasswordEncoder(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegate");
    }
}
