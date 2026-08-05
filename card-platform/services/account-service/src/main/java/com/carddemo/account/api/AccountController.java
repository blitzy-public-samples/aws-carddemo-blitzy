package com.carddemo.account.api;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.cobol.PicClause;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * The account surface, replacing two Customer Information Control System (CICS) transactions.
 *
 * <p>{@code app/cbl/COACTVWC.cbl} runs under transaction {@code CAVW} and displays one account.
 * {@code app/cbl/COACTUPC.cbl} runs under transaction {@code CAUP} and updates one account and its
 * customer. Both are pseudo-conversational: each sends a mapset, ends, and resumes when the terminal
 * replies, carrying its state in a communication area. The two methods below take one request and
 * return one response, so no conversation state survives a call.
 *
 * <p>A read returns the account and nothing else, and this is a consequence of the source rather than
 * a simplification. {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} carries no customer
 * identifier, so the source reaches a customer only through the cross-reference record, at
 * {@code app/cbl/COACTVWC.cbl:L828-L829}. This service owns no cross-reference table, because the
 * card service owns that record, so a caller that wants the customer reads
 * {@code GET /customers/{customerId}} with an identifier it already holds. Inventing an
 * account-to-customer column here would create a relationship the source has never had.
 *
 * <p>An update submits whole blocks. Ten components of {@code CustomerDataRequest} and nine of
 * {@code AccountDataRequest} carry a mandatory-field edit, each reproducing one of the edits
 * {@code app/cbl/COACTUPC.cbl:L1470-L1676} performs, and those edits refuse an absent value exactly as
 * they refuse a blank one. So a block that is present has to be complete, which is the 3270 screen's own
 * contract: the screen was filled from the fetched record and submitted every field.
 *
 * <p>What a caller may omit is a whole block, or one of the components carrying no mandatory edit. This
 * class merges what arrives over what is stored, so an omitted account block leaves every account column
 * as stored and an omitted optional component keeps its own value. A component present in the body
 * replaces it, and an empty string clears it.
 *
 * <p>Four answers are possible for an update, and each one is a distinct outcome of the source.
 *
 * <ul>
 *   <li>{@code 200} when the pair was written, from {@code app/cbl/COACTUPC.cbl:L4066} and
 *       {@code :L4086}.</li>
 *   <li>{@code 200} carrying the no-change text when the submitted pair equalled the fetched pair,
 *       from {@code app/cbl/COACTUPC.cbl:L1463-L1467}. Nothing was written and no event was produced,
 *       and saying so is more useful than an empty success.</li>
 *   <li>{@code 409} when another writer changed a row first, from
 *       {@code app/cbl/COACTUPC.cbl:L3947-L3952}, or when a row could not be locked, from
 *       {@code :L3907-L3915}.</li>
 *   <li>{@code 422} when a field failed an edit, from {@code app/cbl/COACTUPC.cbl:L1470-L1676}, with
 *       the verbatim text in the problem document.</li>
 * </ul>
 *
 * <p>{@code 404} answers a request naming an account or a customer this service does not hold, which
 * the source reports as a screen message on a keyed read that missed.
 *
 * <p>No identifier and no submitted value reaches a log line from this class. An account identifier
 * does travel in the path, which an access log records, and that is the one identifier
 * {@code config/SecurityConfig} scopes a caller against, so a caller can only put an identifier there
 * that it is already entitled to read.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes both methods, written by hand so no
 * documentation generator joins the classpath.
 */
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {

    /** Writes the diagnostic lines this class emits, none carrying a submitted value. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /**
     * The width and the alphabet of an account identifier in a path.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. The leading zeros belong to the
     * value, because the source compares the identifier as text, so account fifty is
     * {@code 00000000050} and not {@code 50}.
     */
    static final String ACCOUNT_ID_PATTERN = "^[0-9]{" + PicClause.ACCT_ID_WIDTH + "}$";

    /** The text a path carrying anything but eleven digits is rejected with. */
    static final String ACCOUNT_ID_MESSAGE =
            "Account Id must be an 11 digit Number, zero padded on the left";

    /** The text a body naming no customer is rejected with. */
    static final String CUSTOMER_ID_REQUIRED_MESSAGE =
            "Customer Id must be supplied, because an account record carries none";

    /** The text a successful write answers with. */
    static final String UPDATE_APPLIED_MESSAGE = "Changes committed to database";

    /** Reads the account row, and locks it for the update path. */
    private final AccountRepository accounts;

    /** Reads the customer row the update path names. */
    private final CustomerRepository customers;

    /** Runs the edits, the concurrency check, both writes and the one event row. */
    private final AccountUpdateService accountUpdates;

    /**
     * Takes the two stores and the update service.
     *
     * @param accounts       store of the account master row
     * @param customers      store of the customer master row
     * @param accountUpdates the service that edits and writes one pair
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountController(AccountRepository accounts, CustomerRepository customers,
            AccountUpdateService accountUpdates) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must be present");
        this.customers = Objects.requireNonNull(customers, "customers must be present");
        this.accountUpdates =
                Objects.requireNonNull(accountUpdates, "accountUpdates must be present");
    }

    /**
     * Reads one account.
     *
     * <p>The keyed read reproduces {@code app/cbl/COACTVWC.cbl:L790-L800}, and a read that misses
     * answers {@code 404} where the source moves a text onto the screen.
     *
     * <p>The transaction is read-only, so no statement on this path can write and the database can
     * plan the read as one.
     *
     * @param accountId the account to read, eleven decimal digits
     * @return {@code 200} carrying the account, or {@code 404} when this service holds no such row
     */
    @GetMapping("/{accountId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> readAccount(
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MESSAGE)
            String accountId) {

        Optional<AccountEntity> stored = accounts.findByAccountId(accountId);
        if (stored.isEmpty()) {
            LOG.info("An account read found no row");
            return notFound();
        }
        return ResponseEntity.ok(viewOf(stored.get()));
    }

    /**
     * Updates one account and its customer, in one transaction, producing one event.
     *
     * <p>This method reads the stored pair and hands it to the service as the fetched copy, which is
     * what {@code app/cbl/COACTUPC.cbl} calls {@code ACUP-OLD-ACCT-DATA} and
     * {@code ACUP-OLD-CUST-DATA}. The service then re-reads both rows under a write lock and compares
     * them against that copy, so a writer that committed between this read and that lock is detected
     * and answered with {@code 409}. This reproduces the field-by-field comparison at
     * {@code app/cbl/COACTUPC.cbl:L4109-L4193} rather than adding a version column, because a version
     * column detects a different set of conflicts and would change the outcome.
     *
     * <p>The two reads and the service call share one transaction, so the fetched copy this method
     * builds and the locked rows the service reads come from one consistent view.
     *
     * <p>The path identifier wins over any identifier in the body. A body naming a different account
     * would let a caller entitled to one account write another, which is exactly what
     * {@code config/SecurityConfig} scopes the path against.
     *
     * @param accountId the account to update, eleven decimal digits
     * @param request   the submitted pair, validated before this method runs
     * @return {@code 200}, {@code 404}, {@code 409} or {@code 422} as described on this class
     */
    @PutMapping("/{accountId}")
    @Transactional(propagation = Propagation.REQUIRED)
    public ResponseEntity<?> updateAccount(
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MESSAGE)
            String accountId,
            @Valid @RequestBody AccountUpdateRequest request) {

        CustomerDataRequest customerData = request.customerData();
        if (customerData == null || customerData.customerId() == null
                || customerData.customerId().isBlank()) {
            LOG.info("Rejecting an account update that named no customer");
            return validationFailed(CUSTOMER_ID_REQUIRED_MESSAGE);
        }

        Optional<AccountEntity> fetchedAccount = accounts.findByAccountId(accountId);
        if (fetchedAccount.isEmpty()) {
            LOG.info("An account update found no account row");
            return notFound();
        }
        Optional<CustomerEntity> fetchedCustomer =
                customers.findByCustomerId(customerData.customerId());
        if (fetchedCustomer.isEmpty()) {
            LOG.info("An account update found no customer row");
            return notFound();
        }

        AccountEntity proposedAccount =
                proposedAccountOf(accountId, request.accountData(), fetchedAccount.get());
        CustomerEntity proposedCustomer =
                proposedCustomerOf(customerData, fetchedCustomer.get());

        EditResult verdict = accountUpdates.updateAccount(proposedAccount, proposedCustomer,
                copyOf(fetchedAccount.get()), copyOf(fetchedCustomer.get()));

        return render(verdict, accountId);
    }

    /**
     * Turns one verdict into the response this endpoint returns.
     *
     * <p>A passing verdict carrying no message wrote the pair. A passing verdict carrying one wrote
     * nothing and says why. A failing verdict is either a lost race or a failed edit, and the two
     * differ in status because they differ in what the caller should do next: retry the first, correct
     * the second.
     *
     * @param verdict   what the service answered
     * @param accountId the account the call named
     * @return the response
     */
    private ResponseEntity<?> render(EditResult verdict, String accountId) {
        if (verdict.valid()) {
            String message = verdict.message() == null ? UPDATE_APPLIED_MESSAGE : verdict.message();
            AccountView account = accounts.findByAccountId(accountId)
                    .map(AccountController::viewOf)
                    .orElse(null);
            LOG.info("An account update completed and answered its caller");
            return ResponseEntity.ok(new AccountUpdateResponse(message, account));
        }

        if (isLostRace(verdict.message())) {
            LOG.info("An account update lost a race against another writer");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiProblem.of(HttpStatus.CONFLICT.value(), ApiProblem.CONFLICT,
                            verdict.message()));
        }

        LOG.info("An account update failed one edit");
        return validationFailed(verdict.message());
    }

    /**
     * Reports whether a failing message names a lost race rather than a failed edit.
     *
     * <p>Three messages describe a lost race: the two lock failures at
     * {@code app/cbl/COACTUPC.cbl:L3907-L3915} and {@code :L3934-L3942}, and the changed-record
     * verdict at {@code :L3950-L3952}. Every other message describes a field a caller can correct.
     *
     * @param message the message the verdict carried
     * @return {@code true} when the caller should retry rather than correct
     */
    private static boolean isLostRace(String message) {
        return ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE.equals(message)
                || AccountUpdateService.lockFailureMessages().contains(message);
    }

    /**
     * Builds the proposed account from the submitted values over the stored ones.
     *
     * <p>A component the body omits, which arrives as {@code null}, keeps the stored value. A component
     * the body carries replaces it. An omitted block leaves every column of that record as stored, which
     * is how a caller updates the customer alone.
     *
     * <p>Nine of the ten components carry a mandatory-field edit, so within a block that is present only
     * {@code groupId} reaches the omitted branch. The branch still guards every component, because a
     * guard that holds for one field and not its neighbour is a guard a later change quietly breaks.
     *
     * <p>The identifier comes from the path and never from the body.
     *
     * @param accountId   the account the path named
     * @param submitted   the submitted account values, or {@code null} when the body carried none
     * @param stored      the stored row
     * @return a new entity carrying the merged values, leaving {@code stored} untouched
     */
    private static AccountEntity proposedAccountOf(String accountId, AccountDataRequest submitted,
            AccountEntity stored) {
        AccountEntity proposed = copyOf(stored);
        proposed.setAccountId(accountId);
        if (submitted == null) {
            return proposed;
        }

        if (submitted.activeStatus() != null) {
            proposed.setActiveStatus(submitted.activeStatus());
        }
        if (submitted.currentBalance() != null) {
            proposed.setCurrentBalance(money(submitted.currentBalance(),
                    PicClause.ACCT_CURR_BAL_SCALE, stored.getCurrentBalance()));
        }
        if (submitted.creditLimit() != null) {
            proposed.setCreditLimit(money(submitted.creditLimit(),
                    PicClause.ACCT_CREDIT_LIMIT_SCALE, stored.getCreditLimit()));
        }
        if (submitted.cashCreditLimit() != null) {
            proposed.setCashCreditLimit(money(submitted.cashCreditLimit(),
                    PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE, stored.getCashCreditLimit()));
        }
        if (submitted.currentCycleCredit() != null) {
            proposed.setCurrentCycleCredit(money(submitted.currentCycleCredit(),
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE, stored.getCurrentCycleCredit()));
        }
        if (submitted.currentCycleDebit() != null) {
            proposed.setCurrentCycleDebit(money(submitted.currentCycleDebit(),
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE, stored.getCurrentCycleDebit()));
        }
        if (submitted.openDate() != null) {
            proposed.setOpenDate(submitted.openDate());
        }
        if (submitted.expirationDate() != null) {
            proposed.setExpirationDate(submitted.expirationDate());
        }
        if (submitted.reissueDate() != null) {
            proposed.setReissueDate(submitted.reissueDate());
        }
        if (submitted.groupId() != null) {
            proposed.setGroupId(submitted.groupId());
        }
        return proposed;
    }

    /**
     * Builds the proposed customer from the submitted values over the stored ones.
     *
     * <p>The three Social Security parts are one column, so they are recombined only when the body
     * carries all three. A body carrying one part and not the others would otherwise write a number
     * two thirds of which came from the stored row, which is a value no caller asked for.
     *
     * @param submitted the submitted customer values
     * @param stored    the stored row
     * @return a new entity carrying the merged values, leaving {@code stored} untouched
     */
    private static CustomerEntity proposedCustomerOf(CustomerDataRequest submitted,
            CustomerEntity stored) {
        CustomerEntity proposed = copyOf(stored);
        proposed.setCustomerId(submitted.customerId());

        if (submitted.firstName() != null) {
            proposed.setFirstName(submitted.firstName());
        }
        if (submitted.middleName() != null) {
            proposed.setMiddleName(submitted.middleName());
        }
        if (submitted.lastName() != null) {
            proposed.setLastName(submitted.lastName());
        }
        if (submitted.addressLine1() != null) {
            proposed.setAddressLine1(submitted.addressLine1());
        }
        if (submitted.addressLine2() != null) {
            proposed.setAddressLine2(submitted.addressLine2());
        }
        if (submitted.addressCity() != null) {
            proposed.setAddressCity(submitted.addressCity());
        }
        if (submitted.addressStateCode() != null) {
            proposed.setAddressStateCode(submitted.addressStateCode());
        }
        if (submitted.addressCountryCode() != null) {
            proposed.setAddressCountryCode(submitted.addressCountryCode());
        }
        if (submitted.addressZip() != null) {
            proposed.setAddressZip(submitted.addressZip());
        }
        if (submitted.phoneNumber1() != null) {
            proposed.setPhoneNumber1(submitted.phoneNumber1());
        }
        if (submitted.phoneNumber2() != null) {
            proposed.setPhoneNumber2(submitted.phoneNumber2());
        }
        if (submitted.socialSecurityPart1() != null && submitted.socialSecurityPart2() != null
                && submitted.socialSecurityPart3() != null) {
            proposed.setSocialSecurityNumber(submitted.socialSecurityPart1()
                    + submitted.socialSecurityPart2() + submitted.socialSecurityPart3());
        }
        if (submitted.governmentIssuedId() != null) {
            proposed.setGovernmentIssuedId(submitted.governmentIssuedId());
        }
        if (submitted.dateOfBirth() != null) {
            proposed.setDateOfBirth(submitted.dateOfBirth());
        }
        if (submitted.eftAccountId() != null) {
            proposed.setEftAccountId(submitted.eftAccountId());
        }
        if (submitted.primaryCardHolderIndicator() != null) {
            proposed.setPrimaryCardHolderIndicator(submitted.primaryCardHolderIndicator());
        }
        if (submitted.ficoCreditScore() != null) {
            proposed.setFicoCreditScore(creditScore(submitted.ficoCreditScore(),
                    stored.getFicoCreditScore()));
        }
        return proposed;
    }

    /**
     * Copies one account row, so nothing this class builds is the row the persistence context manages.
     *
     * <p>A managed entity handed to the service as the fetched copy would be the same object the
     * service later mutates, and the comparison would then find every field equal however much
     * changed. The copy is what makes the comparison mean anything.
     *
     * @param source the row to copy
     * @return a detached copy carrying the same twelve values
     */
    private static AccountEntity copyOf(AccountEntity source) {
        AccountEntity copy = new AccountEntity();
        copy.setAccountId(source.getAccountId());
        copy.setActiveStatus(source.getActiveStatus());
        copy.setCurrentBalance(source.getCurrentBalance());
        copy.setCreditLimit(source.getCreditLimit());
        copy.setCashCreditLimit(source.getCashCreditLimit());
        copy.setOpenDate(source.getOpenDate());
        copy.setExpirationDate(source.getExpirationDate());
        copy.setReissueDate(source.getReissueDate());
        copy.setCurrentCycleCredit(source.getCurrentCycleCredit());
        copy.setCurrentCycleDebit(source.getCurrentCycleDebit());
        copy.setAddressZip(source.getAddressZip());
        copy.setGroupId(source.getGroupId());
        return copy;
    }

    /**
     * Copies one customer row, for the same reason {@link #copyOf(AccountEntity)} copies an account.
     *
     * @param source the row to copy
     * @return a detached copy carrying the same nineteen values
     */
    private static CustomerEntity copyOf(CustomerEntity source) {
        CustomerEntity copy = new CustomerEntity();
        copy.setCustomerId(source.getCustomerId());
        copy.setFirstName(source.getFirstName());
        copy.setMiddleName(source.getMiddleName());
        copy.setLastName(source.getLastName());
        copy.setAddressLine1(source.getAddressLine1());
        copy.setAddressLine2(source.getAddressLine2());
        copy.setAddressCity(source.getAddressCity());
        copy.setAddressStateCode(source.getAddressStateCode());
        copy.setAddressCountryCode(source.getAddressCountryCode());
        copy.setAddressZip(source.getAddressZip());
        copy.setPhoneNumber1(source.getPhoneNumber1());
        copy.setPhoneNumber2(source.getPhoneNumber2());
        copy.setSocialSecurityNumber(source.getSocialSecurityNumber());
        copy.setGovernmentIssuedId(source.getGovernmentIssuedId());
        copy.setDateOfBirth(source.getDateOfBirth());
        copy.setEftAccountId(source.getEftAccountId());
        copy.setPrimaryCardHolderIndicator(source.getPrimaryCardHolderIndicator());
        copy.setFicoCreditScore(source.getFicoCreditScore());
        return copy;
    }

    /**
     * Reads one submitted amount at the scale its column holds.
     *
     * <p>The grammar is the tolerant one {@code FUNCTION NUMVAL-C} accepts, which is what
     * {@code app/cbl/COACTUPC.cbl:L2201} gates on, so {@code $1,234.56} reads as one thousand two
     * hundred thirty four and fifty six hundredths. A value that will not read is left as stored and
     * the edit inside the service reports it, because this method is not where a caller learns its
     * mistake.
     *
     * @param submitted the submitted text
     * @param scale     fractional digits the column holds, from {@link PicClause}
     * @param stored    the stored amount, returned when the text will not read
     * @return the amount at {@code scale}
     */
    private static BigDecimal money(String submitted, int scale, BigDecimal stored) {
        if (!NumvalParser.isValidNumvalCurrency(submitted)) {
            return stored;
        }
        return NumvalParser.numvalCurrency(submitted).setScale(scale, java.math.RoundingMode.DOWN);
    }

    /**
     * Reads one submitted credit score.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L21} holds three
     * digits and no fraction. A value that will not read is left as stored, and the range edit at
     * {@code app/cbl/COACTUPC.cbl} reports it.
     *
     * @param submitted the submitted text
     * @param stored    the stored score, returned when the text will not read
     * @return the score
     */
    private static BigDecimal creditScore(String submitted, BigDecimal stored) {
        if (!NumvalParser.isValidNumval(submitted)) {
            return stored;
        }
        return NumvalParser.numval(submitted).setScale(0, java.math.RoundingMode.DOWN);
    }

    /**
     * Renders one stored account as the view this endpoint returns.
     *
     * @param stored the stored row
     * @return the view, carrying all eleven components the record declares
     */
    static AccountView viewOf(AccountEntity stored) {
        return new AccountView(stored.getAccountId(), stored.getActiveStatus(),
                stored.getCurrentBalance(), stored.getCreditLimit(), stored.getCashCreditLimit(),
                stored.getCurrentCycleCredit(), stored.getCurrentCycleDebit(), stored.getOpenDate(),
                stored.getExpirationDate(), stored.getReissueDate(), stored.getGroupId());
    }

    /**
     * Builds the {@code 404} every read that missed answers with.
     *
     * <p>The detail names no identifier, for the reason {@code config/SecurityConfig} gives for its
     * own 403: a caller probing for another subject's rows should learn nothing from the answer.
     *
     * @return the response
     */
    private static ResponseEntity<ApiProblem> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(HttpStatus.NOT_FOUND.value(), ApiProblem.NOT_FOUND,
                        ApiProblem.NOT_FOUND_DETAIL));
    }

    /**
     * Builds the {@code 422} a failed edit answers with.
     *
     * @param message the verbatim text the edit produced
     * @return the response
     */
    private static ResponseEntity<ApiProblem> validationFailed(String message) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        ApiProblem.VALIDATION_FAILED, ApiProblem.VALIDATION_FAILED_DETAIL,
                        List.of(message)));
    }
}
