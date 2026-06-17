package com.carddemo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable per-row projection for the paginated transaction browse
 * ({@code GET /transactions}).
 *
 * <p>Each instance represents a single row of the legacy 3270 transaction-list
 * screen and is intended to be wrapped by {@code PageResponse<TransactionListItem>}
 * (page size {@code 7}, matching the legacy browse pagination). It is a compact,
 * read-only projection of the much larger transaction record: only the four
 * columns that the original list screen rendered are exposed here. The complete
 * single-transaction view (merchant details, processing timestamp, card number,
 * type/category codes, etc.) is reserved for {@code TransactionResponse}.</p>
 *
 * <h2>Source-of-truth lineage</h2>
 * <p>This DTO is derived from two legacy artifacts (both retained as REFERENCE,
 * never modified):</p>
 * <ul>
 *   <li><b>BMS list screen {@code COTRN00}</b> ({@code app/cpy-bms/COTRN00.CPY})
 *       &mdash; defines the ten per-row field groups, each with exactly four data
 *       columns: {@code TRNIDnnI PIC X(16)} (transaction id),
 *       {@code TDATEnnI PIC X(8)} (date), {@code TDESCnnI PIC X(26)} (description,
 *       truncated <i>for display only</i>), and {@code TAMTnnnI PIC X(12)}
 *       (amount, edited/formatted for display).</li>
 *   <li><b>Transaction record {@code CVTRA05Y}</b> ({@code app/cpy/CVTRA05Y.cpy},
 *       {@code RECLN = 350}) &mdash; the authoritative field shapes backing the
 *       columns above.</li>
 * </ul>
 *
 * <h2>COBOL &rarr; Java type mapping</h2>
 * <table border="1">
 *   <caption>Field-by-field transformation</caption>
 *   <tr><th>COBOL field (CVTRA05Y)</th><th>PIC</th><th>Java component</th></tr>
 *   <tr><td>{@code TRAN-ID}</td><td>{@code X(16)}</td><td>{@code String tranId}</td></tr>
 *   <tr><td>{@code TRAN-ORIG-TS} (date portion)</td><td>{@code X(26)}</td><td>{@code LocalDate origDate}</td></tr>
 *   <tr><td>{@code TRAN-DESC}</td><td>{@code X(100)}</td><td>{@code String description}</td></tr>
 *   <tr><td>{@code TRAN-AMT}</td><td>{@code S9(09)V99}</td><td>{@code BigDecimal amount}</td></tr>
 * </table>
 *
 * <h2>Design notes (parity-critical)</h2>
 * <ul>
 *   <li><b>Date column.</b> The list screen's {@code TDATE} column shows only the
 *       <i>date portion</i> of the 26-character origination timestamp
 *       ({@code TRAN-ORIG-TS}). It is therefore exposed here as a
 *       {@link java.time.LocalDate}. The entity&rarr;DTO mapper performs the
 *       conversion (conceptually {@code transaction.getOrigTs().toLocalDate()});
 *       this DTO deliberately carries no time-of-day component.</li>
 *   <li><b>Description is not truncated.</b> The legacy 26-character screen
 *       truncation ({@code TDESCnnI PIC X(26)}) is a display concern only. The
 *       data contract preserves the full record description
 *       ({@code TRAN-DESC PIC X(100)}); callers/clients may truncate for
 *       presentation as needed.</li>
 *   <li><b>Monetary precision.</b> {@code amount} is the signed transaction amount
 *       as a {@link java.math.BigDecimal} (scale {@code 2}), preserving the COBOL
 *       packed-decimal {@code S9(09)V99} semantics. The application's global
 *       Jackson configuration serializes it as a JSON number with two decimal
 *       places (e.g. {@code -12.34}).</li>
 *   <li><b>Excluded fields.</b> Card number, merchant fields, type/category codes,
 *       and the processing timestamp ({@code TRAN-PROC-TS}) are intentionally
 *       omitted; they belong to the fuller {@code TransactionResponse} contract.</li>
 * </ul>
 *
 * <h2>Serialization contract</h2>
 * <p>With the {@code JavaTimeModule} registered in the sibling Jackson
 * configuration, an instance serializes to JSON of the form:</p>
 * <pre>{@code
 * {
 *   "tranId":      "0000000000000123",
 *   "origDate":    "2022-07-19",
 *   "description": "POS PURCHASE - GROCERY",
 *   "amount":      -12.34
 * }
 * }</pre>
 * <p>(JSON object member ordering is not semantically significant; the canonical
 * component order is {@code tranId}, {@code origDate}, {@code description},
 * {@code amount}.)</p>
 *
 * <p>This type is a Java 17 {@code record}: it is immutable, exposes a canonical
 * constructor and the four component accessors, and provides value-based
 * {@code equals}, {@code hashCode}, and {@code toString} implementations.</p>
 *
 * @param tranId      the 16-character transaction identifier
 *                    ({@code TRAN-ID PIC X(16)}); may be {@code null} only if the
 *                    backing record is itself unset, though in practice every
 *                    transaction has a non-null id
 * @param origDate    the date portion of the origination timestamp
 *                    ({@code TRAN-ORIG-TS}); rendered in the {@code TDATE} list
 *                    column
 * @param description the full transaction description
 *                    ({@code TRAN-DESC PIC X(100)}, not truncated)
 * @param amount      the signed transaction amount as {@code BigDecimal}
 *                    (scale {@code 2}; {@code TRAN-AMT PIC S9(09)V99})
 *
 * @see java.math.BigDecimal
 * @see java.time.LocalDate
 */
public record TransactionListItem(
        String tranId,
        LocalDate origDate,
        String description,
        BigDecimal amount
) {
}
