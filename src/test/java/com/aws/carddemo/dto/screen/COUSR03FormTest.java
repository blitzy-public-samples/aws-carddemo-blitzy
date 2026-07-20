package com.aws.carddemo.dto.screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COUSR03Form}. Oracle: legacy/cpy-bms/COUSR03.CPY
 * (BMS mapset COUSR03, map COUSR3A).
 *
 * <p>{@code COUSR03Form} is the Spring MVC form-backing bean for the administrative
 * user-delete screen (CICS transaction {@code CU03}, program {@code COUSR03C}). Its
 * eleven {@code String} properties mirror the {@code COUSR3AI} symbolic input map and
 * preserve the legacy 24x80 field-length contract through Jakarta Bean Validation
 * {@link jakarta.validation.constraints.Size} upper bounds. These tests verify, for the
 * production class exactly as declared:</p>
 * <ul>
 *   <li>a fully populated form within every field length yields no constraint violations;</li>
 *   <li>a value one character over a field's maximum raises a size violation on that
 *       exact property (checked for {@code usridin}, {@code fname}, {@code lname},
 *       {@code usrtype}, and {@code errmsg});</li>
 *   <li>every getter faithfully returns the value written through its setter; and</li>
 *   <li>the delete screen exposes <em>no</em> password field or accessor (unlike the
 *       user-add / user-update screens) and its {@code toString()} never leaks field
 *       values.</li>
 * </ul>
 *
 * <p>This is a pure JUnit 5 (Jupiter) + AssertJ unit test: the {@link Validator} is built
 * programmatically via {@link Validation#buildDefaultValidatorFactory()}, so there is no
 * Spring context, no database, and no Testcontainers. There are no monetary fields on this
 * screen, so no {@code BigDecimal} / money-as-String assertions apply.</p>
 */
@DisplayName("COUSR03Form (user-delete screen) unit tests")
class COUSR03FormTest {

    /** Field maximums declared on {@link COUSR03Form}, used to build valid and oversize inputs. */
    private static final int MAX_TRNNAME = 4;
    private static final int MAX_TITLE = 40;
    private static final int MAX_CURDATE = 8;
    private static final int MAX_PGMNAME = 8;
    private static final int MAX_CURTIME = 8;
    private static final int MAX_USRIDIN = 8;
    private static final int MAX_FNAME = 20;
    private static final int MAX_LNAME = 20;
    private static final int MAX_USRTYPE = 1;
    private static final int MAX_ERRMSG = 78;

    /** The eleven property names declared by the production form, in declaration order. */
    private static final String[] EXPECTED_PROPERTIES = {
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime",
        "usridin", "fname", "lname", "usrtype", "errmsg"
    };

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("a fully populated in-range form has no constraint violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    @Test
    @DisplayName("a value one character over max raises a size violation on that exact property")
    void oversize_fields_violate_size_on_exact_property() {
        COUSR03Form usridinOver = validForm();
        usridinOver.setUsridin("X".repeat(MAX_USRIDIN + 1)); // 9 > 8
        assertSingleSizeViolationOn(usridinOver, "usridin");

        COUSR03Form fnameOver = validForm();
        fnameOver.setFname("X".repeat(MAX_FNAME + 1)); // 21 > 20
        assertSingleSizeViolationOn(fnameOver, "fname");

        COUSR03Form lnameOver = validForm();
        lnameOver.setLname("X".repeat(MAX_LNAME + 1)); // 21 > 20
        assertSingleSizeViolationOn(lnameOver, "lname");

        COUSR03Form usrtypeOver = validForm();
        usrtypeOver.setUsrtype("X".repeat(MAX_USRTYPE + 1)); // 2 > 1
        assertSingleSizeViolationOn(usrtypeOver, "usrtype");

        COUSR03Form errmsgOver = validForm();
        errmsgOver.setErrmsg("X".repeat(MAX_ERRMSG + 1)); // 79 > 78
        assertSingleSizeViolationOn(errmsgOver, "errmsg");
    }

    @Test
    @DisplayName("every getter returns the value written through its setter")
    void getters_return_set_values() {
        COUSR03Form form = new COUSR03Form();
        form.setTrnname("CU03");
        form.setTitle01("Title Line One Value");
        form.setCurdate("12/31/24");
        form.setPgmname("COUSR03C");
        form.setTitle02("Title Line Two Value");
        form.setCurtime("23:59:59");
        form.setUsridin("ADMIN001");
        form.setFname("ALEXANDER");
        form.setLname("WASHINGTON");
        form.setUsrtype("A");
        form.setErrmsg("User located; confirm deletion.");

        assertThat(form.getTrnname()).isEqualTo("CU03");
        assertThat(form.getTitle01()).isEqualTo("Title Line One Value");
        assertThat(form.getCurdate()).isEqualTo("12/31/24");
        assertThat(form.getPgmname()).isEqualTo("COUSR03C");
        assertThat(form.getTitle02()).isEqualTo("Title Line Two Value");
        assertThat(form.getCurtime()).isEqualTo("23:59:59");
        assertThat(form.getUsridin()).isEqualTo("ADMIN001");
        assertThat(form.getFname()).isEqualTo("ALEXANDER");
        assertThat(form.getLname()).isEqualTo("WASHINGTON");
        assertThat(form.getUsrtype()).isEqualTo("A");
        assertThat(form.getErrmsg()).isEqualTo("User located; confirm deletion.");
    }

    @Test
    @DisplayName("the delete screen exposes exactly its 11 fields and no password field or accessor")
    void form_does_not_expose_a_password_property() {
        // The user-delete screen (unlike user-add / user-update) has no password field. We
        // assert the absence reflectively because referencing a getPasswd()/getPassword()
        // accessor would not compile. Synthetic and $-prefixed members (e.g. JaCoCo's
        // $jacocoData / $jacocoInit added during coverage runs) are filtered out so the
        // field set reflects only the declared JavaBean properties.
        Set<String> fieldNames = declaredFieldNames();
        // The hidden single-use confirmation nonce (review finding F12) is a deliberate non-BMS
        // control field (it restores the BMS protected-confirmation-field contract over HTTP), not
        // part of the COUSR3A map. Assert it is present, then exclude it from the BMS-property check.
        assertThat(fieldNames).contains("confirmToken");
        Set<String> bmsFieldNames = new java.util.HashSet<>(fieldNames);
        bmsFieldNames.remove("confirmToken");
        assertThat(bmsFieldNames)
            .hasSize(EXPECTED_PROPERTIES.length)
            .containsExactlyInAnyOrder(EXPECTED_PROPERTIES)
            .noneMatch(COUSR03FormTest::mentionsPassword);

        Set<String> methodNames = declaredMethodNames();
        assertThat(methodNames).noneMatch(COUSR03FormTest::mentionsPassword);
    }

    @Test
    @DisplayName("toString() renders the class name and never leaks field values or a password")
    void to_string_does_not_leak_field_values() {
        // PRODUCTION WINS: the agent-prompt field table anticipated toString() rendering
        // usridin/fname/lname. The production COUSR03Form.toString() is instead intentionally
        // security-hardened (CWE-532): it renders only the simple class name plus an opaque
        // identity token and deliberately omits every screen-field value. We therefore assert
        // the actual hardened behavior rather than the anticipated one.
        COUSR03Form form = validForm();
        form.setUsridin("SECRET99");
        form.setFname("CONFIDENTL");
        form.setLname("PRIVATENAM");

        String rendered = form.toString();

        assertThat(rendered).startsWith("COUSR03Form@");
        assertThat(rendered).doesNotContain("SECRET99", "CONFIDENTL", "PRIVATENAM");
        assertThat(rendered.toLowerCase(java.util.Locale.ROOT)).doesNotContain("passwd", "password");
    }

    /**
     * Builds a form whose every property is populated at or under its declared maximum
     * length, so validation of the returned instance yields no violations.
     *
     * @return a fully populated, valid {@link COUSR03Form}
     */
    private static COUSR03Form validForm() {
        COUSR03Form form = new COUSR03Form();
        form.setTrnname("C".repeat(MAX_TRNNAME));   // "CCCC" (4)
        form.setTitle01("AWS CardDemo - Delete User");
        form.setCurdate("01/15/24");
        form.setPgmname("COUSR03C");
        form.setTitle02("User Security Administration");
        form.setCurtime("14:30:00");
        form.setUsridin("USER0001");                // exactly MAX_USRIDIN
        form.setFname("JOHN");
        form.setLname("DOE");
        form.setUsrtype("U");
        form.setErrmsg("");                          // blank error line on a valid screen
        return form;
    }

    /**
     * Validates {@code form} and asserts that it produces exactly one constraint violation
     * whose property path equals {@code expectedProperty}.
     *
     * @param form             the form instance to validate
     * @param expectedProperty the property name expected to carry the sole violation
     */
    private static void assertSingleSizeViolationOn(COUSR03Form form, String expectedProperty) {
        Set<ConstraintViolation<COUSR03Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(expectedProperty);
    }

    /**
     * Returns the names of the non-synthetic instance fields declared by {@link COUSR03Form}.
     *
     * @return the declared JavaBean property field names
     */
    private static Set<String> declaredFieldNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Field field : COUSR03Form.class.getDeclaredFields()) {
            if (field.isSynthetic() || field.getName().startsWith("$")) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Returns the names of the non-synthetic methods declared by {@link COUSR03Form}.
     *
     * @return the declared method names
     */
    private static Set<String> declaredMethodNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Method method : COUSR03Form.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.getName().startsWith("$")) {
                continue;
            }
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Indicates whether a member name refers to a password, case-insensitively.
     *
     * @param name the field or method name to inspect
     * @return {@code true} if the name mentions a password
     */
    private static boolean mentionsPassword(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("passwd") || lower.contains("password");
    }
}
