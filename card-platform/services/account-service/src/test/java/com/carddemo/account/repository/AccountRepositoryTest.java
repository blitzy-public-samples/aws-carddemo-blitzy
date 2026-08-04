package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.entity.AccountEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks the two finders of {@link AccountRepository} and the field values the seeded account rows
 * carry.
 *
 * <p>The class extends {@link AbstractAccountPostgresTest}, which owns the one PostgreSQL container
 * the module shares and starts the Spring context. No container declaration, no context annotation
 * and no class-level transaction appear here.</p>
 *
 * <p><b>The two source reads.</b> {@link AccountRepository#findByAccountId(String)} reproduces the
 * plain keyed read at {@code app/cbl/COACTUPC.cbl:L3703-L3711}, which paragraph
 * {@code 9300-GETACCTDATA-BYACCT.} at {@code app/cbl/COACTUPC.cbl:L3701} issues.
 * {@link AccountRepository#findForUpdateByAccountId(String)} reproduces the keyed read carrying
 * {@code UPDATE} at {@code app/cbl/COACTUPC.cbl:L3894-L3903}, which
 * {@code 9600-WRITE-PROCESSING.} at {@code app/cbl/COACTUPC.cbl:L3888} issues. Both are Customer
 * Information Control System (CICS) reads against {@code LIT-ACCTFILENAME}.</p>
 *
 * <p><b>The operation-code ancestor.</b> {@code app/cbl/CBSTM03B.CBL:L100-L112} declares one
 * parameter area carrying an eight-character dataset name at {@code L101} and a one-character
 * operation code at {@code L102}, with six codes at {@code L103-L108}. The dispatch at
 * {@code app/cbl/CBSTM03B.CBL:L118-L127} selects one paragraph per dataset.</p>
 *
 * <p>Four of those codes map onto {@link AccountRepository}. The keyed read {@code 'K'} becomes
 * a find by identifier, the sequential read {@code 'R'} becomes a stream of every row, the write
 * {@code 'W'} becomes an insert, and the rewrite {@code 'Z'} becomes an update. The open code
 * {@code 'O'} and the close code {@code 'C'} map onto nothing here, and the framework opens and
 * closes each connection.</p>
 *
 * <p><b>The key.</b> The identifier holds eleven digit characters, from {@code ACCT-ID PIC 9(11)}
 * at {@code app/cpy/CVACT01Y.cpy:L5} and {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}.
 * That Job Control Language (JCL) member defines the Virtual Storage Access Method (VSAM)
 * key-sequenced data set the {@code account} table replaces.</p>
 *
 * <p><b>Where the expected values come from.</b> Record 1 of {@code app/data/ASCII/acctdata.txt}
 * supplies every value the assertions below name, and each assertion names the columns it reads.
 * The record spans 122 mapped characters followed by the 178-character filler at
 * {@code app/cpy/CVACT01Y.cpy:L17}, reaching the 300 that {@code RECORDSIZE(300 300)} at
 * {@code app/jcl/ACCTFILE.jcl:L41} declares. Every method here queries the database and opens no
 * fixture file.</p>
 *
 * <p>{@code card-platform/docs/decision-log.md} records the decisions the class rests on, and
 * {@code card-platform/docs/data-model.md} draws the table.</p>
 */
@DisplayName("AccountRepository over the seeded account table")
class AccountRepositoryTest extends AbstractAccountPostgresTest {

    /**
     * The key of record 1 of {@code app/data/ASCII/acctdata.txt}, columns 1 to 11. The fixture
     * carries every leading zero, and {@code app/cpy/CVACT01Y.cpy:L5} declares the eleven
     * positions.
     */
    private static final String SEEDED_ACCOUNT_ID = "00000000001";

    /**
     * A well-formed eleven-digit key that no seeded row carries. The 50 records of
     * {@code app/data/ASCII/acctdata.txt} run from {@code 00000000001} to {@code 00000000050} at
     * columns 1 to 11.
     */
    private static final String UNSEEDED_ACCOUNT_ID = "99999999999";

    /** The record count of {@code app/data/ASCII/acctdata.txt}, one row per record. */
    private static final int SEEDED_ACCOUNT_COUNT = 50;

    /**
     * The scale every account amount carries. {@code app/cpy/CVACT01Y.cpy:L7-L9} and
     * {@code app/cpy/CVACT01Y.cpy:L13-L14} declare five fields as {@code PIC S9(10)V99}, so each
     * holds two digits after the decimal point.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * The group identifier of every seeded row: ten spaces. {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L16} sets the width, and columns 113 to 122 of all 50 records of
     * {@code app/data/ASCII/acctdata.txt} hold spaces.
     */
    private static final String BLANK_GROUP_ID = " ".repeat(10);

    /**
     * The address postal code of every seeded row. Columns 103 to 112 of all 50 records of
     * {@code app/data/ASCII/acctdata.txt} hold this value, the one distinct value in the column.
     */
    private static final String SEEDED_ADDRESS_ZIP = "A000000000";

    @Autowired
    private AccountRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("findByAccountId resolves the seeded account at columns 1 to 11")
    void findByAccountIdResolvesSeededAccount() {
        Optional<AccountEntity> found = repository.findByAccountId(SEEDED_ACCOUNT_ID);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getAccountId()).isEqualTo(SEEDED_ACCOUNT_ID);
    }

    @Test
    @DisplayName("findByAccountId returns an empty Optional for an identifier no row carries")
    void findByAccountIdReturnsEmptyForUnknownIdentifier() {
        Optional<AccountEntity> found = repository.findByAccountId(UNSEEDED_ACCOUNT_ID);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findById resolves the same row as findByAccountId through the declared key type")
    void findByIdResolvesTheSameRow() {
        Optional<AccountEntity> throughInheritedFinder = repository.findById(SEEDED_ACCOUNT_ID);
        Optional<AccountEntity> throughDerivedFinder =
                repository.findByAccountId(SEEDED_ACCOUNT_ID);

        assertThat(throughInheritedFinder).isPresent();
        assertThat(throughDerivedFinder).isPresent();
        AccountEntity inherited = throughInheritedFinder.orElseThrow();
        AccountEntity derived = throughDerivedFinder.orElseThrow();
        assertThat(inherited.getAccountId())
                .as("identifier the inherited finder returns")
                .isEqualTo(derived.getAccountId());
        assertThat(inherited.getCurrentBalance())
                .as("balance the inherited finder returns, one field off the key")
                .isEqualByComparingTo(derived.getCurrentBalance());
    }

    @Test
    @DisplayName("both finders and the entity identifier declare the eleven-character text key")
    void identifierTypeIsText() {
        Method plainFinder = declaredFinder("findByAccountId");
        Method lockingFinder = declaredFinder("findForUpdateByAccountId");

        assertThat(plainFinder.getParameterTypes())
                .as("declared parameter of findByAccountId")
                .containsExactly(String.class);
        assertThat(lockingFinder.getParameterTypes())
                .as("declared parameter of findForUpdateByAccountId")
                .containsExactly(String.class);
        assertThat(entityManager.getMetamodel().entity(AccountEntity.class).getIdType()
                .getJavaType())
                .as("identifier type the Jakarta Persistence model reports for AccountEntity")
                .isEqualTo(String.class);
    }

    @Test
    @DisplayName("count reads the 50 records of app/data/ASCII/acctdata.txt")
    void countReadsEverySeededRow() {
        assertThat(repository.count()).isEqualTo(SEEDED_ACCOUNT_COUNT);
    }

    @Test
    @Transactional
    @DisplayName("findForUpdateByAccountId resolves the seeded account inside a transaction")
    void findForUpdateByAccountIdResolvesSeededAccount() {
        Optional<AccountEntity> locked = repository.findForUpdateByAccountId(SEEDED_ACCOUNT_ID);

        assertThat(locked).isPresent();
        assertThat(locked.orElseThrow().getAccountId()).isEqualTo(SEEDED_ACCOUNT_ID);
    }

    @Test
    @Transactional
    @DisplayName("findForUpdateByAccountId returns an empty Optional for an identifier no row "
            + "carries")
    void findForUpdateByAccountIdReturnsEmptyForUnknownIdentifier() {
        Optional<AccountEntity> locked = repository.findForUpdateByAccountId(UNSEEDED_ACCOUNT_ID);

        assertThat(locked).isEmpty();
    }

    @Test
    @DisplayName("findForUpdateByAccountId declares a pessimistic write lock")
    void findForUpdateByAccountIdDeclaresPessimisticWriteLock() {
        Lock declaredLock = declaredFinder("findForUpdateByAccountId").getAnnotation(Lock.class);

        assertThat(declaredLock)
                .as("Lock annotation on findForUpdateByAccountId")
                .isNotNull();
        assertThat(declaredLock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("findByAccountId returns the identifier and the status of record 1, columns 1 "
            + "to 12")
    void recordOneCarriesItsIdentifierAndStatus() {
        AccountEntity account = seededAccountOne();

        assertThat(account.getAccountId())
                .as("account identifier, columns 1 to 11")
                .isEqualTo(SEEDED_ACCOUNT_ID);
        assertThat(account.getActiveStatus())
                .as("active status, column 12")
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("findByAccountId returns the balance and both limits of record 1 at scale 2, "
            + "columns 13 to 48")
    void recordOneCarriesItsBalanceAndLimits() {
        AccountEntity account = seededAccountOne();
        BigDecimal currentBalance = account.getCurrentBalance();
        BigDecimal creditLimit = account.getCreditLimit();
        BigDecimal cashCreditLimit = account.getCashCreditLimit();

        assertThat(currentBalance)
                .as("current balance, columns 13 to 24")
                .isEqualByComparingTo("194.00");
        assertThat(currentBalance.scale())
                .as("scale of the current balance")
                .isEqualTo(MONEY_SCALE);
        assertThat(creditLimit)
                .as("credit limit, columns 25 to 36")
                .isEqualByComparingTo("2020.00");
        assertThat(creditLimit.scale())
                .as("scale of the credit limit")
                .isEqualTo(MONEY_SCALE);
        assertThat(cashCreditLimit)
                .as("cash credit limit, columns 37 to 48")
                .isEqualByComparingTo("1020.00");
        assertThat(cashCreditLimit.scale())
                .as("scale of the cash credit limit")
                .isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("findByAccountId returns both cycle accumulators of record 1 at scale 2, "
            + "columns 79 to 102")
    void recordOneCarriesBothCycleAccumulators() {
        AccountEntity account = seededAccountOne();
        BigDecimal cycleCredit = account.getCurrentCycleCredit();
        BigDecimal cycleDebit = account.getCurrentCycleDebit();

        assertThat(cycleCredit)
                .as("current cycle credit, columns 79 to 90")
                .isEqualByComparingTo("0.00");
        assertThat(cycleCredit.scale())
                .as("scale of the current cycle credit")
                .isEqualTo(MONEY_SCALE);
        assertThat(cycleDebit)
                .as("current cycle debit, columns 91 to 102")
                .isEqualByComparingTo("0.00");
        assertThat(cycleDebit.scale())
                .as("scale of the current cycle debit")
                .isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("findByAccountId returns the three dates of record 1 as stored text, columns 49 "
            + "to 78")
    void recordOneCarriesItsThreeDates() {
        AccountEntity account = seededAccountOne();

        assertThat(account.getOpenDate())
                .as("open date, columns 49 to 58")
                .isEqualTo("2014-11-20");
        assertThat(account.getExpirationDate())
                .as("expiration date, columns 59 to 68")
                .isEqualTo("2025-05-20");
        assertThat(account.getReissueDate())
                .as("reissue date, columns 69 to 78")
                .isEqualTo("2025-05-20");
    }

    @Test
    @DisplayName("findByAccountId returns the address zip and the group identifier of record 1, "
            + "columns 103 to 122")
    void recordOneCarriesItsAddressZipAndGroupIdentifier() {
        AccountEntity account = seededAccountOne();

        assertThat(account.getAddressZip())
                .as("address zip, columns 103 to 112")
                .isEqualTo(SEEDED_ADDRESS_ZIP);
        assertThat(account.getGroupId())
                .as("group identifier, columns 113 to 122")
                .isEqualTo(BLANK_GROUP_ID);
    }

    @Test
    @DisplayName("every seeded row carries ten spaces in the group identifier, columns 113 to 122")
    void everySeededRowCarriesABlankGroupIdentifier() {
        List<AccountEntity> everyRow = repository.findAll();

        assertThat(everyRow)
                .as("group identifier of all 50 records of app/data/ASCII/acctdata.txt")
                .extracting(AccountEntity::getGroupId)
                .containsOnly(BLANK_GROUP_ID);
    }

    @Test
    @DisplayName("every seeded row carries the same address zip, columns 103 to 112")
    void everySeededRowCarriesTheSameAddressZip() {
        List<AccountEntity> everyRow = repository.findAll();

        assertThat(everyRow)
                .as("address zip of all 50 records of app/data/ASCII/acctdata.txt")
                .extracting(AccountEntity::getAddressZip)
                .containsOnly(SEEDED_ADDRESS_ZIP);
    }

    /**
     * Reads record 1 through {@link AccountRepository#findByAccountId(String)}.
     *
     * @return the account at columns 1 to 11 of record 1 of {@code app/data/ASCII/acctdata.txt}
     */
    private AccountEntity seededAccountOne() {
        return repository.findByAccountId(SEEDED_ACCOUNT_ID)
                .orElseThrow(() -> new AssertionError(
                        "The account table carries no row keyed " + SEEDED_ACCOUNT_ID));
    }

    /**
     * Returns the one method {@link AccountRepository} declares under the given name.
     *
     * <p>The lookup names no parameter type, so the caller asserts the parameter types the
     * interface declares. The assertion below also fails on an overload, which would leave the
     * caller reading one of two methods.</p>
     *
     * @param name the method name to resolve
     * @return the single declared method carrying that name
     */
    private static Method declaredFinder(String name) {
        List<Method> matches = Arrays.stream(AccountRepository.class.getDeclaredMethods())
                .filter(candidate -> name.equals(candidate.getName()))
                .toList();

        assertThat(matches)
                .as("methods AccountRepository declares under the name " + name)
                .hasSize(1);
        return matches.getFirst();
    }
}
