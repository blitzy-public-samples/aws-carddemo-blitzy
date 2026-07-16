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
package com.aws.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.AccountNotFoundException;
import com.aws.carddemo.account.mapper.AccountMapper;
import com.aws.carddemo.account.repository.AccountRepository;

/**
 * Pure Mockito unit tests for {@link AccountService}, verifying the <em>orchestration</em> of the
 * account inquiry and update use cases in isolation from Spring, JPA, and any database.
 *
 * <p>The service's three collaborators &mdash; {@link AccountRepository}, {@link AccountMapper},
 * and {@link AccountValidator} &mdash; are replaced with Mockito mocks and injected through the
 * single 3-argument constructor via {@link InjectMocks}. Mocking the validator and mapper
 * deliberately bypasses their real behavior so these tests focus solely on the control flow of the
 * service (the sequencing, the not-found handling, the optimistic-concurrency decision, and the
 * write/no-write outcome). Real validation parity is covered by {@code AccountValidatorTest} and
 * formatting parity by {@code AccountMapperTest}; this class does not duplicate them.</p>
 *
 * <h2>Legacy behavior proven (extraction-by-reference; the COBOL is a read-only oracle)</h2>
 * <ul>
 *   <li><strong>Account inquiry ({@code CAVW}).</strong> The found and not-found paths of
 *       {@link AccountService#getAccount(String)} reproduce {@code 9300-GETACCTDATA-BYACCT} of
 *       {@code app/cbl/COACTVWC.cbl}: the {@code DFHRESP(NORMAL)} branch returns the record while the
 *       {@code DFHRESP(NOTFND)} branch (L786&ndash;L807) surfaces a not-found outcome, mapped here to
 *       {@link AccountNotFoundException} (HTTP&nbsp;404).</li>
 *   <li><strong>Account update ({@code CAUP}).</strong> The success and conflict paths of
 *       {@link AccountService#updateAccount(String, AccountUpdateRequest)} reproduce the
 *       {@code COACTUPC.cbl} write flow, in particular the before-image comparison of
 *       {@code 9700-CHECK-CHANGE-IN-REC} (L4109&ndash;L4193): if the record changed under the user,
 *       the legacy program sets {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and skips the rewrite. The
 *       modern analog is a JPA {@code @Version} mismatch surfaced as
 *       {@link ObjectOptimisticLockingFailureException} (HTTP&nbsp;409); the "{@code save} never
 *       called" verification is exactly what proves the write was aborted.</li>
 * </ul>
 *
 * <p>Test-to-rule traceability (AAP&nbsp;&sect;0.6.5): "Not-found on read", "Concurrent-change
 * conflict", and "Identifier/group immutability".</p>
 *
 * <p><strong>Mockito strictness.</strong> {@link MockitoExtension} applies {@code STRICT_STUBS} by
 * default. Every test therefore stubs only the interactions its code path actually exercises, and no
 * shared stubs are placed in a {@code @BeforeEach}; the not-found and conflict tests stub only
 * {@code findById} because {@code save}/{@code toResponse}/{@code applyUpdate} are never reached.</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    /**
     * Canonical zero-padded 11-digit account key, mirroring seed record&nbsp;#1
     * ({@code 00000000001}) [app/data/ASCII/acctdata.txt]. Used as both the path id and the entity
     * key throughout the suite.
     */
    private static final String ACCOUNT_ID = "00000000001";

    /** Mocked persistence gateway; stands in for the {@code accounts} table. */
    @Mock
    AccountRepository repository;

    /** Mocked entity&harr;DTO mapper; its formatting behavior is intentionally not exercised here. */
    @Mock
    AccountMapper mapper;

    /**
     * Mocked business-rule validator. Its {@code void} methods do nothing by default, which bypasses
     * real validation so these tests isolate the service's orchestration.
     */
    @Mock
    AccountValidator validator;

    /** Class under test; Mockito injects the three mocks via the single 3-arg constructor. */
    @InjectMocks
    AccountService service;

    // ------------------------------------------------------------------
    // getAccount — legacy COACTVWC 9300-GETACCTDATA-BYACCT
    // AAP §0.6.5 "Not-found on read"
    // ------------------------------------------------------------------

    /**
     * Found path: a present row is read by key and handed to the mapper, whose projection is returned
     * unchanged. Reproduces the legacy {@code DFHRESP(NORMAL)} branch.
     */
    @Test
    void getAccount_whenRecordExists_returnsMappedResponse() {
        final Account account = accountWithVersion(0L);
        final AccountResponse response = new AccountResponse();
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(mapper.toResponse(account)).thenReturn(response);

        final AccountResponse result = service.getAccount(ACCOUNT_ID);

        // The service returns exactly what the mapper produced (no post-processing).
        assertThat(result).isSameAs(response);
        verify(repository).findById(ACCOUNT_ID);
    }

    /**
     * Not-found path: an empty {@link Optional} reproduces the legacy {@code DFHRESP(NOTFND)} branch
     * [app/cbl/COACTVWC.cbl:L786-L807] and yields {@link AccountNotFoundException} (HTTP&nbsp;404).
     *
     * <p>The message is asserted verbatim against the confirmed collaborator contract. The migrated
     * exception carries a deliberately generic, id-free message (AAP&nbsp;&sect;0.6.6 &mdash; the full
     * account number must never leak into logs or error payloads), so it does <em>not</em> interpolate
     * the account id and contains no {@code Resp:}/{@code Reas:} suffix from the legacy 3270 text.</p>
     */
    @Test
    void getAccount_whenRecordMissing_throwsAccountNotFound() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found in Acct Master file.");

        // The mapper is never reached when the record is absent (STRICT_STUBS: not stubbed above).
        verify(mapper, never()).toResponse(any());
    }

    // ------------------------------------------------------------------
    // updateAccount — legacy COACTUPC 9600-WRITE-PROCESSING / 9700-CHECK-CHANGE-IN-REC
    // AAP §0.6.5 "Concurrent-change conflict"
    // ------------------------------------------------------------------

    /**
     * Success path (matching version): the loaded entity's version equals the client's submitted
     * version, so the edits are applied and the record is rewritten. Verifies the full
     * validate&rarr;apply&rarr;save sequence and that the mapper's projection of the saved entity is
     * returned.
     */
    @Test
    void updateAccount_whenVersionMatches_appliesEditsAndSaves() {
        final AccountUpdateRequest request = requestWithVersion(0L);
        final Account account = accountWithVersion(0L);
        final AccountResponse response = new AccountResponse();
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        // save returns the same managed instance; Account uses identity equality so the matcher hits.
        when(repository.save(account)).thenReturn(account);
        when(mapper.toResponse(account)).thenReturn(response);

        final AccountResponse result = service.updateAccount(ACCOUNT_ID, request);

        assertThat(result).isSameAs(response);
        // The editable-field validation, the field copy, and the rewrite all occur exactly once.
        verify(validator).validate(request);
        verify(mapper).applyUpdate(request, account);
        verify(repository).save(account);
    }

    /**
     * Conflict path: the client submits a stale version (1) while the persisted record is at version
     * 0. The explicit before-image comparison rejects the update with
     * {@link ObjectOptimisticLockingFailureException}, the modern analog of the legacy
     * {@code 9700-CHECK-CHANGE-IN-REC} field-by-field check [app/cbl/COACTUPC.cbl:L4109-L4193].
     *
     * <p>Only the exception <em>type</em> is asserted: the HTTP&nbsp;409 wording
     * ("{@code Record updated by another user - please retry}") is owned by
     * {@code GlobalExceptionHandler}, not the service. The crux of the parity is that <strong>no
     * write occurs</strong> &mdash; verified by asserting {@code save} and {@code applyUpdate} are
     * never invoked (mirroring the legacy skip to {@code 9600-WRITE-PROCESSING-EXIT}).</p>
     */
    @Test
    void updateAccount_whenVersionStale_throwsOptimisticLockAndDoesNotWrite() {
        final AccountUpdateRequest request = requestWithVersion(1L); // client's stale version
        final Account account = accountWithVersion(0L);              // current DB version differs
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Write is aborted on a stale version — this is the before-image parity guarantee.
        verify(repository, never()).save(any());
        verify(mapper, never()).applyUpdate(any(), any());
    }

    /**
     * Not-found on update: an absent record reproduces the {@code READ ... UPDATE} miss and yields
     * {@link AccountNotFoundException} (HTTP&nbsp;404) before any concurrency check or write. The
     * id-free message is asserted verbatim (AAP&nbsp;&sect;0.6.6), and neither {@code applyUpdate} nor
     * {@code save} is invoked.
     */
    @Test
    void updateAccount_whenRecordMissing_throwsAccountNotFoundAndDoesNotWrite() {
        final AccountUpdateRequest request = requestWithVersion(0L);
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found in Acct Master file.");

        verify(repository, never()).save(any());
        verify(mapper, never()).applyUpdate(any(), any());
    }

    // ------------------------------------------------------------------
    // Identifier / group immutability (structural)
    // AAP §0.6.5 "Identifier/group immutability" + §0.7.2 read-only tightening
    // ------------------------------------------------------------------

    /**
     * Proves the read-only tightening structurally: {@link AccountUpdateRequest} exposes neither
     * {@code accountId} nor {@code groupId} accessors, so those fields can never be mutated through
     * the update endpoint (over-posting is impossible by construction). Per AAP&nbsp;&sect;2.2.3.2 the
     * account id and group id are display-only / PROTECTED; the account id for an update comes solely
     * from the URL path variable (validated via {@link AccountValidator#validateAccountId(String)}),
     * never from the request body.
     *
     * <p>Conversely, the {@code version} accessor <em>must</em> remain reachable so the optimistic-lock
     * token can round-trip from {@code AccountResponse} back into the next {@link AccountUpdateRequest}.
     * This is verified by reflection so no Spring context is required.</p>
     */
    @Test
    void accountUpdateRequest_structurallyOmitsIdentifierAndGroupId() {
        // No id/group getters or setters exist -> reflective lookup must fail.
        assertThatThrownBy(() -> AccountUpdateRequest.class.getMethod("getAccountId"))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> AccountUpdateRequest.class.getMethod("setAccountId", String.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> AccountUpdateRequest.class.getMethod("getGroupId"))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> AccountUpdateRequest.class.getMethod("setGroupId", String.class))
                .isInstanceOf(NoSuchMethodException.class);

        // The optimistic-lock token remains reachable so the version round-trip is possible.
        assertThatCode(() -> AccountUpdateRequest.class.getMethod("getVersion"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a minimal {@link Account} carrying only the fields the service's control flow touches:
     * the id (for parity with the path key) and the optimistic-lock version. The remaining columns are
     * irrelevant because the mapper and validator are mocked.
     *
     * @param version the optimistic-lock version to stamp on the entity (auto-boxed to {@link Long})
     * @return a new {@link Account} with {@code accountId} and {@code version} set
     */
    private Account accountWithVersion(final long version) {
        final Account account = new Account();
        account.setAccountId(ACCOUNT_ID);
        account.setVersion(version);
        return account;
    }

    /**
     * Builds a minimal {@link AccountUpdateRequest} carrying only the optimistic-lock version the
     * service compares against the loaded entity. All editable fields are left null because the
     * mocked {@link AccountValidator} and {@link AccountMapper} never inspect them.
     *
     * @param version the client's last-seen version to submit (auto-boxed to {@link Long})
     * @return a new {@link AccountUpdateRequest} with only {@code version} set
     */
    private AccountUpdateRequest requestWithVersion(final long version) {
        final AccountUpdateRequest request = new AccountUpdateRequest();
        request.setVersion(version);
        return request;
    }
}
