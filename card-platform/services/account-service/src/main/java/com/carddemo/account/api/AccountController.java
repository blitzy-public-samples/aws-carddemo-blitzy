package com.carddemo.account.api;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.domain.AccountUpdateOutcome;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account read and update endpoints, replacing two Customer Information Control System (CICS)
 * transactions.
 *
 * <p>{@code app/cbl/COACTVWC.cbl} displays one account and {@code app/cbl/COACTUPC.cbl} updates one
 * account with its customer. Both are pseudo-conversational and carry their state in a
 * communication area between terminal exchanges. Each method here takes one request and answers one
 * response, so no state survives a call.
 *
 * <p>A read answers the eleven values {@code 1200-SETUP-SCREEN-VARS} moves to the screen at
 * {@code app/cbl/COACTVWC.cbl:L460-L537}. It answers no customer value. {@code ACCOUNT-RECORD} at
 * {@code app/cpy/CVACT01Y.cpy:L4-L17} declares no customer identifier, and the source resolves one
 * through the cross-reference record read at {@code app/cbl/COACTVWC.cbl:L723}. This service holds
 * no cross-reference table, and {@code GET /customers/{customerId}} answers the customer.
 *
 * <p>A read that misses answers {@code 404} carrying the text
 * {@code 9300-GETACCTDATA-BYACCT} builds at {@code app/cbl/COACTVWC.cbl:L796-L806}. The first
 * message stands: the source guards every message it builds with {@code WS-RETURN-MSG-OFF} at
 * {@code app/cbl/COACTVWC.cbl:L793}, so a later miss leaves the first text in place.
 *
 * <p>An update answers one of four outcomes.
 *
 * <ul>
 *   <li>{@code 200} once both rows are written, from {@code app/cbl/COACTUPC.cbl:L4066} and
 *       {@code :L4086}, or carrying the no-change text of {@code :L1463-L1467}.</li>
 *   <li>{@code 404} when this service holds no such account row or customer row.</li>
 *   <li>{@code 409} when a row changed under the caller, from
 *       {@code app/cbl/COACTUPC.cbl:L3950-L3952}, or could not be locked, from {@code :L3907-L3915}
 *       and {@code :L3934-L3942}.</li>
 *   <li>{@code 422} when one field failed an edit, from {@code app/cbl/COACTUPC.cbl:L1470-L1676},
 *       carrying that one text.</li>
 * </ul>
 *
 * <p>Every answer carries one message. {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} holds one text under the guard at {@code :L480}, so one text is
 * all a validation pass produces. A failed edit is an answer and never a thrown failure.
 *
 * <p>A body submits blocks of text values, matching the screen fields {@code ACUP-NEW-DETAILS}
 * declares at {@code app/cbl/COACTUPC.cbl:L757}. The customer identifier belongs to the customer
 * block, whose first field is {@code ACUP-NEW-CUST-ID-X} at {@code app/cbl/COACTUPC.cbl:L798}. A
 * caller may omit a whole block or a component carrying no mandatory edit, and what it omits stands
 * as stored. Each date arrives as eight characters and each amount as up to fifteen, and
 * {@code AccountRecordMapper} converts both to the width and scale its column holds.
 *
 * <p>Neither the Social Security Number at {@code app/cpy/CVCUS01Y.cpy:L17} nor the
 * government-issued identifier at {@code :L18} reaches a response, a log line or an event. A failed
 * edit names the field it refused and none of the submitted value.
 *
 * <p>Rationale for the deviations this class takes part in, D1 and D4 through D10:
 * {@code card-platform/docs/decision-log.md}. Flagged source findings, among them the two
 * unreachable guards at {@code app/cbl/COACTVWC.cbl:L704} and {@code :L713} whose only setters are
 * commented out at {@code :L792} and {@code :L842}:
 * {@code card-platform/docs/business-rule-flags.md}. Field mapping, the status values the two
 * transaction-monitor codes map to, and every source construct that reaches no component:
 * {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes both endpoints.
 */
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /**
     * The width and the alphabet of an account identifier in a path.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. The leading zeros belong to
     * the value and the source compares the identifier as text, so a bare form of the same number
     * names no account.
     */
    static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /** The text a path carrying anything but eleven digits is refused with. */
    static final String ACCOUNT_ID_MESSAGE =
            "Account Id must be an 11 digit Number, zero padded on the left";

    /**
     * The text a body naming no customer is refused with.
     *
     * <p>{@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} declares no customer
     * identifier, so a caller names the customer it means to write.
     */
    static final String CUSTOMER_ID_REQUIRED_MESSAGE =
            "Customer Id must be supplied. An account record carries none";

    /** The text an update answers with once both rows are written. */
    static final String UPDATE_APPLIED_MESSAGE = "Changes committed to database";

    /**
     * The first literal of the text a read that missed answers with, from
     * {@code app/cbl/COACTVWC.cbl:L797}.
     *
     * <p>The account identifier follows it, as {@code WS-CARD-RID-ACCT-ID-X PIC X(11)} at
     * {@code app/cbl/COACTVWC.cbl:L79-L80}.
     */
    static final String ACCOUNT_NOT_FOUND_OPENING = "Account:";

    /** The second literal of that text, from {@code app/cbl/COACTVWC.cbl:L799}. */
    static final String ACCOUNT_NOT_FOUND_MISSING = " not found in";

    /**
     * The third literal of that text, from {@code app/cbl/COACTVWC.cbl:L800}.
     *
     * <p>The source carries no space after {@code file.} and none after the colon, and both are
     * reproduced.
     */
    static final String ACCOUNT_NOT_FOUND_FILE_AND_RESPONSE = " Acct Master file.Resp:";

    /** The fourth literal of that text, from {@code app/cbl/COACTVWC.cbl:L802}. */
    static final String ACCOUNT_NOT_FOUND_REASON = " Reas:";

    /** Reads the account master row. */
    private final AccountRepository accounts;

    /** Reads the customer master row an update names. */
    private final CustomerRepository customers;

    /** Runs the edits, the concurrency check and both writes. */
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
     * <p>One keyed read serves this endpoint, reproducing {@code 9300-GETACCTDATA-BYACCT} at
     * {@code app/cbl/COACTVWC.cbl:L774-L784}. The cross-reference read at
     * {@code app/cbl/COACTVWC.cbl:L723} and the customer read at {@code :L825} are the other two
     * hops of {@code 9000-READ-ACCT} at {@code :L687-L720}. Neither reads a row this service holds.
     *
     * <p>The response carries the eleven values of {@code app/cbl/COACTVWC.cbl:L468-L490}. The
     * account postal code at {@code app/cpy/CVACT01Y.cpy:L15} reaches no component, and the source
     * paragraph moves it to no screen field.
     *
     * @param accountId the account to read, eleven decimal digits
     * @return {@code 200} carrying the account, or {@code 404} carrying the text of
     *         {@code app/cbl/COACTVWC.cbl:L796-L806}
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<?> readAccount(
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MESSAGE)
            String accountId) {

        Optional<AccountEntity> stored = accounts.findByAccountId(accountId);
        if (stored.isEmpty()) {
            LOG.info("An account read found no row");
            return problem(HttpStatus.NOT_FOUND, ApiProblem.NOT_FOUND,
                    accountNotFoundText(accountId, HttpStatus.NOT_FOUND));
        }
        return ResponseEntity.ok(AccountRecordMapper.viewOf(stored.get()));
    }

    /**
     * Updates one account with its customer.
     *
     * <p>This method reads both stored rows and hands them to {@link AccountUpdateService} as the
     * pair the caller was shown, which {@code app/cbl/COACTUPC.cbl} holds in
     * {@code ACUP-OLD-ACCT-DATA} and {@code ACUP-OLD-CUST-DATA}. That service opens the
     * transaction, re-reads both rows under a write lock, compares them field by field as
     * {@code app/cbl/COACTUPC.cbl:L4109-L4193} does, and writes. A writer that committed between
     * this read and that lock is detected there and answered with {@code 409}.
     *
     * <p>The account identifier comes from the path. {@code AccountUpdateRequest} declares no
     * identifier component, so a body cannot name a second account.
     *
     * <p>The customer identifier comes from the body and is checked here, before any row is read.
     * {@code AccountUpdateRequest} declares no cascade marker, so the bean constraints of
     * {@code CustomerDataRequest} do not run through this route and this method holds the rule
     * instead. A value that is absent or blank answers {@value #CUSTOMER_ID_REQUIRED_MESSAGE}, and a
     * value that is not the nine digits {@code CustomerDataRequest#CUSTOMER_ID_PATTERN} declares
     * answers {@code CustomerDataRequest#CUSTOMER_ID_MESSAGE}. Both answer {@code 422}, and neither
     * reaches the customer store, so a malformed identifier is not reported as a row this service
     * does not hold. This mirrors the order the source uses: {@code 1200-EDIT-MAP-INPUTS} at
     * {@code app/cbl/COACTUPC.cbl:L1429} performs the search-key edit {@code 1210-EDIT-ACCOUNT} at
     * {@code app/cbl/COACTUPC.cbl:L1434-L1435} and then leaves at
     * {@code app/cbl/COACTUPC.cbl:L1446}, so a key of the wrong shape is answered before any field
     * edit runs.
     *
     * <p>A submitted amount is read under the grammar {@code app/cbl/COACTUPC.cbl:L2201} gates on,
     * so a currency sign and thousands separators are accepted. A supplied value that will not
     * convert answers {@code 422} carrying the text of the edit that refused it.
     *
     * @param accountId the account to update, eleven decimal digits
     * @param request   the submitted pair
     * @return {@code 200}, {@code 404}, {@code 409} or {@code 422}, each carrying one message
     */
    @PutMapping("/{accountId}")
    public ResponseEntity<?> updateAccount(
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_MESSAGE)
            String accountId,
            @Valid @RequestBody AccountUpdateRequest request) {

        CustomerDataRequest customerData = request.customerData();
        if (customerData == null || !AccountRecordMapper.isSupplied(customerData.customerId())) {
            LOG.info("An account update named no customer");
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                    CUSTOMER_ID_REQUIRED_MESSAGE);
        }

        if (!customerData.customerId().matches(CustomerDataRequest.CUSTOMER_ID_PATTERN)) {
            LOG.info("An account update named a customer of the wrong width");
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                    CustomerDataRequest.CUSTOMER_ID_MESSAGE);
        }

        AccountDataRequest accountData = request.accountData();
        EditResult convertible = AccountRecordMapper.convertibleValues(accountData, customerData);
        if (!convertible.valid()) {
            LOG.info("An account update carried a value one edit refused");
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                    convertible.message());
        }

        Optional<AccountEntity> storedAccount = accounts.findByAccountId(accountId);
        if (storedAccount.isEmpty()) {
            LOG.info("An account update found no account row");
            return problem(HttpStatus.NOT_FOUND, ApiProblem.NOT_FOUND, ApiProblem.NOT_FOUND_DETAIL);
        }

        Optional<CustomerEntity> storedCustomer =
                customers.findByCustomerId(customerData.customerId());
        if (storedCustomer.isEmpty()) {
            LOG.info("An account update found no customer row");
            return problem(HttpStatus.NOT_FOUND, ApiProblem.NOT_FOUND, ApiProblem.NOT_FOUND_DETAIL);
        }

        AccountUpdateOutcome outcome = accountUpdates.updateAccount(
                accountOverStored(accountId, accountData, storedAccount.get()),
                customerOverStored(customerData, storedCustomer.get()),
                fetchedCopy(accountId, storedAccount.get()),
                fetchedCopy(storedCustomer.get()));

        return answerOf(outcome);
    }

    /**
     * Turns one verdict into the response an update answers with.
     *
     * <p>A passing verdict carrying no text wrote both rows, and the slot then carries
     * {@value #UPDATE_APPLIED_MESSAGE}. A passing verdict carrying a text wrote nothing and says
     * what it found, which is the no-change return of {@code app/cbl/COACTUPC.cbl:L1463-L1467}. A
     * failing verdict is a lost race or a refused field, and the two answer different statuses: a
     * caller retries the first and corrects the second.
     *
     * <p>The account this response carries is the snapshot the service read off the row inside the
     * transaction that wrote it. It is not read again here. A second read would issue another
     * statement to answer a question the transaction had already answered, and it would answer it as
     * of a later instant, so it was never the more truthful of the two.
     *
     * @param outcome what the service answered: the verdict, and the account as the transaction
     *                left it
     * @return the response, carrying one message
     */
    private ResponseEntity<?> answerOf(AccountUpdateOutcome outcome) {
        EditResult verdict = outcome.verdict();
        if (verdict.valid()) {
            String message = verdict.hasMessage() ? verdict.message() : UPDATE_APPLIED_MESSAGE;
            AccountView account = outcome.account() == null
                    ? null
                    : AccountRecordMapper.viewOf(outcome.account());
            LOG.info("An account update answered its caller");
            return ResponseEntity.ok(new AccountUpdateResponse(message, account));
        }

        if (namesALostRace(verdict.message())) {
            LOG.info("An account update lost a race against another writer");
            return problem(HttpStatus.CONFLICT, ApiProblem.CONFLICT, verdict.message());
        }

        LOG.info("An account update failed one edit");
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                verdict.message());
    }

    /**
     * Reports whether a failing text names a row this call could not write.
     *
     * <p>Three texts do. {@link ConcurrentChangeDetector#RECORD_CHANGED_MESSAGE} is the verdict of
     * {@code app/cbl/COACTUPC.cbl:L3950-L3952}, and the two of
     * {@link AccountUpdateService#lockFailureMessages()} come from {@code :L3907-L3915} and
     * {@code :L3934-L3942}. Every other text names a field a caller can correct.
     *
     * @param message the text the verdict carried
     * @return {@code true} when the call should be retried against the row as it now stands
     */
    private static boolean namesALostRace(String message) {
        return ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE.equals(message)
                || AccountUpdateService.lockFailureMessages().contains(message);
    }

    /**
     * Builds the account values a caller submitted over the values stored.
     *
     * <p>{@code AccountRecordMapper} converts every submitted value. An amount reaches the scale
     * its column holds from up to fifteen characters of text. A date reaches the ten characters
     * {@code ACCT-UPDATE-RECORD} declares at {@code app/cbl/COACTUPC.cbl:L427-L429} from the eight
     * of {@code ACUP-NEW-OPEN-DATE} at {@code :L772}.
     *
     * <p>A present block has to be complete. Nine of the ten components carry a mandatory-field edit
     * in {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:L1470-L1676}, and those edits
     * refuse an absent value exactly as they refuse a blank one: the map area a 3270 screen sends is
     * fixed width, so an operator who cleared a field sent spaces and the edit read spaces. This
     * method therefore hands an absent component through unchanged rather than filling it from the
     * stored row, and the edit that owns the field reports its own text. Filling it would answer
     * {@code 200} to a caller that dropped a field and tell it nothing, which is a silent write of
     * the value the caller never sent.
     *
     * <p>{@code groupId} is the one component that may be omitted, and it then keeps its stored
     * value. No edit of {@code app/cbl/COACTUPC.cbl} reads it and the program moves no label for it,
     * so there is no refusal to reproduce.
     *
     * <p>A submission of {@code null} answers a copy of the stored row. That is how a caller updates
     * the customer alone, and it is also how {@link #fetchedCopy(String, AccountEntity)} builds the
     * baseline the concurrency check compares against.
     *
     * <p>The postal code at {@code app/cpy/CVACT01Y.cpy:L15} is carried through from the stored
     * row. {@code AccountDataRequest} declares no component for it, so no submitted value reaches
     * it.
     *
     * @param accountId the account the path named
     * @param submitted the submitted account values, or {@code null} when the body carried none
     * @param stored    the stored row
     * @return a new entity, leaving {@code stored} untouched
     */
    private static AccountEntity accountOverStored(String accountId, AccountDataRequest submitted,
            AccountEntity stored) {

        AccountEntity proposed = new AccountEntity();
        proposed.setAccountId(accountId);
        proposed.setAddressZip(stored.getAddressZip());
        proposed.setGroupId(stored.getGroupId());
        if (submitted == null) {
            proposed.setActiveStatus(stored.getActiveStatus());
            proposed.setCurrentBalance(stored.getCurrentBalance());
            proposed.setCreditLimit(stored.getCreditLimit());
            proposed.setCashCreditLimit(stored.getCashCreditLimit());
            proposed.setOpenDate(stored.getOpenDate());
            proposed.setExpirationDate(stored.getExpirationDate());
            proposed.setReissueDate(stored.getReissueDate());
            proposed.setCurrentCycleCredit(stored.getCurrentCycleCredit());
            proposed.setCurrentCycleDebit(stored.getCurrentCycleDebit());
            return proposed;
        }

        // A present block carries every one of these nine, and an absent one reaches the edit that
        // owns the field rather than the stored value. app/cbl/COACTUPC.cbl:L1472-L1527 runs those
        // nine edits, each of which refuses a value that was not supplied.
        AccountEntity converted = AccountRecordMapper.accountOf(accountId, submitted);
        proposed.setActiveStatus(converted.getActiveStatus());
        proposed.setCurrentBalance(converted.getCurrentBalance());
        proposed.setCreditLimit(converted.getCreditLimit());
        proposed.setCashCreditLimit(converted.getCashCreditLimit());
        proposed.setOpenDate(converted.getOpenDate());
        proposed.setExpirationDate(converted.getExpirationDate());
        proposed.setReissueDate(converted.getReissueDate());
        proposed.setCurrentCycleCredit(converted.getCurrentCycleCredit());
        proposed.setCurrentCycleDebit(converted.getCurrentCycleDebit());

        // app/cbl/COACTUPC.cbl:L796 declares the group identifier and no paragraph edits it.
        if (AccountRecordMapper.isSupplied(submitted.groupId())) {
            proposed.setGroupId(converted.getGroupId());
        }
        return proposed;
    }

    /**
     * Builds the customer values a caller submitted over the values stored.
     *
     * <p>A present block has to be complete, as on the account block. Eleven of its components carry
     * a mandatory-field edit in {@code 1200-EDIT-MAP-INPUTS} at
     * {@code app/cbl/COACTUPC.cbl:L1470-L1676}, and those edits refuse an absent value exactly as
     * they refuse a blank one. An absent component therefore reaches the edit that owns the field
     * rather than the stored value, and the edit reports its own text.
     *
     * <p>Six components carry no mandatory edit and keep their stored value when omitted. The
     * middle name reaches {@code AlphabeticOptionalValidator} at edit 14. Both telephone numbers
     * reach the edit at {@code app/cbl/COACTUPC.cbl:L2225-L2244}, whose own comment reads
     * {@code Not mandatory to enter a phone number} and which accepts a wholly blank value. The date
     * of birth reaches a calendar edit that treats an absent value as {@code LOW-VALUES}. The second
     * address line and the government-issued identifier reach no edit at all.
     *
     * <p>The identifier reaches no edit either. {@code app/cbl/COACTUPC.cbl:L1222} calls it
     * {@code actually not editable}, and this service refuses a submitted identifier that is not the
     * one the fetched row carries, whether it arrives blank or not at all.
     *
     * <p>The three Social Security Number parts are one column, so they replace it only when all
     * three arrive. One part alone would write a number two thirds of which came from the stored
     * row. Because all three are required, a body carrying one part alone leaves the column absent
     * and the edit at {@code app/cbl/COACTUPC.cbl:L1529-L1531} refuses it.
     *
     * <p>The identifier is the one the body named, which is also the identifier this call read the
     * row by, so the ownership check inside {@link AccountUpdateService} compares two equal values.
     *
     * <p>A submission of {@code null} answers a copy of the stored row.
     *
     * @param submitted the submitted customer values, or {@code null} when the body carried none
     * @param stored    the stored row
     * @return a new entity, leaving {@code stored} untouched
     */
    private static CustomerEntity customerOverStored(CustomerDataRequest submitted,
            CustomerEntity stored) {

        CustomerEntity proposed = new CustomerEntity();
        proposed.setCustomerId(stored.getCustomerId());
        proposed.setMiddleName(stored.getMiddleName());
        proposed.setAddressLine2(stored.getAddressLine2());
        proposed.setPhoneNumber1(stored.getPhoneNumber1());
        proposed.setPhoneNumber2(stored.getPhoneNumber2());
        proposed.setGovernmentIssuedId(stored.getGovernmentIssuedId());
        proposed.setDateOfBirth(stored.getDateOfBirth());
        if (submitted == null) {
            proposed.setFirstName(stored.getFirstName());
            proposed.setLastName(stored.getLastName());
            proposed.setAddressLine1(stored.getAddressLine1());
            proposed.setAddressCity(stored.getAddressCity());
            proposed.setAddressStateCode(stored.getAddressStateCode());
            proposed.setAddressCountryCode(stored.getAddressCountryCode());
            proposed.setAddressZip(stored.getAddressZip());
            proposed.setSocialSecurityNumber(stored.getSocialSecurityNumber());
            proposed.setEftAccountId(stored.getEftAccountId());
            proposed.setPrimaryCardHolderIndicator(stored.getPrimaryCardHolderIndicator());
            proposed.setFicoCreditScore(stored.getFicoCreditScore());
            return proposed;
        }

        // A present block carries every one of these ten, and an absent one reaches the edit that
        // owns the field. Edits 13, 15 through 20, 23 and 24 of app/cbl/COACTUPC.cbl:L1560-L1662
        // each refuse a value that was not supplied, and edit 12 at :L1545-L1556 refuses an absent
        // credit score.
        CustomerEntity converted = AccountRecordMapper.customerOf(submitted);
        proposed.setCustomerId(submitted.customerId());
        proposed.setFirstName(converted.getFirstName());
        proposed.setLastName(converted.getLastName());
        proposed.setAddressLine1(converted.getAddressLine1());
        proposed.setAddressCity(converted.getAddressCity());
        proposed.setAddressStateCode(converted.getAddressStateCode());
        proposed.setAddressCountryCode(converted.getAddressCountryCode());
        proposed.setAddressZip(converted.getAddressZip());
        proposed.setEftAccountId(converted.getEftAccountId());
        proposed.setPrimaryCardHolderIndicator(converted.getPrimaryCardHolderIndicator());
        proposed.setFicoCreditScore(converted.getFicoCreditScore());

        // All three parts are required, so an incomplete triple leaves the column absent and edit
        // 10 at app/cbl/COACTUPC.cbl:L1529-L1531 reads the nine spaces that absence composes. The
        // setter guards the column and refuses an absent number, so the field keeps the absent value
        // it was constructed with rather than reaching the setter. The stored number is not
        // substituted: doing so would write a number the caller never sent, and two thirds of it
        // would come from the row on an incomplete triple.
        if (converted.getSocialSecurityNumber() != null) {
            proposed.setSocialSecurityNumber(converted.getSocialSecurityNumber());
        }

        // The six components no mandatory edit reads keep their stored value when omitted.
        if (AccountRecordMapper.isSupplied(submitted.middleName())) {
            proposed.setMiddleName(converted.getMiddleName());
        }
        if (AccountRecordMapper.isSupplied(submitted.addressLine2())) {
            proposed.setAddressLine2(converted.getAddressLine2());
        }
        if (AccountRecordMapper.isSupplied(submitted.phoneNumber1())) {
            proposed.setPhoneNumber1(converted.getPhoneNumber1());
        }
        if (AccountRecordMapper.isSupplied(submitted.phoneNumber2())) {
            proposed.setPhoneNumber2(converted.getPhoneNumber2());
        }
        if (AccountRecordMapper.isSupplied(submitted.governmentIssuedId())) {
            proposed.setGovernmentIssuedId(converted.getGovernmentIssuedId());
        }
        if (AccountRecordMapper.isSupplied(submitted.dateOfBirth())) {
            proposed.setDateOfBirth(converted.getDateOfBirth());
        }
        return proposed;
    }

    /**
     * Copies the stored account row for {@link AccountUpdateService} to compare against.
     *
     * <p>The service re-reads the row under a write lock and compares that row against this copy.
     * This copy holds the values as they were read, and no write of that service reaches it.
     *
     * @param accountId the account the path named
     * @param stored    the stored row
     * @return a copy carrying the same twelve values
     */
    private static AccountEntity fetchedCopy(String accountId, AccountEntity stored) {
        return accountOverStored(accountId, null, stored);
    }

    /**
     * Copies the stored customer row for {@link AccountUpdateService} to compare against.
     *
     * @param stored the stored row
     * @return a copy carrying the same eighteen values
     */
    private static CustomerEntity fetchedCopy(CustomerEntity stored) {
        return customerOverStored(null, stored);
    }

    /**
     * Builds the one text a read that missed answers with.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:L796-L806} concatenates four literals around the account
     * identifier, a response code and a reason code. The two codes are what the transaction monitor
     * set on the keyed read at {@code app/cbl/COACTVWC.cbl:L782-L783}. This text carries the status
     * of the response and its reason phrase in their place.
     *
     * @param accountId the account the caller named
     * @param status    the status this response carries
     * @return the text, with the spacing of the source preserved
     */
    private static String accountNotFoundText(String accountId, HttpStatus status) {
        return ACCOUNT_NOT_FOUND_OPENING + accountId + ACCOUNT_NOT_FOUND_MISSING
                + ACCOUNT_NOT_FOUND_FILE_AND_RESPONSE + status.value() + ACCOUNT_NOT_FOUND_REASON
                + status.getReasonPhrase();
    }

    /**
     * Builds one problem document carrying one message.
     *
     * <p>The message travels in the detail member. One text is all a validation pass of
     * {@code app/cbl/COACTUPC.cbl:L1470-L1676} produces, and one miss text is all
     * {@code app/cbl/COACTVWC.cbl:L793} lets a read build.
     *
     * @param status the status to answer
     * @param title  the fixed title for this class of outcome
     * @param detail the one message
     * @return the response
     */
    private static ResponseEntity<ApiProblem> problem(HttpStatus status, String title,
            String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(status.value(), title, detail));
    }
}
