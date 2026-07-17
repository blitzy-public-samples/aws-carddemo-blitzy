/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.TransactionAddRequest;
import com.aws.carddemo.dto.TransactionAddResponse;
import com.aws.carddemo.dto.TransactionListResponse;
import com.aws.carddemo.dto.TransactionViewResponse;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link TransactionMapper}, the hand-written
 * entity&harr;DTO mapper for the three CardDemo transaction screens &mdash; list
 * ({@code CT00}/{@code COTRN00C}), view ({@code CT01}/{@code COTRN01C}) and add
 * ({@code CT02}/{@code COTRN02C}), the Java re-platform of legacy programs
 * {@code COTRN00C}/{@code COTRN01C}/{@code COTRN02C} over copybook
 * {@code CVTRA05Y.cpy} ({@code TRAN-RECORD}).
 *
 * <p>The suite is deliberately a <em>pure</em> unit test: the mapper is stateless,
 * so it is exercised through a plain {@code new TransactionMapper()} with no Spring
 * context, no database and no mocking framework. A fixed {@link #NOW} makes every
 * header date/time assertion deterministic.</p>
 *
 * <p>Three behavioral contracts carry the highest parity risk and are asserted
 * explicitly (AAP &sect;0.7.1 H3, &sect;0.9.2):</p>
 * <ol>
 *   <li><strong>Monetary fidelity</strong> &mdash; every amount is a
 *       {@link BigDecimal} carried at scale 2; assertions use
 *       {@code isEqualByComparingTo} <em>and</em> pin {@code scale() == 2}. Money
 *       is only ever built with {@code new BigDecimal("...")}; {@code double}/{@code
 *       float} are never used.</li>
 *   <li><strong>Code formatting</strong> &mdash; the numeric category code
 *       ({@code TRAN-CAT-CD PIC 9(04)}) renders zero-padded to four digits
 *       ({@code %04d}) and the numeric merchant id ({@code TRAN-MERCHANT-ID PIC
 *       9(09)}) to nine digits ({@code %09d}); a {@code null} of either renders as
 *       the empty string, never {@code "0000"}/{@code "null"} and never an
 *       exception.</li>
 *   <li><strong>Add-screen record assembly</strong> &mdash; {@code toEntity} leaves
 *       the {@code tranId} primary key {@code null} (it is assigned later by the
 *       service-side {@code IdGenerator}) and never maps the screen-only
 *       {@code accountId} (nor the {@code confirm} control flag) onto the entity,
 *       because neither is a field of {@code TRAN-RECORD}.</li>
 * </ol>
 */
@DisplayName("TransactionMapper")
class TransactionMapperTest {

    /**
     * Fixed reference instant used for every header date/time derivation so the
     * assertions never depend on the wall clock. Rendered by {@code DateUtils} as
     * {@link #EXPECTED_HEADER_DATE} ({@code MM/dd/uu}) and
     * {@link #EXPECTED_HEADER_TIME} ({@code HH:mm:ss}).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /** The {@code MM/dd/uu} header date that {@link #NOW} must render to. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The {@code HH:mm:ss} header time that {@link #NOW} must render to. */
    private static final String EXPECTED_HEADER_TIME = "23:15:58";

    /** The account id that lives only on the add screen and must never reach the entity. */
    private static final String SCREEN_ONLY_ACCOUNT_ID = "12345678901";

    /** The stateless mapper under test, constructed directly (no Spring, no mocks). */
    private final TransactionMapper mapper = new TransactionMapper();

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /**
     * Builds the canonical {@link Transaction} fixture through the public
     * all-arguments constructor (the no-argument constructor is {@code protected}
     * for the JPA provider and is not visible from this package). The category code
     * {@code 5} and merchant id {@code 999L} are single-/low-digit values chosen to
     * prove the {@code %04d}/{@code %09d} zero-padding, and the amount
     * {@code 42.50} is already at scale 2.
     *
     * @return a fully-populated transaction fixture
     */
    private Transaction sampleTransaction() {
        return new Transaction(
                "0000000000000123",            // TRAN-ID
                "01",                          // TRAN-TYPE-CD
                Integer.valueOf(5),            // TRAN-CAT-CD  -> "0005"
                "POS",                         // TRAN-SOURCE
                "COFFEE SHOP PURCHASE",        // TRAN-DESC
                new BigDecimal("42.50"),       // TRAN-AMT     (scale 2)
                Long.valueOf(999L),            // TRAN-MERCHANT-ID -> "000000999"
                "STARBUCKS",                   // TRAN-MERCHANT-NAME
                "SEATTLE",                     // TRAN-MERCHANT-CITY
                "98101",                       // TRAN-MERCHANT-ZIP
                "4111111111111111",            // TRAN-CARD-NUM
                "2022-07-19-12.34.56.789000",  // TRAN-ORIG-TS (26 chars)
                "2022-07-19-12.35.00.000000"); // TRAN-PROC-TS (26 chars)
    }

    /**
     * Builds the canonical add-screen request fixture ({@code COTRN02} symbolic
     * input map). The {@code accountId} is populated with {@link
     * #SCREEN_ONLY_ACCOUNT_ID} precisely so the tests can prove it is not carried
     * onto the entity; {@code action} is left {@code null} because the mapper does
     * not consult the attention key.
     *
     * @return a fully-populated add-screen request fixture
     */
    private TransactionAddRequest sampleAddRequest() {
        return new TransactionAddRequest(
                SCREEN_ONLY_ACCOUNT_ID,        // ACTIDINI (screen-only account id)
                "4111111111111111",            // CARDNINI
                "01",                          // TTYPCDI
                "0005",                        // TCATCDI
                "POS",                         // TRNSRCI
                "COFFEE SHOP PURCHASE",        // TDESCI
                new BigDecimal("42.50"),       // TRNAMTI  (BigDecimal, scale 2)
                "2022-07-19",                  // TORIGDTI
                "2022-07-19",                  // TPROCDTI
                "000000999",                   // MIDI
                "STARBUCKS",                   // MNAMEI
                "SEATTLE",                     // MCITYI
                "98101",                       // MZIPI
                "Y",                           // CONFIRMI (control flag; not mapped to entity)
                null);                         // action  (PfKeyAction; not mapped)
    }

    // ------------------------------------------------------------------------
    // Transaction List screen (CT00 / COTRN00C / map COTRN00)
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("List screen (CT00 / COTRN00C)")
    class ListScreen {

        @Test
        @DisplayName("toListRow projects id, MM/DD/YY origination date, description and a scale-2 amount")
        void toListRow_projectsSummaryRow() {
            TransactionListResponse.TransactionListRow row = mapper.toListRow(sampleTransaction());

            assertThat(row.transactionId()).isEqualTo("0000000000000123");
            // TRAN-ORIG-TS "2022-07-19-..." renders as the MM/DD/YY list date, not the raw ISO prefix.
            assertThat(row.date()).isEqualTo("07/19/22");
            assertThat(row.description()).isEqualTo("COFFEE SHOP PURCHASE");
            assertThat(row.amount()).isEqualByComparingTo("42.50");
            assertThat(row.amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("toListResponse builds the header, echoes the page number, maps rows and carries the message")
        void toListResponse_buildsPage() {
            TransactionListResponse resp =
                    mapper.toListResponse(List.of(sampleTransaction()), "00001", "info line", NOW);

            assertThat(resp.transactionName()).isEqualTo("CT00");
            assertThat(resp.programName()).isEqualTo("COTRN00C");
            assertThat(resp.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(resp.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(resp.pageNumber()).isEqualTo("00001");
            assertThat(resp.errorMessage()).isEqualTo("info line");
            assertThat(resp.transactions()).hasSize(1);
            assertThat(resp.transactions().get(0).transactionId()).isEqualTo("0000000000000123");
            assertThat(resp.transactions().get(0).date()).isEqualTo("07/19/22");
        }

        @Test
        @DisplayName("toListResponse returns a defensive, immutable page unaffected by later source mutation")
        void toListResponse_defensiveImmutableCopy() {
            List<Transaction> source = new ArrayList<>();
            source.add(sampleTransaction());

            TransactionListResponse resp = mapper.toListResponse(source, "00001", null, NOW);
            assertThat(resp.transactions()).hasSize(1);

            // Mutating the caller's list after the mapping must not change the response page.
            source.clear();
            assertThat(resp.transactions()).hasSize(1);

            // The returned page is unmodifiable.
            TransactionListResponse.TransactionListRow row = resp.transactions().get(0);
            assertThatThrownBy(() -> resp.transactions().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toListResponse treats a null transaction list as an empty, non-null page")
        void toListResponse_nullListYieldsEmptyPage() {
            TransactionListResponse resp = mapper.toListResponse(null, "00001", null, NOW);

            assertThat(resp.transactions()).isNotNull().isEmpty();
            assertThat(resp.pageNumber()).isEqualTo("00001");
        }

        @Test
        @DisplayName("toListRow renders a blank date for a null or too-short origination timestamp (no exception)")
        void toListRow_dateNullAndShortSafe() {
            Transaction nullTs = new Transaction(
                    "0000000000000124", "01", Integer.valueOf(5), "POS", "X",
                    new BigDecimal("1.00"), Long.valueOf(1L), "M", "C", "00000",
                    "4111111111111111", null, null);
            assertThat(mapper.toListRow(nullTs).date()).isEmpty();

            Transaction shortTs = new Transaction(
                    "0000000000000125", "01", Integer.valueOf(5), "POS", "X",
                    new BigDecimal("1.00"), Long.valueOf(1L), "M", "C", "00000",
                    "4111111111111111", "2022", "2022");
            assertThat(mapper.toListRow(shortTs).date()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Transaction View screen (CT01 / COTRN01C / map COTRN01)
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("View screen (CT01 / COTRN01C)")
    class ViewScreen {

        @Test
        @DisplayName("toViewResponse carries every detail field, zero-pads codes and keeps the amount at scale 2")
        void toViewResponse_fullDetail() {
            TransactionViewResponse resp = mapper.toViewResponse(sampleTransaction(), null, NOW);

            assertThat(resp.transactionName()).isEqualTo("CT01");
            assertThat(resp.programName()).isEqualTo("COTRN01C");
            assertThat(resp.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(resp.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);

            assertThat(resp.transactionId()).isEqualTo("0000000000000123");
            assertThat(resp.cardNumber()).isEqualTo("4111111111111111");
            assertThat(resp.typeCode()).isEqualTo("01");
            assertThat(resp.categoryCode()).isEqualTo("0005");   // %04d of Integer 5
            assertThat(resp.source()).isEqualTo("POS");
            assertThat(resp.description()).isEqualTo("COFFEE SHOP PURCHASE");
            assertThat(resp.amount()).isEqualByComparingTo("42.50");
            assertThat(resp.amount().scale()).isEqualTo(2);
            assertThat(resp.originDate()).isEqualTo("2022-07-19");  // leading ten of TRAN-ORIG-TS
            assertThat(resp.processDate()).isEqualTo("2022-07-19"); // leading ten of TRAN-PROC-TS
            assertThat(resp.merchantId()).isEqualTo("000000999");   // %09d of Long 999
            assertThat(resp.merchantName()).isEqualTo("STARBUCKS");
            assertThat(resp.merchantCity()).isEqualTo("SEATTLE");
            assertThat(resp.merchantZip()).isEqualTo("98101");
        }

        @Test
        @DisplayName("toViewResponse passes the error/message line through verbatim")
        void toViewResponse_carriesErrorMessage() {
            TransactionViewResponse resp =
                    mapper.toViewResponse(sampleTransaction(), "Invalid key pressed", NOW);

            assertThat(resp.errorMessage()).isEqualTo("Invalid key pressed");
        }

        @Test
        @DisplayName("toViewResponse renders blank origination/processing dates for null timestamps (no exception)")
        void toViewResponse_dateNullSafe() {
            Transaction nullTs = new Transaction(
                    "0000000000000126", "01", Integer.valueOf(5), "POS", "X",
                    new BigDecimal("1.00"), Long.valueOf(1L), "M", "C", "00000",
                    "4111111111111111", null, null);

            TransactionViewResponse resp = mapper.toViewResponse(nullTs, null, NOW);
            assertThat(resp.originDate()).isEmpty();
            assertThat(resp.processDate()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Code formatting (category %04d / merchant id %09d, null -> "")
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Code formatting (category %04d / merchant id %09d, null -> \"\")")
    class CodeFormatting {

        @Test
        @DisplayName("A single-digit category code is left-zero-padded to four digits")
        void categoryCode_zeroPaddedToFour() {
            assertThat(mapper.toViewResponse(sampleTransaction(), null, NOW).categoryCode())
                    .isEqualTo("0005");
        }

        @Test
        @DisplayName("A small merchant id is left-zero-padded to nine digits")
        void merchantId_zeroPaddedToNine() {
            assertThat(mapper.toViewResponse(sampleTransaction(), null, NOW).merchantId())
                    .isEqualTo("000000999");
        }

        @Test
        @DisplayName("A null category code and merchant id both render as empty strings (never \"0000\"/\"null\"/NPE)")
        void nullCodes_renderAsEmptyStrings() {
            Transaction nullCodes = new Transaction(
                    "0000000000000127", "01", null, "POS", "COFFEE SHOP PURCHASE",
                    new BigDecimal("42.50"), null, "STARBUCKS", "SEATTLE", "98101",
                    "4111111111111111", "2022-07-19-12.34.56.789000",
                    "2022-07-19-12.35.00.000000");

            TransactionViewResponse resp = mapper.toViewResponse(nullCodes, null, NOW);
            assertThat(resp.categoryCode()).isEmpty();
            assertThat(resp.merchantId()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Transaction Add screen (CT02 / COTRN02C / map COTRN02) - toEntity
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("toEntity (CT02 / COTRN02C add) - record assembly before the write")
    class ToEntity {

        @Test
        @DisplayName("toEntity leaves the primary key (tranId) null for the service-side IdGenerator")
        void toEntity_leavesTranIdNull() {
            Transaction entity = mapper.toEntity(sampleAddRequest());

            assertThat(entity.getTranId()).isNull();
        }

        @Test
        @DisplayName("toEntity maps every TRAN-RECORD field: codes parsed, amount at scale 2, dates verbatim")
        void toEntity_mapsAllRecordFields() {
            Transaction entity = mapper.toEntity(sampleAddRequest());

            // Every entity field is pinned to its exact expected value; this simultaneously
            // proves that neither the account id nor the confirm flag leaked onto any field.
            assertThat(entity.getTranId()).isNull();
            assertThat(entity.getTypeCd()).isEqualTo("01");
            assertThat(entity.getCatCd()).isEqualTo(5);              // parsed from "0005"
            assertThat(entity.getTranSource()).isEqualTo("POS");
            assertThat(entity.getTranDesc()).isEqualTo("COFFEE SHOP PURCHASE");
            assertThat(entity.getTranAmt()).isEqualByComparingTo("42.50");
            assertThat(entity.getTranAmt().scale()).isEqualTo(2);
            assertThat(entity.getTranMerchantId()).isEqualTo(999L);  // parsed from "000000999"
            assertThat(entity.getTranMerchantName()).isEqualTo("STARBUCKS");
            assertThat(entity.getTranMerchantCity()).isEqualTo("SEATTLE");
            assertThat(entity.getTranMerchantZip()).isEqualTo("98101");
            assertThat(entity.getCardNum()).isEqualTo("4111111111111111");
            assertThat(entity.getOrigTs()).isEqualTo("2022-07-19");  // entered date, verbatim
            assertThat(entity.getProcTs()).isEqualTo("2022-07-19");  // entered date, verbatim
        }

        @Test
        @DisplayName("The screen-only accountId is not mapped: cardNum keeps the card, never the account id")
        void toEntity_screenOnlyAccountIdNotMapped() {
            Transaction entity = mapper.toEntity(sampleAddRequest());

            // The card field carries the card number, not the account id key.
            assertThat(entity.getCardNum())
                    .isEqualTo("4111111111111111")
                    .isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);

            // No other string field inadvertently receives the account id either.
            assertThat(entity.getTypeCd()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(entity.getTranSource()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(entity.getTranDesc()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(entity.getTranMerchantName()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(entity.getTranMerchantCity()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(entity.getTranMerchantZip()).isNotEqualTo(SCREEN_ONLY_ACCOUNT_ID);
        }

        @Test
        @DisplayName("The confirm control flag is not persisted onto any entity field")
        void toEntity_confirmFlagNotMapped() {
            // "Y" is the confirm value in the fixture; no mapped field should equal it.
            Transaction entity = mapper.toEntity(sampleAddRequest());

            assertThat(entity.getTypeCd()).isNotEqualTo("Y");
            assertThat(entity.getTranSource()).isNotEqualTo("Y");
            assertThat(entity.getTranDesc()).isNotEqualTo("Y");
            assertThat(entity.getTranMerchantName()).isNotEqualTo("Y");
            assertThat(entity.getTranMerchantCity()).isNotEqualTo("Y");
            assertThat(entity.getTranMerchantZip()).isNotEqualTo("Y");
            assertThat(entity.getCardNum()).isNotEqualTo("Y");
        }

        @Test
        @DisplayName("A blank category code and merchant id parse to null entity values")
        void toEntity_blankCodesParseToNull() {
            TransactionAddRequest req = new TransactionAddRequest(
                    SCREEN_ONLY_ACCOUNT_ID, "4111111111111111", "01", "   ", "POS",
                    "COFFEE SHOP PURCHASE", new BigDecimal("42.50"), "2022-07-19",
                    "2022-07-19", "", "STARBUCKS", "SEATTLE", "98101", "Y", null);

            Transaction entity = mapper.toEntity(req);
            assertThat(entity.getCatCd()).isNull();
            assertThat(entity.getTranMerchantId()).isNull();
        }
    }

    // ------------------------------------------------------------------------
    // Transaction Add screen (CT02 / COTRN02C / map COTRN02) - toAddResponse
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("toAddResponse (CT02 / COTRN02C) - echo and post-persist redisplay")
    class AddResponse {

        @Test
        @DisplayName("Request echo returns the entered values verbatim (codes not re-derived) with a scale-2 amount")
        void toAddResponse_echoesRequestVerbatim() {
            TransactionAddResponse resp = mapper.toAddResponse(sampleAddRequest(), null, NOW);

            assertThat(resp.transactionName()).isEqualTo("CT02");
            assertThat(resp.programName()).isEqualTo("COTRN02C");
            assertThat(resp.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(resp.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);

            // The add RESPONSE echoes the account id (unlike toEntity, which drops it).
            assertThat(resp.accountId()).isEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(resp.cardNumber()).isEqualTo("4111111111111111");
            assertThat(resp.typeCode()).isEqualTo("01");
            assertThat(resp.categoryCode()).isEqualTo("0005");   // echoed verbatim (the submitted string)
            assertThat(resp.source()).isEqualTo("POS");
            assertThat(resp.description()).isEqualTo("COFFEE SHOP PURCHASE");
            assertThat(resp.amount()).isEqualByComparingTo("42.50");
            assertThat(resp.amount().scale()).isEqualTo(2);
            assertThat(resp.originDate()).isEqualTo("2022-07-19");
            assertThat(resp.processDate()).isEqualTo("2022-07-19");
            assertThat(resp.merchantId()).isEqualTo("000000999");  // echoed verbatim
            assertThat(resp.merchantName()).isEqualTo("STARBUCKS");
            assertThat(resp.merchantCity()).isEqualTo("SEATTLE");
            assertThat(resp.merchantZip()).isEqualTo("98101");
            assertThat(resp.confirm()).isEqualTo("Y");             // echoed
        }

        @Test
        @DisplayName("Post-persist redisplay zero-pads codes from the entity and clears the confirm flag")
        void toAddResponse_postPersistFormatsFromEntity() {
            TransactionAddResponse resp =
                    mapper.toAddResponse(sampleTransaction(), SCREEN_ONLY_ACCOUNT_ID, "Added", NOW);

            assertThat(resp.transactionName()).isEqualTo("CT02");
            assertThat(resp.programName()).isEqualTo("COTRN02C");
            assertThat(resp.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(resp.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);

            // The account id is the one the service resolved and passed in.
            assertThat(resp.accountId()).isEqualTo(SCREEN_ONLY_ACCOUNT_ID);
            assertThat(resp.cardNumber()).isEqualTo("4111111111111111");
            assertThat(resp.categoryCode()).isEqualTo("0005");     // %04d from Integer 5
            assertThat(resp.merchantId()).isEqualTo("000000999");  // %09d from Long 999
            assertThat(resp.amount()).isEqualByComparingTo("42.50");
            assertThat(resp.amount().scale()).isEqualTo(2);
            assertThat(resp.originDate()).isEqualTo("2022-07-19");  // leading ten
            assertThat(resp.processDate()).isEqualTo("2022-07-19");
            assertThat(resp.confirm()).isEmpty();                  // cleared after persist
            assertThat(resp.errorMessage()).isEqualTo("Added");
        }
    }
}
