package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.cobol.reference.UsPhoneAreaCodes;
import com.carddemo.cobol.reference.UsStateCodes;
import com.carddemo.cobol.reference.UsStateZipPrefixes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Compares the three reference bands the Java library holds against the three reference tables the
 * migration seeds, value for value and in both directions.
 *
 * <p><b>What this test measures.</b> The same three bands of {@code app/cpy/CSLKPCDY.cpy} were
 * transformed twice. {@code libs/cobol-compat} holds them as three sets, and
 * {@code src/main/resources/db/migration/V3__reference_data.sql} holds them as three tables.
 * {@code UsPhoneAreaCodesTest}, {@code UsStateCodesTest} and {@code UsStateZipPrefixesTest} assert
 * the first copy against the copybook, and {@link ReferenceDataMigrationTest} asserts the second copy
 * against the same copybook. Neither compares one copy against the other, so the two could drift and
 * both suites would still pass: a value corrected in the library and not in the migration, or the
 * reverse, produces a service that refuses a value its own database calls valid.
 *
 * <p><b>What the two copies are for.</b> The library sets are what the field edits read.
 * {@code app/cbl/COACTUPC.cbl:L2225} tests a submitted telephone area code,
 * {@code app/cbl/COACTUPC.cbl:L2493} tests a state code, and
 * {@code app/cbl/COACTUPC.cbl:L2536} tests a state and postcode pair; each reduces to one set
 * membership test here. The tables are what a reader, a report or a later feature joins against. The
 * two therefore have to agree exactly, which is what the assertions below state: no value in the
 * library is absent from its table, and no row of a table is absent from its band.
 *
 * <p><b>Direction matters.</b> A one-directional comparison misses half the drift. A count comparison
 * misses all of it whenever one value is replaced by another. Every assertion below names the missing
 * values on each side rather than reporting a size, so a failure says which value moved.
 *
 * <p>The container arrives from {@link AbstractAccountPostgresTest}. Each query runs on the schema
 * Flyway migrated into, located from the catalog before each test.
 */
@DisplayName("The Java reference bands against the tables V3__reference_data.sql seeds")
class ReferenceDataBridgeTest extends AbstractAccountPostgresTest {

    /** Locates the schema the three tables sit in, exactly as its sibling test locates it. */
    private static final String SCHEMA_QUERY =
            "SELECT DISTINCT table_schema FROM information_schema.tables WHERE table_name = ?";

    /** Every seeded area code, whichever band it came from. */
    private static final String ALL_AREA_CODES =
            "SELECT area_code FROM us_phone_area_code ORDER BY area_code";

    /** Every seeded area code of one band. */
    private static final String AREA_CODES_OF_BAND =
            "SELECT area_code FROM us_phone_area_code WHERE band = ? ORDER BY area_code";

    /** Every seeded state or territory code. */
    private static final String ALL_STATE_CODES =
            "SELECT state_code FROM us_state_code ORDER BY state_code";

    /** Every seeded state and postcode-prefix pair. */
    private static final String ALL_STATE_ZIP_PREFIXES =
            "SELECT state_zip_prefix FROM us_state_zip_prefix ORDER BY state_zip_prefix";

    /** Band label the migration writes for {@code app/cpy/CSLKPCDY.cpy:L521}. */
    private static final String GENERAL_PURPOSE = "GENERAL_PURPOSE";

    /** Band label the migration writes for {@code app/cpy/CSLKPCDY.cpy:L931}. */
    private static final String EASILY_RECOGNISABLE = "EASILY_RECOGNISABLE";

    /** The table {@link #locateMigratedSchema()} looks the schema up by. */
    private static final String ANCHOR_TABLE = "us_phone_area_code";

    @Autowired
    private DataSource dataSource;

    /** The schema Flyway migrated into, read from the catalog before each test. */
    private String migratedSchema;

    /**
     * Reads the schema the reference tables sit in.
     *
     * @throws SQLException when the catalog query fails
     */
    @BeforeEach
    void locateMigratedSchema() throws SQLException {
        List<String> schemas = column(SCHEMA_QUERY, null, ANCHOR_TABLE);
        if (schemas.size() != 1) {
            throw new IllegalStateException(
                    "One schema holds " + ANCHOR_TABLE + ". The catalog reports " + schemas);
        }
        migratedSchema = schemas.getFirst();
    }

    @Test
    @DisplayName("Every telephone area code the library holds is seeded, and every seeded code is "
            + "in the library")
    void theAreaCodeBandAndItsTableHoldOneSetOfValues() throws SQLException {
        Set<String> library = UsPhoneAreaCodes.phoneAreaCodes();
        Set<String> seeded = new LinkedHashSet<>(column(ALL_AREA_CODES, migratedSchema));

        assertThat(missingFrom(library, seeded))
                .as("area codes the library validates that no row of us_phone_area_code carries")
                .isEmpty();
        assertThat(missingFrom(seeded, library))
                .as("rows of us_phone_area_code the library would refuse")
                .isEmpty();
        assertThat(seeded)
                .as("the two copies are one set")
                .isEqualTo(library);
    }

    @Test
    @DisplayName("Each of the two area-code bands matches the rows carrying its own label")
    void eachAreaCodeBandMatchesItsLabelledRows() throws SQLException {
        Set<String> generalPurpose = UsPhoneAreaCodes.generalPurposeCodes();
        Set<String> easilyRecognisable = UsPhoneAreaCodes.easilyRecognisableAreaCodes();
        Set<String> seededGeneral =
                new LinkedHashSet<>(column(AREA_CODES_OF_BAND, migratedSchema, GENERAL_PURPOSE));
        Set<String> seededEasily = new LinkedHashSet<>(
                column(AREA_CODES_OF_BAND, migratedSchema, EASILY_RECOGNISABLE));

        assertThat(missingFrom(generalPurpose, seededGeneral))
                .as("codes of app/cpy/CSLKPCDY.cpy:L521 no %s row carries", GENERAL_PURPOSE)
                .isEmpty();
        assertThat(missingFrom(seededGeneral, generalPurpose))
                .as("%s rows the L521 band does not hold", GENERAL_PURPOSE)
                .isEmpty();
        assertThat(missingFrom(easilyRecognisable, seededEasily))
                .as("codes of app/cpy/CSLKPCDY.cpy:L931 no %s row carries", EASILY_RECOGNISABLE)
                .isEmpty();
        assertThat(missingFrom(seededEasily, easilyRecognisable))
                .as("%s rows the L931 band does not hold", EASILY_RECOGNISABLE)
                .isEmpty();
    }

    @Test
    @DisplayName("Every state or territory code the library holds is seeded, and the reverse")
    void theStateCodeBandAndItsTableHoldOneSetOfValues() throws SQLException {
        Set<String> library = UsStateCodes.stateAndTerritoryCodes();
        Set<String> seeded = new LinkedHashSet<>(column(ALL_STATE_CODES, migratedSchema));

        assertThat(missingFrom(library, seeded))
                .as("state codes the library validates that no row of us_state_code carries")
                .isEmpty();
        assertThat(missingFrom(seeded, library))
                .as("rows of us_state_code the library would refuse")
                .isEmpty();
        assertThat(seeded).as("the two copies are one set").isEqualTo(library);
    }

    @Test
    @DisplayName("Every state and postcode pair the library holds is seeded, and the reverse")
    void theStateZipBandAndItsTableHoldOneSetOfValues() throws SQLException {
        Set<String> library = UsStateZipPrefixes.validCombinations();
        Set<String> seeded = new LinkedHashSet<>(column(ALL_STATE_ZIP_PREFIXES, migratedSchema));

        assertThat(missingFrom(library, seeded))
                .as("pairs the library validates that no row of us_state_zip_prefix carries")
                .isEmpty();
        assertThat(missingFrom(seeded, library))
                .as("rows of us_state_zip_prefix the library would refuse")
                .isEmpty();
        assertThat(seeded).as("the two copies are one set").isEqualTo(library);
    }

    @Test
    @DisplayName("Every seeded row is a value the validators accept, so a join and an edit agree")
    void everySeededRowIsAValueTheValidatorsAccept() throws SQLException {
        for (String areaCode : column(ALL_AREA_CODES, migratedSchema)) {
            assertThat(UsPhoneAreaCodes.isValidPhoneAreaCode(areaCode))
                    .as("the edit at app/cbl/COACTUPC.cbl:L2225 accepts the seeded code %s",
                            areaCode)
                    .isTrue();
        }
        for (String stateCode : column(ALL_STATE_CODES, migratedSchema)) {
            assertThat(UsStateCodes.isValidUsStateCode(stateCode))
                    .as("the edit at app/cbl/COACTUPC.cbl:L2493 accepts the seeded code %s",
                            stateCode)
                    .isTrue();
        }
        for (String pair : column(ALL_STATE_ZIP_PREFIXES, migratedSchema)) {
            assertThat(UsStateZipPrefixes.isValidUsStateZipCd2Combo(pair))
                    .as("the edit at app/cbl/COACTUPC.cbl:L2536 accepts the seeded pair %s", pair)
                    .isTrue();
        }
    }

    /**
     * Names the values of one set that the other does not hold.
     *
     * @param expected the values to look for
     * @param actual   the values to look in
     * @return the values of {@code expected} absent from {@code actual}, in encounter order
     */
    private static List<String> missingFrom(Set<String> expected, Set<String> actual) {
        return expected.stream().filter(value -> !actual.contains(value)).toList();
    }

    /**
     * Reads the first column of every row a query returns.
     *
     * @param sql        a query returning one column
     * @param schema     the schema to run it on, or {@code null} to use the login default
     * @param parameters values bound to the placeholders of the query, in order
     * @return the column values, in the order the query returned them
     * @throws SQLException when the query fails
     */
    private List<String> column(String sql, String schema, String... parameters)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            if (schema != null) {
                connection.setSchema(schema);
            }
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
}
