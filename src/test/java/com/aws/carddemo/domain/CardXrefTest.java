package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit mapping test for the JPA entity
 * {@link com.aws.carddemo.domain.CardXref} - the card-customer-account
 * cross-reference with a three-part composite key realized through
 * {@link jakarta.persistence.IdClass}.
 *
 * <p>COBOL oracle (Javadoc origin): {@code legacy/cpy/CVACT03Y.cpy} defines
 * {@code CARD-XREF-RECORD} (record length 50):</p>
 * <pre>
 * 01  CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM   PIC X(16).   -- 16 bytes -&gt; xrefCardNum (composite-key part 1)
 *     05  XREF-CUST-ID    PIC 9(09).   --  9 bytes -&gt; xrefCustId  (composite-key part 2)
 *     05  XREF-ACCT-ID    PIC 9(11).   -- 11 bytes -&gt; xrefAcctId  (composite-key part 3)
 *     05  FILLER          PIC X(14).   -- 14 bytes -- NOT persisted
 * </pre>
 *
 * <p>This test verifies, by reflection alone, that the migrated entity preserves
 * the legacy VSAM {@code CCXREF} primary-key semantics (AAP &sect;0.6.2): all
 * three business fields form the composite key and the {@code FILLER} bytes are
 * intentionally not persisted. It also exercises the composite-key
 * {@code equals}/{@code hashCode} contract over each of the three parts, which
 * is what makes the mapping traceable back to the copybook (AAP &sect;0.6.10).</p>
 *
 * <p>It is a strict unit test: it starts no Spring context, opens no database
 * connection, and boots no persistence provider. Its class name ends in
 * {@code Test}, so it is executed by the Surefire (unit) plugin rather than
 * Failsafe.</p>
 */
class CardXrefTest {

    /**
     * Sample 16-character card number reused across the composite-key
     * assertions. Sourced from the legacy ASCII cross-reference fixtures so the
     * value is representative of real data widths.
     */
    private static final String SAMPLE_CARD_NUM = "0500024453765740";

    /** Sample nine-digit customer id used for the composite-key assertions. */
    private static final Long SAMPLE_CUST_ID = 50L;

    /** Sample eleven-digit account id used for the composite-key assertions. */
    private static final Long SAMPLE_ACCT_ID = 50L;

    /**
     * Resolves a declared field on {@link CardXref} by name, making it
     * accessible for reflective inspection and failing the test with a clear
     * message when the field is absent.
     *
     * @param name the declared field name to resolve
     * @return the resolved, accessible {@link Field}
     */
    private static Field declaredField(String name) {
        try {
            Field field = CardXref.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "Expected CardXref to declare field '" + name + "'", e);
        }
    }

    /**
     * The class is a JPA entity mapped to the {@code card_xref} table and
     * declares its composite identity via {@code @IdClass} pointing at the
     * nested {@link CardXref.CardXrefId}.
     */
    @Test
    void classIsJpaEntityWithTableAndIdClass() {
        assertThat(CardXref.class.isAnnotationPresent(Entity.class))
                .as("CardXref must be annotated @Entity")
                .isTrue();

        Table table = CardXref.class.getAnnotation(Table.class);
        assertThat(table).as("CardXref must declare @Table").isNotNull();
        assertThat(table.name())
                .as("@Table name must preserve the CCXREF cluster mapping")
                .isEqualTo("card_xref");

        IdClass idClass = CardXref.class.getAnnotation(IdClass.class);
        assertThat(idClass).as("CardXref must declare @IdClass").isNotNull();
        assertThat(idClass.value())
                .as("@IdClass must reference the nested CardXrefId composite key")
                .isEqualTo(CardXref.CardXrefId.class);
    }

    /**
     * Each of the three copybook key fields is present with the correct Java
     * type, is annotated {@code @Id}, and carries the expected column mapping.
     * Column {@code length} is asserted only where the production entity
     * declares it (the {@code X(16)} card number), and {@code precision} only
     * where declared (the numeric customer and account ids).
     */
    @Test
    void allThreeKeyColumnsMapped() {
        Field cardNum = declaredField("xrefCardNum");
        assertThat(cardNum.getType())
                .as("xrefCardNum maps XREF-CARD-NUM PIC X(16) to String")
                .isEqualTo(String.class);
        assertThat(cardNum.isAnnotationPresent(Id.class))
                .as("xrefCardNum is part of the composite key")
                .isTrue();
        Column cardColumn = cardNum.getAnnotation(Column.class);
        assertThat(cardColumn).as("xrefCardNum must declare @Column").isNotNull();
        assertThat(cardColumn.name()).isEqualTo("xref_card_num");
        assertThat(cardColumn.length())
                .as("X(16) preserves the 16-character fixed width")
                .isEqualTo(16);

        Field custId = declaredField("xrefCustId");
        assertThat(custId.getType())
                .as("xrefCustId maps XREF-CUST-ID PIC 9(09) to Long")
                .isEqualTo(Long.class);
        assertThat(custId.isAnnotationPresent(Id.class))
                .as("xrefCustId is part of the composite key")
                .isTrue();
        Column custColumn = custId.getAnnotation(Column.class);
        assertThat(custColumn).as("xrefCustId must declare @Column").isNotNull();
        assertThat(custColumn.name()).isEqualTo("xref_cust_id");
        assertThat(custColumn.precision())
                .as("9(09) preserves nine-digit precision")
                .isEqualTo(9);

        Field acctId = declaredField("xrefAcctId");
        assertThat(acctId.getType())
                .as("xrefAcctId maps XREF-ACCT-ID PIC 9(11) to Long")
                .isEqualTo(Long.class);
        assertThat(acctId.isAnnotationPresent(Id.class))
                .as("xrefAcctId is part of the composite key")
                .isTrue();
        Column acctColumn = acctId.getAnnotation(Column.class);
        assertThat(acctColumn).as("xrefAcctId must declare @Column").isNotNull();
        assertThat(acctColumn.name()).isEqualTo("xref_acct_id");
        assertThat(acctColumn.precision())
                .as("9(11) preserves eleven-digit precision")
                .isEqualTo(11);
    }

    /**
     * The composite-key {@code equals}/{@code hashCode} contract holds over all
     * three parts: two identically populated ids are equal with equal hash
     * codes, and independently varying any single part (card, customer, or
     * account) breaks equality.
     */
    @Test
    void idClassEqualsAndHashCodeOverAllThreeParts() {
        CardXref.CardXrefId base =
                new CardXref.CardXrefId(SAMPLE_CARD_NUM, SAMPLE_CUST_ID, SAMPLE_ACCT_ID);
        CardXref.CardXrefId same =
                new CardXref.CardXrefId(SAMPLE_CARD_NUM, SAMPLE_CUST_ID, SAMPLE_ACCT_ID);

        assertThat(base)
                .as("identical composite keys must be equal")
                .isEqualTo(same);
        assertThat(base.hashCode())
                .as("equal composite keys must share a hash code")
                .isEqualTo(same.hashCode());

        CardXref.CardXrefId differentCard =
                new CardXref.CardXrefId("9999999999999999", SAMPLE_CUST_ID, SAMPLE_ACCT_ID);
        assertThat(base)
                .as("a differing card number must break equality")
                .isNotEqualTo(differentCard);

        CardXref.CardXrefId differentCust =
                new CardXref.CardXrefId(SAMPLE_CARD_NUM, 51L, SAMPLE_ACCT_ID);
        assertThat(base)
                .as("a differing customer id must break equality")
                .isNotEqualTo(differentCust);

        CardXref.CardXrefId differentAcct =
                new CardXref.CardXrefId(SAMPLE_CARD_NUM, SAMPLE_CUST_ID, 99L);
        assertThat(base)
                .as("a differing account id must break equality")
                .isNotEqualTo(differentAcct);
    }

    /**
     * The composite-key {@code equals} implementation is defensive: comparing
     * against {@code null} or an unrelated type yields {@code false} rather than
     * throwing. These calls deliberately invoke {@code equals} directly because
     * assertion helpers that accept {@code null} short-circuit and would not
     * exercise the contract.
     */
    @Test
    void idClassEqualsHandlesNullAndType() {
        CardXref.CardXrefId id =
                new CardXref.CardXrefId(SAMPLE_CARD_NUM, SAMPLE_CUST_ID, SAMPLE_ACCT_ID);

        assertThat(id.equals(null))
                .as("equals(null) must return false")
                .isFalse();
        assertThat(id.equals("someString"))
                .as("equals against an unrelated type must return false")
                .isFalse();
    }

    /**
     * Per the copybook the only meaningful fields are the three composite-key
     * parts; the trailing {@code FILLER PIC X(14)} carries no business meaning
     * and is not persisted. This asserts the entity exposes exactly those three
     * instance fields and introduces no floating-point field (monetary/decimal
     * concerns do not apply to this cross-reference record). Synthetic and
     * static fields are ignored.
     */
    @Test
    void entityHasNoNonKeyBusinessFieldsBeyondFiller() {
        List<String> instanceFieldNames = new ArrayList<>();
        for (Field field : CardXref.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            instanceFieldNames.add(field.getName());

            Class<?> type = field.getType();
            assertThat(type)
                    .as("cross-reference record must not use floating point")
                    .isNotEqualTo(float.class)
                    .isNotEqualTo(double.class);
        }

        assertThat(instanceFieldNames)
                .as("only the three composite-key parts are persisted; "
                        + "FILLER X(14) is intentionally dropped")
                .containsExactlyInAnyOrder("xrefCardNum", "xrefCustId", "xrefAcctId");
    }
}
