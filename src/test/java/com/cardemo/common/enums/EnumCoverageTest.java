package com.cardemo.common.enums;

import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.common.exception.RecordNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for all four enum classes: FileStatusCode, TransactionCategory,
 * TransactionType, and UserType.
 */
class EnumCoverageTest {

    // ====================================================================
    // FileStatusCode
    // ====================================================================

    @ParameterizedTest
    @EnumSource(FileStatusCode.class)
    @DisplayName("FileStatusCode — every constant has non-null code and description")
    void fileStatusCodeFieldsNotNull(FileStatusCode status) {
        assertThat(status.getCode()).isNotNull().isNotBlank();
        assertThat(status.getDescription()).isNotNull().isNotBlank();
        assertThat(status.toString()).contains(status.getCode());
    }

    @Test
    @DisplayName("FileStatusCode — fromCode resolves all known codes")
    void fileStatusCodeFromCode() {
        assertThat(FileStatusCode.fromCode("00")).isEqualTo(FileStatusCode.SUCCESS);
        assertThat(FileStatusCode.fromCode("02")).isEqualTo(FileStatusCode.DUPLICATE_ALTERNATE_KEY);
        assertThat(FileStatusCode.fromCode("10")).isEqualTo(FileStatusCode.END_OF_FILE);
        assertThat(FileStatusCode.fromCode("22")).isEqualTo(FileStatusCode.DUPLICATE_KEY);
        assertThat(FileStatusCode.fromCode("23")).isEqualTo(FileStatusCode.RECORD_NOT_FOUND);
        assertThat(FileStatusCode.fromCode("35")).isEqualTo(FileStatusCode.FILE_NOT_AVAILABLE);
        assertThat(FileStatusCode.fromCode("46")).isEqualTo(FileStatusCode.SEQUENTIAL_READ_NO_POSITION);
        assertThat(FileStatusCode.fromCode("47")).isEqualTo(FileStatusCode.READ_FILE_NOT_OPEN);
    }

    @Test
    @DisplayName("FileStatusCode — fromCode throws for unknown code")
    void fileStatusCodeFromCodeUnknown() {
        assertThatThrownBy(() -> FileStatusCode.fromCode("99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("FileStatusCode — toException maps correctly")
    void fileStatusCodeToException() {
        assertThat(FileStatusCode.SUCCESS.toException()).isNull();
        assertThat(FileStatusCode.DUPLICATE_KEY.toException())
                .isInstanceOf(DuplicateRecordException.class);
        assertThat(FileStatusCode.DUPLICATE_ALTERNATE_KEY.toException())
                .isInstanceOf(DuplicateRecordException.class);
        assertThat(FileStatusCode.RECORD_NOT_FOUND.toException())
                .isInstanceOf(RecordNotFoundException.class);
        assertThat(FileStatusCode.END_OF_FILE.toException())
                .isInstanceOf(RecordNotFoundException.class);
        assertThat(FileStatusCode.FILE_NOT_AVAILABLE.toException())
                .isInstanceOf(FileStatusException.class);
        assertThat(FileStatusCode.SEQUENTIAL_READ_NO_POSITION.toException())
                .isInstanceOf(FileStatusException.class);
        assertThat(FileStatusCode.READ_FILE_NOT_OPEN.toException())
                .isInstanceOf(FileStatusException.class);
    }

    // ====================================================================
    // TransactionCategory
    // ====================================================================

    @ParameterizedTest
    @EnumSource(TransactionCategory.class)
    @DisplayName("TransactionCategory — every constant has non-null code and description")
    void transactionCategoryFieldsNotNull(TransactionCategory cat) {
        assertThat(cat.getCode()).isNotNull().hasSize(6);
        assertThat(cat.getDescription()).isNotNull().isNotBlank();
        assertThat(cat.toString()).contains(cat.getCode());
    }

    @Test
    @DisplayName("TransactionCategory — fromCode resolves all categories")
    void transactionCategoryFromCode() {
        assertThat(TransactionCategory.fromCode("010001"))
                .isEqualTo(TransactionCategory.REGULAR_SALES_DRAFT);
        assertThat(TransactionCategory.fromCode("020001"))
                .isEqualTo(TransactionCategory.CASH_PAYMENT);
        assertThat(TransactionCategory.fromCode("070001"))
                .isEqualTo(TransactionCategory.SALES_DRAFT_CREDIT_ADJUSTMENT);
    }

    @Test
    @DisplayName("TransactionCategory — fromCode throws for unknown code")
    void transactionCategoryFromCodeUnknown() {
        assertThatThrownBy(() -> TransactionCategory.fromCode("999999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TransactionCategory — 18 categories exist matching TRANCATG data")
    void transactionCategoryCount() {
        assertThat(TransactionCategory.values()).hasSize(18);
    }

    // ====================================================================
    // TransactionType
    // ====================================================================

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    @DisplayName("TransactionType — every constant has non-null code and description")
    void transactionTypeFieldsNotNull(TransactionType type) {
        assertThat(type.getCode()).isNotNull().isNotBlank();
        assertThat(type.getDescription()).isNotNull().isNotBlank();
        assertThat(type.toString()).contains(type.getCode());
    }

    @Test
    @DisplayName("TransactionType — fromCode resolves all 7 types")
    void transactionTypeFromCode() {
        assertThat(TransactionType.fromCode("01")).isEqualTo(TransactionType.PURCHASE);
        assertThat(TransactionType.fromCode("02")).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    @DisplayName("TransactionType — fromCode throws for unknown code")
    void transactionTypeFromCodeUnknown() {
        assertThatThrownBy(() -> TransactionType.fromCode("XX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TransactionType — 7 types exist matching TRANTYPE data")
    void transactionTypeCount() {
        assertThat(TransactionType.values()).hasSize(7);
    }

    // ====================================================================
    // UserType
    // ====================================================================

    @Test
    @DisplayName("UserType — ADMIN and USER constants exist with correct codes")
    void userTypeConstants() {
        assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
        assertThat(UserType.USER.getCode()).isEqualTo('U');
    }

    @ParameterizedTest
    @EnumSource(UserType.class)
    @DisplayName("UserType — toString is non-null for all values")
    void userTypeToString(UserType type) {
        assertThat(type.toString()).isNotNull();
    }

    @Test
    @DisplayName("UserType — fromCode resolves ADMIN and USER")
    void userTypeFromCode() {
        assertThat(UserType.fromCode('A')).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromCode('U')).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("UserType — fromCode throws for unknown code")
    void userTypeFromCodeUnknown() {
        assertThatThrownBy(() -> UserType.fromCode('X'))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
