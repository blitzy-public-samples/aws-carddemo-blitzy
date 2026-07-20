package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

/**
 * Mechanism test for review finding #14 (lost-update / {@code READ ... UPDATE} parity). Verifies by
 * reflection that every write-turn re-read finder used by the online read-modify-rewrite services
 * carries {@link Lock @Lock}{@code (}{@link LockModeType#PESSIMISTIC_WRITE}{@code )}, i.e. issues a
 * PostgreSQL {@code SELECT ... FOR UPDATE} row lock.
 *
 * <p><strong>COBOL oracle (AAP &sect;0.6.5, &sect;0.6.10).</strong> Every online update program in
 * the legacy system re-reads the record it is about to change with an exclusive record lock via
 * {@code EXEC CICS READ ... UPDATE} held until the {@code REWRITE}/{@code DELETE}:
 * {@code legacy/cbl/COACTUPC.cbl} (account then customer, lock order preserved),
 * {@code legacy/cbl/COCRDUPC.cbl} (card), {@code legacy/cbl/COBIL00C.cbl} (account),
 * {@code legacy/cbl/COUSR02C.cbl} and {@code legacy/cbl/COUSR03C.cbl} (user). The faithful Java
 * equivalent is a pessimistic write lock on the write-turn re-read; a plain {@code findById} at the
 * default {@code READ COMMITTED} isolation would <em>not</em> lock the row and would leave the
 * compare-then-write sequence exposed to the lost-update race that finding #14 flags.</p>
 *
 * <p><strong>Why a reflection test.</strong> The presence and mode of the lock is a static contract
 * of the repository method that must not silently regress (for example, being dropped during a
 * refactor back to a lock-free {@code findById}). This test pins that contract deterministically and
 * without a database; the end-to-end commit behaviour of these locked paths is covered by the
 * controller integration tests, and the concurrent lost-update proof (two threads racing a
 * read-modify-write) is covered by the dedicated concurrency suite (finding #15).</p>
 *
 * <p><strong>Origin:</strong> net-new test infrastructure with no COBOL ancestor; it supports the
 * concurrency-parity contract mandated by the AWS CardDemo migration
 * (COBOL/CICS/VSAM &rarr; Java 25 + Spring Boot), whose legacy sources are retained read-only under
 * {@code legacy/**}.</p>
 */
class PessimisticLockFinderTest {

    /**
     * Asserts a repository finder method exists and is annotated with a JPA pessimistic write lock.
     *
     * @param repository     the Spring Data repository interface declaring the finder
     * @param methodName     the finder method name (a locked write-turn re-read)
     * @param parameterType  the finder's single key parameter type
     */
    private static void assertPessimisticWriteLock(
            Class<?> repository, String methodName, Class<?> parameterType) {
        Method finder;
        try {
            finder = repository.getMethod(methodName, parameterType);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "Expected locked write-turn finder " + repository.getSimpleName() + "."
                            + methodName + "(" + parameterType.getSimpleName()
                            + ") to exist (review finding #14)",
                    e);
        }

        Lock lock = finder.getAnnotation(Lock.class);
        assertThat(lock)
                .as("%s.%s must be annotated @Lock so the write-turn re-read issues "
                        + "SELECT ... FOR UPDATE (COBOL EXEC CICS READ ... UPDATE parity, #14)",
                        repository.getSimpleName(), methodName)
                .isNotNull();
        assertThat(lock.value())
                .as("%s.%s must lock PESSIMISTIC_WRITE (exclusive row lock), not a weaker mode",
                        repository.getSimpleName(), methodName)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("#14 AccountRepository.findByIdForUpdate is a PESSIMISTIC_WRITE lock (COACTUPC/COBIL00C READ UPDATE)")
    void accountFinderLocksPessimisticWrite() {
        assertPessimisticWriteLock(AccountRepository.class, "findByIdForUpdate", Long.class);
    }

    @Test
    @DisplayName("#14 CustomerRepository.findByIdForUpdate is a PESSIMISTIC_WRITE lock (COACTUPC READ UPDATE)")
    void customerFinderLocksPessimisticWrite() {
        assertPessimisticWriteLock(CustomerRepository.class, "findByIdForUpdate", Long.class);
    }

    @Test
    @DisplayName("#14 CardRepository.findByIdForUpdate is a PESSIMISTIC_WRITE lock (COCRDUPC READ UPDATE)")
    void cardFinderLocksPessimisticWrite() {
        assertPessimisticWriteLock(CardRepository.class, "findByIdForUpdate", String.class);
    }

    @Test
    @DisplayName("#14 UserSecurityRepository.findByUsrIdForUpdate is a PESSIMISTIC_WRITE lock (COUSR02C/COUSR03C READ UPDATE)")
    void userSecurityFinderLocksPessimisticWrite() {
        assertPessimisticWriteLock(UserSecurityRepository.class, "findByUsrIdForUpdate", String.class);
    }
}
