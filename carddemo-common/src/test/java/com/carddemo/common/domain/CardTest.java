package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural and behavioral specification for the {@link Card} JPA entity.
 *
 * :purpose: Lock the mapping contract that migrates the legacy COBOL
 *     ``CARD-RECORD`` layout (copybook ``CVACT02Y``, RECLN 150) onto the
 *     PostgreSQL ``cards`` table. The suite enforces three invariants that must
 *     survive the mainframe-to-Java migration: (a) the 16-character
 *     ``CARD-NUM`` maps to the ``@Id`` primary key while the owning account is
 *     modeled as a scalar ``Long`` foreign key rather than a writable object
 *     association; (b) the misspelled legacy identifier ``CARD-EXPIRAION-DATE``
 *     is preserved character-for-character as ``cardExpiraionDate`` /
 *     ``card_expiraion_date`` (spec-literal fidelity); and (c) the sensitive
 *     card verification value (``CARD-CVV-CD``) is secured — encrypted at rest
 *     and never emitted by {@link Card#toString()}.
 * :output: JUnit 5 / AssertJ assertions only. The suite is a pure-JVM
 *     reflection and behavioral test; it starts no Spring context, opens no
 *     database, and uses no Testcontainers, so it never triggers the attribute
 *     converter that would require an encryption key.
 */
final class CardTest {

    // ------------------------------------------------------------------
    // Reflection helpers (do not depend on setter/getter names)
    // ------------------------------------------------------------------

    /**
     * Look up a declared field of {@link Card} by name.
     *
     * :param name: the declared field name to resolve.
     * :returns: the reflective {@link Field} handle for ``name``.
     */
    private static Field field(String name) throws NoSuchFieldException {
        return Card.class.getDeclaredField(name);
    }

    /**
     * Read the ``jakarta.persistence.Column`` mapping of a declared field.
     *
     * :param name: the declared field name whose ``@Column`` is required.
     * :returns: the ``@Column`` annotation, or ``null`` when the field carries none.
     */
    private static Column column(String name) throws NoSuchFieldException {
        return field(name).getAnnotation(Column.class);
    }

    /**
     * Report whether {@link Card} declares a field with the given name.
     *
     * :param name: the candidate declared field name.
     * :returns: ``true`` when the field exists, ``false`` otherwise.
     */
    private static boolean fieldExists(String name) {
        try {
            Card.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    /**
     * Enumerate the persistable instance fields of {@link Card}.
     *
     * :returns: the declared, non-static, non-synthetic fields; synthetic and
     *     static members (for example a coverage-agent field) are excluded so
     *     the mapping assertions inspect only real entity state.
     */
    private static List<Field> instanceFields() {
        return Arrays.stream(Card.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .toList();
    }

    /**
     * Assign a value to a {@link Card} field reflectively.
     *
     * :param target: the card instance to mutate.
     * :param name: the declared field name to set.
     * :param value: the value to assign.
     */
    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field f = Card.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Instantiate a {@link Card} through its public no-argument constructor.
     *
     * :returns: a fresh, empty {@link Card} instance.
     */
    private static Card newCard() throws ReflectiveOperationException {
        return Card.class.getDeclaredConstructor().newInstance();
    }

    // ------------------------------------------------------------------
    // Entity structure and primary key
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that {@link Card} is a JPA-managed entity.
     */
    @Test
    @DisplayName("Card is a JPA @Entity")
    void isJpaEntity() {
        assertThat(Card.class.isAnnotationPresent(Entity.class)).isTrue();
    }

    /**
     * :purpose: Assert that {@link Card} maps to the ``cards`` relational table.
     */
    @Test
    @DisplayName("Card maps to the \"cards\" table")
    void mapsToCardsTable() {
        Table table = Card.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("cards");
    }

    /**
     * :purpose: Assert that ``cardNum`` is the 16-character ``String`` primary
     *     key (COBOL ``CARD-NUM PIC X(16)``).
     */
    @Test
    @DisplayName("cardNum is the @Id String primary key of length 16")
    void cardNumIsPrimaryKeyLength16() throws Exception {
        Field cardNum = field("cardNum");
        assertThat(cardNum.isAnnotationPresent(Id.class)).isTrue();
        assertThat(cardNum.getType()).isEqualTo(String.class);
        assertThat(column("cardNum").length()).isEqualTo(16);
    }

    // ------------------------------------------------------------------
    // Account foreign key: scalar Long, not a writable object association
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that the owning-account foreign key is modeled as a
     *     scalar ``Long`` (``cardAcctId``), mirroring COBOL
     *     ``CARD-ACCT-ID PIC 9(11)``.
     */
    @Test
    @DisplayName("cardAcctId is a scalar Long foreign key")
    void accountFkIsScalarLong() throws Exception {
        assertThat(field("cardAcctId").getType()).isEqualTo(Long.class);
    }

    /**
     * :purpose: Assert that the scalar ``cardAcctId`` is the single WRITABLE
     *     representation of the account foreign key, and that any ``Account``
     *     object association is read-only. The entity declares the account
     *     linkage through the scalar id; a read-only ``@ManyToOne`` (mapped
     *     ``insertable = false, updatable = false`` over the same column) may
     *     additionally declare the database foreign-key constraint, but it must
     *     never become a second writable mapping of ``card_acct_id``.
     */
    @Test
    @DisplayName("Account FK is the scalar Long; any Account association is read-only")
    void accountFkIsScalarNotWritableAssociation() throws Exception {
        Column acctId = column("cardAcctId");
        assertThat(acctId).isNotNull();
        assertThat(acctId.insertable()).isTrue();
        assertThat(acctId.updatable()).isTrue();

        for (Field f : instanceFields()) {
            if (f.getType() == Account.class) {
                JoinColumn join = f.getAnnotation(JoinColumn.class);
                assertThat(join)
                        .as("an Account association must be a read-only @JoinColumn mapping")
                        .isNotNull();
                assertThat(join.insertable())
                        .as("the Account association must not be insertable")
                        .isFalse();
                assertThat(join.updatable())
                        .as("the Account association must not be updatable")
                        .isFalse();
            }
        }
    }

    // ------------------------------------------------------------------
    // Spec-literal preservation of the misspelled expiration identifier
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that the legacy misspelling ``CARD-EXPIRAION-DATE``
     *     (missing the second ``T``) is preserved verbatim as the ``String``
     *     field ``cardExpiraionDate`` mapped to column ``card_expiraion_date``.
     */
    @Test
    @DisplayName("cardExpiraionDate preserves the legacy misspelling")
    void preservesCardExpiraionDateMisspelling() throws Exception {
        assertThat(fieldExists("cardExpiraionDate")).isTrue();
        assertThat(field("cardExpiraionDate").getType()).isEqualTo(String.class);
        assertThat(column("cardExpiraionDate").name()).isEqualTo("card_expiraion_date");
    }

    /**
     * :purpose: Assert that the misspelling is NOT silently corrected — neither
     *     a ``cardExpirationDate`` field nor a ``card_expiration_date`` column
     *     name may appear.
     */
    @Test
    @DisplayName("The expiration spelling is not corrected")
    void doesNotCorrectExpirationSpelling() throws Exception {
        assertThat(fieldExists("cardExpirationDate")).isFalse();
        assertThat(column("cardExpiraionDate").name()).isNotEqualTo("card_expiration_date");
    }

    // ------------------------------------------------------------------
    // Sensitive-field handling: CVV encrypted at rest and masked from toString
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that the card verification value ``cardCvvCd`` (COBOL
     *     ``CARD-CVV-CD PIC 9(03)``) is a ``String`` secured at rest by a JPA
     *     attribute converter (``@Convert``), so the sensitive value is stored
     *     encrypted rather than as plaintext.
     */
    @Test
    @DisplayName("cardCvvCd is an encrypted (converted) String")
    void cvvFieldIsEncryptedSecuredString() throws Exception {
        Field cvv = field("cardCvvCd");
        assertThat(cvv.getType()).isEqualTo(String.class);
        assertThat(cvv.isAnnotationPresent(Convert.class))
                .as("the sensitive CVV must be encrypted at rest via a JPA @Convert converter")
                .isTrue();
    }

    /**
     * :purpose: Assert that {@link Card#toString()} never leaks the sensitive
     *     CVV, while remaining a genuinely overridden, informative
     *     representation (not the default ``Object`` form).
     */
    @Test
    @DisplayName("toString omits the sensitive CVV but stays informative")
    void toStringExcludesCvv() throws Exception {
        Card card = newCard();
        setField(card, "cardCvvCd", "CVVSENTINEL");
        setField(card, "cardNum", "CARDNUMSENTINEL0");
        setField(card, "cardEmbossedName", "EMBOSSSENTINEL");

        String rendered = card.toString();

        // Security-critical: the CVV must never appear in the rendered form.
        assertThat(rendered).doesNotContain("CVVSENTINEL");

        // Non-vacuous: at least one non-sensitive sentinel must survive, proving
        // toString() is genuinely overridden (the card number is masked to its
        // last four characters, so the embossed name is the surviving sentinel).
        assertThat(rendered.contains("CARDNUMSENTINEL0") || rendered.contains("EMBOSSSENTINEL"))
                .isTrue();
    }
}
