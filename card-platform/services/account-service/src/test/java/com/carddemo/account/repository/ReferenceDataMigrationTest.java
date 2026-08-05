package com.carddemo.account.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the rows {@code db/migration/V3__reference_data.sql} seeded, and checks each count and each
 * membership against the band of app/cpy/CSLKPCDY.cpy that declares it.
 *
 * <p><b>Three bands, 786 rows.</b> The migration writes one row per distinct copybook value.
 * {@code 88 VALID-PHONE-AREA-CODE} at app/cpy/CSLKPCDY.cpy:L30 declares 490 distinct area codes,
 * and {@code 88 VALID-US-STATE-CODE} at app/cpy/CSLKPCDY.cpy:L1013 declares 56 state codes.
 * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at app/cpy/CSLKPCDY.cpy:L1073 declares 240 state and zip
 * combinations. The three counts sum to 786, and every count below is a count of distinct
 * values.</p>
 *
 * <p><b>The band column.</b> Two more condition names overlay the same three-character item at
 * app/cpy/CSLKPCDY.cpy:L24. {@code 88 VALID-GENERAL-PURP-CODE} at app/cpy/CSLKPCDY.cpy:L521
 * declares 410 codes, and {@code 88 VALID-EASY-RECOG-AREA-CODE} at app/cpy/CSLKPCDY.cpy:L931
 * declares 80. The two sets share no code, and together they cover the 490 of
 * app/cpy/CSLKPCDY.cpy:L30.</p>
 *
 * <p>The {@code band} column records which of the two declares each code. Paragraph
 * {@code 1260-EDIT-US-PHONE-NUM} at app/cbl/COACTUPC.cbl:L2225 tests the general-purpose set alone,
 * at app/cbl/COACTUPC.cbl:L2298.</p>
 *
 * <p><b>One fact the validation tests carry.</b> Record 1 of app/data/ASCII/custdata.txt holds
 * state code {@code NC} at offsets 235 to 236 and zip code {@code 12546} from offset 240, per the
 * layout at app/cpy/CVCUS01Y.cpy:L12 and app/cpy/CVCUS01Y.cpy:L14. Paragraph
 * {@code 1280-EDIT-US-STATE-ZIP-CD} at app/cbl/COACTUPC.cbl:L2536 assembles the key {@code NC12}
 * from those two fields, at app/cbl/COACTUPC.cbl:L2537-L2540. The band of
 * app/cpy/CSLKPCDY.cpy:L1073 omits that key. Each test in
 * {@code com.carddemo.account.domain.validation} writes its own inputs and drives no validator from
 * a database row.</p>
 *
 * <p><b>Scope.</b> Every query below reads, and none writes. The three tables carry no Jakarta
 * Persistence (JPA) entity and no repository, and each query is plain SQL against the database
 * {@link AbstractAccountPostgresTest} supplies. Column types, seed row counts and validator
 * behaviour sit in other classes of this module.
 */
@DisplayName("V3 reference data, 786 rows from the three copybook bands of app/cpy/CSLKPCDY.cpy")
class ReferenceDataMigrationTest extends AbstractAccountPostgresTest {

    /** Distinct codes {@code 88 VALID-PHONE-AREA-CODE} declares at app/cpy/CSLKPCDY.cpy:L30. */
    private static final long AREA_CODE_ROWS = 490L;

    /** Distinct codes {@code 88 VALID-GENERAL-PURP-CODE} declares at app/cpy/CSLKPCDY.cpy:L521. */
    private static final long GENERAL_PURPOSE_ROWS = 410L;

    /**
     * Distinct codes {@code 88 VALID-EASY-RECOG-AREA-CODE} declares at app/cpy/CSLKPCDY.cpy:L931.
     */
    private static final long EASILY_RECOGNISABLE_ROWS = 80L;

    /** Distinct codes {@code 88 VALID-US-STATE-CODE} declares at app/cpy/CSLKPCDY.cpy:L1013. */
    private static final long STATE_CODE_ROWS = 56L;

    /**
     * Distinct combinations {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} declares at
     * app/cpy/CSLKPCDY.cpy:L1073.
     */
    private static final long STATE_ZIP_PREFIX_ROWS = 240L;

    /**
     * Rows the three tables hold together, the sum of the three distinct band sizes declared above.
     */
    private static final long ALL_REFERENCE_ROWS = 786L;

    /** Band label V3__reference_data.sql writes for app/cpy/CSLKPCDY.cpy:L521. */
    private static final String GENERAL_PURPOSE = "GENERAL_PURPOSE";

    /** Band label V3__reference_data.sql writes for app/cpy/CSLKPCDY.cpy:L931. */
    private static final String EASILY_RECOGNISABLE = "EASILY_RECOGNISABLE";

    /**
     * Locates the schema the three tables sit in. One row comes back, and the value of that row is
     * the schema Flyway migrated into.
     */
    private static final String SCHEMA_QUERY =
            "SELECT DISTINCT table_schema FROM information_schema.tables WHERE table_name = ?";

    /** Every state code of the band, ordered, one row each. */
    private static final String ALL_STATE_CODES =
            "SELECT state_code FROM us_state_code ORDER BY state_code";

    /** Every state and zip combination of the band, ordered, one row each. */
    private static final String ALL_STATE_ZIP_PREFIXES =
            "SELECT state_zip_prefix FROM us_state_zip_prefix ORDER BY state_zip_prefix";

    @Autowired
    private DataSource dataSource;

    /** Set by {@link #locateMigratedSchema()} before each test method runs. */
    private String migratedSchema;

    /**
     * Reads the schema of {@code us_phone_area_code} from the catalog of the running database.
     *
     * <p>Spring injects the datasource once the context has started, and Flyway has run by then.
     * The connections that datasource hands out land on the default schema of the login. Every
     * query below runs against the schema this method finds.</p>
     *
     * @throws SQLException when the catalog query fails
     */
    @BeforeEach
    void locateMigratedSchema() throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SCHEMA_QUERY)) {
            statement.setString(1, "us_phone_area_code");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    schemas.add(rows.getString(1));
                }
            }
        }
        if (schemas.size() != 1) {
            throw new IllegalStateException(
                    "One schema holds us_phone_area_code. The catalog reports " + schemas);
        }
        migratedSchema = schemas.getFirst();
    }

    /**
     * Runs a single-value counting query over the migrated schema.
     *
     * @param sql        a query whose first row carries the count in its first column
     * @param parameters values bound to the placeholders of the query, in order
     * @return the counted value
     * @throws SQLException when the query fails
     */
    private long count(String sql, String... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(migratedSchema);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setString(index + 1, parameters[index]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("A counting query returned no row: " + sql);
                    }
                    return rows.getLong(1);
                }
            }
        }
    }

    /**
     * Reads the first column of every row a query returns, over the migrated schema.
     *
     * @param sql        a query returning one column
     * @param parameters values bound to the placeholders of the query, in order
     * @return the column values, in the order the query returned them
     * @throws SQLException when the query fails
     */
    private List<String> column(String sql, String... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(migratedSchema);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setString(index + 1, parameters[index]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        values.add(rows.getString(1));
                    }
                }
            }
        }
        return values;
    }

    @Test
    @DisplayName("us_phone_area_code holds 490 rows, and 490 distinct area codes, from CSLKPCDY.cpy "
            + "L30")
    void phoneAreaCodeTableHolds490DistinctCodes() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_phone_area_code"))
                .as("rows of the band at app/cpy/CSLKPCDY.cpy:L30")
                .isEqualTo(AREA_CODE_ROWS);
        assertThat(count("SELECT count(DISTINCT area_code) FROM us_phone_area_code"))
                .as("distinct area codes of the band at app/cpy/CSLKPCDY.cpy:L30")
                .isEqualTo(AREA_CODE_ROWS);
    }

    @Test
    @DisplayName("us_state_code holds 56 rows, and 56 distinct state codes, from CSLKPCDY.cpy "
            + "L1013")
    void stateCodeTableHolds56DistinctCodes() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_state_code"))
                .as("rows of the band at app/cpy/CSLKPCDY.cpy:L1013")
                .isEqualTo(STATE_CODE_ROWS);
        assertThat(count("SELECT count(DISTINCT state_code) FROM us_state_code"))
                .as("distinct state codes of the band at app/cpy/CSLKPCDY.cpy:L1013")
                .isEqualTo(STATE_CODE_ROWS);
    }

    @Test
    @DisplayName("us_state_zip_prefix holds 240 rows, and 240 distinct combinations, from "
            + "CSLKPCDY.cpy L1073")
    void stateZipPrefixTableHolds240DistinctCombinations() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_state_zip_prefix"))
                .as("rows of the band at app/cpy/CSLKPCDY.cpy:L1073")
                .isEqualTo(STATE_ZIP_PREFIX_ROWS);
        assertThat(count("SELECT count(DISTINCT state_zip_prefix) FROM us_state_zip_prefix"))
                .as("distinct combinations of the band at app/cpy/CSLKPCDY.cpy:L1073")
                .isEqualTo(STATE_ZIP_PREFIX_ROWS);
    }

    @Test
    @DisplayName("The three tables hold 786 rows together, one per distinct copybook value and not "
            + "one per copybook occurrence")
    void threeReferenceTablesHold786RowsTogether() throws SQLException {
        long total = count("SELECT (SELECT count(*) FROM us_phone_area_code)"
                + " + (SELECT count(*) FROM us_state_code)"
                + " + (SELECT count(*) FROM us_state_zip_prefix)");

        assertThat(total)
                .as("rows V3__reference_data.sql seeds across app/cpy/CSLKPCDY.cpy:L30, "
                        + "app/cpy/CSLKPCDY.cpy:L1013 and app/cpy/CSLKPCDY.cpy:L1073")
                .isEqualTo(ALL_REFERENCE_ROWS);
    }

    @Test
    @DisplayName("Every area code carries three characters, and all three are digits")
    void everyAreaCodeCarriesThreeDigits() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_phone_area_code WHERE area_code !~ '^[0-9]{3}$'"))
                .as("area codes outside the three-digit shape of app/cpy/CSLKPCDY.cpy:L30, in the "
                        + "three-character item at app/cpy/CSLKPCDY.cpy:L24")
                .isZero();
    }

    @Test
    @DisplayName("Every state code carries two characters, and both are letters")
    void everyStateCodeCarriesTwoLetters() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_state_code WHERE state_code !~ '^[A-Z]{2}$'"))
                .as("state codes outside the two-letter shape of app/cpy/CSLKPCDY.cpy:L1013, in "
                        + "the two-character item at app/cpy/CSLKPCDY.cpy:L1012")
                .isZero();
    }

    @Test
    @DisplayName("Every combination carries four characters, two letters then two digits")
    void everyStateZipPrefixCarriesTwoLettersThenTwoDigits() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_state_zip_prefix "
                + "WHERE state_zip_prefix !~ '^[A-Z]{2}[0-9]{2}$'"))
                .as("combinations outside the shape app/cbl/COACTUPC.cbl:L2537-L2540 assembles, in "
                        + "the four-character item at app/cpy/CSLKPCDY.cpy:L1072")
                .isZero();
    }

    @Test
    @DisplayName("The band column splits the 490 codes into 410 general purpose and 80 easily "
            + "recognisable")
    void bandColumnSplits490CodesInto410And80() throws SQLException {
        long generalPurpose =
                count("SELECT count(*) FROM us_phone_area_code WHERE band = ?", GENERAL_PURPOSE);
        long easilyRecognisable =
                count("SELECT count(*) FROM us_phone_area_code WHERE band = ?", EASILY_RECOGNISABLE);

        assertThat(generalPurpose)
                .as("codes of the band at app/cpy/CSLKPCDY.cpy:L521")
                .isEqualTo(GENERAL_PURPOSE_ROWS);
        assertThat(easilyRecognisable)
                .as("codes of the band at app/cpy/CSLKPCDY.cpy:L931")
                .isEqualTo(EASILY_RECOGNISABLE_ROWS);
        assertThat(generalPurpose + easilyRecognisable)
                .as("codes the two bands cover of the 490 at app/cpy/CSLKPCDY.cpy:L30")
                .isEqualTo(AREA_CODE_ROWS);
    }

    @Test
    @DisplayName("No area code carries both band values")
    void noAreaCodeCarriesBothBandValues() throws SQLException {
        assertThat(count("SELECT count(*) FROM ("
                + "SELECT area_code FROM us_phone_area_code WHERE band = ?"
                + " INTERSECT "
                + "SELECT area_code FROM us_phone_area_code WHERE band = ?"
                + ") AS shared", GENERAL_PURPOSE, EASILY_RECOGNISABLE))
                .as("codes app/cpy/CSLKPCDY.cpy:L521 and app/cpy/CSLKPCDY.cpy:L931 both declare")
                .isZero();
    }

    @Test
    @DisplayName("The band column carries exactly two values, one per copybook subset")
    void bandColumnCarriesExactlyTwoValues() throws SQLException {
        assertThat(column("SELECT DISTINCT band FROM us_phone_area_code ORDER BY band"))
                .as("band values for app/cpy/CSLKPCDY.cpy:L521 and app/cpy/CSLKPCDY.cpy:L931")
                .containsExactly(EASILY_RECOGNISABLE, GENERAL_PURPOSE);
    }

    @Test
    @DisplayName("The state band holds the territory codes AS, GU and VI")
    void stateBandHoldsThreeTerritoryCodes() throws SQLException {
        assertThat(column(ALL_STATE_CODES))
                .as("codes of the band at app/cpy/CSLKPCDY.cpy:L1013")
                .contains("AS", "GU", "VI");
    }

    @Test
    @DisplayName("The state band omits AP, FM, MH and PW, the four codes the seeded customers "
            + "carry outside it")
    void stateBandOmitsTheFourSeededCodesOutsideIt() throws SQLException {
        assertThat(column(ALL_STATE_CODES))
                .as("codes of the band at app/cpy/CSLKPCDY.cpy:L1013, read against the state field "
                        + "at app/cpy/CVCUS01Y.cpy:L12 of app/data/ASCII/custdata.txt")
                .doesNotContain("AP", "FM", "MH", "PW");
    }

    @Test
    @DisplayName("The combination band holds exactly two North Carolina entries, NC27 and NC28")
    void combinationBandHoldsExactlyTwoNorthCarolinaEntries() throws SQLException {
        assertThat(column("SELECT state_zip_prefix FROM us_state_zip_prefix "
                + "WHERE state_zip_prefix LIKE ? ORDER BY state_zip_prefix", "NC%"))
                .as("North Carolina combinations of the band at app/cpy/CSLKPCDY.cpy:L1073")
                .containsExactly("NC27", "NC28");
    }

    @Test
    @DisplayName("The combination band omits NC12, the key from seeded customer record 1")
    void combinationBandOmitsTheKeyOfSeededCustomerRecordOne() throws SQLException {
        assertThat(count("SELECT count(*) FROM us_state_zip_prefix WHERE state_zip_prefix = ?",
                "NC12"))
                .as("rows for the key app/cbl/COACTUPC.cbl:L2537-L2540 assembles from record 1 of "
                        + "app/data/ASCII/custdata.txt")
                .isZero();
    }

    @Test
    @DisplayName("The combination band holds AP96 and ME49, the two seeded keys it accepts")
    void combinationBandHoldsTheTwoSeededKeysItAccepts() throws SQLException {
        assertThat(column(ALL_STATE_ZIP_PREFIXES))
                .as("combinations of the band at app/cpy/CSLKPCDY.cpy:L1073, read against the "
                        + "state and zip fields at app/cpy/CVCUS01Y.cpy:L12 and "
                        + "app/cpy/CVCUS01Y.cpy:L14 of app/data/ASCII/custdata.txt")
                .contains("AP96", "ME49");
    }
}
