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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;

/**
 * Unit tests for {@link CardDemoUserDetails}, focused on the credential-confidentiality
 * contract (finding F-P9-C; AAP &sect;0.7.3 / &sect;0.9.3).
 *
 * <p>{@link org.springframework.security.core.userdetails.UserDetails} extends
 * {@link java.io.Serializable}, so an authenticated principal can be written into a
 * serialized {@code SecurityContext}. The stored BCrypt credential must never appear
 * in that serialized form. These tests lock down two mechanisms that guarantee it:
 * the credential field is {@code transient} (excluded from the object stream), and the
 * principal implements {@link CredentialsContainer} so Spring Security erases the
 * credential immediately after a successful authentication.</p>
 *
 * <p>The literals below are non-sensitive test fixtures: {@link #USERNAME} is a login
 * id and {@link #BCRYPT_HASH} is a synthetic, clearly-marked value that is not a real
 * credential — it exists only so its absence from the serialized bytes can be
 * asserted.</p>
 */
class CardDemoUserDetailsTest {

    /** A representative login id ({@code SEC-USR-ID}); appears in serialized bytes because it is a normal field. */
    private static final String USERNAME = "ADMIN001";

    /**
     * A synthetic, unmistakable stand-in for the stored BCrypt hash. It is deliberately
     * distinctive so that a substring search over the serialized bytes reliably detects
     * whether the credential leaked. It is not a real password or hash.
     */
    private static final String BCRYPT_HASH = "$2a$10$DONOTSERIALIZEthisBcryptMarker0123456789abcdefghij";

    @Test
    @DisplayName("The stored BCrypt credential never appears in the serialized principal (transient), while the username does")
    void serializedPrincipalDoesNotContainCredential() throws IOException {
        CardDemoUserDetails principal = new CardDemoUserDetails(USERNAME, BCRYPT_HASH, UserRole.ADMIN);

        byte[] bytes = serialize(principal);

        // Interpret the raw bytes as Latin-1 so every byte maps to one char and a
        // contiguous ASCII marker is detectable by a plain substring search.
        String asLatin1 = new String(bytes, StandardCharsets.ISO_8859_1);

        // The credential must be absent...
        assertThat(asLatin1).doesNotContain(BCRYPT_HASH);
        // ...and the username present, proving the object really was serialized (so the
        // credential's absence is due to `transient`, not an empty/failed stream).
        assertThat(asLatin1).contains(USERNAME);
    }

    @Test
    @DisplayName("A deserialized principal carries a null credential but preserves username, role, and authorities")
    void deserializedPrincipalHasNullCredentialButKeepsIdentity() throws IOException, ClassNotFoundException {
        CardDemoUserDetails principal = new CardDemoUserDetails(USERNAME, BCRYPT_HASH, UserRole.ADMIN);

        CardDemoUserDetails restored = deserialize(serialize(principal));

        // transient => the credential does not survive the round-trip.
        assertThat(restored.getPassword()).isNull();
        // Everything required after authentication survives.
        assertThat(restored.getUsername()).isEqualTo(USERNAME);
        assertThat(restored.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(restored.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        // Identity is anchored to the username, so the restored principal still equals the original.
        assertThat(restored).isEqualTo(principal);
    }

    @Test
    @DisplayName("CardDemoUserDetails is a CredentialsContainer so Spring Security can erase its credential")
    void principalIsACredentialsContainer() {
        assertThat(new CardDemoUserDetails(USERNAME, BCRYPT_HASH, UserRole.ADMIN))
                .isInstanceOf(CredentialsContainer.class);
    }

    @Test
    @DisplayName("eraseCredentials() clears the credential in memory, is idempotent, and leaves identity/role intact")
    void eraseCredentialsClearsCredentialAndIsIdempotent() {
        CardDemoUserDetails principal = new CardDemoUserDetails(USERNAME, BCRYPT_HASH, UserRole.USER);

        // Before erasure the freshly built principal still exposes the credential for the
        // DaoAuthenticationProvider comparison.
        assertThat(principal.getPassword()).isEqualTo(BCRYPT_HASH);

        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();

        // Idempotent: erasing again on an already-cleared principal is a harmless no-op.
        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();

        // Erasing the credential must not disturb identity, role, or authorities.
        assertThat(principal.getUsername()).isEqualTo(USERNAME);
        assertThat(principal.getRole()).isEqualTo(UserRole.USER);
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("toString() never exposes the credential")
    void toStringNeverExposesCredential() {
        String text = new CardDemoUserDetails(USERNAME, BCRYPT_HASH, UserRole.ADMIN).toString();

        assertThat(text).doesNotContain(BCRYPT_HASH);
        assertThat(text).contains(USERNAME);
    }

    // ------------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------------

    private static byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(object);
        }
        return buffer.toByteArray();
    }

    private static CardDemoUserDetails deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (CardDemoUserDetails) in.readObject();
        }
    }
}
