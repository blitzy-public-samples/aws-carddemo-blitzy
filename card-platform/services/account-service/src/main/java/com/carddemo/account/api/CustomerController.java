package com.carddemo.account.api;

import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.CustomerRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer read endpoint, answering the customer values an account view displays.
 *
 * <p>{@code 1200-SETUP-SCREEN-VARS} at {@code app/cbl/COACTVWC.cbl:L460} moves eighteen customer
 * fields to a 3270 screen at {@code app/cbl/COACTVWC.cbl:L494-L522}, under the gate
 * {@code IF FOUND-CUST-IN-MASTER} at {@code :L493}. This endpoint answers sixteen of those
 * eighteen as one Representational State Transfer (REST) resource. It takes one request and
 * answers one response, so no state survives a call.
 *
 * <p>The path names the customer. {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17}
 * declares twelve fields and a filler, and none of them is a customer identifier. The source reads
 * the identifier from the cross-reference record at {@code app/cbl/COACTVWC.cbl:L739} and keys the
 * customer file with it at {@code :L708}.
 *
 * <p>A read that misses answers {@code 404} carrying the one text
 * {@code 9400-GETCUSTDATA-BYCUST} builds at {@code app/cbl/COACTVWC.cbl:L846-L856}. The first
 * message stands: the source builds that text under {@code WS-RETURN-MSG-OFF} at {@code :L845}, so
 * a later miss leaves the first text in place. A failure of the store answers {@code 500}, where
 * the source moves a file-error text onto the same screen at {@code :L865}.
 *
 * <p>Two fields the source displays reach no response. The Social Security Number,
 * {@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy:L17}, reaches the screen as nine digits with two
 * hyphens from the {@code STRING} at {@code app/cbl/COACTVWC.cbl:L496-L504}. The
 * government-issued identifier, {@code CUST-GOVT-ISSUED-ID} at
 * {@code app/cpy/CVCUS01Y.cpy:L18}, reaches it from the move at {@code :L519}. Neither value
 * reaches a response body, a log line or an event.
 *
 * <p>Rationale for the deviations this class takes part in, D5, D6, D8 and D10:
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>Flagged source findings: {@code card-platform/docs/business-rule-flags.md}. Three of them
 * concern the paragraph this endpoint reproduces. The guard at
 * {@code app/cbl/COACTVWC.cbl:L713} is unreachable, its only setter being commented out at
 * {@code :L842}. The response and reason moves sit outside the message guard at {@code :L843-L844},
 * where the account paragraph keeps them inside it at {@code :L794-L795}. The file-error move at
 * {@code :L865} carries no guard at all.
 *
 * <p>Field mapping, and the status values the two transaction-monitor codes map to:
 * {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>{@code src/main/resources/openapi.yaml} describes this endpoint.
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
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}, keyed through
     * {@code WS-CARD-RID-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTVWC.cbl:L76-L77}. The leading
     * zeros belong to the value, and the source keys the record as text, so customer one is
     * {@code 000000001} and not {@code 1}.
     *
     * <p>{@link CustomerDataRequest#CUSTOMER_ID_PATTERN} declares the value, so the path of this
     * route and the body of {@code PUT /accounts/{accountId}} are held to one rule.
     */
    static final String CUSTOMER_ID_PATTERN = CustomerDataRequest.CUSTOMER_ID_PATTERN;

    /** The text a path carrying anything but nine digits is refused with. */
    static final String CUSTOMER_ID_MESSAGE = CustomerDataRequest.CUSTOMER_ID_MESSAGE;

    /**
     * The first literal of the text a read that missed answers with, from
     * {@code app/cbl/COACTVWC.cbl:L847}.
     *
     * <p>The customer identifier follows it, as {@code WS-CARD-RID-CUST-ID-X PIC X(09)} at
     * {@code app/cbl/COACTVWC.cbl:L76-L77}.
     */
    static final String CUSTOMER_NOT_FOUND_OPENING = "CustId:";

    /** The second literal of that text, from {@code app/cbl/COACTVWC.cbl:L849}. */
    static final String CUSTOMER_NOT_FOUND_MISSING = " not found";

    /**
     * The third literal of that text, from {@code app/cbl/COACTVWC.cbl:L850}.
     *
     * <p>The source carries one space before {@code in}, none after {@code master.}, and one after
     * the colon. All three are reproduced.
     */
    static final String CUSTOMER_NOT_FOUND_FILE_AND_RESPONSE = " in customer master.Resp: ";

    /**
     * The fourth literal of that text, from {@code app/cbl/COACTVWC.cbl:L852}.
     *
     * <p>The source spells this literal in upper case and spells the account literal
     * {@code ' Reas:'} at {@code app/cbl/COACTVWC.cbl:L802}. Each is reproduced as it is spelled.
     */
    static final String CUSTOMER_NOT_FOUND_REASON = " REAS:";

    /** Reads the customer master row. */
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
     * <p>One keyed read serves this endpoint, reproducing the read
     * {@code 9400-GETCUSTDATA-BYCUST} issues against the customer file at
     * {@code app/cbl/COACTVWC.cbl:L826-L834}. The response carries the sixteen values of
     * {@code app/cbl/COACTVWC.cbl:L494-L522} that reach a caller.
     *
     * <p>Three field shapes arrive as the row stores them. The date of birth is the ten characters
     * of {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19}, such as
     * {@code 1961-06-08}. Each telephone number is the fifteen characters of
     * {@code CUST-PHONE-NUM-1 PIC X(15)} at {@code :L15} and {@code CUST-PHONE-NUM-2 PIC X(15)} at
     * {@code :L16}, holding the mask {@code app/cbl/COACTUPC.cbl:L82-L100} redefines and its two
     * trailing spaces. The city is {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy:L11},
     * which {@code app/cbl/COACTVWC.cbl:L513} moves to the city field and
     * {@code app/cbl/COACTUPC.cbl:L1615-L1616} labels {@code 'City'}.
     *
     * <p>A stored credit score is answered as it stands. {@code FICO-RANGE-IS-VALID} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} accepts 300 through 850 on an update, and 21 of the 50
     * rows of {@code app/data/ASCII/custdata.txt} carry a lower value at columns 330 to 332.
     *
     * @param customerId the customer to read, nine decimal digits
     * @return {@code 200} carrying the customer, or {@code 404} carrying the text of
     *         {@code app/cbl/COACTVWC.cbl:L846-L856}
     */
    @GetMapping("/{customerId}")
    public ResponseEntity<?> readCustomer(
            @PathVariable
            @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
            String customerId) {

        Optional<CustomerEntity> stored = customers.findByCustomerId(customerId);
        if (stored.isEmpty()) {
            LOG.info("A customer read found no row");
            return problem(HttpStatus.NOT_FOUND, ApiProblem.NOT_FOUND,
                    customerNotFoundText(customerId, HttpStatus.NOT_FOUND));
        }

        CustomerView view = AccountRecordMapper.viewOf(stored.get());
        return ResponseEntity.ok(view);
    }

    /**
     * Builds the one text a read that missed answers with.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:L846-L856} concatenates four literals around the customer
     * identifier, a response code and a reason code. The two codes are what the transaction monitor
     * set on the keyed read at {@code app/cbl/COACTVWC.cbl:L832-L833}. This text carries the status
     * of the response and its reason phrase in their place, and fits the 75 characters
     * {@code WS-RETURN-MSG} holds at {@code app/cbl/COACTVWC.cbl:L117}.
     *
     * @param customerId the customer the caller named
     * @param status     the status this response carries
     * @return the text, with the spelling and the spacing of the source preserved
     */
    private static String customerNotFoundText(String customerId, HttpStatus status) {
        return CUSTOMER_NOT_FOUND_OPENING + customerId + CUSTOMER_NOT_FOUND_MISSING
                + CUSTOMER_NOT_FOUND_FILE_AND_RESPONSE + status.value()
                + CUSTOMER_NOT_FOUND_REASON + status.getReasonPhrase();
    }

    /**
     * Builds one problem document carrying one message.
     *
     * <p>The message travels in the detail member. One miss text is all
     * {@code app/cbl/COACTVWC.cbl:L845} lets a read build, so the document carries no message list.
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
