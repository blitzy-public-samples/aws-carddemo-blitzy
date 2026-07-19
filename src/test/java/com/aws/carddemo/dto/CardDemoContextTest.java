/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit tests for {@link CardDemoContext}, the session-scoped replacement
 * for the CICS pseudo-conversational communication area (COMMAREA).
 *
 * <p><b>Origin / oracle:</b> {@code legacy/cpy/COCOM01Y.cpy} (COBOL group
 * {@code CARDDEMO-COMMAREA}, lines 19-44). See AAP &sect;0.6.8
 * (pseudo-conversational state: COMMAREA &rarr; HTTP-session context).</p>
 *
 * <p>These tests exercise the <em>plain state logic</em> of the context in
 * isolation. They construct the object directly with {@code new
 * CardDemoContext()} and deliberately never start a Spring or web context,
 * never touch a database, and never load {@code com.aws.carddemo.domain}
 * types. The {@code @Component}/{@code @SessionScope} stereotype on the
 * production class is irrelevant to direct instantiation, so no application
 * context is required to verify the behavior asserted here.</p>
 *
 * <p>The two parity-critical behaviors verified here are:</p>
 * <ol>
 *   <li><b>First-entry default.</b> A freshly-created context is in the
 *       program-enter state ({@code pgmContext == 0}) - the unit-level
 *       equivalent of the CICS {@code EIBCALEN = 0} first-entry test and the
 *       copybook's {@code CDEMO-PGM-ENTER} default.</li>
 *   <li><b>User-type routing.</b> The {@code "A"}/{@code "U"} user-type
 *       88-level helpers ({@link CardDemoContext#isAdmin()} /
 *       {@link CardDemoContext#isUser()}) that drive post-signon
 *       admin-versus-user routing.</li>
 * </ol>
 *
 * <p>Because {@code equals}/{@code hashCode} are intentionally absent on the
 * production class, every assertion is made through getters and the boolean
 * 88-level helpers after invoking setters.</p>
 */
class CardDemoContextTest {

    // --- Phase A: first-entry / program-context enter-reenter semantics -----

    /**
     * A freshly-constructed context defaults to the program-enter state.
     *
     * <p>In COBOL, cross-transaction first entry is detected via
     * {@code EIBCALEN = 0}; in the modernized design an <em>absent</em> context
     * in the HTTP session signals first entry, a decision enforced by the
     * controller layer. At the unit level the equivalent invariant is that a
     * brand-new context reports the {@code CDEMO-PGM-ENTER} default
     * ({@code pgmContext == 0}).</p>
     */
    @Test
    void fresh_context_defaults_to_program_enter_state() {
        CardDemoContext context = new CardDemoContext();

        assertThat(context.getPgmContext()).isZero();
        assertThat(context.isProgramEnter()).isTrue();
        assertThat(context.isProgramReenter()).isFalse();
    }

    /**
     * {@code markReenter()} moves the context into the re-enter state
     * ({@code CDEMO-PGM-REENTER}, value {@code 1}).
     */
    @Test
    void mark_reenter_sets_program_context_to_reenter() {
        CardDemoContext context = new CardDemoContext();

        context.markReenter();

        assertThat(context.getPgmContext()).isEqualTo(1);
        assertThat(context.isProgramReenter()).isTrue();
        assertThat(context.isProgramEnter()).isFalse();
    }

    /**
     * {@code markEnter()} restores the enter state after a previous
     * {@code markReenter()} (the enter/re-enter flag is a two-way toggle).
     */
    @Test
    void mark_enter_after_reenter_returns_to_enter() {
        CardDemoContext context = new CardDemoContext();

        context.markReenter();
        context.markEnter();

        assertThat(context.getPgmContext()).isZero();
        assertThat(context.isProgramEnter()).isTrue();
        assertThat(context.isProgramReenter()).isFalse();
    }

    /**
     * The 88-level helpers read the underlying {@code pgmContext} field, so a
     * direct {@code setPgmContext(...)} is reflected by
     * {@code isProgramEnter()} / {@code isProgramReenter()}.
     */
    @Test
    void set_pgm_context_directly_is_reflected_by_helpers() {
        CardDemoContext context = new CardDemoContext();

        context.setPgmContext(1);
        assertThat(context.isProgramReenter()).isTrue();
        assertThat(context.isProgramEnter()).isFalse();

        context.setPgmContext(0);
        assertThat(context.isProgramEnter()).isTrue();
        assertThat(context.isProgramReenter()).isFalse();
    }

    // --- Phase B: user-type 88-level helpers --------------------------------

    /**
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'}: user type {@code "A"} is recognized
     * as admin only.
     */
    @Test
    void user_type_A_is_recognized_as_admin_only() {
        CardDemoContext context = new CardDemoContext();

        context.setUserType("A");

        assertThat(context.isAdmin()).isTrue();
        assertThat(context.isUser()).isFalse();
    }

    /**
     * {@code CDEMO-USRTYP-USER VALUE 'U'}: user type {@code "U"} is recognized
     * as user only.
     */
    @Test
    void user_type_U_is_recognized_as_user_only() {
        CardDemoContext context = new CardDemoContext();

        context.setUserType("U");

        assertThat(context.isUser()).isTrue();
        assertThat(context.isAdmin()).isFalse();
    }

    /**
     * A default (null) user type satisfies neither 88-level condition and the
     * helpers must not throw (they compare {@code constant.equals(field)}).
     */
    @Test
    void null_user_type_is_neither_admin_nor_user() {
        CardDemoContext context = new CardDemoContext();

        assertThat(context.getUserType()).isNull();
        assertThat(context.isAdmin()).isFalse();
        assertThat(context.isUser()).isFalse();
    }

    /**
     * Blank and unknown user-type values satisfy neither 88-level condition and
     * do not throw.
     */
    @Test
    void blank_or_unknown_user_type_is_neither_admin_nor_user() {
        CardDemoContext context = new CardDemoContext();

        context.setUserType("");
        assertThat(context.isAdmin()).isFalse();
        assertThat(context.isUser()).isFalse();

        context.setUserType("X");
        assertThat(context.isAdmin()).isFalse();
        assertThat(context.isUser()).isFalse();
    }

    /**
     * The convenience setter {@code setAdmin()} sets the raw user type to the
     * canonical {@code "A"} and is reported as admin.
     */
    @Test
    void set_admin_convenience_setter_marks_admin() {
        CardDemoContext context = new CardDemoContext();

        context.setAdmin();

        assertThat(context.isAdmin()).isTrue();
        assertThat(context.isUser()).isFalse();
        assertThat(context.getUserType()).isEqualTo("A");
    }

    /**
     * The convenience setter {@code setUser()} sets the raw user type to the
     * canonical {@code "U"} and is reported as user.
     */
    @Test
    void set_user_convenience_setter_marks_user() {
        CardDemoContext context = new CardDemoContext();

        context.setUser();

        assertThat(context.isUser()).isTrue();
        assertThat(context.isAdmin()).isFalse();
        assertThat(context.getUserType()).isEqualTo("U");
    }

    // --- Phase C: identifier types align with domain keys -------------------

    /**
     * {@code CDEMO-ACCT-ID PIC 9(11)} is modeled as a {@link Long} to align
     * with {@code Account.acctId}.
     */
    @Test
    void acct_id_is_stored_as_long() {
        CardDemoContext context = new CardDemoContext();

        context.setAcctId(12345678901L);

        Long acctId = context.getAcctId();
        assertThat(acctId).isInstanceOf(Long.class).isEqualTo(12345678901L);
    }

    /**
     * {@code CDEMO-CUST-ID PIC 9(09)} is modeled as a {@link Long} to align
     * with {@code Customer.custId}.
     */
    @Test
    void cust_id_is_stored_as_long() {
        CardDemoContext context = new CardDemoContext();

        context.setCustId(123456789L);

        Long custId = context.getCustId();
        assertThat(custId).isInstanceOf(Long.class).isEqualTo(123456789L);
    }

    /**
     * {@code CDEMO-CARD-NUM PIC 9(16)} is modeled as a {@link String} (not a
     * numeric type) to align with {@code Card.cardNum} and to preserve leading
     * zeros in the 16-digit card number.
     */
    @Test
    void card_num_is_stored_as_string_preserving_leading_zeros() {
        CardDemoContext context = new CardDemoContext();

        context.setCardNum("0000111122223333");

        String cardNum = context.getCardNum();
        assertThat(cardNum).isInstanceOf(String.class).isEqualTo("0000111122223333");
    }

    // --- Phase D: from/to hand-off + remaining field round-trips ------------

    /**
     * Models the CICS {@code XCTL} / {@code RETURN TRANSID} hand-off: the
     * calling screen records where control came from ({@code from*}) and where
     * it is going ({@code to*}). Values chosen mirror the signon-to-menu
     * navigation ({@code CC00}/{@code COSGN00C} &rarr; {@code CM00}/{@code
     * COMEN01C}).
     */
    @Test
    void from_to_handoff_fields_round_trip() {
        CardDemoContext context = new CardDemoContext();

        context.setFromTranid("CC00");
        context.setFromProgram("COSGN00C");
        context.setToTranid("CM00");
        context.setToProgram("COMEN01C");

        assertThat(context.getFromTranid()).isEqualTo("CC00");
        assertThat(context.getFromProgram()).isEqualTo("COSGN00C");
        assertThat(context.getToTranid()).isEqualTo("CM00");
        assertThat(context.getToProgram()).isEqualTo("COMEN01C");
    }

    /**
     * The remaining scalar string fields (user id, account status, customer
     * name parts, and the last map/mapset) round-trip through their
     * getters/setters unchanged.
     */
    @Test
    void remaining_string_fields_round_trip() {
        CardDemoContext context = new CardDemoContext();

        context.setUserId("ADMIN001");
        context.setAcctStatus("Y");
        context.setCustFirstName("JOHN");
        context.setCustMiddleName("Q");
        context.setCustLastName("PUBLIC");
        context.setLastMap("COSGN0A");
        context.setLastMapset("COSGN00");

        assertThat(context.getUserId()).isEqualTo("ADMIN001");
        assertThat(context.getAcctStatus()).isEqualTo("Y");
        assertThat(context.getCustFirstName()).isEqualTo("JOHN");
        assertThat(context.getCustMiddleName()).isEqualTo("Q");
        assertThat(context.getCustLastName()).isEqualTo("PUBLIC");
        assertThat(context.getLastMap()).isEqualTo("COSGN0A");
        assertThat(context.getLastMapset()).isEqualTo("COSGN00");
    }

    // --- Phase E: Serializable contract -------------------------------------

    /**
     * The context implements {@link Serializable} so it is safe to store in and
     * replicate across the HTTP session.
     */
    @Test
    void context_is_serializable() {
        CardDemoContext context = new CardDemoContext();

        assertThat(context).isInstanceOf(Serializable.class);
    }

    /**
     * A populated context survives a full Java serialization round-trip with
     * its navigation and selection state intact, confirming the session
     * carrier can be persisted/replicated.
     *
     * <p>The {@code ByteArrayInputStream}/{@code ByteArrayOutputStream} are held
     * as plain locals (their {@code close()} is a no-op) while only the object
     * streams are managed as try-with-resources, keeping the code free of the
     * {@code -Xlint:try} "resource never referenced" warning under the
     * zero-warning build.</p>
     *
     * @throws Exception if serialization or deserialization fails
     */
    @Test
    void context_survives_java_serialization_round_trip() throws Exception {
        CardDemoContext original = new CardDemoContext();
        original.setUserId("USER0001");
        original.setUserType("A");
        original.setAcctId(12345678901L);
        original.setCardNum("0000111122223333");
        original.markReenter();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(original);
        }
        byte[] serialized = buffer.toByteArray();

        ByteArrayInputStream source = new ByteArrayInputStream(serialized);
        CardDemoContext restored;
        try (ObjectInputStream in = new ObjectInputStream(source)) {
            restored = (CardDemoContext) in.readObject();
        }

        assertThat(restored).isNotNull();
        assertThat(restored.getUserId()).isEqualTo("USER0001");
        assertThat(restored.isAdmin()).isTrue();
        assertThat(restored.getAcctId()).isEqualTo(12345678901L);
        assertThat(restored.getCardNum()).isEqualTo("0000111122223333");
        assertThat(restored.isProgramReenter()).isTrue();
    }

    // --- Diagnostic representation ------------------------------------------

    /**
     * {@code toString()} yields a non-null diagnostic string in the safe identity form
     * (class name + identity hash), exposing no field content.
     */
    @Test
    void to_string_uses_safe_identity_form() {
        CardDemoContext context = new CardDemoContext();
        context.setUserId("USER0001");
        context.markReenter();

        String rendered = context.toString();

        assertThat(rendered)
                .isNotNull()
                .startsWith("CardDemoContext@");
    }

}
