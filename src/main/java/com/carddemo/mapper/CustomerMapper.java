package com.carddemo.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.carddemo.dto.customer.CustomerDto;
import com.carddemo.entity.Customer;

import org.springframework.stereotype.Component;

/**
 * Hand-coded mapper between the {@link Customer} JPA entity and the customer
 * REST DTO ({@link CustomerDto}). Used by {@code CustomerController} (GET/PUT
 * endpoints) and {@code CustomerService}, and indirectly by {@code AccountService}
 * and {@code CardService} when they surface customer information.
 *
 * <p>This class is a Spring {@code @Component} so it can be constructor-injected
 * into its consumers (PR-29: constructor injection only, no field injection). It
 * carries no injectable dependencies — the default constructor is sufficient —
 * and therefore needs no Lombok {@code @RequiredArgsConstructor}.</p>
 *
 * <p><strong>CRITICAL SECURITY BOUNDARY (PR-20 — SSN masking).</strong> This mapper
 * is the authoritative point at which customer data crosses the REST boundary. The
 * raw 9-digit Social Security Number stored in {@code Customer.ssn} is PII and MUST
 * NEVER be returned in plain form. Accordingly {@link #toDto(Customer)} (and
 * {@link #toDtoList(List)}, which delegates to it) masks the SSN to the format
 * {@code ***-**-####} (only the last 4 digits visible) via {@link #maskSsn(String)}.
 * The entity always holds the unmasked value; masking is exclusively a mapper /
 * DTO-boundary concern.</p>
 *
 * <p><strong>Round-trip SSN preservation (CRITICAL).</strong> Because outbound
 * responses carry a masked SSN, a client that performs a GET-then-PUT round trip
 * would naively echo back the masked string ({@code ***-**-6789}). To prevent that
 * masked value from overwriting (and permanently destroying) the stored 9-digit
 * SSN, {@link #updateEntity(CustomerDto, Customer)} preserves the existing entity
 * SSN whenever the inbound value is {@code null} or detected as already masked
 * ({@link #isMaskedSsn(String)}). On the create path, {@link #toEntity(CustomerDto)}
 * rejects an inbound masked SSN by setting the entity SSN to {@code null}, so a
 * round-tripped masked value can never be persisted as if it were a real SSN.</p>
 *
 * <p><strong>Field-name translations (6, bidirectional).</strong> The entity uses
 * shorter COBOL-derived Java names; the DTO uses extended English names. Every
 * mapping method applies the full set:</p>
 * <table border="1">
 *   <caption>Entity &harr; DTO field-name translations</caption>
 *   <tr><th>Customer (entity)</th><th>CustomerDto (DTO)</th></tr>
 *   <tr><td>{@code stateCd}</td><td>{@code addrStateCd}</td></tr>
 *   <tr><td>{@code countryCd}</td><td>{@code addrCountryCd}</td></tr>
 *   <tr><td>{@code zipCd}</td><td>{@code addrZip}</td></tr>
 *   <tr><td>{@code dob}</td><td>{@code dateOfBirth}</td></tr>
 *   <tr><td>{@code primaryCardHolderInd}</td><td>{@code priCardHolderInd}</td></tr>
 *   <tr><td>{@code ficoScore}</td><td>{@code ficoCreditScore}</td></tr>
 * </table>
 *
 * <p><strong>Date-of-birth type conversion.</strong> The {@code dob} translation is
 * also a <em>type</em> conversion: the entity field {@code Customer.dob} is a
 * {@link LocalDate} (mapped to a SQL {@code DATE} column) whereas the DTO field
 * {@code CustomerDto.dateOfBirth} is a {@link String} in {@code YYYY-MM-DD} form
 * (matching the COBOL {@code CUST-DOB-YYYY-MM-DD PIC X(10)} external shape). Outbound
 * the {@code LocalDate} is rendered with {@link LocalDate#toString()} (ISO-8601,
 * {@code YYYY-MM-DD}); inbound the string is parsed with {@link LocalDate#parse(CharSequence)}
 * (also ISO-8601). Both directions are {@code null}-safe. No custom
 * {@code DateTimeFormatter} or {@code DateConversionUtil} is required because the ISO
 * format already equals the COBOL/DTO external format; a malformed inbound date that
 * bypassed DTO validation surfaces as a {@code DateTimeParseException} (handled at the
 * REST boundary).</p>
 *
 * <p><strong>Exposed surface (PR-13, PR-22).</strong> Exactly 18 user fields from the
 * 500-byte {@code CUSTOMER-RECORD} are mapped; the trailing COBOL {@code FILLER
 * PIC X(168)} carries no business meaning and is NOT exposed. The entity's
 * {@code @Version} optimistic-locking field and the Spring Data audit fields
 * ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}) are
 * server-controlled and intentionally NOT exposed in the DTO.</p>
 *
 * <p>Reference COBOL source: {@code app/cpy/CVCUS01Y.cpy} (500-byte
 * {@code CUSTOMER-RECORD}, CardDemo_v1.0-15-g27d6c6f-68).</p>
 *
 * @see com.carddemo.entity.Customer
 * @see com.carddemo.dto.customer.CustomerDto
 */
@Component
public class CustomerMapper {

    /**
     * The masked SSN value emitted when the source SSN is {@code null} or shorter
     * than 4 characters. Conveys "no usable SSN available" while keeping the
     * consistent {@code ***-**-####} shape expected by clients (PR-20).
     */
    private static final String MASKED_SSN_DEFAULT = "***-**-####";

    /**
     * The masked-SSN prefix used to detect an already-masked value on inbound
     * requests (the GET-then-PUT round trip). When an inbound SSN starts with this
     * prefix, the existing entity SSN is preserved rather than overwritten
     * ({@link #updateEntity(CustomerDto, Customer)}) or rejected on create
     * ({@link #toEntity(CustomerDto)}).
     */
    private static final String MASKED_SSN_PREFIX = "***";

    /**
     * Converts a {@link Customer} entity to a {@link CustomerDto} for outbound REST
     * responses.
     *
     * <p><strong>CRITICAL PR-20:</strong> the SSN is masked via
     * {@link #maskSsn(String)} to {@code ***-**-####} (last 4 digits visible only);
     * the raw SSN MUST NEVER leave the server through this mapper.</p>
     *
     * <p>Applies the 6 field-name translations (entity &rarr; DTO):
     * {@code stateCd}&rarr;{@code addrStateCd}, {@code countryCd}&rarr;{@code addrCountryCd},
     * {@code zipCd}&rarr;{@code addrZip}, {@code dob}&rarr;{@code dateOfBirth},
     * {@code primaryCardHolderInd}&rarr;{@code priCardHolderInd},
     * {@code ficoScore}&rarr;{@code ficoCreditScore}. The {@code dob} translation also
     * converts {@link LocalDate} &rarr; {@code String} (ISO {@code YYYY-MM-DD}) in a
     * {@code null}-safe manner.</p>
     *
     * <p>The {@code @Version} field, audit fields, and the 168-byte COBOL
     * {@code FILLER} are intentionally NOT exposed (PR-22, PR-13).</p>
     *
     * @param customer the {@code Customer} entity (may be {@code null})
     * @return a {@code CustomerDto} with a masked SSN, or {@code null} if the input
     *         is {@code null}
     */
    public CustomerDto toDto(Customer customer) {
        if (customer == null) {
            return null;
        }
        return CustomerDto.builder()
                .custId(customer.getCustId())
                .firstName(customer.getFirstName())
                .middleName(customer.getMiddleName())
                .lastName(customer.getLastName())
                .addrLine1(customer.getAddrLine1())
                .addrLine2(customer.getAddrLine2())
                .addrLine3(customer.getAddrLine3())
                .addrStateCd(customer.getStateCd())                   // translation 1
                .addrCountryCd(customer.getCountryCd())               // translation 2
                .addrZip(customer.getZipCd())                         // translation 3
                .phoneNum1(customer.getPhoneNum1())
                .phoneNum2(customer.getPhoneNum2())
                .ssn(maskSsn(customer.getSsn()))                      // PR-20 SSN MASKING
                .govtIssuedId(customer.getGovtIssuedId())
                .dateOfBirth(formatDob(customer.getDob()))            // translation 4 (LocalDate -> String)
                .eftAccountId(customer.getEftAccountId())
                .priCardHolderInd(customer.getPrimaryCardHolderInd()) // translation 5
                .ficoCreditScore(customer.getFicoScore())             // translation 6
                .build();
    }

    /**
     * Builds a transient {@link Customer} entity from a {@link CustomerDto} for the
     * admin customer-creation flow.
     *
     * <p>Applies the reverse of the 6 field-name translations (DTO &rarr; entity),
     * including the {@code dateOfBirth} ({@code String}) &rarr; {@code dob}
     * ({@link LocalDate}) conversion (ISO {@code YYYY-MM-DD}, {@code null}-safe).</p>
     *
     * <p><strong>SSN handling:</strong> if the DTO's {@code ssn} starts with the mask
     * prefix ({@code "***"}) it is rejected by setting {@code customer.ssn} to
     * {@code null} — an inbound masked SSN on a create call indicates a client error
     * (a GET response was naively round-tripped). The caller (service / validator) is
     * responsible for ensuring an inbound SSN is in raw 9-digit form on create.</p>
     *
     * <p>Server-controlled fields are NOT set: {@code version} (JPA optimistic
     * locking) and the audit fields (JPA {@code AuditingEntityListener}).</p>
     *
     * @param dto the {@code CustomerDto} (may be {@code null})
     * @return a transient {@code Customer} entity, or {@code null} if the input is
     *         {@code null}
     */
    public Customer toEntity(CustomerDto dto) {
        if (dto == null) {
            return null;
        }
        Customer customer = new Customer();
        customer.setCustId(dto.getCustId());
        customer.setFirstName(dto.getFirstName());
        customer.setMiddleName(dto.getMiddleName());
        customer.setLastName(dto.getLastName());
        customer.setAddrLine1(dto.getAddrLine1());
        customer.setAddrLine2(dto.getAddrLine2());
        customer.setAddrLine3(dto.getAddrLine3());
        customer.setStateCd(dto.getAddrStateCd());                   // reverse translation 1
        customer.setCountryCd(dto.getAddrCountryCd());               // reverse translation 2
        customer.setZipCd(dto.getAddrZip());                         // reverse translation 3
        customer.setPhoneNum1(dto.getPhoneNum1());
        customer.setPhoneNum2(dto.getPhoneNum2());
        customer.setSsn(isMaskedSsn(dto.getSsn()) ? null : dto.getSsn()); // reject masked SSN on create
        customer.setGovtIssuedId(dto.getGovtIssuedId());
        customer.setDob(parseDob(dto.getDateOfBirth()));             // reverse translation 4 (String -> LocalDate)
        customer.setEftAccountId(dto.getEftAccountId());
        customer.setPrimaryCardHolderInd(dto.getPriCardHolderInd()); // reverse translation 5
        customer.setFicoScore(dto.getFicoCreditScore());             // reverse translation 6
        return customer;
    }

    /**
     * Applies a partial update from a {@link CustomerDto} to an existing managed
     * {@link Customer} entity for the {@code PUT /api/customers/{custId}} flow. The
     * entity is mutated in place so JPA dirty-checking issues the {@code UPDATE}
     * within the active transaction.
     *
     * <p>Applies the reverse of the 6 field-name translations, including the
     * {@code dateOfBirth} ({@code String}) &rarr; {@code dob} ({@link LocalDate})
     * conversion ({@code null}-safe).</p>
     *
     * <p><strong>CRITICAL SSN round-trip preservation:</strong> if the DTO's
     * {@code ssn} is {@code null} or starts with the mask prefix ({@code "***"}), the
     * existing entity SSN is preserved (NOT overwritten). This prevents the common
     * bug where a GET-then-PUT round trip would write the masked value
     * ({@code ***-**-6789}) back over the stored 9-digit SSN, destroying data. A new
     * raw SSN (not masked, not {@code null}) is applied normally.</p>
     *
     * <p>The {@code custId} primary key is NOT updated (immutable). Server-controlled
     * fields ({@code version}, audit fields) are NOT modified.</p>
     *
     * @param dto      the {@code CustomerDto} carrying updated values (must not be
     *                 {@code null})
     * @param customer the existing managed {@code Customer} entity to mutate (must not
     *                 be {@code null})
     * @throws NullPointerException if {@code dto} or {@code customer} is {@code null}
     */
    public void updateEntity(CustomerDto dto, Customer customer) {
        Objects.requireNonNull(dto, "dto must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        // custId NOT updated (immutable primary key)
        customer.setFirstName(dto.getFirstName());
        customer.setMiddleName(dto.getMiddleName());
        customer.setLastName(dto.getLastName());
        customer.setAddrLine1(dto.getAddrLine1());
        customer.setAddrLine2(dto.getAddrLine2());
        customer.setAddrLine3(dto.getAddrLine3());
        customer.setStateCd(dto.getAddrStateCd());                   // reverse translation 1
        customer.setCountryCd(dto.getAddrCountryCd());               // reverse translation 2
        customer.setZipCd(dto.getAddrZip());                         // reverse translation 3
        customer.setPhoneNum1(dto.getPhoneNum1());
        customer.setPhoneNum2(dto.getPhoneNum2());
        // CRITICAL: preserve existing SSN if inbound is null or masked (round-trip safety)
        if (dto.getSsn() != null && !isMaskedSsn(dto.getSsn())) {
            customer.setSsn(dto.getSsn());
        }
        // else: customer.ssn left unchanged
        customer.setGovtIssuedId(dto.getGovtIssuedId());
        customer.setDob(parseDob(dto.getDateOfBirth()));             // reverse translation 4 (String -> LocalDate)
        customer.setEftAccountId(dto.getEftAccountId());
        customer.setPrimaryCardHolderInd(dto.getPriCardHolderInd()); // reverse translation 5
        customer.setFicoScore(dto.getFicoCreditScore());             // reverse translation 6
    }

    /**
     * Converts a list of {@link Customer} entities to a list of {@link CustomerDto}
     * objects, preserving order. Every element is mapped via {@link #toDto(Customer)},
     * so PR-20 SSN masking applies uniformly to every entry.
     *
     * <p>Returns an empty, immutable list when the input is {@code null} or empty; the
     * result is never {@code null}.</p>
     *
     * @param customers the list of {@code Customer} entities (may be {@code null})
     * @return a new immutable list of {@code CustomerDto} with masked SSNs, in input
     *         order (never {@code null})
     */
    public List<CustomerDto> toDtoList(List<Customer> customers) {
        if (customers == null || customers.isEmpty()) {
            return List.of();
        }
        return customers.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Masks a raw 9-digit SSN to the format {@code ***-**-####} (last 4 digits visible
     * only) per PR-20. This is the sole mechanism by which an SSN is emitted to the
     * REST boundary; any new SSN-emitting code path MUST route through here.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "123456789"} &rarr; {@code "***-**-6789"}</li>
     *   <li>{@code "987654321"} &rarr; {@code "***-**-4321"}</li>
     *   <li>{@code null} &rarr; {@code "***-**-####"}</li>
     *   <li>{@code ""} &rarr; {@code "***-**-####"}</li>
     *   <li>{@code "12"} (too short) &rarr; {@code "***-**-####"}</li>
     * </ul>
     *
     * @param ssn the raw SSN (may be {@code null}, may be malformed)
     * @return a masked SSN string in the format {@code ***-**-####}; never {@code null}
     */
    private static String maskSsn(String ssn) {
        if (ssn == null || ssn.length() < 4) {
            return MASKED_SSN_DEFAULT;
        }
        return "***-**-" + ssn.substring(ssn.length() - 4);
    }

    /**
     * Detects whether an inbound SSN string appears to be an already-masked value
     * (starts with the mask prefix {@code "***"}). Used by {@link #toEntity(CustomerDto)}
     * and {@link #updateEntity(CustomerDto, Customer)} for round-trip safety.
     *
     * @param ssn the inbound SSN string (may be {@code null})
     * @return {@code true} if the input is non-null and starts with {@code "***"};
     *         {@code false} otherwise (including {@code null})
     */
    private static boolean isMaskedSsn(String ssn) {
        return ssn != null && ssn.startsWith(MASKED_SSN_PREFIX);
    }

    /**
     * Renders an entity {@link LocalDate} date-of-birth as the DTO's {@code String}
     * representation in ISO {@code YYYY-MM-DD} form (matching the COBOL
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} external shape). {@code null}-safe.
     *
     * @param dob the entity date of birth (may be {@code null})
     * @return the ISO {@code YYYY-MM-DD} string, or {@code null} if {@code dob} is
     *         {@code null}
     */
    private static String formatDob(LocalDate dob) {
        return dob == null ? null : dob.toString();
    }

    /**
     * Parses the DTO's {@code String} date-of-birth (ISO {@code YYYY-MM-DD}) into the
     * entity {@link LocalDate}. {@code null}-safe. A non-null, malformed value (one
     * that bypassed the DTO's {@code @Pattern} validation) raises
     * {@link java.time.format.DateTimeParseException}, which is handled at the REST
     * boundary.
     *
     * @param dateOfBirth the DTO date-of-birth string (may be {@code null})
     * @return the parsed {@link LocalDate}, or {@code null} if {@code dateOfBirth} is
     *         {@code null}
     */
    private static LocalDate parseDob(String dateOfBirth) {
        return dateOfBirth == null ? null : LocalDate.parse(dateOfBirth);
    }
}
