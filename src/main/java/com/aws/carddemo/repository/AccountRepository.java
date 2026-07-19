package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} entity, replacing the legacy
 * VSAM {@code ACCTDAT} key-sequenced data set (KSDS) I/O that the mainframe application
 * performed through {@code EXEC CICS} file commands and COBOL {@code FILE SECTION} access.
 *
 * <p>Origin: legacy/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300); VSAM ACCTDAT; CSD DEFINE FILE(ACCTDAT).</p>
 *
 * <p>All access is keyed by {@code acctId} (COBOL {@code ACCT-ID PIC 9(11)}), the single
 * primary key of the account master. The inherited {@link JpaRepository} operations reproduce
 * the COBOL access paths exactly: random read by account id ({@code findById(Long)}),
 * read-update-rewrite of balances and cycle totals ({@code save(Account)}), and the sequential
 * account-print scan ({@code findAll(org.springframework.data.domain.Sort)} ordered by
 * {@code acctId}). No derived-query finders are declared, because the COBOL programs access
 * ACCTDAT only by its primary key; adding other finders would be feature expansion.</p>
 *
 * <p>Consumed by the online account view and update services
 * ({@code AccountViewService}, {@code AccountUpdateService}) and by the account-print,
 * interest-calculation and transaction-posting batch jobs
 * ({@code AccountPrintJobConfig}, {@code InterestCalcJobConfig},
 * {@code PostTransactionJobConfig}).</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
