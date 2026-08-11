package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@code CardXref} JPA mapping contract.
 *
 * :purpose: Freeze the card-to-customer-to-account cross-reference contract that backs 100%
 *     referential integrity in the modernized CardDemo application. The legacy 50-byte
 *     ``CARD-XREF-RECORD`` (copybook ``CVACT03Y``, the ``CXACAIX`` alternate index) carries
 *     three identifiers -- ``XREF-CARD-NUM PIC X(16)`` (record key), ``XREF-CUST-ID PIC 9(09)``
 *     and ``XREF-ACCT-ID PIC 9(11)`` -- and these reflection assertions lock the ``CardXref``
 *     entity that re-expresses them: the ``@Entity`` mapping onto the ``card_xref`` table, the
 *     16-character ``String`` primary key ``xrefCardNum``, and the two ``Long`` linkage
 *     identifiers ``xrefCustId`` and ``xrefAcctId`` that become the database foreign keys.
 * :output: JUnit 5 / AssertJ assertions only, driven purely by JDK reflection over
 *     ``CardXref`` and its RUNTIME-retained ``jakarta.persistence`` annotations; the class holds
 *     no state and touches no database, Spring context, or other external resource.
 */
final class CardXrefTest {

    /**
     * Resolve a declared field of {@code CardXref} by name.
     *
     * :param name: the declared field name to resolve.
     * :returns: the reflected {@link java.lang.reflect.Field}.
     */
    private static Field field(String name) {
        try {
            return CardXref.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("CardXref must declare a field named '" + name + "'", e);
        }
    }

    /**
     * Resolve the {@code @Column} mapping of a declared field of {@code CardXref}.
     *
     * :param name: the declared field name whose column mapping is required.
     * :returns: the non-null {@link jakarta.persistence.Column} annotation on that field.
     */
    private static Column column(String name) {
        Column mapping = field(name).getAnnotation(Column.class);
        assertThat(mapping)
                .as("field '%s' must carry a @Column mapping", name)
                .isNotNull();
        return mapping;
    }

    /**
     * Assert that {@code CardXref} is a JPA-managed entity.
     */
    @Test
    @DisplayName("CardXref is a JPA @Entity")
    void isJpaEntity() {
        assertThat(CardXref.class.isAnnotationPresent(Entity.class))
                .as("CardXref must be annotated with @Entity")
                .isTrue();
    }

    /**
     * Assert that {@code CardXref} maps onto the ``card_xref`` relational table.
     */
    @Test
    @DisplayName("CardXref maps to the card_xref table")
    void mapsToCardXrefTable() {
        Table table = CardXref.class.getAnnotation(Table.class);
        assertThat(table)
                .as("CardXref must declare a @Table mapping")
                .isNotNull();
        assertThat(table.name())
                .as("CardXref must map to the card_xref table")
                .isEqualTo("card_xref");
    }

    /**
     * Assert that ``xrefCardNum`` is the primary key: a 16-character ``String`` marked
     * {@code @Id}, mirroring ``XREF-CARD-NUM PIC X(16)``.
     */
    @Test
    @DisplayName("xrefCardNum is the @Id, a 16-character String primary key")
    void xrefCardNumIsPrimaryKeyLength16() {
        Field cardNum = field("xrefCardNum");
        assertThat(cardNum.isAnnotationPresent(Id.class))
                .as("xrefCardNum must be the @Id primary key")
                .isTrue();
        assertThat(cardNum.getType())
                .as("xrefCardNum must map XREF-CARD-NUM PIC X(16) to String")
                .isEqualTo(String.class);
        assertThat(column("xrefCardNum").length())
                .as("xrefCardNum column length must equal the 16-character COBOL width")
                .isEqualTo(16);
    }

    /**
     * Assert that the customer linkage identifier ``xrefCustId`` is a ``Long``, mirroring
     * ``XREF-CUST-ID PIC 9(09)``.
     */
    @Test
    @DisplayName("xrefCustId customer linkage is a Long")
    void custLinkageIsLong() {
        assertThat(field("xrefCustId").getType())
                .as("xrefCustId must map XREF-CUST-ID PIC 9(09) to Long")
                .isEqualTo(Long.class);
    }

    /**
     * Assert that the account linkage identifier ``xrefAcctId`` is a ``Long``, mirroring
     * ``XREF-ACCT-ID PIC 9(11)``.
     */
    @Test
    @DisplayName("xrefAcctId account linkage is a Long")
    void acctLinkageIsLong() {
        assertThat(field("xrefAcctId").getType())
                .as("xrefAcctId must map XREF-ACCT-ID PIC 9(11) to Long")
                .isEqualTo(Long.class);
    }

    /**
     * Assert that {@code CardXref} exposes the public no-argument constructor the JPA provider
     * requires (on a non-final entity class) and that its accessors round-trip the three
     * linkage identifiers.
     *
     * :raises ReflectiveOperationException: if the no-argument constructor cannot be resolved or
     *     invoked, which itself fails the contract.
     */
    @Test
    @DisplayName("public no-arg constructor and accessors round-trip the linkage identifiers")
    void publicNoArgConstructorAndAccessorsRoundTrip() throws ReflectiveOperationException {
        Constructor<CardXref> constructor = CardXref.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("CardXref must expose a public no-arg constructor for the JPA provider")
                .isTrue();
        assertThat(Modifier.isFinal(CardXref.class.getModifiers()))
                .as("a JPA @Entity class must not be final")
                .isFalse();

        CardXref xref = constructor.newInstance();
        xref.setXrefCardNum("1234567890123456");
        xref.setXrefCustId(9L);
        xref.setXrefAcctId(11L);

        assertThat(xref.getXrefCardNum()).isEqualTo("1234567890123456");
        assertThat(xref.getXrefCustId()).isEqualTo(9L);
        assertThat(xref.getXrefAcctId()).isEqualTo(11L);
    }
}
