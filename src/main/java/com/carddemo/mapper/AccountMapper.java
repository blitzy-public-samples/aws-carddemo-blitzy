package com.carddemo.mapper;

import java.time.LocalDate;
import java.util.List;

import com.carddemo.dto.account.AccountDto;
import com.carddemo.entity.Account;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.DateConversionUtil;

import org.springframework.stereotype.Component;

/**
 * Hand-coded mapper between the {@link Account} JPA entity and the account REST
 * DTO ({@link AccountDto}). Used by {@code AccountController} (the
 * {@code GET /api/accounts/{acctId}} view and {@code PUT /api/accounts/{acctId}}
 * update endpoints) and {@code AccountService}, and indirectly by batch jobs that
 * surface account information.
 *
 * <p>This class is a Spring {@code @Component} so it can be constructor-injected
 * into its consumers (PR-29: constructor injection only, no field injection). It
 * carries no injectable dependencies — the default constructor is sufficient —
 * and therefore needs no Lombok {@code @RequiredArgsConstructor}. The class is
 * intentionally NOT {@code final} so Spring may create a CGLIB proxy if required.</p>
 *
 * <p><strong>Money normalization (PR-16 — CRITICAL).</strong> All five monetary
 * {@link java.math.BigDecimal} fields ({@code currBal}, {@code creditLimit},
 * {@code cashCreditLimit}, {@code currCycCredit}, {@code currCycDebit}) are
 * normalized to scale 2 with {@link java.math.RoundingMode#HALF_UP} via
 * {@link BigDecimalUtil#ensureScaleTwo(java.math.BigDecimal)} on EVERY mapping
 * direction. {@code ensureScaleTwo} is null-safe: a {@code null} monetary value
 * becomes {@code 0.00} (scale 2). This guards against scale drift introduced by
 * JSON deserialization of inbound REST payloads (which may produce scale 0 or
 * scale &gt; 2), independent of the entity's {@code @Column(precision, scale)}
 * guarantees. {@code float}/{@code double} are forbidden for any monetary value.</p>
 *
 * <p><strong>Date conversion (AAP &sect;0.4.1.7, PR-14).</strong> The three date
 * fields differ in type across the boundary: the {@link Account} entity persists
 * {@code openDate}, {@code expirationDate}, and {@code reissueDate} as
 * {@link LocalDate} (SQL {@code DATE} columns), whereas {@link AccountDto} carries
 * them as {@link String} in ISO {@code yyyy-MM-dd} form (matching the COBOL
 * {@code PIC X(10)} external shape). Outbound, the {@link LocalDate} is rendered
 * with the shared {@link DateConversionUtil#ISO_DATE_FORMATTER} ({@code yyyy-MM-dd});
 * inbound, the string is parsed with that same shared formatter. Both directions
 * are {@code null}-safe (see {@link #formatDate(LocalDate)} and
 * {@link #parseDate(String)}). Routing through the centralized
 * {@code DateConversionUtil} formatter (rather than ad-hoc {@link LocalDate#toString()} /
 * {@link LocalDate#parse(CharSequence)}) keeps all CardDemo date conversion behavior
 * in one place; because that formatter's pattern is exactly the ISO
 * {@code yyyy-MM-dd} shape already used by the COBOL/DTO external format, the
 * rendered and parsed values are unchanged. A malformed inbound date that bypassed
 * DTO {@code @Pattern} validation surfaces as a
 * {@link java.time.format.DateTimeParseException}, handled at the REST boundary.
 * Per PR-14 the COBOL field {@code ACCT-EXPIRAION-DATE} [sic — misspelled in the
 * copybook] is normalized to the correctly spelled Java field
 * {@code expirationDate}; no additional translation is performed at the mapper
 * layer.</p>
 *
 * <p><strong>Exposed surface (PR-13, PR-22).</strong> Exactly the 12 user fields
 * from the 300-byte {@code ACCOUNT-RECORD} are mapped; the trailing COBOL
 * {@code FILLER PIC X(178)} carries no business meaning and is NOT exposed. The
 * entity's {@code @Version} optimistic-locking field is intentionally NOT exposed
 * in the DTO (PR-22) — clients never see version numbers; JPA manages optimistic
 * concurrency transparently and a conflict surfaces as
 * {@code OptimisticLockException} (HTTP 409). The Spring Data audit fields
 * ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}) are
 * server-controlled and also NOT exposed; they are populated by the JPA
 * {@code AuditingEntityListener}.</p>
 *
 * <p>Reference COBOL source: {@code app/cpy/CVACT01Y.cpy} (300-byte
 * {@code ACCOUNT-RECORD}, CardDemo_v1.0-15-g27d6c6f-68); account view/update flows
 * {@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl}.</p>
 *
 * @see com.carddemo.entity.Account
 * @see com.carddemo.dto.account.AccountDto
 * @see com.carddemo.util.BigDecimalUtil
 */
@Component
public class AccountMapper {

    /**
     * Converts an {@link Account} entity to an {@link AccountDto} for outbound REST
     * responses.
     *
     * <p>All 5 monetary {@link java.math.BigDecimal} fields are normalized to scale 2
     * with {@link java.math.RoundingMode#HALF_UP} via
     * {@link BigDecimalUtil#ensureScaleTwo(java.math.BigDecimal)} (PR-16); a
     * {@code null} monetary value becomes {@code 0.00}. The three
     * {@link LocalDate} date fields are rendered to ISO {@code yyyy-MM-dd} strings
     * via {@link #formatDate(LocalDate)} ({@code null}-safe).</p>
     *
     * <p>The {@code @Version} field and audit fields ({@code createdAt},
     * {@code updatedAt}, {@code createdBy}, {@code updatedBy}) are intentionally NOT
     * exposed in the DTO (PR-22).</p>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param account the {@code Account} entity (may be {@code null})
     * @return an {@code AccountDto} with scale-2 monetary fields and ISO date
     *         strings, or {@code null} if the input is {@code null}
     */
    public AccountDto toDto(Account account) {
        if (account == null) {
            return null;
        }
        return AccountDto.builder()
                .acctId(account.getAcctId())
                .activeStatus(account.getActiveStatus())
                .currBal(BigDecimalUtil.ensureScaleTwo(account.getCurrBal()))
                .creditLimit(BigDecimalUtil.ensureScaleTwo(account.getCreditLimit()))
                .cashCreditLimit(BigDecimalUtil.ensureScaleTwo(account.getCashCreditLimit()))
                .openDate(formatDate(account.getOpenDate()))
                .expirationDate(formatDate(account.getExpirationDate()))
                .reissueDate(formatDate(account.getReissueDate()))
                .currCycCredit(BigDecimalUtil.ensureScaleTwo(account.getCurrCycCredit()))
                .currCycDebit(BigDecimalUtil.ensureScaleTwo(account.getCurrCycDebit()))
                .addrZip(account.getAddrZip())
                .groupId(account.getGroupId())
                .build();
    }

    /**
     * Builds a transient {@link Account} entity from an {@link AccountDto} for the
     * account create/update flows or for service-layer testing.
     *
     * <p>All 5 monetary fields are normalized to scale 2 with HALF_UP rounding via
     * {@link BigDecimalUtil#ensureScaleTwo(java.math.BigDecimal)} (PR-16). The three
     * ISO {@code yyyy-MM-dd} date strings are parsed to {@link LocalDate} via
     * {@link #parseDate(String)} ({@code null}-safe).</p>
     *
     * <p>The {@code version} field is NOT copied from the DTO (it is managed by JPA
     * optimistic locking, PR-22), and the audit fields ({@code createdAt},
     * {@code updatedAt}, {@code createdBy}, {@code updatedBy}) are NOT copied (they
     * are managed by the JPA {@code AuditingEntityListener}).</p>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param dto the {@code AccountDto} (may be {@code null})
     * @return a transient {@code Account} entity, or {@code null} if the input is
     *         {@code null}
     */
    public Account toEntity(AccountDto dto) {
        if (dto == null) {
            return null;
        }
        Account account = new Account();
        account.setAcctId(dto.getAcctId());
        account.setActiveStatus(dto.getActiveStatus());
        account.setCurrBal(BigDecimalUtil.ensureScaleTwo(dto.getCurrBal()));
        account.setCreditLimit(BigDecimalUtil.ensureScaleTwo(dto.getCreditLimit()));
        account.setCashCreditLimit(BigDecimalUtil.ensureScaleTwo(dto.getCashCreditLimit()));
        account.setOpenDate(parseDate(dto.getOpenDate()));
        account.setExpirationDate(parseDate(dto.getExpirationDate()));
        account.setReissueDate(parseDate(dto.getReissueDate()));
        account.setCurrCycCredit(BigDecimalUtil.ensureScaleTwo(dto.getCurrCycCredit()));
        account.setCurrCycDebit(BigDecimalUtil.ensureScaleTwo(dto.getCurrCycDebit()));
        account.setAddrZip(dto.getAddrZip());
        account.setGroupId(dto.getGroupId());
        return account;
    }

    /**
     * Applies a partial update from an {@link AccountDto} to an existing managed
     * {@link Account} entity for the {@code PUT /api/accounts/{acctId}} flow. The
     * entity is mutated in place (PATCH-like semantics) so JPA dirty-checking issues
     * the {@code UPDATE} within the active transaction.
     *
     * <p>All 5 monetary fields are normalized to scale 2 with HALF_UP rounding via
     * {@link BigDecimalUtil#ensureScaleTwo(java.math.BigDecimal)} (PR-16). The three
     * ISO {@code yyyy-MM-dd} date strings are parsed to {@link LocalDate} via
     * {@link #parseDate(String)} ({@code null}-safe).</p>
     *
     * <p>The {@code acctId} primary key is NOT updated (immutable). Server-controlled
     * fields ({@code version}, audit fields) are NOT modified.</p>
     *
     * @param dto      the source {@code AccountDto} carrying updated values (must not
     *                 be {@code null})
     * @param existing the existing managed {@code Account} entity to mutate (must not
     *                 be {@code null})
     * @throws NullPointerException if {@code dto} or {@code existing} is {@code null}
     */
    public void updateEntity(AccountDto dto, Account existing) {
        if (dto == null || existing == null) {
            throw new NullPointerException("dto and existing must not be null");
        }
        // acctId intentionally NOT updated (PK is immutable)
        existing.setActiveStatus(dto.getActiveStatus());
        existing.setCurrBal(BigDecimalUtil.ensureScaleTwo(dto.getCurrBal()));
        existing.setCreditLimit(BigDecimalUtil.ensureScaleTwo(dto.getCreditLimit()));
        existing.setCashCreditLimit(BigDecimalUtil.ensureScaleTwo(dto.getCashCreditLimit()));
        existing.setOpenDate(parseDate(dto.getOpenDate()));
        existing.setExpirationDate(parseDate(dto.getExpirationDate()));
        existing.setReissueDate(parseDate(dto.getReissueDate()));
        existing.setCurrCycCredit(BigDecimalUtil.ensureScaleTwo(dto.getCurrCycCredit()));
        existing.setCurrCycDebit(BigDecimalUtil.ensureScaleTwo(dto.getCurrCycDebit()));
        existing.setAddrZip(dto.getAddrZip());
        existing.setGroupId(dto.getGroupId());
    }

    /**
     * Converts a list of {@link Account} entities to a list of {@link AccountDto}
     * objects, preserving order. Each element is mapped via {@link #toDto(Account)},
     * so PR-16 scale normalization and ISO date rendering apply uniformly to every
     * entry.
     *
     * <p>Returns an empty, immutable list when the input is {@code null} or empty;
     * the result is never {@code null}.</p>
     *
     * @param accounts the list of {@code Account} entities (may be {@code null})
     * @return a new immutable list of {@code AccountDto} in input order (never
     *         {@code null})
     */
    public List<AccountDto> toDtoList(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Renders an entity {@link LocalDate} as the DTO's {@code String} representation
     * in ISO {@code yyyy-MM-dd} form (matching the COBOL {@code PIC X(10)} external
     * shape), using the shared {@link DateConversionUtil#ISO_DATE_FORMATTER} so that
     * all date formatting flows through the central migration utility. {@code null}-safe.
     *
     * @param date the entity date (may be {@code null})
     * @return the ISO {@code yyyy-MM-dd} string, or {@code null} if {@code date} is
     *         {@code null}
     */
    private static String formatDate(LocalDate date) {
        return date == null ? null : date.format(DateConversionUtil.ISO_DATE_FORMATTER);
    }

    /**
     * Parses the DTO's {@code String} date (ISO {@code yyyy-MM-dd}) into the entity
     * {@link LocalDate}, using the shared {@link DateConversionUtil#ISO_DATE_FORMATTER}
     * so that all date parsing flows through the central migration utility.
     * {@code null}-safe. A non-null, malformed value (one that bypassed the DTO's
     * {@code @Pattern} validation) raises {@link java.time.format.DateTimeParseException},
     * which is handled at the REST boundary.
     *
     * @param date the DTO date string (may be {@code null})
     * @return the parsed {@link LocalDate}, or {@code null} if {@code date} is
     *         {@code null}
     */
    private static LocalDate parseDate(String date) {
        return date == null ? null : LocalDate.parse(date, DateConversionUtil.ISO_DATE_FORMATTER);
    }
}
