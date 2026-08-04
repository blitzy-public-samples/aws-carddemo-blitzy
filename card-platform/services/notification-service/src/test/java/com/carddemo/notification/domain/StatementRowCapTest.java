package com.carddemo.notification.domain;

import com.carddemo.notification.domain.NotificationRenderer.CardholderContext;
import com.carddemo.notification.domain.NotificationRenderer.TransactionRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the ceiling on how many detail rows one rendered alert may carry.
 *
 * <p>Both renderers assemble every record into a list and join it, so the memory one call needs
 * grows with the row count it is handed. Nothing bounded that count.
 * {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} needed no ceiling, because
 * it wrote each record to a sequential file and held one record at a time.
 *
 * <p>The ceiling is enforced by one static method on the shared interface, and both implementations
 * call it. These tests run against both, so an implementation that stops calling it fails here.
 *
 * <p>The ceiling equals the maximum page size of {@code GET /notifications/{cardToken}}. One test
 * below holds that equality, because the two numbers drifting apart is what would let a caller ask
 * for a page no renderer will accept.
 */
class StatementRowCapTest {

    /** Both implementations of the interface under test. */
    private static Stream<NotificationRenderer> renderers() {
        return Stream.of(new PlainTextRenderer(), new HtmlRenderer());
    }

    /** A cardholder context with every component present. */
    private static CardholderContext context() {
        return new CardholderContext("CARDHOLDER NAME", "ADDRESS LINE ONE", "ADDRESS LINE TWO",
                "ADDRESS LINE THREE", "00000000001", "194.00", "650");
    }

    /**
     * Builds a row list of the requested size.
     *
     * @param size how many rows to build
     * @return the rows
     */
    private static List<TransactionRow> rows(int size) {
        List<TransactionRow> rows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            rows.add(new TransactionRow(String.format("%016d", index), "detail " + index, "1.00"));
        }
        return rows;
    }

    @Test
    @DisplayName("The ceiling matches the maximum page size of the history endpoint")
    void ceilingMatchesTheMaximumPageSize() {
        assertThat(NotificationRenderer.MAXIMUM_STATEMENT_ROWS)
                .as("carddemo.history.maximum-page-size in application.yml carries the same number")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("The guard returns a list at the ceiling and refuses one above it")
    void guardAcceptsTheCeilingAndRefusesAbove() {
        List<TransactionRow> atCeiling = rows(NotificationRenderer.MAXIMUM_STATEMENT_ROWS);
        assertThat(NotificationRenderer.requireRenderableRowCount(atCeiling))
                .isSameAs(atCeiling);

        assertThatThrownBy(() -> NotificationRenderer.requireRenderableRowCount(
                rows(NotificationRenderer.MAXIMUM_STATEMENT_ROWS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows holds at most 200")
                .hasMessageContaining("holds 201");
    }

    @Test
    @DisplayName("The guard refuses a null list")
    void guardRefusesNull() {
        assertThatThrownBy(() -> NotificationRenderer.requireRenderableRowCount(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("renderers")
    @DisplayName("Every renderer accepts a list at the ceiling")
    void rendererAcceptsTheCeiling(NotificationRenderer renderer) {
        assertThatCode(() -> renderer.renderStatementAlert(context(),
                rows(NotificationRenderer.MAXIMUM_STATEMENT_ROWS), new BigDecimal("200.00")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("renderers")
    @DisplayName("Every renderer refuses one row above the ceiling")
    void rendererRefusesAboveTheCeiling(NotificationRenderer renderer) {
        assertThatThrownBy(() -> renderer.renderStatementAlert(context(),
                rows(NotificationRenderer.MAXIMUM_STATEMENT_ROWS + 1), new BigDecimal("201.00")))
                .as("%s assembles every record in memory, so it has to refuse an unbounded list",
                        renderer.getClass().getSimpleName())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @ParameterizedTest
    @MethodSource("renderers")
    @DisplayName("Every renderer refuses a list far above the ceiling without assembling it")
    void rendererRefusesFarAboveTheCeiling(NotificationRenderer renderer) {
        assertThatThrownBy(() -> renderer.renderStatementAlert(context(), rows(10_000),
                new BigDecimal("10000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds 10000");
    }

    @ParameterizedTest
    @MethodSource("renderers")
    @DisplayName("Every renderer still accepts an empty list and a small list")
    void rendererAcceptsSmallLists(NotificationRenderer renderer) {
        assertThat(renderer.renderStatementAlert(context(), List.of(), BigDecimal.ZERO))
                .isNotEmpty();
        assertThat(renderer.renderStatementAlert(context(), rows(3), new BigDecimal("3.00")))
                .isNotEmpty();
    }
}
