package com.carddemo.controller;

import com.carddemo.dto.customer.CustomerDto;
import com.carddemo.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer read REST endpoint &mdash; the stateless replacement for the
 * customer-information portion of the legacy CICS account-view program
 * {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}).
 *
 * <p>In the original online flow the account-view transaction resolved customer
 * data through a multi-step chain: it read the account ({@code 9300-GETACCTDATA-BYACCT}),
 * located the card cross-reference to obtain the customer id, then performed a keyed
 * read of the customer master ({@code CUSTDAT}) in paragraph
 * {@code 9400-GETCUSTDATA-BYCUST} ({@code app/cbl/COACTVWC.cbl:L825-L870}) via
 * {@code EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID)}, after which the customer
 * detail fields were moved onto the BMS map for display. The modernized stack exposes
 * the customer-master read directly as a single resource-oriented operation:</p>
 *
 * <ul>
 *   <li>{@code GET /api/customers/{custId}} &mdash; retrieve one customer by its
 *       9-digit primary key; {@code 200 OK} with the customer body on success.</li>
 * </ul>
 *
 * <h2>COBOL parity (replaces the {@code COACTVWC} customer-info read)</h2>
 * <p>This controller is a thin, stateless HTTP boundary; the business semantics of the
 * legacy {@code 9400-GETCUSTDATA-BYCUST} paragraph are preserved by {@link CustomerService}
 * rather than re-implemented here:</p>
 * <ul>
 *   <li><b>Keyed customer-master read</b> ({@code EXEC CICS READ DATASET('CUSTDAT')
 *       RIDFLD(CUST-ID)}, {@code COACTVWC.cbl:L826-L834}) becomes
 *       {@code customerRepository.findById(custId)} inside
 *       {@link CustomerService#getCustomer(Long)}.</li>
 *   <li><b>{@code DFHRESP(NORMAL)}</b> ({@code COACTVWC.cbl:L837-L838}, the
 *       {@code FOUND-CUST-IN-MASTER} branch) maps to the {@code 200 OK} response
 *       carrying the {@link CustomerDto}.</li>
 *   <li><b>{@code DFHRESP(NOTFND)}</b> ({@code COACTVWC.cbl:L839-L848}, 88-level
 *       {@code DID-NOT-FIND-CUST-IN-CUSTDAT}) causes {@link CustomerService} to raise
 *       {@code AccountNotFoundException} carrying the exact COBOL message literal
 *       {@code "Did not find associated customer in master file"}
 *       ({@code COACTVWC.cbl:L133-L134}); {@code GlobalExceptionHandler} maps that to
 *       {@code 404 Not Found}.</li>
 *   <li><b>{@code WHEN OTHER}</b> ({@code COACTVWC.cbl:L859-L868}, the file-error branch)
 *       maps to an unchecked data-access failure surfaced as {@code 500 Internal Server
 *       Error} by {@code GlobalExceptionHandler}.</li>
 * </ul>
 *
 * <h2>Request validation (PR-13)</h2>
 * <p>The {@code custId} path variable mirrors COBOL {@code CUST-ID PIC 9(09)}
 * ({@code app/cpy/CVCUS01Y.cpy:L5}) &mdash; a 9-digit positive integer. The class-level
 * {@link Validated @Validated} activates Jakarta Bean Validation on method parameters, so
 * the {@link Min @Min(1)} / {@link Max @Max(999999999)} constraints on {@code custId} are
 * enforced by Spring: a zero, negative, or 10-digit value raises
 * {@code jakarta.validation.ConstraintViolationException}, which
 * {@code GlobalExceptionHandler} maps to {@code 400 Bad Request}. A non-numeric path
 * segment fails {@code Long} conversion and is mapped to {@code 400} via
 * {@code MethodArgumentTypeMismatchException}.</p>
 *
 * <h2>SSN masking (PR-20 &mdash; CRITICAL security boundary)</h2>
 * <p>The customer Social Security Number is PII and is <em>never</em> returned in plain
 * form. Masking to {@code ***-**-####} (only the last four digits visible) is performed
 * exclusively by {@code CustomerMapper} inside the service layer. By the time a
 * {@link CustomerDto} reaches this controller the SSN is already masked; the controller
 * never reads the raw customer entity, never invokes the mapper, and never logs the
 * response body &mdash; only the {@code custId} path variable is logged (at {@code DEBUG}),
 * so no PII can leak through application logs.</p>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any
 * <em>authenticated</em> caller (USER or ADMIN) may read customer data, exactly as the
 * legacy account-view transaction was reachable from the regular user menu. The
 * requirement that the caller be authenticated at all is enforced by the application
 * {@code SecurityFilterChain} (see {@code com.carddemo.security.SecurityConfig}) &mdash;
 * anonymous requests are rejected with {@code 401} before reaching this method.
 * Finer-grained, per-record authorization (e.g. "a user may read only their own customer
 * record") is a service-layer concern and is not part of the legacy behavior, so it is not
 * introduced here (AAP &sect;0.7.2 &mdash; no feature additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code custId} validated as a 9-digit numeric
 *       ({@code @Min(1)}/{@code @Max(999999999)}) mirroring {@code CUST-ID PIC 9(09)}.</li>
 *   <li><b>PR-20</b> &mdash; SSN masking happens in the mapper/service; this controller
 *       only forwards an already-masked {@link CustomerDto} and never logs the body.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*});
 *       no {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok
 *       {@link RequiredArgsConstructor} over the {@code final} {@link CustomerService}
 *       field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Why only {@code GET}:</b> per AAP &sect;0.4.1.1 only {@code GET
 *       /api/customers/{custId}} is in scope. The legacy system exposed no direct customer
 *       mutation outside account-update and bill-payment flows, so no {@code POST}/{@code PUT}
 *       endpoints are added (no feature additions).</li>
 *   <li><b>Why {@code Long} for {@code custId}:</b> {@code CUST-ID PIC 9(09)} has a maximum
 *       of {@code 999,999,999}, which fits comfortably in a signed 64-bit {@link Long} and
 *       matches the {@code custId} declaration on the {@code Customer} JPA entity.</li>
 *   <li><b>No PF3/PF12 navigation:</b> the COBOL program chained back to {@code COMEN01C}/
 *       {@code COADM01C} via {@code EXEC CICS XCTL}; REST is stateless and the client
 *       controls navigation, so no return-to-screen logic exists here.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CVCUS01Y customer record layout).
 *
 * @see CustomerService the service that performs the keyed customer-master read and SSN masking
 * @see CustomerDto the outbound (SSN-masked) customer payload
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Customer", description = "Customer read endpoints (replaces COACTVWC customer-info portion, TRANID=CAVW)")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    /**
     * Customer retrieval service replacing the {@code COACTVWC} customer-master read
     * ({@code 9400-GETCUSTDATA-BYCUST}). Injected by type through the Lombok-generated
     * constructor (PR-29). {@link CustomerService#getCustomer(Long)} performs the keyed
     * lookup, raises {@code AccountNotFoundException} on a not-found customer, and returns
     * a {@link CustomerDto} whose SSN is already masked per PR-20 &mdash; this controller
     * never accesses raw customer data nor performs masking itself.
     */
    private final CustomerService customerService;

    /**
     * Retrieves a single customer by its 9-digit primary key and returns the SSN-masked
     * {@link CustomerDto}.
     *
     * <p>This is the REST replacement for the customer-master read of
     * {@code app/cbl/COACTVWC.cbl} paragraph {@code 9400-GETCUSTDATA-BYCUST}
     * ({@code COACTVWC.cbl:L825-L870}). The lookup, not-found handling, and SSN masking are
     * delegated entirely to {@link CustomerService#getCustomer(Long)}:</p>
     * <ul>
     *   <li>customer found ({@code DFHRESP(NORMAL)}) &rarr; {@code 200 OK} with the masked
     *       {@link CustomerDto};</li>
     *   <li>customer absent ({@code DFHRESP(NOTFND)}) &rarr; the service throws
     *       {@code AccountNotFoundException} with the exact COBOL message
     *       {@code "Did not find associated customer in master file"}, mapped to
     *       {@code 404 Not Found};</li>
     *   <li>a file/read error ({@code WHEN OTHER}) &rarr; data-access failure mapped to
     *       {@code 500 Internal Server Error}.</li>
     * </ul>
     *
     * <p>Validation runs during argument binding (PR-13): the class-level {@code @Validated}
     * causes the {@link Min @Min(1)} / {@link Max @Max(999999999)} constraints to be
     * enforced, so an out-of-range {@code custId} is rejected with {@code 400 Bad Request}
     * (via {@code ConstraintViolationException}) and a non-numeric segment with {@code 400}
     * (via {@code MethodArgumentTypeMismatchException}) &mdash; both before the service is
     * invoked.</p>
     *
     * <p>Only the {@code custId} is logged, at {@code DEBUG}. The returned {@link CustomerDto}
     * carries PII (masked SSN, government id, date of birth, phone numbers) and is therefore
     * never logged (PR-20).</p>
     *
     * @param custId the customer primary key, mirroring COBOL {@code CUST-ID PIC 9(09)};
     *               must be a positive 9-digit number ({@code 1 .. 999999999})
     * @return {@code 200 OK} with the customer as a {@link CustomerDto} whose SSN is masked
     *         to {@code ***-**-####} (PR-20)
     */
    @GetMapping("/{custId}")
    @Operation(
            summary = "Get customer by ID",
            description = "Retrieves a customer record by 9-digit customer ID. "
                    + "SSN is masked as ***-**-#### in the response (PR-20). "
                    + "Replaces the customer-info read of COACTVWC paragraph 9400-GETCUSTDATA-BYCUST.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer found"),
            @ApiResponse(responseCode = "400", description = "Invalid customer ID format (not a positive 9-digit number)"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Customer not found ('Did not find associated customer in master file')"),
            @ApiResponse(responseCode = "500", description = "Internal server error (customer file read error)")
    })
    public ResponseEntity<CustomerDto> getCustomer(
            @Parameter(description = "9-digit numeric customer ID", example = "100000001")
            @PathVariable("custId")
            @Min(value = 1L, message = "Customer ID must be a positive 9-digit number")
            @Max(value = 999999999L, message = "Customer ID must be a positive 9-digit number")
            Long custId) {

        // Log the path variable only (DEBUG). The response DTO carries PII (masked SSN,
        // government id, DOB, phone numbers) and must never be logged (PR-20).
        log.debug("GET /api/customers/{}", custId);

        // Delegate to the service, which performs the keyed CUSTDAT read (COACTVWC
        // 9400-GETCUSTDATA-BYCUST), throws AccountNotFoundException on a not-found customer
        // (-> 404), and returns a CustomerDto with the SSN already masked (PR-20). The
        // controller forwards the masked DTO unchanged in the 200 OK response body.
        CustomerDto customer = customerService.getCustomer(custId);
        return ResponseEntity.ok(customer);
    }
}
