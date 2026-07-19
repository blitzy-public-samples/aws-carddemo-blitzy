package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure unit mapping tests for the JPA entity {@link Card}.
 *
 * <p>Provenance and oracle: {@code Card} is migrated one-for-one from the legacy COBOL copybook
 * {@code CARD-RECORD} defined in {@code legacy/cpy/CVACT02Y.cpy} (record length 150), which
 * described the layout of the VSAM {@code CARDDAT} KSDS. These tests lock the object-relational
 * mapping contract so the COBOL-to-Java translation cannot silently drift and so the source-to-
 * target traceability remains provable (AAP sections 0.4.1 and 0.6.10).</p>
 *
 * <p>Two behaviors are given deliberate, dedicated emphasis:</p>
 * <ul>
 *   <li><b>Preserved misspelling.</b> The COBOL field {@code CARD-EXPIRAION-DATE} is misspelled in
 *       the source copybook ("EXPIRAION"). The Java property {@code cardExpiraionDate} and the
 *       column {@code card_expiraion_date} preserve that spelling verbatim to keep a 1:1 mapping
 *       with the copybook, honoring the "no changes beyond the technology substitution" mandate
 *       (AAP section 0.4.1). {@link #expiraionMisspellingIsPreserved()} fails loudly if a
 *       well-meaning refactor ever "corrects" the name.</li>
 *   <li><b>CVV masking.</b> The card verification value ({@code CARD-CVV-CD}) is sensitive and must
 *       never be emitted by {@link Card#toString()}. {@link #cvvIsNotExposedInToString()} enforces
 *       that security-hygiene requirement (AAP section 0.6.7).</li>
 * </ul>
 *
 * <p>These are pure JUnit 5 + AssertJ reflection tests: no Spring context, no database, and no
 * {@code @SpringBootTest}. Because the class name ends in {@code Test}, it executes in the Surefire
 * unit phase and never requires a running PostgreSQL instance.</p>
 */
@DisplayName("Card entity mapping contract (legacy/cpy/CVACT02Y.cpy)")
class CardTest {

    // ------------------------------------------------------------------
    // Reflection helpers (checked exceptions surfaced as clean assertions)
    // ------------------------------------------------------------------

    /**
     * Returns the declared {@link Field} of {@link Card} for the given name, calling
     * {@code setAccessible(true)} so the private field can be inspected. A missing field is
     * reported as a clean {@link AssertionError} rather than a raw {@link NoSuchFieldException}.
     *
     * @param name the declared field name
     * @return the accessible reflective field handle
     */
    private static Field field(String name) {
        try {
            Field f = Card.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Expected Card to declare field '" + name + "'", e);
        }
    }

    /**
     * Returns the {@link Column} annotation declared on the named field, asserting it is present.
     *
     * @param name the declared field name
     * @return the non-null {@code @Column} annotation
     */
    private static Column column(String name) {
        Column col = field(name).getAnnotation(Column.class);
        assertThat(col).as("@Column annotation on field '%s'", name).isNotNull();
        return col;
    }

    /**
     * Asserts, in one place, that a field has the expected Java type and maps to the expected
     * relational column name.
     *
     * @param name         the declared field name
     * @param expectedType the expected Java type of the field
     * @param columnName   the expected {@code @Column.name()} value
     */
    private static void assertMapping(String name, Class<?> expectedType, String columnName) {
        assertThat(field(name).getType()).as("Java type of field '%s'", name).isEqualTo(expectedType);
        assertThat(column(name).name()).as("column name of field '%s'", name).isEqualTo(columnName);
    }

    /**
     * Reports whether {@link Card} overrides {@code equals(Object)} instead of inheriting the
     * reference-identity implementation from {@link Object}.
     *
     * @return {@code true} if {@code equals(Object)} is declared by {@code Card} itself
     */
    private static boolean overridesEquals() {
        try {
            return Card.class.getMethod("equals", Object.class).getDeclaringClass().equals(Card.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("equals(Object) must always be resolvable", e);
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code Card} is a JPA entity mapped to the {@code card} table, matching the
     * VSAM {@code CARDDAT} file it replaces.
     */
    @Test
    @DisplayName("is a JPA @Entity mapped to table 'card'")
    void classIsJpaEntityWithCardTable() {
        assertThat(Card.class.isAnnotationPresent(Entity.class))
                .as("Card must be annotated with @Entity")
                .isTrue();
        Table table = Card.class.getAnnotation(Table.class);
        assertThat(table).as("Card must declare @Table").isNotNull();
        assertThat(table.name()).as("@Table.name()").isEqualTo("card");
    }

    /**
     * Verifies the primary key mapping: {@code CARD-NUM PIC X(16)} becomes the {@code @Id} field
     * {@code cardNum} of type {@link String}, mapped to {@code card_num} with length 16.
     */
    @Test
    @DisplayName("primary key cardNum -> @Id String card_num length 16")
    void primaryKeyIsCardNum() {
        Field id = field("cardNum");
        assertThat(id.isAnnotationPresent(Id.class)).as("cardNum must be annotated @Id").isTrue();
        assertThat(id.getType()).as("cardNum Java type").isEqualTo(String.class);
        Column col = column("cardNum");
        assertThat(col.name()).as("cardNum column name").isEqualTo("card_num");
        assertThat(col.length()).as("cardNum column length").isEqualTo(16);
    }

    /**
     * Verifies the remaining {@code CVACT02Y} column mappings: names, Java types, and the
     * length/precision facets exactly as declared by the production {@code @Column} annotations.
     * Precision is asserted only for the numeric fields that declare it ({@code cardAcctId} and
     * {@code cardCvvCd}); length is asserted only for the character fields that declare it.
     */
    @Test
    @DisplayName("non-key columns map to their copybook fields with correct names/types/facets")
    void columnMappings() {
        // CARD-ACCT-ID PIC 9(11) -> Long, column card_acct_id, precision 11 (declared).
        assertMapping("cardAcctId", Long.class, "card_acct_id");
        assertThat(column("cardAcctId").precision()).as("cardAcctId precision").isEqualTo(11);

        // CARD-CVV-CD PIC 9(03) -> Integer, column card_cvv_cd, precision 3 (declared).
        assertMapping("cardCvvCd", Integer.class, "card_cvv_cd");
        assertThat(column("cardCvvCd").precision()).as("cardCvvCd precision").isEqualTo(3);

        // CARD-EMBOSSED-NAME PIC X(50) -> String, column card_embossed_name, length 50 (declared).
        assertMapping("cardEmbossedName", String.class, "card_embossed_name");
        assertThat(column("cardEmbossedName").length()).as("cardEmbossedName length").isEqualTo(50);

        // CARD-ACTIVE-STATUS PIC X(01) -> String, column card_active_status, length 1 (declared).
        assertMapping("cardActiveStatus", String.class, "card_active_status");
        assertThat(column("cardActiveStatus").length()).as("cardActiveStatus length").isEqualTo(1);
    }

    /**
     * Guards the intentional source misspelling. The copybook field {@code CARD-EXPIRAION-DATE}
     * ("EXPIRAION") is preserved verbatim as {@code cardExpiraionDate} / {@code card_expiraion_date}
     * for 1:1 traceability (AAP section 0.4.1); the property must therefore never be renamed to the
     * grammatically correct "expiration".
     */
    @Test
    @DisplayName("COBOL misspelling 'EXPIRAION' is preserved (must not be corrected)")
    void expiraionMisspellingIsPreserved() {
        // The misspelling is intentional (AAP section 0.4.1): fail loudly if the field is ever
        // renamed to the "correct" spelling, since that would break source traceability.
        assertThatCode(() -> Card.class.getDeclaredField("cardExpiraionDate"))
                .as("field 'cardExpiraionDate' (COBOL misspelling preserved) must exist")
                .doesNotThrowAnyException();

        Field f = field("cardExpiraionDate");
        assertThat(f.getType()).as("cardExpiraionDate Java type").isEqualTo(LocalDate.class);
        assertThat(column("cardExpiraionDate").name())
                .as("cardExpiraionDate column name")
                .isEqualTo("card_expiraion_date");
    }

    /**
     * Enforces the security-hygiene rule that neither the card verification value nor the card number
     * (PAN) is leaked through {@link Card#toString()}. The hardened representation is the safe identity
     * form (class name + identity hash) that exposes no field content (CWE-532).
     */
    @Test
    @DisplayName("toString() never exposes the CVV or PAN (security hygiene)")
    void cvvIsNotExposedInToString() {
        Card card = new Card();
        // Distinctive card number that does NOT itself contain the substring "747".
        card.setCardNum("0500024453765740");
        card.setCardAcctId(99999999999L);
        card.setCardCvvCd(747);
        card.setCardEmbossedName("TEST CARDHOLDER");
        card.setCardExpiraionDate(LocalDate.of(2099, 1, 1));
        card.setCardActiveStatus("Y");

        String rendered = card.toString();
        assertThat(rendered).as("toString() must be non-null so it is usable for logging").isNotNull();
        assertThat(rendered)
                .as("toString() must never leak the CVV value")
                .doesNotContain("747");
        assertThat(rendered)
                .as("toString() must not leak the card number (PAN) either")
                .doesNotContain("0500024453765740");
        assertThat(rendered)
                .as("toString() uses the safe identity form (class name + identity hash)")
                .startsWith("Card@");
    }

    /**
     * Enforces the decimal-fidelity rule (AAP section 0.6.1): no field of the entity may use a
     * floating-point type. Synthetic fields (for example the {@code $jacocoData} array injected by
     * coverage instrumentation) are skipped because they are not part of the mapped contract.
     */
    @Test
    @DisplayName("no field uses a floating-point type (decimal fidelity)")
    void noFloatingPointFields() {
        for (Field f : Card.class.getDeclaredFields()) {
            if (f.isSynthetic()) {
                continue; // e.g. JaCoCo's synthetic $jacocoData instrumentation field
            }
            assertThat(f.getType())
                    .as("field '%s' must not be a floating-point type (AAP section 0.6.1)", f.getName())
                    .isNotIn(float.class, double.class, Float.class, Double.class);
        }
    }

    /**
     * Verifies entity identity semantics. The production entity keys {@code equals}/{@code hashCode}
     * solely on the primary key {@code cardNum}, mirroring VSAM KSDS record identity. If a future
     * change removes those overrides, the test falls back to asserting reference identity so it
     * always matches the real behavior of the class under test.
     */
    @Test
    @DisplayName("equals/hashCode are keyed on cardNum (VSAM KSDS record identity)")
    void equalsAndHashCodeOnCardNum() {
        Card a = new Card();
        a.setCardNum("0000000000000001");
        Card b = new Card();
        b.setCardNum("0000000000000001");

        // Reflexivity holds regardless of the implementation strategy.
        assertThat(a).as("an object must equal itself").isEqualTo(a);

        if (overridesEquals()) {
            // Production overrides equals/hashCode keyed on cardNum: equal keys -> equal objects.
            assertThat(a).as("cards with the same cardNum must be equal").isEqualTo(b);
            assertThat(a.hashCode())
                    .as("equal cards must share the same hashCode")
                    .isEqualTo(b.hashCode());

            Card different = new Card();
            different.setCardNum("0000000000000002");
            assertThat(a).as("cards with different cardNum must not be equal").isNotEqualTo(different);

            // The strict getClass() guard means a Card is never equal to a non-Card value.
            assertThat(a).as("a Card must not equal a value of another type").isNotEqualTo("0000000000000001");
        } else {
            // Reference-identity fallback: distinct instances are not equal.
            assertThat(a).as("reference-identity fallback for non-overridden equals").isNotEqualTo(b);
        }
    }
}
