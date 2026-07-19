package com.aws.carddemo.domain;

import java.lang.reflect.Field;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit mapping test for the JPA entity {@link TransactionCategory}, which
 * models the AWS CardDemo transaction-category reference table using a composite
 * primary key declared via {@link IdClass}.
 *
 * <p>COBOL oracle: {@code legacy/cpy/CVTRA04Y.cpy} record {@code TRAN-CAT-RECORD}
 * (RECLN 60), whose composite key {@code TRAN-CAT-KEY} is
 * {@code { TRAN-TYPE-CD PIC X(02), TRAN-CAT-CD PIC 9(04) }}, followed by
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)} and a trailing {@code FILLER PIC X(04)}
 * that carries no business data. This test locks the object/relational mapping
 * and the composite-key {@code equals}/{@code hashCode} contract against that
 * copybook, supporting the migration's 100% bidirectional traceability
 * requirement (Technical Specification section 0.6.10).</p>
 *
 * <p>The test is deliberately hermetic: it performs no database access and
 * bootstraps no Spring application context. It inspects the entity purely
 * through reflection and plain constructor/accessor invocation, so it runs as a
 * fast Maven Surefire unit test (the class name ends in {@code Test}).</p>
 */
class TransactionCategoryTest {

    /** Expected physical table name mapped from the legacy VSAM {@code TRANCATG} file. */
    private static final String TABLE_NAME = "transaction_category";

    /** Expected column name for {@code TRAN-TYPE-CD} (first composite-key part). */
    private static final String TYPE_CD_COLUMN = "tran_type_cd";

    /** Expected column name for {@code TRAN-CAT-CD} (second composite-key part). */
    private static final String CAT_CD_COLUMN = "tran_cat_cd";

    /** Expected column name for {@code TRAN-CAT-TYPE-DESC}. */
    private static final String DESCRIPTION_COLUMN = "tran_cat_type_desc";

    /**
     * Resolves a declared field on {@link TransactionCategory} by name and makes
     * it reflectively accessible. A missing field is a hard mapping defect, so a
     * {@link NoSuchFieldException} is surfaced as an {@link AssertionError} rather
     * than propagated as a checked exception; this keeps the individual
     * {@code @Test} method signatures free of {@code throws} clauses.
     *
     * @param name the declared field name to resolve
     * @return the accessible {@link Field}
     */
    private static Field declaredField(String name) {
        try {
            Field field = TransactionCategory.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "TransactionCategory must declare field '" + name + "'", e);
        }
    }

    /**
     * Builds a fully-populated {@link TransactionCategory} via its public no-arg
     * constructor and JavaBean setters (the same surface the JPA provider uses).
     *
     * @param typeCd      transaction type code (first key part)
     * @param catCd       transaction category code (second key part)
     * @param description human-readable description (non-key)
     * @return a populated entity instance
     */
    private static TransactionCategory newCategory(String typeCd, Integer catCd,
            String description) {
        TransactionCategory category = new TransactionCategory();
        category.setTypeCd(typeCd);
        category.setCatCd(catCd);
        category.setDescription(description);
        return category;
    }

    /**
     * The class must be a JPA {@code @Entity} mapped to the
     * {@code transaction_category} table and must declare the composite-key
     * {@code @IdClass} pointing at the nested
     * {@link TransactionCategory.TransactionCategoryId}.
     */
    @Test
    void classIsJpaEntityWithTableAndIdClass() {
        assertThat(TransactionCategory.class.isAnnotationPresent(Entity.class))
                .as("@Entity present on TransactionCategory")
                .isTrue();

        Table table = TransactionCategory.class.getAnnotation(Table.class);
        assertThat(table).as("@Table present on TransactionCategory").isNotNull();
        assertThat(table.name())
                .as("@Table name mirrors the legacy TRANCATG file")
                .isEqualTo(TABLE_NAME);

        IdClass idClass = TransactionCategory.class.getAnnotation(IdClass.class);
        assertThat(idClass).as("@IdClass present on TransactionCategory").isNotNull();

        Class<?> idClassValue = idClass.value();
        assertThat(idClassValue)
                .as("@IdClass points at the nested composite-key type")
                .isEqualTo(TransactionCategory.TransactionCategoryId.class);
    }

    /**
     * {@code typeCd} is the first key part ({@code TRAN-TYPE-CD PIC X(02)}): a
     * {@code String} {@code @Id} mapped to column {@code tran_type_cd} with
     * length 2.
     */
    @Test
    void typeCdIsIdMappedToTranTypeCd() {
        Field field = declaredField("typeCd");

        assertThat(field.isAnnotationPresent(Id.class))
                .as("typeCd is part of the composite @Id").isTrue();
        assertThat(field.getType())
                .as("typeCd Java type").isEqualTo(String.class);

        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("typeCd @Column present").isNotNull();
        assertThat(column.name()).as("typeCd column name").isEqualTo(TYPE_CD_COLUMN);
        assertThat(column.length())
                .as("typeCd column length mirrors PIC X(02)").isEqualTo(2);
    }

    /**
     * {@code catCd} is the second key part ({@code TRAN-CAT-CD PIC 9(04)}): an
     * {@code Integer} {@code @Id} mapped to column {@code tran_cat_cd} with
     * precision 4. It is modeled as {@link Integer} (never a floating-point
     * type) to preserve exact fixed-scale decimal semantics.
     */
    @Test
    void catCdIsIdMappedToTranCatCd() {
        Field field = declaredField("catCd");

        assertThat(field.isAnnotationPresent(Id.class))
                .as("catCd is part of the composite @Id").isTrue();
        assertThat(field.getType())
                .as("catCd Java type is Integer (exact fixed-scale, no floating point)")
                .isEqualTo(Integer.class);

        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("catCd @Column present").isNotNull();
        assertThat(column.name()).as("catCd column name").isEqualTo(CAT_CD_COLUMN);
        assertThat(column.precision())
                .as("catCd column precision mirrors PIC 9(04)").isEqualTo(4);
    }

    /**
     * {@code description} ({@code TRAN-CAT-TYPE-DESC PIC X(50)}) is a non-key
     * {@code String} column mapped to {@code tran_cat_type_desc} with length 50.
     */
    @Test
    void descriptionColumnMapping() {
        Field field = declaredField("description");

        assertThat(field.isAnnotationPresent(Id.class))
                .as("description is NOT part of the composite key").isFalse();
        assertThat(field.getType())
                .as("description Java type").isEqualTo(String.class);

        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("description @Column present").isNotNull();
        assertThat(column.name())
                .as("description column name").isEqualTo(DESCRIPTION_COLUMN);
        assertThat(column.length())
                .as("description column length mirrors PIC X(50)").isEqualTo(50);
    }

    /**
     * The composite-key class {@link TransactionCategory.TransactionCategoryId}
     * must implement value equality over BOTH parts (type + category): two keys
     * are equal iff both components match, with a consistent hash code. This
     * mirrors the legacy VSAM key {@code TRAN-CAT-KEY}.
     */
    @Test
    void idClassEqualsAndHashCodeOverBothParts() {
        TransactionCategory.TransactionCategoryId key =
                new TransactionCategory.TransactionCategoryId("01", 1);
        TransactionCategory.TransactionCategoryId sameKey =
                new TransactionCategory.TransactionCategoryId("01", 1);

        assertThat(sameKey)
                .as("identical composite keys are equal").isEqualTo(key);
        assertThat(sameKey)
                .as("equal composite keys share a hash code").hasSameHashCodeAs(key);

        TransactionCategory.TransactionCategoryId differentCategory =
                new TransactionCategory.TransactionCategoryId("01", 2);
        assertThat(differentCategory)
                .as("differing category code breaks key equality").isNotEqualTo(key);

        TransactionCategory.TransactionCategoryId differentType =
                new TransactionCategory.TransactionCategoryId("02", 1);
        assertThat(differentType)
                .as("differing type code breaks key equality").isNotEqualTo(key);
    }

    /**
     * Entity {@code equals}/{@code hashCode} must be consistent with the
     * composite key only: two {@link TransactionCategory} rows with the same
     * (type, category) are equal even when the non-key {@code description}
     * differs, and rows with differing key parts are unequal. Reflexivity and
     * inequality against {@code null} and an unrelated type are also verified.
     */
    @Test
    void entityEqualsConsistentWithCompositeKey() {
        TransactionCategory first = newCategory("01", 1, "Regular Sales Draft");
        TransactionCategory sameKeyDifferentDesc =
                newCategory("01", 1, "Totally different description");

        assertThat(sameKeyDifferentDesc)
                .as("equality is defined over the composite key, not the description")
                .isEqualTo(first);
        assertThat(sameKeyDifferentDesc)
                .as("equal composite keys yield equal hash codes")
                .hasSameHashCodeAs(first);

        assertThat(newCategory("01", 2, "Regular Sales Draft"))
                .as("differing category code breaks entity equality")
                .isNotEqualTo(first);
        assertThat(newCategory("02", 1, "Regular Sales Draft"))
                .as("differing type code breaks entity equality")
                .isNotEqualTo(first);

        assertThat(first).as("equality is reflexive").isEqualTo(first);
        assertThat(first).as("an entity is never equal to null").isNotEqualTo(null);
        assertThat(first)
                .as("an entity is never equal to an unrelated type")
                .isNotEqualTo("not a TransactionCategory");
    }
}
