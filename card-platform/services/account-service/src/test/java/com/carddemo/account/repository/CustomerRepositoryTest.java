package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.account.entity.CustomerEntity;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;

/**
 * Checks that {@link CustomerRepository} resolves the customer master row, and that the seeded
 * table holds record one of {@code app/data/ASCII/custdata.txt}.
 *
 * <p>The two finders reproduce two Customer Information Control System (CICS) reads of one
 * dataset. Paragraph {@code 9400-GETCUSTDATA-BYCUST.} issues the plain keyed read at
 * {@code app/cbl/COACTUPC.cbl:L3753-L3761}. The write path issues the keyed read for update at
 * {@code app/cbl/COACTUPC.cbl:L3921-L3930}.</p>
 *
 * <p>The key holds nine digits. {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}
 * declares the width, and {@code KEYS(9 0)} at {@code app/jcl/CUSTFILE.jcl:L50} repeats it in the
 * Virtual Storage Access Method (VSAM) cluster definition. One record spans 500 bytes, which
 * {@code RECORDSIZE(500 500)} at {@code app/jcl/CUSTFILE.jcl:L51} names: 332 mapped bytes and the
 * trailing {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23}.</p>
 *
 * <p>Every value assertion cites its one-based fixture offsets and its copybook line. The seed
 * migration stores each character column at its full declared width, so a value shorter than its
 * field arrives padded with spaces. These tests read the database and read no fixture byte.</p>
 *
 * <p>{@code card-platform/docs/traceability-matrix.md} records one rename:
 * {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy:L11} maps to column
 * {@code address_city}. {@code card-platform/docs/decision-log.md} holds the reasoning for the
 * column types the migration declares. {@code card-platform/docs/data-model.md} draws the
 * table.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerRepositoryTest extends AbstractAccountPostgresTest {

    /** Key of record one, offsets (1,9), {@code app/cpy/CVCUS01Y.cpy:L5}. */
    private static final String RECORD_ONE_KEY = "000000001";

    /** A well-formed nine-digit key that no seeded row carries. */
    private static final String ABSENT_KEY = "999999999";

    @Autowired
    private CustomerRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Resolves record one through the finder under test.
     *
     * @return the seeded customer the key {@value #RECORD_ONE_KEY} names
     */
    private CustomerEntity recordOne() {
        return repository.findByCustomerId(RECORD_ONE_KEY).orElseThrow();
    }

    /**
     * Reads the declared parameter and annotations of one finder, which erasure keeps intact on the
     * interface.
     *
     * @param name the finder name
     * @return the declared method
     */
    private static Method declaredFinder(String name) {
        return Arrays.stream(CustomerRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @Order(1)
    @DisplayName("findByCustomerId resolves seeded customer 000000001 and returns a present"
            + " Optional")
    void findByCustomerIdResolvesRecordOne() {
        Optional<CustomerEntity> found = repository.findByCustomerId(RECORD_ONE_KEY);

        assertThat(found).isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("findByCustomerId returns an empty Optional for a key no row carries")
    void findByCustomerIdReturnsEmptyOptionalForAnAbsentKey() {
        Optional<CustomerEntity> found = repository.findByCustomerId(ABSENT_KEY);

        assertThat(found).isNotNull().isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("the inherited findById takes the same nine-character key and resolves the same"
            + " customer row")
    void findByIdResolvesTheSameRowAsFindByCustomerId() {
        Optional<CustomerEntity> byId = repository.findById(RECORD_ONE_KEY);

        assertThat(byId).isPresent();
        assertThat(byId.orElseThrow().getCustomerId()).isEqualTo(recordOne().getCustomerId());
    }

    /**
     * The declared finder parameter and the mapped identifier both hold text.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} is a display field nine
     * characters wide, and {@code KEYS(9 0)} at {@code app/jcl/CUSTFILE.jcl:L50} keys the cluster
     * on those nine characters. Offsets (1,9) of record one hold {@value #RECORD_ONE_KEY}, whose
     * eight leading zeros survive only in a text column.</p>
     */
    @Test
    @Order(4)
    @DisplayName("the customer identifier is String on the finder parameter and on the Jakarta"
            + " Persistence mapping, and is no number type")
    void theCustomerIdentifierIsTextAndNoNumberType() {
        Class<?> finderParameter = declaredFinder("findByCustomerId").getParameterTypes()[0];
        Class<?> mappedIdentifier = entityManagerFactory.getMetamodel()
                .entity(CustomerEntity.class)
                .getIdType()
                .getJavaType();

        assertThat(finderParameter).isEqualTo(String.class);
        assertThat(mappedIdentifier).isEqualTo(String.class);
        assertThat(Number.class.isAssignableFrom(mappedIdentifier)).isFalse();
        assertThat(recordOne().getCustomerId()).isEqualTo(RECORD_ONE_KEY);
    }

    /**
     * The read-for-update finder declares a pessimistic write lock and resolves record one.
     *
     * <p>The source issues that read at {@code app/cbl/COACTUPC.cbl:L3921-L3930} and the plain
     * keyed read at {@code app/cbl/COACTUPC.cbl:L3753-L3761}. The assertions read the declared
     * annotation of each finder and then the resolved row.</p>
     */
    @Test
    @Order(5)
    @Transactional
    @DisplayName("findForUpdateByCustomerId declares the PESSIMISTIC_WRITE lock mode,"
            + " findByCustomerId declares none, and the locking finder resolves customer"
            + " 000000001")
    void findForUpdateByCustomerIdDeclaresPessimisticWriteAndResolvesRecordOne() {
        Lock declaredOnUpdateFinder = declaredFinder("findForUpdateByCustomerId")
                .getAnnotation(Lock.class);
        Lock declaredOnPlainFinder = declaredFinder("findByCustomerId").getAnnotation(Lock.class);

        assertThat(declaredOnUpdateFinder).isNotNull();
        assertThat(declaredOnUpdateFinder.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(declaredOnPlainFinder).isNull();

        Optional<CustomerEntity> locked = repository.findForUpdateByCustomerId(RECORD_ONE_KEY);

        assertThat(locked).isPresent();
        assertThat(locked.orElseThrow().getCustomerId()).isEqualTo(RECORD_ONE_KEY);
    }

    /**
     * The three name columns hold record one at their full declared widths.
     *
     * <p>| Field | Offsets | Copybook locator | Stored value |
     * | --- | --- | --- | --- |
     * | first name | 10-34 | {@code app/cpy/CVCUS01Y.cpy:L6} | {@code Immanuel} and 17 spaces |
     * | middle name | 35-59 | {@code app/cpy/CVCUS01Y.cpy:L7} | {@code Madeline} and 17 spaces |
     * | last name | 60-84 | {@code app/cpy/CVCUS01Y.cpy:L8} | {@code Kessler} and 18 spaces |</p>
     */
    @Test
    @Order(6)
    @DisplayName("the first, middle and last name columns hold record one padded to twenty-five"
            + " characters")
    void theNameColumnsHoldRecordOneInStoredForm() {
        CustomerEntity customer = recordOne();

        assertThat(customer.getFirstName()).isEqualTo("Immanuel                 ");
        assertThat(customer.getMiddleName()).isEqualTo("Madeline                 ");
        assertThat(customer.getLastName()).isEqualTo("Kessler                  ");
    }

    /**
     * The six address columns hold record one at their full declared widths.
     *
     * <p>| Field | Offsets | Copybook locator | Stored value |
     * | --- | --- | --- | --- |
     * | address line 1 | 85-134 | {@code app/cpy/CVCUS01Y.cpy:L9} | {@code 618 Deshaun Route} and
     * 33 spaces |
     * | address line 2 | 135-184 | {@code app/cpy/CVCUS01Y.cpy:L10} | {@code Apt. 802} and 42
     * spaces |
     * | address line 3, column {@code address_city} | 185-234 |
     * {@code app/cpy/CVCUS01Y.cpy:L11} | {@code Altenwerthshire} and 35 spaces |
     * | state code | 235-236 | {@code app/cpy/CVCUS01Y.cpy:L12} | {@code NC} |
     * | country code | 237-239 | {@code app/cpy/CVCUS01Y.cpy:L13} | {@code USA} |
     * | postal code | 240-249 | {@code app/cpy/CVCUS01Y.cpy:L14} | {@code 12546} and 5 spaces |</p>
     */
    @Test
    @Order(7)
    @DisplayName("the two address lines, the city, state, country and postal code columns hold"
            + " record one in stored form")
    void theAddressColumnsHoldRecordOneInStoredForm() {
        CustomerEntity customer = recordOne();

        assertThat(customer.getAddressLine1())
                .isEqualTo("618 Deshaun Route                                 ");
        assertThat(customer.getAddressLine2())
                .isEqualTo("Apt. 802                                          ");
        assertThat(customer.getAddressCity())
                .isEqualTo("Altenwerthshire                                   ");
        assertThat(customer.getAddressStateCode()).isEqualTo("NC");
        assertThat(customer.getAddressCountryCode()).isEqualTo("USA");
        assertThat(customer.getAddressZip()).isEqualTo("12546     ");
    }

    /**
     * Both telephone columns hold record one at their full declared widths.
     *
     * <p>| Field | Offsets | Copybook locator | Stored value |
     * | --- | --- | --- | --- |
     * | telephone number 1 | 250-264 | {@code app/cpy/CVCUS01Y.cpy:L15} | {@code (908)119-8310} and
     * 2 spaces |
     * | telephone number 2 | 265-279 | {@code app/cpy/CVCUS01Y.cpy:L16} | {@code (373)693-8684} and
     * 2 spaces |</p>
     */
    @Test
    @Order(8)
    @DisplayName("both telephone number columns hold record one padded to fifteen characters")
    void theTelephoneColumnsHoldRecordOneInStoredForm() {
        CustomerEntity customer = recordOne();

        assertThat(customer.getPhoneNumber1()).isEqualTo("(908)119-8310  ");
        assertThat(customer.getPhoneNumber2()).isEqualTo("(373)693-8684  ");
    }

    /**
     * Two text columns keep every leading zero the fixture encodes.
     *
     * <p>| Field | Offsets | Copybook locator | Leading zeros |
     * | --- | --- | --- | --- |
     * | Social Security Number | 280-288 | {@code app/cpy/CVCUS01Y.cpy:L17} | one, kept |
     * | government-issued identifier | 289-308 | {@code app/cpy/CVCUS01Y.cpy:L18} | twelve,
     * kept |</p>
     *
     * <p>{@code CUST-SSN PIC 9(09)} names nine digits, and the stored form holds all nine
     * characters. A numeric column returns eight digits and names a different person, and the
     * second assertion separates the two forms. Neither value reaches a log line, an event payload
     * or a failure message, and every assertion here keeps the default description.</p>
     */
    @Test
    @Order(9)
    @DisplayName("the Social Security Number and government-issued identifier columns keep every"
            + " leading zero the fixture encodes")
    void theSensitiveIdentifierColumnsKeepEveryLeadingZero() {
        CustomerEntity customer = recordOne();

        assertThat(customer.getSocialSecurityNumber()).isEqualTo("020973888");
        assertThat(customer.getSocialSecurityNumber()).isNotEqualTo("20973888");
        assertThat(customer.getGovernmentIssuedId()).isEqualTo("00000000000049368437");
    }

    /**
     * The four remaining columns hold record one.
     *
     * <p>| Field | Offsets | Copybook locator | Stored value |
     * | --- | --- | --- | --- |
     * | date of birth | 309-318 | {@code app/cpy/CVCUS01Y.cpy:L19} | {@code 1961-06-08} |
     * | transfer account identifier | 319-328 | {@code app/cpy/CVCUS01Y.cpy:L20} |
     * {@code 0053581756} |
     * | primary card-holder flag | 329 | {@code app/cpy/CVCUS01Y.cpy:L21} | {@code Y} |
     * | credit score | 330-332 | {@code app/cpy/CVCUS01Y.cpy:L22} | 274 |</p>
     *
     * <p>The date of birth holds ten characters in year, month and day order with two hyphens.
     * The credit score is the one numeric column of the row, and the comparison here reads its
     * magnitude.</p>
     */
    @Test
    @Order(10)
    @DisplayName("the date of birth, transfer account identifier, primary card-holder flag and"
            + " credit score columns hold record one values")
    void theRemainingColumnsHoldRecordOneValues() {
        CustomerEntity customer = recordOne();

        assertThat(customer.getDateOfBirth()).isEqualTo("1961-06-08");
        assertThat(customer.getEftAccountId()).isEqualTo("0053581756");
        assertThat(customer.getPrimaryCardHolderIndicator()).isEqualTo("Y");
        assertThat(customer.getFicoCreditScore()).isEqualByComparingTo(new BigDecimal("274"));
    }

    /**
     * The row count after every other test in the class.
     *
     * <p>{@code app/data/ASCII/custdata.txt} holds fifty records of 500 bytes, and the seed
     * migration loads each one. The method order places this test last, and no test above writes a
     * row, so the same count also reports that the table came through the class untouched.</p>
     */
    @Test
    @Order(11)
    @DisplayName("the inherited count reads 50 seeded customers after every other test in the"
            + " class")
    void countReadsFiftySeededCustomers() {
        assertThat(repository.count()).isEqualTo(50L);
    }
}
