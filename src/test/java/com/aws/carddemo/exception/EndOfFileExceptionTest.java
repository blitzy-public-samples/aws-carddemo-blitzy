package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EndOfFileException}, the typed subtype that represents COBOL
 * {@code FILE STATUS = '10'} (end-of-file) and the CICS {@code ENDFILE} condition in the migrated
 * AWS CardDemo application.
 *
 * <p>These tests lock down the contract that the batch tier relies on to treat end-of-input as a
 * <strong>normal control signal, not a hard error or abend</strong>: the exception inherits from
 * {@link FileStatusException} (and is therefore unchecked, mirroring Spring's
 * {@code DataAccessException} hierarchy); it publishes the canonical two-character code {@code "10"}
 * as {@link EndOfFileException#FILE_STATUS} so readers and step configurations reference it without
 * magic strings; the stored {@code fileStatus} always round-trips to that same {@code "10"} code
 * regardless of the constructor used; the cause-preserving constructor keeps the wrapped trigger's
 * stack trace; and it declares {@code serialVersionUID = 1L} so the zero-warning {@code -Xlint:all}
 * / {@code failOnWarning} build succeeds. This parity mirrors the legacy {@code APPL-EOF} behaviour
 * where a {@code '10'} status ends a read loop cleanly rather than aborting it.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it exercises {@link EndOfFileException} in isolation through its public API and
 * therefore runs under Surefire (the {@code *Test} contract), not Failsafe.</p>
 *
 * <p>Origin oracle: legacy/cbl/CBACT01C.cbl 1000-ACCTFILE-GET-NEXT (IF ACCTFILE-STATUS='10' &rarr;
 * APPL-EOF &rarr; END-OF-FILE='Y'); legacy/cbl/CBTRN02C.cbl 1000-DALYTRAN-GET-NEXT. See Technical
 * Specification &sect;0.6.5.</p>
 */
class EndOfFileExceptionTest {

    /**
     * Verifies the type sits under {@link FileStatusException} and is unchecked: its direct
     * superclass is {@link FileStatusException}, {@link FileStatusException} is assignable from it,
     * and a constructed instance is both a {@link FileStatusException} and a {@link RuntimeException}
     * so callers are never forced to declare or catch it.
     */
    @Test
    void extendsFileStatusException() {
        // The immediate parent in the hierarchy is FileStatusException, not RuntimeException directly.
        assertThat(EndOfFileException.class.getSuperclass()).isEqualTo(FileStatusException.class);
        assertThat(FileStatusException.class.isAssignableFrom(EndOfFileException.class)).isTrue();
        // A concrete instance is a FileStatusException and, transitively, an unchecked RuntimeException.
        EndOfFileException ex = new EndOfFileException("eof");
        assertThat(ex).isInstanceOf(FileStatusException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies the published {@link EndOfFileException#FILE_STATUS} constant is exactly the COBOL
     * end-of-file code {@code "10"}, so batch readers can reference the canonical code without magic
     * strings.
     */
    @Test
    void fileStatusConstant_isTen() {
        // The public constant must equal the two-character COBOL literal for end-of-file.
        assertThat(EndOfFileException.FILE_STATUS).isEqualTo("10");
    }

    /**
     * Verifies the single-argument constructor stores the {@code "10"} status and the supplied
     * message, leaves the cause null, and ties the stored code to the published constant.
     */
    @Test
    void messageConstructor_setsStatusTenAndMessage() {
        // Message-only construction fixes the status to "10" and preserves the caller's message.
        EndOfFileException ex = new EndOfFileException("end reached");
        assertThat(ex.getFileStatus()).isEqualTo("10");
        assertThat(ex.getMessage()).isEqualTo("end reached");
        assertThat(ex.getCause()).isNull();
        // The stored code is the same value published as the constant (no divergence).
        assertThat(ex.getFileStatus()).isEqualTo(EndOfFileException.FILE_STATUS);
    }

    /**
     * Verifies the two-argument constructor stores the {@code "10"} status and message and preserves
     * the exact originating cause instance, so a wrapped reader failure keeps its stack trace.
     */
    @Test
    void causeConstructor_preservesCauseAndStatus() {
        // The cause overload retains status "10", the message, and the very same cause instance.
        Throwable cause = new IllegalStateException("reader closed");
        EndOfFileException ex = new EndOfFileException("eof", cause);
        assertThat(ex.getFileStatus()).isEqualTo("10");
        assertThat(ex.getMessage()).isEqualTo("eof");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies the no-argument convenience constructor fixes the {@code "10"} status, supplies the
     * factual default message {@code "End of file reached"}, and leaves the cause null.
     */
    @Test
    void noArgConstructor_setsStatusTenAndDefaultMessage() {
        // The convenience constructor is used by readers that reach EOF with no custom description.
        EndOfFileException ex = new EndOfFileException();
        assertThat(ex.getFileStatus()).isEqualTo("10");
        assertThat(ex.getMessage()).isEqualTo("End of file reached");
        assertThat(ex.getCause()).isNull();
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. Required because the
     * hierarchy is {@link java.io.Serializable} (via {@link RuntimeException}) and the build treats
     * the {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass} reads the declared
     * value without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        // The declared serialVersionUID must be 1L to satisfy the zero-warning migration convention.
        assertThat(ObjectStreamClass.lookup(EndOfFileException.class).getSerialVersionUID())
                .isEqualTo(1L);
    }
}
