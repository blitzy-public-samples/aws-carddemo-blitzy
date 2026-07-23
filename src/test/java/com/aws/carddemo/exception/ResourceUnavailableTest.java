package com.aws.carddemo.exception;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResourceUnavailable}, the typed subtype of {@link FileStatusException} that
 * represents the COBOL <em>FILE STATUS</em> {@code "93"} (resource not available / file not open)
 * code and the equivalent CICS {@code NOTOPEN} condition.
 *
 * <p>These tests lock down the small but load-bearing contract the rest of the migration relies on:
 * the class name is <strong>exactly</strong> {@code ResourceUnavailable} (never
 * {@code ResourceUnavailableException} or {@code ResourceNotAvailable}); it extends
 * {@link FileStatusException} and is therefore an unchecked {@link RuntimeException}, mirroring
 * Spring's {@code org.springframework.dao.DataAccessException} hierarchy; its
 * {@code FILE_STATUS} constant is the two-character string {@code "93"}; both constructors fix that
 * status while storing the supplied message (and, for the cause overload, the originating
 * throwable); and {@code serialVersionUID} is declared as {@code 1L} so the zero-warning
 * {@code -Xlint:all} / {@code failOnWarning} build succeeds.</p>
 *
 * <p>The exact class name and the {@code "93"} status are guarded here because the
 * {@code GlobalExceptionHandler} surfaces this type as HTTP <strong>503</strong> (Service
 * Unavailable) and bridges Spring's {@code DataAccessResourceFailureException} (and
 * {@code CannotGetJdbcConnectionException}) to it by fully-qualified name; drift in either the name
 * or the status code would silently break that downstream 503 mapping.</p>
 *
 * <p>This is a pure unit test. It starts no Spring context and touches no database, Docker, or
 * Testcontainers; it does not extend {@code AbstractPostgresIntegrationTest}. It exercises
 * {@link ResourceUnavailable} in isolation through its public API and reflective metadata only.</p>
 *
 * <p>Origin oracle: FILE STATUS {@code '93'} / CICS {@code NOTOPEN}; legacy/cbl/CBTRN02C.cbl
 * file-open guards (0300-DALYREJS-OPEN) &rarr; 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM. See
 * Technical Specification &sect;0.6.5.</p>
 */
class ResourceUnavailableTest {

    /**
     * Verifies the inheritance chain: the direct superclass is {@link FileStatusException} (so the
     * {@code '93'}/{@code NOTOPEN} path participates in the shared FILE STATUS exception hierarchy),
     * and an instance is-a {@link FileStatusException} and, transitively, an unchecked
     * {@link RuntimeException} &mdash; matching Spring's {@code DataAccessException} hierarchy so
     * callers are never forced to declare or catch it.
     */
    @Test
    void extendsFileStatusException() {
        assertThat(ResourceUnavailable.class.getSuperclass()).isEqualTo(FileStatusException.class);

        ResourceUnavailable ex = new ResourceUnavailable("closed");
        assertThat(ex).isInstanceOf(FileStatusException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies the exact class name (simple and fully qualified). The migration pins this name and
     * the {@code GlobalExceptionHandler} bridges Spring's {@code DataAccessResourceFailureException}
     * to this type by name, so any drift must fail the build.
     */
    @Test
    void hasExactSimpleName() {
        assertThat(ResourceUnavailable.class.getSimpleName()).isEqualTo("ResourceUnavailable");
        assertThat(ResourceUnavailable.class.getName())
                .isEqualTo("com.aws.carddemo.exception.ResourceUnavailable");
    }

    /**
     * Verifies the public {@code FILE_STATUS} constant is the two-character string {@code "93"} &mdash;
     * the COBOL FILE STATUS code for resource-not-available / file-not-open (CICS {@code NOTOPEN}).
     */
    @Test
    void fileStatusConstant_isNinetyThree() {
        assertThat(ResourceUnavailable.FILE_STATUS).isEqualTo("93");
    }

    /**
     * Verifies the single-argument constructor fixes the status to {@code "93"}, stores the message,
     * and leaves the cause {@code null}; the stored status equals the public {@code FILE_STATUS}
     * constant so the two never diverge.
     */
    @Test
    void messageConstructor_setsStatusAndMessage() {
        ResourceUnavailable ex = new ResourceUnavailable("file not open");
        assertThat(ex.getFileStatus()).isEqualTo("93");
        assertThat(ex.getMessage()).isEqualTo("file not open");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getFileStatus()).isEqualTo(ResourceUnavailable.FILE_STATUS);
    }

    /**
     * Verifies the cause-preserving constructor fixes the status to {@code "93"}, stores the message,
     * and retains the exact originating cause instance. This is the overload the
     * {@code GlobalExceptionHandler} uses to wrap a Spring {@code DataAccessResourceFailureException}
     * (for example connection-pool exhaustion) without losing the original stack trace.
     */
    @Test
    void causeConstructor_preservesCauseAndStatus() {
        Throwable cause = new IllegalStateException("connection pool exhausted");
        ResourceUnavailable ex = new ResourceUnavailable("resource unavailable", cause);
        assertThat(ex.getFileStatus()).isEqualTo("93");
        assertThat(ex.getMessage()).isEqualTo("resource unavailable");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Verifies the mandatory {@code serialVersionUID} is declared as {@code 1L}. It is required
     * because {@link RuntimeException} implements {@link java.io.Serializable} and the build treats
     * the {@code -Xlint:serial} warning as an error; {@link ObjectStreamClass} reads the declared
     * value without reflection or module-access concerns.
     */
    @Test
    void serialVersionUID_isDeclaredAsOne() {
        assertThat(ObjectStreamClass.lookup(ResourceUnavailable.class).getSerialVersionUID())
                .isEqualTo(1L);
    }
}
