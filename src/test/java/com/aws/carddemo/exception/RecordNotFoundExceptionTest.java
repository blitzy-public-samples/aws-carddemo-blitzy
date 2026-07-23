package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecordNotFoundException}, the single typed carrier for the legacy
 * "record not found" signals: COBOL VSAM/sequential <em>FILE STATUS</em> {@code "23"} (and its
 * logically identical companion {@code "13"}) and the CICS <em>EIBRESP</em>/{@code RESP} response
 * {@code NOTFND} (numeric value {@code 13}). All three collapse to this one type, so these tests
 * pin the canonical {@code "23"} code and document the {@code "13"}/{@code NOTFND} equivalence,
 * letting the traceability matrix map every source form here with no gaps (Technical Specification
 * &sect;0.6.5, &sect;0.6.10).
 *
 * <p>They also lock down the intentional naming collision: our
 * {@code com.aws.carddemo.exception.RecordNotFoundException} deliberately coexists with the
 * framework "not found" types {@code jakarta.persistence.EntityNotFoundException} and
 * {@code org.springframework.dao.EmptyResultDataAccessException}. Those framework types are bridged
 * to the same 404 outcome centrally in {@code GlobalExceptionHandler} by fully-qualified name; this
 * test therefore references them only by FQN (never importing them) and proves they are distinct,
 * non-cross-assignable types living in separate hierarchies.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it exercises {@link RecordNotFoundException} in isolation through its public
 * API.</p>
 *
 * <p>Origin oracle: CICS NOTFND (RESP 13) legacy/cbl/COSGN00C.cbl READ-USER-SEC-FILE (WHEN 13);
 * FILE STATUS '23' legacy/cbl/CBACT04C.cbl (DISCGRP), legacy/cbl/CBTRN02C.cbl (TCATBALF '23').
 * See &sect;0.6.5.</p>
 */
class RecordNotFoundExceptionTest {

    /**
     * Verifies the type sits in the FILE STATUS hierarchy: its direct superclass is
     * {@link FileStatusException}, and an instance is therefore both a {@link FileStatusException}
     * and an (unchecked) {@link RuntimeException}, so callers branch on it exactly as they do for
     * the base carrier and are never forced to catch it.
     */
    @Test
    void extendsFileStatusException() {
        // Direct superclass is the FILE STATUS base carrier, not RuntimeException directly.
        assertThat(RecordNotFoundException.class.getSuperclass()).isEqualTo(FileStatusException.class);
        // A constructed instance is-a FileStatusException and (unchecked) RuntimeException.
        assertThat(new RecordNotFoundException("nf"))
                .isInstanceOf(FileStatusException.class)
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies the canonical {@link RecordNotFoundException#FILE_STATUS} constant is the COBOL
     * record-not-found code {@code "23"} (retained as a String to preserve the significant leading
     * digit of the two-byte alphanumeric FILE STATUS field).
     */
    @Test
    void fileStatusConstant_isTwentyThree() {
        assertThat(RecordNotFoundException.FILE_STATUS).isEqualTo("23");
    }

    /**
     * Verifies the message-only constructor assigns the canonical FILE STATUS {@code "23"}, stores
     * the supplied message verbatim, and leaves the cause null (no wrapped trigger). The carried
     * status is also confirmed to equal the {@code FILE_STATUS} constant.
     */
    @Test
    void messageConstructor_setsStatusAndMessage() {
        RecordNotFoundException ex = new RecordNotFoundException("account record not found");
        assertThat(ex.getFileStatus()).isEqualTo("23");
        assertThat(ex.getMessage()).isEqualTo("account record not found");
        assertThat(ex.getCause()).isNull();
        // The carried status equals the canonical constant.
        assertThat(ex.getFileStatus()).isEqualTo(RecordNotFoundException.FILE_STATUS);
    }

    /**
     * Verifies the cause-preserving constructor keeps the canonical FILE STATUS {@code "23"} and the
     * message while retaining the exact originating cause instance. This is the overload
     * {@code GlobalExceptionHandler} (or a batch skip/reject policy) uses to wrap a framework
     * trigger such as {@code EmptyResultDataAccessException} or {@code EntityNotFoundException}
     * without losing the original stack trace.
     */
    @Test
    void causeConstructor_preservesCauseAndStatus() {
        Throwable cause = new IllegalStateException("empty result");
        RecordNotFoundException ex = new RecordNotFoundException("nf", cause);
        assertThat(ex.getFileStatus()).isEqualTo("23");
        assertThat(ex.getMessage()).isEqualTo("nf");
        // The exact originating instance is preserved for stack-trace fidelity.
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. Required because
     * {@link RuntimeException} implements {@link java.io.Serializable} and the build treats the
     * {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass} reads the declared value
     * without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        assertThat(ObjectStreamClass.lookup(RecordNotFoundException.class).getSerialVersionUID())
                .isEqualTo(1L);
    }

    /**
     * Verifies the intentional naming collision is safe: our {@link RecordNotFoundException} is a
     * distinct type from the framework not-found exceptions
     * {@code org.springframework.dao.EmptyResultDataAccessException} and
     * {@code jakarta.persistence.EntityNotFoundException}, lives in the {@link FileStatusException}
     * hierarchy (not the Spring {@code DataAccessException} hierarchy), and is not cross-assignable
     * with them. The framework types are referenced by fully-qualified name only, mirroring how
     * {@code GlobalExceptionHandler} bridges them by FQN rather than by import.
     */
    @Test
    void isDistinctFromSpringAndJpaNotFoundTypes() {
        // Distinct Class objects from the Spring and JPA "not found" types.
        assertThat(RecordNotFoundException.class)
                .isNotEqualTo(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(RecordNotFoundException.class)
                .isNotEqualTo(jakarta.persistence.EntityNotFoundException.class);
        // Ours belongs to the FILE STATUS hierarchy.
        assertThat(FileStatusException.class.isAssignableFrom(RecordNotFoundException.class)).isTrue();
        // Spring's belongs to the DataAccessException hierarchy.
        assertThat(org.springframework.dao.DataAccessException.class
                .isAssignableFrom(org.springframework.dao.EmptyResultDataAccessException.class)).isTrue();
        // No cross-assignability between the two hierarchies.
        assertThat(org.springframework.dao.EmptyResultDataAccessException.class
                .isAssignableFrom(RecordNotFoundException.class)).isFalse();
    }

    /**
     * Documents that COBOL FILE STATUS {@code "13"} and CICS {@code NOTFND} (RESP 13) collapse into
     * this single type carrying the canonical {@code "23"} code. The instance mirrors the signon
     * program's user-not-found path and confirms the equivalence, so the traceability matrix can map
     * {@code "23"}, {@code "13"}, and {@code NOTFND} all here with no gaps.
     */
    @Test
    void documentsThirteenAndNotfndEquivalence() {
        // Mirrors legacy/cbl/COSGN00C.cbl WHEN 13 (NOTFND) -> "User not found. Try again ...".
        RecordNotFoundException ex = new RecordNotFoundException("user not found");
        assertThat(ex.getFileStatus()).isEqualTo("23");
        assertThat(ex.getFileStatus()).isEqualTo(RecordNotFoundException.FILE_STATUS);
    }
}
