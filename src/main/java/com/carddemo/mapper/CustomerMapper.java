package com.carddemo.mapper;

import org.springframework.stereotype.Component;

import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.entity.Customer;

/**
 * Hand-written, stateless Spring {@link Component} that bridges the
 * {@link Customer} JPA entity and the <em>customer</em> portion of the flattened
 * account DTOs ({@code AccountResponse} / {@link AccountUpdateRequest}) in the
 * CardDemo COBOL&#8594;Java migration.
 *
 * <h2>Why this mapper exists</h2>
 * <p>The legacy CICS programs {@code COACTVWC} (view) and {@code COACTUPC}
 * (update) present a <em>joined</em> account&nbsp;+&nbsp;customer panel: given an
 * account they resolve the owning customer through the card cross-reference and
 * render (or edit) both records on one 3270 screen. The target system preserves
 * that join by <strong>flattening</strong> the customer fields directly into
 * {@code AccountResponse} and {@link AccountUpdateRequest}; there is deliberately
 * <strong>no</strong> standalone {@code CustomerController} or
 * {@code CustomerResponse}. Consequently this mapper does not build a customer
 * DTO on its own &mdash; instead it owns the two customer-specific concerns that
 * {@code AccountMapper} delegates to it.</p>
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><strong>SSN-suppression enforcement point.</strong>
 *       {@link #maskSsnLastFour(String)} is the single sanctioned path by which any
 *       SSN-derived value leaves the service boundary, and it yields at most the
 *       last four digits. This is the most important PII rule for customer data
 *       (AAP&nbsp;&sect;0.6.8 PII suppression; &sect;0.7.1 &mdash; MUST NOT expose the
 *       full SSN, last-4 maximum).</li>
 *   <li><strong>Customer-side update apply.</strong>
 *       {@link #applyCustomerUpdate(AccountUpdateRequest, Customer)} copies the
 *       editable customer fields of an account-update request onto the managed
 *       {@link Customer} entity, reproducing the customer half of the combined
 *       {@code COACTUPC} maintenance transaction.</li>
 * </ol>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>Foundational.</strong> {@code AccountMapper} constructor-injects this
 *       mapper to render the masked SSN and customer fields of {@code AccountResponse}.</li>
 *   <li><strong>Stateless / leaf.</strong> No injected collaborators and no mutable
 *       state, so the single shared bean instance is inherently thread-safe.</li>
 *   <li><strong>Plain mapping.</strong> No MapStruct, ModelMapper, or Lombok &mdash;
 *       none are project dependencies; the field copies are written by hand so the
 *       PII-sensitive logic is fully auditable.</li>
 *   <li><strong>No PII logging.</strong> Neither method logs the SSN or any other
 *       personally identifiable information.</li>
 * </ul>
 */
@Component
public class CustomerMapper {

    /**
     * Creates the stateless mapper. No dependencies are injected; this mapper is
     * the leaf of the mapper dependency graph, so the Spring-supplied default
     * constructor is sufficient.
     */
    public CustomerMapper() {
        // No collaborators to wire — intentionally empty.
    }

    /**
     * Returns at most the last four digits of the supplied Social Security Number,
     * never the full value (PII suppression &mdash; AAP&nbsp;&sect;0.6.8 / &sect;0.7.1).
     *
     * <p>The {@link Customer} entity stores the full nine-digit SSN
     * ({@code CUST-SSN PIC 9(09)}, persisted as {@code VARCHAR(9)}); this method is
     * the only sanctioned way to surface SSN-derived data outward, guaranteeing the
     * complete value can never leave the service boundary. The result is the bare
     * trailing digits (for example {@code "6789"}) &mdash; no {@code "***-**-"}
     * prefix is fabricated &mdash; because the binding contract field
     * {@code AccountResponse.ssnLastFour} expects a digit-only last-four string.</p>
     *
     * <p>Behavior (null-safe):</p>
     * <ul>
     *   <li>{@code null} input &#8594; {@code null} output.</li>
     *   <li>blank input (empty after trimming) &#8594; {@code null}.</li>
     *   <li>an input whose trimmed length is four or fewer &#8594; the trimmed value
     *       returned whole.</li>
     *   <li>otherwise &#8594; the final four characters of the trimmed value.</li>
     * </ul>
     *
     * <p>The SSN is never logged by this method.</p>
     *
     * @param ssn the raw SSN as stored on the entity, may be {@code null} or blank
     * @return at most the last four digits of {@code ssn}, or {@code null} when the
     *         input is {@code null} or blank
     */
    public String maskSsnLastFour(String ssn) {
        if (ssn == null) {
            return null;
        }
        String digits = ssn.trim();
        if (digits.isEmpty()) {
            return null;
        }
        int len = digits.length();
        return len <= 4 ? digits : digits.substring(len - 4);
    }

    /**
     * Applies the editable customer fields of an {@link AccountUpdateRequest} onto a
     * managed {@link Customer} entity, reproducing the customer half of the combined
     * account&nbsp;+&nbsp;customer maintenance performed by the legacy
     * {@code COACTUPC} transaction.
     *
     * <p><strong>Partial-update semantics.</strong> Each field is copied only when it
     * is non-{@code null} in the request. This matches the {@code PUT} contract
     * documented on {@link AccountUpdateRequest}: a field omitted from the JSON body
     * is left unchanged rather than being overwritten with {@code null}, so callers
     * can perform partial updates without erasing existing data.</p>
     *
     * <p><strong>Immutable key.</strong> The customer identifier ({@code custId}) is
     * never modified here &mdash; it is the assigned primary key resolved by the
     * service through the card cross-reference, not an editable attribute.</p>
     *
     * <p><strong>SSN handling.</strong> The {@code ssn} component is accepted as
     * <em>input only</em> (the {@code COACTUP} screen edits it) and is persisted in
     * full on the entity, but it is never echoed back: the only outward SSN path is
     * {@link #maskSsnLastFour(String)} via {@code AccountResponse.ssnLastFour}. The
     * value is not logged here.</p>
     *
     * <p>If either argument is {@code null}, the method is a safe no-op so the mapper
     * can never be a source of {@link NullPointerException}.</p>
     *
     * @param req      the account-update request carrying the customer edits; ignored
     *                 when {@code null}
     * @param customer the managed customer entity to update in place; ignored when
     *                 {@code null}
     */
    public void applyCustomerUpdate(AccountUpdateRequest req, Customer customer) {
        if (req == null || customer == null) {
            return;
        }

        // --- Name (CUST-FIRST/MIDDLE/LAST-NAME PIC X(25)) ---
        if (req.firstName() != null) {
            customer.setFirstName(req.firstName());
        }
        if (req.middleName() != null) {
            customer.setMiddleName(req.middleName());
        }
        if (req.lastName() != null) {
            customer.setLastName(req.lastName());
        }

        // --- Address (CUST-ADDR-LINE-1/2/3 PIC X(50)) ---
        if (req.addressLine1() != null) {
            customer.setAddrLine1(req.addressLine1());
        }
        if (req.addressLine2() != null) {
            customer.setAddrLine2(req.addressLine2());
        }
        if (req.addressLine3() != null) {
            customer.setAddrLine3(req.addressLine3());
        }

        // --- State / country / ZIP (CUST-ADDR-STATE-CD X(02), -COUNTRY-CD X(03), -ZIP X(10)) ---
        if (req.stateCode() != null) {
            customer.setAddrStateCd(req.stateCode());
        }
        if (req.countryCode() != null) {
            customer.setAddrCountryCd(req.countryCode());
        }
        if (req.customerZip() != null) {
            customer.setAddrZip(req.customerZip());
        }

        // --- Phones (CUST-PHONE-NUM-1/2 PIC X(15)) ---
        if (req.phoneNumber1() != null) {
            customer.setPhoneNum1(req.phoneNumber1());
        }
        if (req.phoneNumber2() != null) {
            customer.setPhoneNum2(req.phoneNumber2());
        }

        // --- SSN (CUST-SSN PIC 9(09)) — INPUT ONLY; persisted in full, never echoed ---
        if (req.ssn() != null) {
            customer.setSsn(req.ssn());
        }

        // --- Government id (CUST-GOVT-ISSUED-ID PIC X(20)) ---
        if (req.governmentIssuedId() != null) {
            customer.setGovtIssuedId(req.governmentIssuedId());
        }

        // --- Date of birth (CUST-DOB-YYYY-MM-DD PIC X(10) -> LocalDate) ---
        if (req.dateOfBirth() != null) {
            customer.setDob(req.dateOfBirth());
        }

        // --- EFT account id (CUST-EFT-ACCOUNT-ID PIC X(10)) ---
        if (req.eftAccountId() != null) {
            customer.setEftAccountId(req.eftAccountId());
        }

        // --- Primary card-holder indicator (CUST-PRI-CARD-HOLDER-IND PIC X(01)) ---
        if (req.primaryCardHolderIndicator() != null) {
            customer.setPriCardHolderInd(req.primaryCardHolderIndicator());
        }

        // --- FICO credit score (CUST-FICO-CREDIT-SCORE PIC 9(03) -> Integer) ---
        if (req.ficoScore() != null) {
            customer.setFicoCreditScore(req.ficoScore());
        }

        // The immutable primary key custId is intentionally NOT modified here.
    }
}
