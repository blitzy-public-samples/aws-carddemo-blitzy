package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogicError}, the typed {@link FileStatusException} subtype representing
 * COBOL <em>FILE STATUS</em> {@code '92'} (a logic error) and the equivalent CICS logic-error
 * responses.
 *
 * <p>These tests lock down the contract that {@code GlobalExceptionHandler} (online) and the
 * batch skip/reject policy depend on: {@link LogicError} is an unchecked exception that extends
 * {@link FileStatusException} (and therefore {@link RuntimeException}); it always reports the
 * two-character status code {@code "92"} via {@link FileStatusException#getFileStatus()}; both
 * constructors preserve their message (and, for the overload, their originating cause); and it
 * declares {@code serialVersionUID = 1L} so the zero-warning {@code -Xlint:all} build succeeds.
 * The exact-name assertions are deliberate guards: Technical Specification &sect;0.6.5 pins the
 * class name {@code LogicError} (alongside {@code ResourceUnavailable}), so a downstream rename
 * fails this test loudly rather than silently breaking the FILE STATUS &rarr; HTTP 500 mapping.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it exercises {@link LogicError} in isolation through its public API and does not
 * extend {@code AbstractPostgresIntegrationTest}.</p>
 *
 * <p>Origin oracle: FILE STATUS '92' logic-error family; legacy/cbl/CBTRN02C.cbl &amp;
 * legacy/cbl/CBACT04C.cbl 9910-DISPLAY-IO-STATUS (IO-STAT1='9') / 9999-ABEND-PROGRAM. See
 * Technical Specification &sect;0.6.5.</p>
 */
class LogicErrorTest {

    /**
     * Verifies the inheritance contract: {@link LogicError}'s direct superclass is
     * {@link FileStatusException}, and an instance is therefore both a {@link FileStatusException}
     * and an (unchecked) {@link RuntimeException}, so callers are never forced to declare or catch
     * it and downstream handlers can match it via the shared base type.
     */
    @Test
    void extendsFileStatusException() {
        assertThat(LogicError.class.getSuperclass()).isEqualTo(FileStatusException.class);
        assertThat(new LogicError("boom"))
                .isInstanceOf(FileStatusException.class)
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies the exact class name required by &sect;0.6.5: the simple name is {@code "LogicError"}
     * and the fully qualified name is {@code "com.aws.carddemo.exception.LogicError"}. This locks
     * the name so a rename cannot silently break the {@code GlobalExceptionHandler} FILE STATUS
     * {@code '92'} &rarr; HTTP 500 mapping.
     */
    @Test
    void hasExactSimpleName() {
        assertThat(LogicError.class.getSimpleName()).isEqualTo("LogicError");
        assertThat(LogicError.class.getName()).isEqualTo("com.aws.carddemo.exception.LogicError");
    }

    /**
     * Verifies the {@link LogicError#FILE_STATUS} constant is the two-character COBOL code
     * {@code "92"}, retained as a {@link String} to preserve the alphanumeric FILE STATUS
     * semantics.
     */
    @Test
    void fileStatusConstant_isNinetyTwo() {
        assertThat(LogicError.FILE_STATUS).isEqualTo("92");
    }

    /**
     * Verifies the single-argument constructor tags the exception with FILE STATUS {@code "92"},
     * stores the supplied message verbatim, leaves the cause null, and reports a status equal to
     * the {@link LogicError#FILE_STATUS} constant.
     */
    @Test
    void messageConstructor_setsStatusAndMessage() {
        LogicError ex = new LogicError("record locked / logic error");
        assertThat(ex.getFileStatus()).isEqualTo("92");
        assertThat(ex.getMessage()).isEqualTo("record locked / logic error");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getFileStatus()).isEqualTo(LogicError.FILE_STATUS);
    }

    /**
     * Verifies the cause-preserving constructor keeps FILE STATUS {@code "92"} and the supplied
     * message while retaining the exact originating cause instance, so a handler can wrap a Spring
     * {@code DataAccessException} (or any other trigger) without losing the stack trace.
     */
    @Test
    void causeConstructor_preservesCauseAndStatus() {
        Throwable cause = new IllegalStateException("driver logic fault");
        LogicError ex = new LogicError("logic error", cause);
        assertThat(ex.getFileStatus()).isEqualTo("92");
        assertThat(ex.getMessage()).isEqualTo("logic error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. Required because
     * {@link FileStatusException} is {@link java.io.Serializable} through {@link RuntimeException}
     * and the build treats the {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass}
     * reads the declared value without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        assertThat(ObjectStreamClass.lookup(LogicError.class).getSerialVersionUID()).isEqualTo(1L);
    }
}
