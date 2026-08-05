package com.carddemo.account.api;

import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.cobol.PicClause;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer surface, which exists because an account record names no customer.
 *
 * <p>{@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} carries no customer identifier, so
 * the source reaches a customer only through the cross-reference record, reading
 * {@code XREF-CUST-ID} at {@code app/cbl/COACTVWC.cbl:L828-L829} and then keying the customer file
 * with it. This service owns no cross-reference table, because the card service owns that record, so
 * this endpoint takes the customer identifier directly.
 *
 * <p>That is why the identifier and not the account is what a caller is scoped against.
 * {@code config/SecurityConfig} checks {@code SCOPE_CUSTOMER_<id>} on the path variable below, and a
 * caller reading a customer it does not own receives {@code 403} before this method runs.
 *
 * <p>The view withholds nothing a caller entitled to the row may not see, and it carries no Social
 * Security number and no government-issued identifier. {@code CustomerView} declares sixteen
 * components and {@code CUSTOMER-RECORD} declares nineteen, and the three the view omits are the two
 * identity documents and the recombined Social Security column. A read is the wrong operation to
 * disclose them through, and no caller of this platform needs them back.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes this endpoint, written by hand so no
 * documentation generator joins the classpath.
 */
@RestController
@RequestMapping("/customers")
@Validated
public class CustomerController {

    /** Writes the diagnostic lines this class emits, none carrying a customer value. */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerController.class);

    /**
     * The width and the alphabet of a customer identifier in a path.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}. The leading zeros belong to the
     * value, so customer fifty is {@code 000000050} and not {@code 50}.
     */
    static final String CUSTOMER_ID_PATTERN = "^[0-9]{" + PicClause.CUST_ID_WIDTH + "}$";

    /** The text a path carrying anything but nine digits is rejected with. */
    static final String CUSTOMER_ID_MESSAGE =
            "Customer Id must be a 9 digit Number, zero padded on the left";

    /** Reads the customer row. */
    private final CustomerRepository customers;

    /**
     * Takes the store this endpoint reads.
     *
     * @param customers store of the customer master row
     * @throws NullPointerException if the argument is {@code null}
     */
    public CustomerController(CustomerRepository customers) {
        this.customers = Objects.requireNonNull(customers, "customers must be present");
    }

    /**
     * Reads one customer.
     *
     * <p>The keyed read reproduces the customer read at {@code app/cbl/COACTVWC.cbl:L860-L870}, and a
     * read that misses answers {@code 404} where the source moves a text onto the screen.
     *
     * <p>The transaction is read-only, so no statement on this path can write.
     *
     * @param customerId the customer to read, nine decimal digits
     * @return {@code 200} carrying the customer, or {@code 404} when this service holds no such row
     */
    @GetMapping("/{customerId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> readCustomer(
            @PathVariable
            @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
            String customerId) {

        Optional<CustomerEntity> stored = customers.findByCustomerId(customerId);
        if (stored.isEmpty()) {
            LOG.info("A customer read found no row");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiProblem.of(HttpStatus.NOT_FOUND.value(), ApiProblem.NOT_FOUND,
                            ApiProblem.NOT_FOUND_DETAIL));
        }
        return ResponseEntity.ok(viewOf(stored.get()));
    }

    /**
     * Renders one stored customer as the view this endpoint returns.
     *
     * <p>The credit score is rendered as a whole number, because
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L21} holds three digits
     * and no fraction, and a score with a decimal point would misdescribe the column.
     *
     * @param stored the stored row
     * @return the view, carrying all sixteen components the record declares
     */
    static CustomerView viewOf(CustomerEntity stored) {
        Integer creditScore = stored.getFicoCreditScore() == null ? null
                : stored.getFicoCreditScore().intValueExact();

        return new CustomerView(stored.getCustomerId(), creditScore, stored.getDateOfBirth(),
                stored.getFirstName(), stored.getMiddleName(), stored.getLastName(),
                stored.getAddressLine1(), stored.getAddressLine2(), stored.getAddressCity(),
                stored.getAddressStateCode(), stored.getAddressZip(), stored.getAddressCountryCode(),
                stored.getPhoneNumber1(), stored.getPhoneNumber2(), stored.getEftAccountId(),
                stored.getPrimaryCardHolderIndicator());
    }
}
