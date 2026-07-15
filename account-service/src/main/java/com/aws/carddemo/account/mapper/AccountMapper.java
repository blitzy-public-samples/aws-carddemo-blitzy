/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;

/**
 * Bridges the {@link Account} JPA entity and the account DTOs, centralizing the
 * presentation-formatting rules originally expressed by the legacy view program
 * {@code COACTVWC.cbl} (format paragraph {@code 1200-SETUP-SCREEN-VARS}, which
 * performs straight {@code MOVE}s of the {@code ACCOUNT-RECORD} fields to the
 * {@code COACTVW.bms} output fields) and the {@code COACTVW.bms} map itself
 * (currency {@code PICOUT '+ZZZ,ZZZ,ZZZ.99'}, ISO {@code YYYY-MM-DD} dates).
 *
 * <p>In the legacy 3270 stack the currency edit ({@code +ZZZ,ZZZ,ZZZ.99}) and the
 * ISO date rendering were applied automatically by the BMS field {@code PICOUT}
 * attribute on the {@code MOVE}. The comma-grouping and always-leading-sign are
 * terminal display artifacts that have no place in a JSON numeric contract (a
 * JSON number cannot contain commas). What carries forward is the two-fraction-digit
 * fidelity and non-scientific rendering. Accordingly, this mapper's monetary
 * responsibility is scale normalization only: every amount is handled exclusively as
 * {@link BigDecimal} with a fixed scale of {@value #MONEY_SCALE} for exact decimal
 * precision (binary approximation numeric types are prohibited in the money path per
 * AAP &sect;0.6.2). Jackson serializes the amounts in plain notation via the global
 * {@code spring.jackson.generator.write-bigdecimal-as-plain=true} setting.
 *
 * <p>Dates are {@link LocalDate}. The {@link AccountResponse} carries a
 * {@code @JsonFormat(pattern = "yyyy-MM-dd")} annotation, so the read model renders
 * ISO {@code yyyy-MM-dd} on its own; {@link #toResponse(Account)} therefore copies
 * dates straight through. The {@link AccountUpdateRequest} intentionally carries its
 * three dates as {@link String} (so {@code AccountValidator} can strict-parse and
 * reject invalid dates such as {@code 2023-02-29} with legacy-parity messages), so
 * {@link #applyUpdate(AccountUpdateRequest, Account)} parses each request date
 * {@link String} into a {@link LocalDate} before persisting it. No business date
 * validation is performed here.
 *
 * <p>This class is a pure, stateless field-copier with no injected collaborators. It
 * is annotated {@link Component} so it can be injected into {@code AccountService}.
 * The read-only tightening documented in AAP &sect;0.7.2 is honored structurally:
 * {@link #applyUpdate(AccountUpdateRequest, Account)} never writes {@code accountId}
 * or {@code groupId}, and optimistic-lock {@code version} reconciliation is left to
 * the service layer.
 */
@Component
public class AccountMapper {

    /** Fixed monetary scale mirroring {@code PIC S9(10)V99} (two fraction digits). */
    private static final int MONEY_SCALE = 2;

    /** ISO date format shared with the response DTO and the request contract. */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Converts a persisted {@link Account} entity into its read-model
     * {@link AccountResponse}. This reproduces the legacy view "format paragraph"
     * ({@code COACTVWC.cbl 1200-SETUP-SCREEN-VARS}): every readable field is copied,
     * each monetary value is normalized to scale {@value #MONEY_SCALE}, the three
     * dates are copied straight (the DTO renders them ISO), and the optimistic-lock
     * version is echoed back so the client can round-trip it into a subsequent update.
     *
     * @param account the source entity; may be {@code null}
     * @return a fully populated {@link AccountResponse}, or {@code null} if
     *         {@code account} is {@code null}
     */
    public AccountResponse toResponse(Account account) {
        if (account == null) {
            return null;
        }
        AccountResponse response = new AccountResponse();
        response.setAccountId(account.getAccountId());
        response.setActiveStatus(account.getActiveStatus());
        response.setCurrentBalance(scale(account.getCurrentBalance()));
        response.setCreditLimit(scale(account.getCreditLimit()));
        response.setCashCreditLimit(scale(account.getCashCreditLimit()));
        response.setOpenDate(account.getOpenDate());
        response.setExpirationDate(account.getExpirationDate());
        response.setReissueDate(account.getReissueDate());
        response.setCurrentCycleCredit(scale(account.getCurrentCycleCredit()));
        response.setCurrentCycleDebit(scale(account.getCurrentCycleDebit()));
        response.setAddressZip(account.getAddressZip());
        response.setGroupId(account.getGroupId());
        response.setVersion(account.getVersion());
        return response;
    }

    /**
     * Applies ONLY the editable business fields from an {@link AccountUpdateRequest}
     * onto the target (managed) {@link Account} entity. Monetary values are normalized
     * to scale {@value #MONEY_SCALE}; the three request date strings are parsed to
     * {@link LocalDate}; the remaining string fields are copied straight.
     *
     * <p>By design this method never touches {@code accountId} or {@code groupId}
     * (read-only tightening, AAP &sect;0.7.2 &mdash; neither is even present on the
     * request DTO), and it never touches {@code version}: optimistic-lock version
     * reconciliation is the {@code AccountService}'s responsibility, preserving this
     * mapper's single-responsibility and the layered dependency direction. Both
     * arguments are null-guarded, so a {@code null} request or target is a no-op.
     *
     * @param request the update request carrying the editable fields; may be {@code null}
     * @param account the managed entity to mutate in place; may be {@code null}
     */
    public void applyUpdate(AccountUpdateRequest request, Account account) {
        if (request == null || account == null) {
            return;
        }
        account.setActiveStatus(request.getActiveStatus());
        account.setCurrentBalance(scale(request.getCurrentBalance()));
        account.setCreditLimit(scale(request.getCreditLimit()));
        account.setCashCreditLimit(scale(request.getCashCreditLimit()));
        account.setCurrentCycleCredit(scale(request.getCurrentCycleCredit()));
        account.setCurrentCycleDebit(scale(request.getCurrentCycleDebit()));
        account.setOpenDate(parseDate(request.getOpenDate()));
        account.setExpirationDate(parseDate(request.getExpirationDate()));
        account.setReissueDate(parseDate(request.getReissueDate()));
        account.setAddressZip(request.getAddressZip());
    }

    /**
     * Null-safe normalization of a monetary amount to the fixed scale
     * {@value #MONEY_SCALE}. A {@code null} input yields {@code null}; otherwise the
     * value is re-scaled with {@link RoundingMode#HALF_UP}, preserving sign and exact
     * decimal precision.
     *
     * @param value the amount to normalize; may be {@code null}
     * @return the amount at scale {@value #MONEY_SCALE}, or {@code null}
     */
    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Parses a pre-validated ISO {@code yyyy-MM-dd} date string into a
     * {@link LocalDate}. A {@code null} or blank input yields {@code null}. By the time
     * this runs, {@code AccountValidator} has already validated the string, so a parse
     * failure is not expected; using the shared ISO formatter keeps behavior consistent
     * with the response contract.
     *
     * @param value the ISO date string to parse; may be {@code null} or blank
     * @return the parsed {@link LocalDate}, or {@code null}
     */
    private LocalDate parseDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value.trim(), ISO_DATE);
    }
}
