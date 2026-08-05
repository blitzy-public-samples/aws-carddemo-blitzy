package com.carddemo.authorization.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the three schema settings of {@code src/main/resources/application.yml} to one value.
 *
 * <p>ADDITIVE. No Common Business Oriented Language (COBOL) program has an equivalent, because a
 * Virtual Storage Access Method dataset is named by a Job Control Language DD statement and there is
 * no second place for that name to disagree with itself. A relational service has three: the
 * {@code currentSchema} of the datasource Uniform Resource Locator, the schema Flyway migrates, and
 * the schema Hibernate validates the entity model against.
 *
 * <p>This test exists because those three can disagree silently. Flyway creates and migrates the
 * schema it is given and reports success. Hibernate then validates against the schema it is given,
 * and if that is a different one it finds no tables. A deployment that sets the shared environment
 * variable makes all three agree and hides the defect completely, so the failure appears only when
 * someone runs the service on its defaults, which is exactly what the onboarding guide asks a new
 * developer to do first.
 *
 * <p>Both directions are checked. With nothing overridden the three must agree, and with the shared
 * variable overridden they must still agree and must all follow the override. A setting that ignored
 * the override would pass the first check and fail the second.
 */
@DisplayName("The schema every layer of the authorization service resolves")
class SchemaResolutionTest {

    /** The schema name this service ships with when nothing overrides it. */
    private static final String SHIPPED_SCHEMA = "authorization_service";

    /** The one variable a deployment sets to move all three settings together. */
    private static final String SHARED_VARIABLE = "AUTHORIZATION_DB_SCHEMA";

    /** Key of the schema Flyway migrates into. */
    private static final String FLYWAY_KEY = "spring.flyway.schemas";

    /** Key of the schema Hibernate validates the entity model against. */
    private static final String HIBERNATE_KEY = "spring.jpa.properties.hibernate.default_schema";

    /** Key of the datasource address, which carries the session schema as a query parameter. */
    private static final String DATASOURCE_KEY = "spring.datasource.url";

    /** Loads the shipped configuration exactly as the application would. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("is one schema across Flyway, Hibernate and the datasource, with no override")
    void isOneSchemaAcrossAllThreeSettingsWithNoOverride() {
        shipped.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty(FLYWAY_KEY))
                    .as("the schema Flyway migrates into")
                    .isEqualTo(SHIPPED_SCHEMA);
            assertThat(environment.getProperty(HIBERNATE_KEY))
                    .as("the schema Hibernate validates against")
                    .isEqualTo(SHIPPED_SCHEMA);
            assertThat(currentSchemaOf(environment.getProperty(DATASOURCE_KEY)))
                    .as("the currentSchema the datasource URL carries")
                    .isEqualTo(SHIPPED_SCHEMA);
        });
    }

    @Test
    @DisplayName("follows the one shared variable in all three settings when it is overridden")
    void followsTheSharedVariableInAllThreeSettings() {
        String override = "authorization_elsewhere";

        shipped.withPropertyValues(SHARED_VARIABLE + "=" + override).run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty(FLYWAY_KEY))
                    .as("the schema Flyway migrates into, under an override")
                    .isEqualTo(override);
            assertThat(environment.getProperty(HIBERNATE_KEY))
                    .as("the schema Hibernate validates against, under an override")
                    .isEqualTo(override);
            assertThat(currentSchemaOf(environment.getProperty(DATASOURCE_KEY)))
                    .as("the currentSchema the datasource URL carries, under an override")
                    .isEqualTo(override);
        });
    }

    /**
     * Asserts Hibernate creates nothing, so the schema it resolves is one Flyway has already built.
     *
     * <p>The agreement above only matters while Flyway owns the schema. If Hibernate were allowed to
     * create tables it would paper over a mismatch by building a second, empty copy of the model in
     * whichever schema it resolved.
     */
    @Test
    @DisplayName("belongs to Flyway alone, because Hibernate only validates")
    void belongsToFlywayAloneBecauseHibernateOnlyValidates() {
        shipped.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                    .as("what Hibernate is permitted to do to the schema")
                    .isEqualTo("validate");
            assertThat(environment.getProperty("spring.flyway.enabled"))
                    .as("whether Flyway runs")
                    .isEqualTo("true");
        });
    }

    /**
     * Reads the {@code currentSchema} query parameter out of a JDBC address.
     *
     * @param url the datasource address
     * @return the schema the address names
     */
    private static String currentSchemaOf(String url) {
        assertThat(url).as("the datasource URL").isNotNull();
        java.util.regex.Matcher schema =
                java.util.regex.Pattern.compile("currentSchema=([^&]+)").matcher(url);
        assertThat(schema.find())
                .as("the datasource URL names a currentSchema in %s", url)
                .isTrue();
        return schema.group(1);
    }
}
