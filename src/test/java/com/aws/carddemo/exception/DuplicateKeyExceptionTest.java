package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DuplicateKeyException}, the typed member of the AWS CardDemo FILE STATUS /
 * CICS {@code RESP} exception hierarchy that represents COBOL <em>FILE STATUS</em> {@code "22"}
 * (duplicate key) and the equivalent CICS <em>DUPREC</em>/<em>DUPKEY</em> condition.
 *
 * <p>These tests lock down the contract that downstream handlers depend on: the exception is a
 * {@link FileStatusException} (and therefore an unchecked {@link RuntimeException}); its
 * {@link DuplicateKeyException#FILE_STATUS FILE_STATUS} constant is exactly {@code "22"}; both
 * constructors tag the carried status with {@code "22"} while preserving message and (optionally)
 * cause; and its {@code serialVersionUID} is declared {@code 1L} so the zero-warning
 * {@code -Xlint:all} build succeeds.</p>
 *
 * <p><strong>Intentional naming collision.</strong> Spring Data ships its own
 * {@code org.springframework.dao.DuplicateKeyException}. Because this test lives in
 * {@code com.aws.carddemo.exception}, the simple name {@code DuplicateKeyException} resolves to
 * <em>our</em> class; Spring's type is referenced only by its fully-qualified name and is
 * deliberately never imported. {@link #isDistinctFromSpringDuplicateKeyException()} asserts the two
 * same-simple-name types are separate {@code Class} objects with disjoint hierarchies, which is the
 * unit-level guard that keeps the {@code GlobalExceptionHandler} FQN bridge honest: if anyone later
 * makes our type extend Spring's (or vice versa), that test fails fast.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it exercises {@link DuplicateKeyException} in isolation through its public API.
 * Referencing {@code org.springframework.dao.*} by FQN for {@code Class}-object comparisons does not
 * start a context.</p>
 *
 * <p>Origin oracle: CICS DFHRESP(DUPREC) add/write paths legacy/cbl/COTRN02C.cbl,
 * legacy/cbl/COUSR01C.cbl; FILE STATUS '22' duplicate-key. See &sect;0.6.5.</p>
 */
class DuplicateKeyExceptionTest {

    /**
     * Verifies the type sits on the CardDemo FILE STATUS hierarchy: its direct superclass is
     * {@link FileStatusException}, and an instance is therefore both a {@link FileStatusException}
     * and (transitively) an unchecked {@link RuntimeException}.
     */
    @Test
    void extendsFileStatusException() {
        assertThat(DuplicateKeyException.class.getSuperclass()).isEqualTo(FileStatusException.class);
        DuplicateKeyException ex = new DuplicateKeyException("dup");
        assertThat(ex).isInstanceOf(FileStatusException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies the public {@code FILE_STATUS} constant is exactly {@code "22"} &mdash; the VSAM
     * duplicate-key FILE STATUS code &mdash; so callers, the exception bridge, and tests reference it
     * symbolically instead of repeating a magic string.
     */
    @Test
    void fileStatusConstant_isTwentyTwo() {
        assertThat(DuplicateKeyException.FILE_STATUS).isEqualTo("22");
    }

    /**
     * Verifies the message-only constructor tags the exception with FILE STATUS {@code "22"}, stores
     * the supplied message, leaves the cause null, and reports the same value as the
     * {@code FILE_STATUS} constant.
     */
    @Test
    void messageConstructor_setsStatusAndMessage() {
        DuplicateKeyException ex = new DuplicateKeyException("record already exists");
        assertThat(ex.getFileStatus()).isEqualTo("22");
        assertThat(ex.getMessage()).isEqualTo("record already exists");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getFileStatus()).isEqualTo(DuplicateKeyException.FILE_STATUS);
    }

    /**
     * Verifies the cause-preserving constructor tags the exception with FILE STATUS {@code "22"},
     * stores the message, and preserves the exact originating cause instance. This overload lets
     * {@code GlobalExceptionHandler} wrap Spring's {@code org.springframework.dao.DuplicateKeyException}
     * or {@code DataIntegrityViolationException} without losing the original stack trace.
     */
    @Test
    void causeConstructor_preservesCauseAndStatus() {
        Throwable cause = new IllegalStateException("constraint violation");
        DuplicateKeyException ex = new DuplicateKeyException("dup", cause);
        assertThat(ex.getFileStatus()).isEqualTo("22");
        assertThat(ex.getMessage()).isEqualTo("dup");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. Required because the
     * exception is {@link java.io.Serializable} (via {@link RuntimeException}) and the build treats the
     * {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass} reads the declared value
     * without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        assertThat(ObjectStreamClass.lookup(DuplicateKeyException.class).getSerialVersionUID())
                .isEqualTo(1L);
    }

    /**
     * Verifies our {@code DuplicateKeyException} is intentionally distinct from Spring's
     * {@code org.springframework.dao.DuplicateKeyException}: the two same-simple-name types are
     * different {@code Class} objects, each sits on its own hierarchy (ours under
     * {@link FileStatusException}, Spring's under {@code org.springframework.dao.DataAccessException}),
     * and neither is assignable to the other. This documents that {@code GlobalExceptionHandler} must
     * distinguish them by fully-qualified name. Spring's type is referenced by FQN only (never
     * imported) so the simple name keeps resolving to our class.
     */
    @Test
    void isDistinctFromSpringDuplicateKeyException() {
        // Different Class objects: our domain type is not Spring's DAO type.
        assertThat(DuplicateKeyException.class)
                .isNotEqualTo(org.springframework.dao.DuplicateKeyException.class);
        // Each type sits on its own, independent hierarchy.
        assertThat(FileStatusException.class.isAssignableFrom(DuplicateKeyException.class)).isTrue();
        assertThat(org.springframework.dao.DataAccessException.class
                .isAssignableFrom(org.springframework.dao.DuplicateKeyException.class)).isTrue();
        // The two hierarchies do not overlap: neither type is assignable to the other.
        assertThat(org.springframework.dao.DuplicateKeyException.class
                .isAssignableFrom(DuplicateKeyException.class)).isFalse();
        assertThat(DuplicateKeyException.class
                .isAssignableFrom(org.springframework.dao.DuplicateKeyException.class)).isFalse();
    }
}
