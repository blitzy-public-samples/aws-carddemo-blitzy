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
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link CicsRespMapper} — the stateless translator that reproduces
 * the repeated COBOL {@code EVALUATE WS-RESP-CD WHEN DFHRESP(...)} blocks by turning
 * a CICS {@code RESP}/{@code RESP2} outcome into the CardDemo typed-exception
 * hierarchy (AAP &sect;0.5.5, &sect;0.7.2 M1).
 *
 * <p>This is a behavioral-parity contract. {@code raiseFor} is the single point at
 * which every migrated online service reproduces the exact COBOL {@code EVALUATE}
 * outcome, so the two properties locked down here are asserted precisely:</p>
 * <ul>
 *   <li>the thrown <strong>type</strong> — {@code NOTFND} &rarr;
 *       {@link RecordNotFoundException}, {@code DUPKEY}/{@code DUPREC} &rarr;
 *       {@link DuplicateKeyException}, and any other non-normal code &rarr; the base
 *       {@link FileStatusException} (never a subclass); and</li>
 *   <li>the carried {@link FileStatusException#getFileStatus() FILE STATUS} —
 *       {@code "23"} for not-found, {@code "22"} for duplicate, and
 *       {@code String.valueOf(respCode)} for a generic error.</li>
 * </ul>
 *
 * <p>{@code NORMAL} (0) and {@code ENDFILE} (20) deliberately do <em>not</em> throw:
 * {@code ENDFILE} is the normal terminal condition of a
 * {@code STARTBR}/{@code READNEXT} browse loop, so asserting the no-throw behavior
 * guards browse-pagination parity (a regression here would abort a legitimate
 * end-of-browse loop, exactly as it would in the COBOL).</p>
 *
 * <p>COBOL evidence (verified against the relocated {@code legacy/cbl/} sources;
 * across the online {@code CO*.cbl} programs the DFHRESP condition names used are
 * NORMAL 43&times;, NOTFND 23&times;, ENDFILE 8&times;, DUPREC 7&times;,
 * DUPKEY 3&times;):</p>
 * <ul>
 *   <li>{@code legacy/cbl/COTRN01C.cbl} L281-289: {@code WHEN DFHRESP(NORMAL)
 *       CONTINUE} / {@code WHEN DFHRESP(NOTFND)} &rarr; "Transaction ID NOT
 *       found..." — NOTFND is the not-found signal.</li>
 *   <li>{@code legacy/cbl/COUSR01C.cbl} L260-266: {@code WHEN DFHRESP(DUPKEY)}
 *       {@code WHEN DFHRESP(DUPREC)} &rarr; "User ID already exist..." — both
 *       duplicate conditions collapse to one "already exists" outcome.</li>
 *   <li>{@code legacy/cbl/COACTUPC.cbl} L1044, L3661: {@code RESP2(WS-REAS-CD)} is
 *       a secondary reason code, preserved by the {@code raiseFor} RESP2
 *       overload.</li>
 * </ul>
 *
 * <p>The classes under test live in this same package
 * ({@code com.aws.carddemo.exception}) and are therefore referenced directly with
 * no import statements; in particular {@link DuplicateKeyException} is the CardDemo
 * domain type, deliberately distinct from
 * {@code org.springframework.dao.DuplicateKeyException}. Per the security discipline
 * (AAP &sect;0.7.3, &sect;0.9.3) every {@code recordType}/{@code key} used below is a
 * non-sensitive entity name or record id; no card CVV or password value ever
 * appears in a test literal.</p>
 *
 * @see CicsRespMapper
 * @see FileStatusException
 * @see RecordNotFoundException
 * @see DuplicateKeyException
 */
class CicsRespMapperTest {

    // ------------------------------------------------------------------
    // 4A. CicsResp enum — the closed set of DFHRESP condition names/values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CicsResp values equal the compile-time DFHRESP constants 0/13/14/15/20")
    void respValuesMatchDfhrespConstants() {
        assertThat(CicsRespMapper.CicsResp.NORMAL.getValue()).isEqualTo(0);
        assertThat(CicsRespMapper.CicsResp.NOTFND.getValue()).isEqualTo(13);
        assertThat(CicsRespMapper.CicsResp.DUPKEY.getValue()).isEqualTo(14);
        assertThat(CicsRespMapper.CicsResp.DUPREC.getValue()).isEqualTo(15);
        assertThat(CicsRespMapper.CicsResp.ENDFILE.getValue()).isEqualTo(20);
    }

    @Test
    @DisplayName("CicsResp is a closed set of exactly five conditions")
    void respEnumIsClosedSetOfExactlyFive() {
        assertThat(CicsRespMapper.CicsResp.values()).hasSize(5);
    }

    @ParameterizedTest
    @EnumSource(CicsRespMapper.CicsResp.class)
    @DisplayName("fromCode round-trips every constant back from its DFHRESP value")
    void fromCodeRoundTripsForEveryConstant(CicsRespMapper.CicsResp resp) {
        assertThat(CicsRespMapper.CicsResp.fromCode(resp.getValue())).isSameAs(resp);
    }

    @Test
    @DisplayName("fromCode rejects a code outside the closed set with IllegalArgumentException")
    void fromCodeThrowsForUnknown() {
        assertThatThrownBy(() -> CicsRespMapper.CicsResp.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown CICS RESP code")
                .hasMessageContaining("999");
    }

    // ------------------------------------------------------------------
    // 4B. raiseFor(int, String, Object) — the primary RESP translation point
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NORMAL (0) returns normally — no exception (COBOL WHEN DFHRESP(NORMAL) CONTINUE)")
    void normalDoesNotThrow() {
        assertThatCode(() -> CicsRespMapper.raiseFor(0, "Transaction", "T1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ENDFILE (20) returns normally — end-of-browse is a normal terminal condition, not an error")
    void endfileDoesNotThrow() {
        assertThatCode(() -> CicsRespMapper.raiseFor(20, "Transaction", "T1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NOTFND (13) throws RecordNotFoundException carrying FILE STATUS '23'")
    void notfndThrowsRecordNotFoundWithStatus23() {
        // COBOL parity: legacy/cbl/COTRN01C.cbl L283 WHEN DFHRESP(NOTFND).
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(13, "Transaction", "T1"));

        assertThat(thrown)
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Transaction")
                .hasMessageContaining("T1");
        // getFileStatus() is an exact caller-visible contract, asserted precisely.
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("23");
    }

    @Test
    @DisplayName("DUPKEY (14) throws DuplicateKeyException carrying FILE STATUS '22'")
    void dupkeyThrowsDuplicateKeyWithStatus22() {
        // COBOL parity: legacy/cbl/COUSR01C.cbl L260 WHEN DFHRESP(DUPKEY).
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(14, "User", "USR01"));

        assertThat(thrown)
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("User")
                .hasMessageContaining("USR01")
                .hasMessageContaining("already exists");
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("22");
    }

    @Test
    @DisplayName("DUPREC (15) also throws DuplicateKeyException '22' — DUPKEY/DUPREC collapse to one outcome (COUSR01C)")
    void duprecThrowsDuplicateKeyWithStatus22() {
        // COBOL parity: legacy/cbl/COUSR01C.cbl L261 WHEN DFHRESP(DUPREC) is
        // stacked with DUPKEY and produces the identical "already exists" outcome.
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(15, "User", "USR01"));

        assertThat(thrown)
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("User")
                .hasMessageContaining("USR01");
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("22");
    }

    @Test
    @DisplayName("An unknown RESP throws EXACTLY the base FileStatusException with status = String.valueOf(respCode)")
    void unknownRespThrowsExactlyBaseFileStatusException() {
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(99, "Account", 1));

        // isExactlyInstanceOf guarantees the base type, NOT a subclass, so an
        // unexpected RESP is never mis-classified as not-found or duplicate.
        assertThat(thrown)
                .isExactlyInstanceOf(FileStatusException.class)
                .hasMessageContaining("Account")
                .hasMessageContaining("RESP=99");
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("99");
    }

    @Test
    @DisplayName("A not-found result is never a duplicate result and vice-versa (guards against mis-mapping)")
    void notfoundIsDistinctFromDuplicate() {
        Throwable notFound = catchThrowable(() -> CicsRespMapper.raiseFor(13, "Transaction", "T1"));
        Throwable duplicate = catchThrowable(() -> CicsRespMapper.raiseFor(14, "User", "USR01"));

        assertThat(notFound)
                .isInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
        assertThat(duplicate)
                .isInstanceOf(DuplicateKeyException.class)
                .isNotInstanceOf(RecordNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // 4C. raiseFor(int, int, String, Object) — the RESP2 diagnostic overload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RESP2 overload appends 'RESP2=<n>' to a NOTFND message and keeps status '23'")
    void notfndWithResp2IncludesResp2InMessage() {
        // COBOL parity: legacy/cbl/COACTUPC.cbl L1044/L3661 RESP2(WS-REAS-CD).
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(13, 42, "Transaction", "T1"));

        assertThat(thrown)
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("T1")
                .hasMessageContaining("RESP2=42");
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("23");
    }

    @Test
    @DisplayName("RESP2 overload preserves both RESP and RESP2 in an unknown-code message")
    void unknownRespWithResp2IncludesBothCodes() {
        Throwable thrown = catchThrowable(() -> CicsRespMapper.raiseFor(99, 7, "Account", 1));

        assertThat(thrown)
                .isExactlyInstanceOf(FileStatusException.class)
                .hasMessageContaining("RESP=99")
                .hasMessageContaining("RESP2=7");
        assertThat(((FileStatusException) thrown).getFileStatus()).isEqualTo("99");
    }

    // ------------------------------------------------------------------
    // Browse-terminator classifiers + utility-class shape (coverage)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isNormal/isEndOfFile classify the non-error browse outcomes symbolically")
    void isNormalAndIsEndOfFileClassifyOutcomes() {
        assertThat(CicsRespMapper.isNormal(0)).isTrue();
        assertThat(CicsRespMapper.isNormal(13)).isFalse();
        assertThat(CicsRespMapper.isEndOfFile(20)).isTrue();
        assertThat(CicsRespMapper.isEndOfFile(0)).isFalse();
    }

    @Test
    @DisplayName("CicsRespMapper is a non-instantiable utility (single private constructor)")
    void constructorIsPrivateSoUtilityIsNonInstantiable() throws Exception {
        Constructor<CicsRespMapper> ctor = CicsRespMapper.class.getDeclaredConstructor();
        assertThat(CicsRespMapper.class.getDeclaredConstructors()).hasSize(1);
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();

        // Reflectively invoking the private constructor succeeds and yields an
        // instance with no state or side effects; this is a coverage aid only —
        // production code never instantiates this utility class.
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(CicsRespMapper.class);
    }
}
