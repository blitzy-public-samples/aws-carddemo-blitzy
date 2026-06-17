package com.carddemo.mapper;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.dto.AccountResponse;
import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;

/**
 * Stateless, hand-written mapper that flattens the {@link Account} JPA entity (joined with its owning
 * {@link Customer}) into the outbound {@link AccountResponse}, and that splits an inbound
 * {@link AccountUpdateRequest} back across the {@link Account} and {@link Customer} entities.
 *
 * <h2>Role in the layered architecture</h2>
 * <p>This component sits on the DTO / mapper boundary between the persistence layer
 * ({@code Account} + {@code Customer} entities) and the REST API contract
 * ({@code AccountResponse}, {@code AccountUpdateRequest}). The {@code AccountController} /
 * {@code AccountService} pair depends on it to translate between the two representations; it holds no
 * business logic of its own. Its only collaborator is the constructor-injected {@link CustomerMapper},
 * which owns the two customer-specific concerns (SSN masking and customer-field update apply); because
 * neither mapper carries mutable state, the single shared {@code @Component} instance is thread-safe.</p>
 *
 * <h2>Legacy lineage</h2>
 * <p>The mapping reproduces the field movements performed by the CICS online account programs
 * {@code COACTVWC} (account view &rarr; {@link #toAccountResponse(Account, Customer)}) and
 * {@code COACTUPC} (account maintenance &rarr; {@link #applyUpdate(AccountUpdateRequest, Account, Customer)}).
 * Those programs present a <em>combined</em> account&nbsp;+&nbsp;customer panel: given an account they
 * resolve the owning customer through the card cross-reference ({@code CARDXREF}) and then read /
 * edit both the {@code ACCOUNT} record (copybook {@code app/cpy/CVACT01Y.cpy}, RECLN&nbsp;300) and the
 * {@code CUSTOMER} record (copybook {@code app/cpy/CVCUS01Y.cpy}, RECLN&nbsp;500) on one 3270 screen.
 * Because the target system exposes no standalone customer endpoint, that join is <strong>flattened</strong>
 * into the single {@link AccountResponse} rather than split into a nested customer DTO (AAP&nbsp;&sect;0.4.1.1
 * / &sect;0.4.1.4, &sect;0.3.2).</p>
 *
 * <h2>Security boundary &mdash; SSN suppression (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <p>The customer Social Security Number ({@code CUST-SSN PIC 9(09)}) is persisted in full on the
 * {@link Customer} entity but <strong>MUST NEVER be serialized to a client nor written to logs in its
 * entirety</strong>. This mapper enforces that rule by sourcing the response's {@code ssnLastFour}
 * component <em>exclusively</em> through {@link CustomerMapper#maskSsnLastFour(String)} &mdash; the single
 * sanctioned outward SSN path, which yields at most the trailing four digits. {@code AccountResponse}
 * declares no full-SSN component by design, so a complete SSN can never leave this boundary.</p>
 *
 * <h2>Optimistic-locking round-trip (AAP &sect;0.6.6)</h2>
 * <p>{@link #toAccountResponse(Account, Customer)} copies the JPA {@code @Version} token of the
 * {@link Account} into {@link AccountResponse#version()} so the client can echo it back on
 * {@code PUT /accounts/{id}}; the service layer compares the echoed value against the managed version to
 * detect a concurrent modification and surface it as HTTP&nbsp;409, reproducing the legacy
 * {@code COACTUPC} {@code READ ... UPDATE} / {@code REWRITE} conflict guard. Crucially,
 * {@link #applyUpdate(AccountUpdateRequest, Account, Customer)} never writes the {@code version} field
 * &mdash; the optimistic-lock comparison is a service concern and the token's lifecycle belongs to
 * Hibernate.</p>
 *
 * <h2>Immutable keys</h2>
 * <p>The account identifier ({@code ACCT-ID}) and the customer identifier ({@code CUST-ID}) are assigned
 * natural keys resolved by the service through the card cross-reference; they are immutable attributes.
 * The update path therefore never invokes {@code Account.setAcctId(...)} nor mutates {@code custId}
 * ({@link AccountUpdateRequest} carries neither field), exactly as in {@code COACTUPC}.</p>
 *
 * <p>This class is implemented as plain, explicit Java (no MapStruct, ModelMapper, or Lombok) so the
 * SSN-suppression, version, and immutability guarantees are auditable by reading the source directly.</p>
 *
 * @see Account
 * @see Customer
 * @see AccountResponse
 * @see AccountUpdateRequest
 * @see CustomerMapper
 */
@Component
public class AccountMapper {

    /**
     * Sibling mapper that owns the customer-specific concerns delegated from this mapper: SSN last-four
     * masking ({@link CustomerMapper#maskSsnLastFour(String)}) and the customer half of an account
     * update ({@link CustomerMapper#applyCustomerUpdate(AccountUpdateRequest, Customer)}).
     */
    private final CustomerMapper customerMapper;

    /**
     * Creates the mapper with its required {@link CustomerMapper} collaborator. As this class declares a
     * single constructor, Spring auto-wires the dependency without an explicit {@code @Autowired}
     * annotation.
     *
     * @param customerMapper the customer mapper used for SSN masking and customer-side update apply;
     *                       supplied by the Spring container
     */
    public AccountMapper(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    /**
     * Flattens a persisted {@link Account} and its owning {@link Customer} into the immutable
     * {@link AccountResponse} returned by the account-view endpoint ({@code GET /accounts/{id}}).
     *
     * <p>The arguments are passed positionally in the <strong>exact</strong> canonical-constructor order
     * declared by the {@code AccountResponse} record: the twelve account components first (copybook
     * {@code CVACT01Y}), then the eighteen customer components ({@code CVCUS01Y} subset), and finally the
     * optimistic-lock {@code version} token. Argument order is load-bearing &mdash; a record has a single
     * canonical constructor &mdash; so this method mirrors that declaration line-for-line.</p>
     *
     * <p><strong>SSN suppression (AAP &sect;0.6.8 / &sect;0.7.1):</strong> the {@code ssnLastFour}
     * component is sourced <em>only</em> from {@link CustomerMapper#maskSsnLastFour(String)}; the raw
     * SSN value is read exclusively as the argument to that masking call and is never placed directly
     * into the response.</p>
     *
     * <p><strong>Optimistic locking (AAP &sect;0.6.6):</strong> {@code version} is taken verbatim from
     * {@link Account#getVersion()} so the client can echo it on the subsequent update.</p>
     *
     * <p><strong>Null policy.</strong> An {@code account} is required by the view contract: when it is
     * {@code null} this method returns {@code null} (consistent with the sibling mappers' "null in &rarr;
     * null out" convention). The {@code customer}, by contrast, may legitimately be {@code null} if the
     * card cross-reference does not resolve an owner; in that case every customer-half component
     * (including {@code ssnLastFour}) is rendered as {@code null} while the account-half components and
     * the {@code version} token are still populated. The normal path supplies both arguments.</p>
     *
     * @param account the source account entity; when {@code null} the method returns {@code null}
     * @param customer the owning customer entity; may be {@code null} (customer-half components become
     *                {@code null})
     * @return a fully-populated {@link AccountResponse}, or {@code null} when {@code account} is
     *         {@code null}
     */
    public AccountResponse toAccountResponse(Account account, Customer customer) {
        if (account == null) {
            return null;
        }

        // Customer-half values resolved null-safely so a missing (unresolved) customer yields null
        // components rather than a NullPointerException. The SSN is touched ONLY inside the masking
        // call below — the full value never reaches the response (AAP §0.6.8 / §0.7.1).
        final boolean hasCustomer = customer != null;
        final Long customerId = hasCustomer ? customer.getCustId() : null;
        final String firstName = hasCustomer ? customer.getFirstName() : null;
        final String middleName = hasCustomer ? customer.getMiddleName() : null;
        final String lastName = hasCustomer ? customer.getLastName() : null;
        final String addressLine1 = hasCustomer ? customer.getAddrLine1() : null;
        final String addressLine2 = hasCustomer ? customer.getAddrLine2() : null;
        final String addressLine3 = hasCustomer ? customer.getAddrLine3() : null;
        final String stateCode = hasCustomer ? customer.getAddrStateCd() : null;
        final String countryCode = hasCustomer ? customer.getAddrCountryCd() : null;
        final String customerZip = hasCustomer ? customer.getAddrZip() : null;
        final String phoneNumber1 = hasCustomer ? customer.getPhoneNum1() : null;
        final String phoneNumber2 = hasCustomer ? customer.getPhoneNum2() : null;
        final String ssnLastFour = hasCustomer ? customerMapper.maskSsnLastFour(customer.getSsn()) : null;
        final String governmentIssuedId = hasCustomer ? customer.getGovtIssuedId() : null;
        final LocalDate dateOfBirth = hasCustomer ? customer.getDob() : null;
        final String eftAccountId = hasCustomer ? customer.getEftAccountId() : null;
        final String primaryCardHolderIndicator = hasCustomer ? customer.getPriCardHolderInd() : null;
        final Integer ficoScore = hasCustomer ? customer.getFicoCreditScore() : null;

        return new AccountResponse(
                // ---- Account half (CVACT01Y) ----
                account.getAcctId(),            // accountId          ACCT-ID                 PIC 9(11)
                account.getActiveStatus(),      // activeStatus       ACCT-ACTIVE-STATUS      PIC X(01)
                account.getCurrBal(),           // currentBalance     ACCT-CURR-BAL           PIC S9(10)V99
                account.getCreditLimit(),       // creditLimit        ACCT-CREDIT-LIMIT       PIC S9(10)V99
                account.getCashCreditLimit(),   // cashCreditLimit    ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99
                account.getOpenDate(),          // openDate           ACCT-OPEN-DATE          PIC X(10)
                account.getExpirationDate(),    // expirationDate     ACCT-EXPIRAION-DATE     PIC X(10)
                account.getReissueDate(),       // reissueDate        ACCT-REISSUE-DATE       PIC X(10)
                account.getCurrCycCredit(),     // currentCycleCredit ACCT-CURR-CYC-CREDIT    PIC S9(10)V99
                account.getCurrCycDebit(),      // currentCycleDebit  ACCT-CURR-CYC-DEBIT     PIC S9(10)V99
                account.getAddrZip(),           // accountAddressZip  ACCT-ADDR-ZIP           PIC X(10)
                account.getGroupId(),           // accountGroupId     ACCT-GROUP-ID           PIC X(10)

                // ---- Customer half (CVCUS01Y subset; SSN masked) ----
                customerId,                     // customerId         CUST-ID                 PIC 9(09)
                firstName,                      // firstName          CUST-FIRST-NAME         PIC X(25)
                middleName,                     // middleName         CUST-MIDDLE-NAME        PIC X(25)
                lastName,                       // lastName           CUST-LAST-NAME          PIC X(25)
                addressLine1,                   // addressLine1       CUST-ADDR-LINE-1        PIC X(50)
                addressLine2,                   // addressLine2       CUST-ADDR-LINE-2        PIC X(50)
                addressLine3,                   // addressLine3       CUST-ADDR-LINE-3        PIC X(50)
                stateCode,                      // stateCode          CUST-ADDR-STATE-CD      PIC X(02)
                countryCode,                    // countryCode        CUST-ADDR-COUNTRY-CD    PIC X(03)
                customerZip,                    // customerZip        CUST-ADDR-ZIP           PIC X(10)
                phoneNumber1,                   // phoneNumber1       CUST-PHONE-NUM-1        PIC X(15)
                phoneNumber2,                   // phoneNumber2       CUST-PHONE-NUM-2        PIC X(15)
                ssnLastFour,                    // ssnLastFour        CUST-SSN -> LAST 4 ONLY (masked)
                governmentIssuedId,             // governmentIssuedId CUST-GOVT-ISSUED-ID     PIC X(20)
                dateOfBirth,                    // dateOfBirth        CUST-DOB-YYYY-MM-DD     PIC X(10)
                eftAccountId,                   // eftAccountId       CUST-EFT-ACCOUNT-ID     PIC X(10)
                primaryCardHolderIndicator,     // primaryCardHolderIndicator CUST-PRI-CARD-HOLDER-IND PIC X(01)
                ficoScore,                      // ficoScore          CUST-FICO-CREDIT-SCORE  PIC 9(03)

                // ---- Optimistic-lock token ----
                account.getVersion());          // version            JPA @Version (echoed on PUT -> 409)
    }

    /**
     * Applies the editable fields of a combined account&nbsp;+&nbsp;customer {@link AccountUpdateRequest}
     * onto the managed {@link Account} and {@link Customer} entities, reproducing the maintenance
     * performed by the legacy {@code COACTUPC} transaction.
     *
     * <p>This mapper owns the <strong>account half</strong> of the update (the eleven editable
     * {@code CVACT01Y} fields below) and delegates the entire <strong>customer half</strong> &mdash;
     * including the SSN <em>input</em> &mdash; to
     * {@link CustomerMapper#applyCustomerUpdate(AccountUpdateRequest, Customer)} so that customer logic
     * lives in exactly one place.</p>
     *
     * <p><strong>Partial-update semantics.</strong> Each account field is applied only when its request
     * accessor returns a non-{@code null} value, matching the {@code PUT} contract and the policy used by
     * {@code CustomerMapper}: a field omitted from the JSON body is left unchanged rather than overwritten
     * with {@code null}.</p>
     *
     * <p><strong>Untouched by design.</strong> The account primary key ({@code acctId}) and the customer
     * primary key ({@code custId}) are immutable and are never set here. The JPA {@code @Version} token is
     * <em>also</em> never written by this mapper: the optimistic-lock comparison of {@code req.version()}
     * against {@link Account#getVersion()} is a service-layer concern (HTTP&nbsp;409 on mismatch,
     * AAP&nbsp;&sect;0.6.6), and the token's increment lifecycle belongs to Hibernate.</p>
     *
     * <p><strong>Null safety.</strong> The account-side copy runs only when both {@code req} and
     * {@code account} are non-{@code null}; the customer-side delegate is itself a safe no-op on
     * {@code null} arguments, so this method can never originate a {@link NullPointerException}.</p>
     *
     * @param req the inbound account-update request carrying the editable account and customer fields;
     *           a {@code null} request applies no changes
     * @param account the managed account entity to mutate in place; a {@code null} account skips the
     *               account-side apply
     * @param customer the managed customer entity to mutate in place (handled by the delegate); may be
     *                {@code null}
     */
    public void applyUpdate(AccountUpdateRequest req, Account account, Customer customer) {
        // 1) Account-side editable fields (owned by this mapper). Each applied only when non-null so a
        //    partial PUT leaves omitted fields unchanged. acctId and version are intentionally untouched.
        if (req != null && account != null) {
            if (req.activeStatus() != null) {
                account.setActiveStatus(req.activeStatus());            // ACCT-ACTIVE-STATUS  PIC X(01)
            }
            if (req.currentBalance() != null) {
                account.setCurrBal(req.currentBalance());               // ACCT-CURR-BAL       PIC S9(10)V99
            }
            if (req.creditLimit() != null) {
                account.setCreditLimit(req.creditLimit());              // ACCT-CREDIT-LIMIT   PIC S9(10)V99
            }
            if (req.cashCreditLimit() != null) {
                account.setCashCreditLimit(req.cashCreditLimit());      // ACCT-CASH-CREDIT-LIMIT S9(10)V99
            }
            if (req.currentCycleCredit() != null) {
                account.setCurrCycCredit(req.currentCycleCredit());     // ACCT-CURR-CYC-CREDIT S9(10)V99
            }
            if (req.currentCycleDebit() != null) {
                account.setCurrCycDebit(req.currentCycleDebit());       // ACCT-CURR-CYC-DEBIT  S9(10)V99
            }
            if (req.openDate() != null) {
                account.setOpenDate(req.openDate());                    // ACCT-OPEN-DATE      PIC X(10)
            }
            if (req.expirationDate() != null) {
                account.setExpirationDate(req.expirationDate());        // ACCT-EXPIRAION-DATE PIC X(10)
            }
            if (req.reissueDate() != null) {
                account.setReissueDate(req.reissueDate());              // ACCT-REISSUE-DATE   PIC X(10)
            }
            if (req.accountAddressZip() != null) {
                account.setAddrZip(req.accountAddressZip());            // ACCT-ADDR-ZIP       PIC X(10)
            }
            if (req.accountGroupId() != null) {
                account.setGroupId(req.accountGroupId());               // ACCT-GROUP-ID       PIC X(10)
            }
            // NOT applied: acctId (immutable key) and version (Hibernate-managed @Version; the 409
            // comparison is performed by the service, never overwritten here — AAP §0.6.6).
        }

        // 2) Customer-side editable fields (including SSN input) — delegated so the logic lives once.
        //    The delegate is null-safe for both arguments.
        customerMapper.applyCustomerUpdate(req, customer);
    }
}
