package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileStatusException}, the base carrier of the AWS CardDemo FILE STATUS /
 * CICS {@code RESP} typed-exception hierarchy.
 *
 * <p>These tests lock down the contract that every mapped status/response subtype depends on: the
 * exception is unchecked (it extends {@link RuntimeException}, mirroring Spring's
 * {@code DataAccessException} hierarchy); it is concrete and directly throwable (the Java analogue
 * of the COBOL {@code WHEN OTHER} generic-error branch); it carries the originating two-character
 * status code verbatim as a {@link String} so that significant leading zeros and the
 * implementation-defined non-numeric ("9x") status bytes survive round-trip; it preserves an
 * optional cause so a wrapped trigger keeps its stack trace; and it declares
 * {@code serialVersionUID = 1L} so the zero-warning {@code -Xlint:all} build succeeds.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it exercises {@link FileStatusException} in isolation through its public API.</p>
 *
 * <p>Origin oracle: FILE STATUS / EIBRESP handling across {@code legacy/cbl/**}
 * (legacy/cbl/CBTRN02C.cbl 9910-DISPLAY-IO-STATUS/9999-ABEND-PROGRAM &mdash; the generic
 * non-{@code 00}/non-{@code 10} status &rarr; display-and-abend path; legacy/cbl/COSGN00C.cbl
 * READ-USER-SEC-FILE &mdash; {@code EXEC CICS READ ... RESP(WS-RESP-CD)} then
 * {@code EVALUATE WS-RESP-CD}). See Technical Specification &sect;0.6.5.</p>
 */
class FileStatusExceptionTest {

    /**
     * Verifies the base type is unchecked: its direct superclass is {@link RuntimeException} (not
     * the checked {@link Exception}), so callers are never forced to declare or catch it.
     */
    @Test
    void extendsRuntimeException_isUnchecked() {
        assertThat(FileStatusException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(RuntimeException.class.isAssignableFrom(FileStatusException.class)).isTrue();
        assertThat(Exception.class.isAssignableFrom(FileStatusException.class)).isTrue();
        // Its direct parent is RuntimeException, not the checked Exception.
        assertThat(Exception.class.equals(FileStatusException.class.getSuperclass())).isFalse();
    }

    /**
     * Verifies the base type is concrete (not abstract) so it can be thrown directly as the generic
     * non-mapped status path, analogous to the COBOL {@code WHEN OTHER} branch.
     */
    @Test
    void isConcreteNotAbstract() {
        assertThat(Modifier.isAbstract(FileStatusException.class.getModifiers())).isFalse();
    }

    /**
     * Verifies the two-argument constructor stores the status code and message and leaves the cause
     * null.
     */
    @Test
    void twoArgConstructor_storesFileStatusAndMessage() {
        FileStatusException ex = new FileStatusException("00", "operation succeeded");
        assertThat(ex.getFileStatus()).isEqualTo("00");
        assertThat(ex.getMessage()).isEqualTo("operation succeeded");
        assertThat(ex.getCause()).isNull();
    }

    /**
     * Verifies the three-argument constructor preserves the exact originating cause instance, so
     * {@code GlobalExceptionHandler} can wrap a Spring {@code DataAccessException} without losing
     * the stack trace.
     */
    @Test
    void threeArgConstructor_preservesCause() {
        Throwable cause = new IllegalStateException("io failure");
        FileStatusException ex = new FileStatusException("92", "logic error", cause);
        assertThat(ex.getFileStatus()).isEqualTo("92");
        assertThat(ex.getMessage()).isEqualTo("logic error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies representative COBOL FILE STATUS codes round-trip verbatim through
     * {@code getFileStatus()}, proving the two-character code (including significant leading zeros)
     * is retained exactly as supplied, matching the two-byte COBOL FILE STATUS field.
     */
    @Test
    void getFileStatus_roundTripsRepresentativeCodes() {
        String[] codes = {"00", "10", "13", "22", "23", "92", "93"};
        for (String code : codes) {
            FileStatusException ex = new FileStatusException(code, "msg");
            assertThat(ex.getFileStatus()).isEqualTo(code);
        }
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. Required because
     * {@link RuntimeException} implements {@link java.io.Serializable} and the build treats the
     * {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass} reads the declared value
     * without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        assertThat(ObjectStreamClass.lookup(FileStatusException.class).getSerialVersionUID())
                .isEqualTo(1L);
    }

    /**
     * Verifies the exception is throwable at runtime as an unchecked exception and surfaces its
     * message unchanged.
     */
    @Test
    void isThrowableAtRuntime() {
        assertThatThrownBy(() -> {
            throw new FileStatusException("93", "unavailable");
        }).isInstanceOf(FileStatusException.class).hasMessage("unavailable");
    }
}
