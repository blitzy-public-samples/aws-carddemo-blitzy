/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto.screen;

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
 * Unit tests for {@link COUSR01Form}. Oracle: legacy/cpy-bms/COUSR01.CPY (BMS
 * mapset COUSR01, map COUSR1A).
 *
 * <p>These are pure-unit (Surefire) tests: they construct the form directly
 * with {@code new COUSR01Form()}, build a Jakarta Bean Validation
 * {@link Validator} programmatically, and never start a Spring context, touch a
 * database, or use Testcontainers. The tests verify the four assertion
 * categories that apply to this screen form:</p>
 * <ol>
 *   <li>a fully-populated form within every {@code @Size(max)} bound produces no
 *       constraint violations;</li>
 *   <li>a value one character over a field's maximum produces a
 *       {@code @Size} violation reported against the exact property;</li>
 *   <li>every one of the 12 String properties round-trips through its
 *       getter/setter pair;</li>
 *   <li>{@link COUSR01Form#toString()} never leaks the cleartext
 *       {@code passwd} value.</li>
 * </ol>
 *
 * <p>The user-id property on this screen is {@code userid} (BMS field
 * {@code USERIDI}); it is deliberately asserted here to distinguish this form
 * from {@code COUSR02Form}, whose corresponding property is {@code usridin}.</p>
 */
class COUSR01FormTest {

    /** Maximum length permitted by {@code COUSR01Form.userid}'s {@code @Size}. */
    private static final int USERID_MAX = 8;

    /** Maximum length permitted by {@code COUSR01Form.passwd}'s {@code @Size}. */
    private static final int PASSWD_MAX = 8;

    /** Maximum length permitted by {@code COUSR01Form.fname}'s {@code @Size}. */
    private static final int FNAME_MAX = 20;

    /** Maximum length permitted by {@code COUSR01Form.lname}'s {@code @Size}. */
    private static final int LNAME_MAX = 20;

    /** Maximum length permitted by {@code COUSR01Form.usrtype}'s {@code @Size}. */
    private static final int USRTYPE_MAX = 1;

    /** Maximum length permitted by {@code COUSR01Form.errmsg}'s {@code @Size}. */
    private static final int ERRMSG_MAX = 78;

    /** Factory backing {@link #validator}; closed once after the whole class runs. */
    private static ValidatorFactory factory;

    /** Shared, stateless Bean Validation validator built without any Spring context. */
    private static Validator validator;

    /**
     * Builds the Jakarta Bean Validation validator programmatically (no Spring,
     * no dependency injection). The default lifecycle is one test instance per
     * method, so this fixture is {@code static}.
     */
    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the {@link ValidatorFactory} once every test in the class has run.
     */
    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /**
     * Creates a form whose 12 properties are all populated at or below their
     * declared {@code @Size(max)} bound, so the instance is valid.
     *
     * @return a fully-populated, valid {@link COUSR01Form}
     */
    private static COUSR01Form validForm() {
        COUSR01Form form = new COUSR01Form();
        form.setTrnname("CU01");                                 // <= 4
        form.setTitle01("AWS CardDemo - Admin User Management"); // <= 40
        form.setCurdate("01/15/26");                             // <= 8
        form.setPgmname("COUSR01C");                             // <= 8
        form.setTitle02("Add New Security User");                // <= 40
        form.setCurtime("12:30:00");                             // <= 8
        form.setFname("JOHN");                                   // <= 20
        form.setLname("DOE");                                    // <= 20
        form.setUserid("USER0001");                              // <= 8
        form.setPasswd("PASSW0RD");                              // <= 8
        form.setUsrtype("U");                                    // <= 1
        form.setErrmsg("");                                      // <= 78
        return form;
    }

    /**
     * Validates a single property value that is exactly one character over its
     * declared maximum and asserts that the resulting {@code @Size} violation is
     * reported against that exact property. Using
     * {@link Validator#validateValue} also proves the property name is correct,
     * because the API throws {@link IllegalArgumentException} for an unknown
     * property.
     *
     * @param property   the exact JavaBean property name expected to violate
     * @param overLength the length of the too-long value to submit (max + 1)
     */
    private static void assertSizeViolationOnProperty(String property, int overLength) {
        Set<ConstraintViolation<COUSR01Form>> violations =
                validator.validateValue(COUSR01Form.class, property, "x".repeat(overLength));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo(property);
    }

    @Test
    @DisplayName("a fully-populated form within all @Size bounds has no violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    @Test
    @DisplayName("a value one char over max violates @Size on the exact property")
    void oversize_fields_violate_size_on_exact_property() {
        assertSizeViolationOnProperty("fname", FNAME_MAX + 1);     // 21
        assertSizeViolationOnProperty("lname", LNAME_MAX + 1);     // 21
        assertSizeViolationOnProperty("userid", USERID_MAX + 1);   // 9
        assertSizeViolationOnProperty("passwd", PASSWD_MAX + 1);   // 9
        assertSizeViolationOnProperty("usrtype", USRTYPE_MAX + 1); // 2
        assertSizeViolationOnProperty("errmsg", ERRMSG_MAX + 1);   // 79
    }

    @Test
    @DisplayName("every one of the 12 String properties round-trips getter/setter")
    void getters_return_set_values() {
        COUSR01Form form = new COUSR01Form();

        form.setTrnname("CU01");
        form.setTitle01("Title One Line");
        form.setCurdate("01/15/26");
        form.setPgmname("COUSR01C");
        form.setTitle02("Title Two Line");
        form.setCurtime("12:30:00");
        form.setFname("JOHN");
        form.setLname("SMITH");
        form.setUserid("NEWUSER1");
        form.setPasswd("SECRET01");
        form.setUsrtype("A");
        form.setErrmsg("No errors");

        assertThat(form.getTrnname()).isEqualTo("CU01");
        assertThat(form.getTitle01()).isEqualTo("Title One Line");
        assertThat(form.getCurdate()).isEqualTo("01/15/26");
        assertThat(form.getPgmname()).isEqualTo("COUSR01C");
        assertThat(form.getTitle02()).isEqualTo("Title Two Line");
        assertThat(form.getCurtime()).isEqualTo("12:30:00");
        assertThat(form.getFname()).isEqualTo("JOHN");
        assertThat(form.getLname()).isEqualTo("SMITH");
        // The new-user-id property on this screen is `userid` (getUserid), not `usridin`.
        assertThat(form.getUserid()).isEqualTo("NEWUSER1");
        assertThat(form.getPasswd()).isEqualTo("SECRET01");
        assertThat(form.getUsrtype()).isEqualTo("A");
        assertThat(form.getErrmsg()).isEqualTo("No errors");
    }

    @Test
    @DisplayName("toString never leaks the cleartext password (review finding F9 / CWE-532)")
    void toString_masks_password() {
        COUSR01Form form = validForm();
        form.setPasswd("PWDSECRET");
        form.setUserid("USER0001");

        String rendered = form.toString();

        // Core security guarantee: the raw cleartext password must never appear in toString().
        assertThat(rendered).doesNotContain("PWDSECRET");

        // PRODUCTION FORM WINS (agent-prompt MANDATORY FIRST STEP, rule 3): the prompt suggested
        // toString() would mask the password as "***" and still render the user id. The actual
        // production COUSR01Form.toString() instead emits ONLY an opaque identity token
        // ("COUSR01Form@<hex>") and renders NO field values at all - a stronger anti-log-injection
        // guarantee (review finding F9, CWE-532). We therefore assert the real behavior and do NOT
        // assert the suggested "***" mask or that the user id appears in the string.
        assertThat(rendered)
                .isNotNull()
                .startsWith("COUSR01Form@")
                .doesNotContain("USER0001");

        // The user-id property on THIS screen is `userid` (BMS USERIDI), not COUSR02's `usridin`;
        // getUserid() must round-trip (this also fails to compile if the property were misnamed).
        assertThat(form.getUserid()).isEqualTo("USER0001");
    }
}
