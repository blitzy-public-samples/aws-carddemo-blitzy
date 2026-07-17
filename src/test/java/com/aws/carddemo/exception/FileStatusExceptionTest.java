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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the CardDemo FILE STATUS typed-exception hierarchy:
 * {@link FileStatusException} and its two subclasses
 * {@link RecordNotFoundException} (status {@code "23"}) and
 * {@link DuplicateKeyException} (status {@code "22"}).
 *
 * <p>This is a behavioral-parity contract (AAP &sect;0.7.2 M1, &sect;0.5.5). The
 * legacy CardDemo programs re-express VSAM {@code FILE STATUS} codes into
 * caller-visible outcomes, and these exceptions carry those two-character codes
 * verbatim so that any Java caller inspecting {@link FileStatusException#getFileStatus()}
 * observes exactly what the COBOL code observed. The single most important
 * property locked down here is that {@code getFileStatus()} returns the exact
 * 2-character status for every construction path, because the downstream
 * {@code CicsRespMapper}, {@code GlobalExceptionHandler}, and batch return-code
 * logic all key off it.</p>
 *
 * <p>COBOL evidence (verified against the relocated {@code legacy/cbl/} sources):</p>
 * <ul>
 *   <li>{@code '00'} success / {@code '10'} end-of-file (a normal terminal
 *       condition, never an error) / {@code '22'} duplicate key / {@code '23'}
 *       record-not-found are the VSAM FILE STATUS codes the batch programs
 *       inspect (for example {@code legacy/cbl/CBTRN02C.cbl} and
 *       {@code legacy/cbl/CBACT04C.cbl} L436 {@code IF DISCGRP-STATUS = '23'}).</li>
 *   <li>Online, {@code legacy/cbl/COTRN01C.cbl} L283-285 maps
 *       {@code WHEN DFHRESP(NOTFND)} to "Transaction ID NOT found..." — the
 *       not-found outcome fixed here to {@code "23"}.</li>
 *   <li>Online, {@code legacy/cbl/COUSR01C.cbl} L260-263 maps
 *       {@code WHEN DFHRESP(DUPKEY)} / {@code WHEN DFHRESP(DUPREC)} to
 *       "User ID already exist..." — the duplicate outcome fixed here to
 *       {@code "22"}.</li>
 * </ul>
 *
 * <p>The classes under test live in this same package
 * ({@code com.aws.carddemo.exception}); they are therefore referenced directly
 * with no import statements. In particular {@link DuplicateKeyException} is the
 * CardDemo domain type, deliberately distinct from
 * {@code org.springframework.dao.DuplicateKeyException}.</p>
 *
 * <p>Per the security discipline (AAP &sect;0.7.3, &sect;0.9.3) every message and
 * key used below is a non-sensitive entity name or identifier; no card CVV or
 * password value ever appears in a test literal.</p>
 */
class FileStatusExceptionTest {

    // ------------------------------------------------------------------
    // 4A. Base FileStatusException
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The four STATUS_* constants equal the COBOL FILE STATUS codes 00/10/22/23 exactly")
    void statusConstantsMatchCobolFileStatusCodes() {
        assertThat(FileStatusException.STATUS_OK).isEqualTo("00");
        assertThat(FileStatusException.STATUS_EOF).isEqualTo("10");
        assertThat(FileStatusException.STATUS_DUPLICATE_KEY).isEqualTo("22");
        assertThat(FileStatusException.STATUS_RECORD_NOT_FOUND).isEqualTo("23");
    }

    @Test
    @DisplayName("Base constructor stores the raw FILE STATUS and the detail message verbatim")
    void constructorStoresFileStatusAndMessage() {
        // "09" is a representative implementor ("9x") status code — preserved as-is.
        FileStatusException ex = new FileStatusException("09", "boom");
        assertThat(ex.getFileStatus()).isEqualTo("09");
        assertThat(ex.getMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("Cause constructor chains the root cause while still carrying the FILE STATUS and message")
    void constructorWithCauseChainsCause() {
        RuntimeException cause = new RuntimeException("root");
        FileStatusException ex = new FileStatusException("09", "boom", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getFileStatus()).isEqualTo("09");
        assertThat(ex.getMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("FileStatusException is an unchecked RuntimeException (COBOL abend path; @Transactional rollback)")
    void isUncheckedRuntimeException() {
        // Unchecked so the COBOL non-recoverable abend path maps cleanly and so
        // Spring's @Transactional rollback semantics (which key off unchecked
        // exceptions) apply without a checked-exception declaration.
        assertThat(new FileStatusException("00", "x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("isFileStatus matches only this exception's own code and is null-safe")
    void isFileStatusMatchesOnlyItsOwnCode() {
        FileStatusException ex = new FileStatusException("23", "not found");
        assertThat(ex.isFileStatus("23")).isTrue();
        assertThat(ex.isFileStatus("22")).isFalse();
        assertThat(ex.isFileStatus(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // 4B. RecordNotFoundException — FILE STATUS '23'
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RecordNotFoundException fixes the FILE STATUS to '23' (COBOL DISCGRP-STATUS = '23')")
    void recordNotFoundFixesStatusTo23() {
        RecordNotFoundException ex = new RecordNotFoundException("Account not found: 1");
        assertThat(ex.getFileStatus()).isEqualTo("23");
        assertThat(ex.getFileStatus()).isEqualTo(FileStatusException.STATUS_RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("RecordNotFoundException is a FileStatusException and a RuntimeException")
    void recordNotFoundIsAFileStatusException() {
        RecordNotFoundException ex = new RecordNotFoundException("Account not found: 1");
        assertThat(ex).isInstanceOf(FileStatusException.class).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("RecordNotFoundException preserves the detail message verbatim")
    void recordNotFoundPreservesMessage() {
        RecordNotFoundException ex = new RecordNotFoundException("Account not found: 1");
        assertThat(ex.getMessage()).isEqualTo("Account not found: 1");
    }

    @Test
    @DisplayName("RecordNotFoundException cause constructor chains the cause and keeps status '23'")
    void recordNotFoundWithCauseChainsCauseAndKeepsStatus23() {
        RuntimeException cause = new RuntimeException("empty result");
        RecordNotFoundException ex = new RecordNotFoundException("Account not found: 1", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getFileStatus()).isEqualTo("23");
        assertThat(ex.getMessage()).isEqualTo("Account not found: 1");
    }

    @Test
    @DisplayName("RecordNotFoundException.of composes '<entity> not found: <key>' and carries status '23'")
    void recordNotFoundOfComposesNotFoundMessage() {
        RecordNotFoundException ex = RecordNotFoundException.of("Account", 123);
        assertThat(ex.getMessage()).isEqualTo("Account not found: 123");
        assertThat(ex.getFileStatus()).isEqualTo("23");
    }

    // ------------------------------------------------------------------
    // 4C. DuplicateKeyException — FILE STATUS '22'
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DuplicateKeyException fixes the FILE STATUS to '22' (COBOL DUPKEY/DUPREC)")
    void duplicateKeyFixesStatusTo22() {
        DuplicateKeyException ex = new DuplicateKeyException("User already exists: USR01");
        assertThat(ex.getFileStatus()).isEqualTo("22");
        assertThat(ex.getFileStatus()).isEqualTo(FileStatusException.STATUS_DUPLICATE_KEY);
    }

    @Test
    @DisplayName("DuplicateKeyException is a FileStatusException and a RuntimeException")
    void duplicateKeyIsAFileStatusException() {
        DuplicateKeyException ex = new DuplicateKeyException("User already exists: USR01");
        assertThat(ex).isInstanceOf(FileStatusException.class).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("DuplicateKeyException preserves the detail message verbatim")
    void duplicateKeyPreservesMessage() {
        DuplicateKeyException ex = new DuplicateKeyException("User already exists: USR01");
        assertThat(ex.getMessage()).isEqualTo("User already exists: USR01");
    }

    @Test
    @DisplayName("DuplicateKeyException cause constructor chains the cause and keeps status '22'")
    void duplicateKeyWithCauseChainsCauseAndKeepsStatus22() {
        RuntimeException cause = new RuntimeException("unique constraint violation");
        DuplicateKeyException ex = new DuplicateKeyException("User already exists: USR01", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getFileStatus()).isEqualTo("22");
        assertThat(ex.getMessage()).isEqualTo("User already exists: USR01");
    }

    @Test
    @DisplayName("DuplicateKeyException.of composes '<entity> already exists: <key>' and carries status '22'")
    void duplicateKeyOfComposesAlreadyExistsMessage() {
        DuplicateKeyException ex = DuplicateKeyException.of("User", "USR01");
        assertThat(ex.getMessage()).isEqualTo("User already exists: USR01");
        assertThat(ex.getFileStatus()).isEqualTo("22");
    }

    // ------------------------------------------------------------------
    // 4D. Polymorphic catch parity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Both subclasses are catchable as the base FileStatusException (COBOL 'any non-OK status' path)")
    void subclassesCaughtAsBaseFileStatusException() {
        // A single catch (FileStatusException e) reproduces the COBOL "any non-OK
        // FILE STATUS -> abend/error path" while the concrete subclass is retained.
        assertThatThrownBy(() -> {
            throw new RecordNotFoundException("Account not found: 1");
        }).isInstanceOf(FileStatusException.class)
          .isInstanceOf(RecordNotFoundException.class);

        assertThatThrownBy(() -> {
            throw new DuplicateKeyException("User already exists: USR01");
        }).isInstanceOf(FileStatusException.class)
          .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("When held as the base type, each subclass still exposes its distinct FILE STATUS via getFileStatus()")
    void caughtAsBaseStillExposesDistinctFileStatus() {
        // Held polymorphically as FileStatusException (as a catch block would),
        // getFileStatus() still distinguishes the specific legacy outcome.
        FileStatusException notFound = new RecordNotFoundException("Account not found: 1");
        FileStatusException duplicate = new DuplicateKeyException("User already exists: USR01");

        assertThat(notFound.getFileStatus()).isEqualTo(FileStatusException.STATUS_RECORD_NOT_FOUND);
        assertThat(duplicate.getFileStatus()).isEqualTo(FileStatusException.STATUS_DUPLICATE_KEY);
        assertThat(notFound.getFileStatus()).isNotEqualTo(duplicate.getFileStatus());
    }
}
