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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.dto.AdminMenuResponse;
import com.aws.carddemo.dto.MainMenuResponse;

/**
 * Fast, isolated, pure-logic unit tests for {@link MenuMapper}, the hand-written response-assembly
 * mapper that consolidates the two CardDemo menu screens &mdash; the <strong>Main Menu</strong>
 * (BMS map {@code COMEN01}, program {@code COMEN01C}, transaction {@code CM00}) and the
 * <strong>Admin Menu</strong> (BMS map {@code COADM01}, program {@code COADM01C}, transaction
 * {@code CA00}). The mapper is the Java re-platform of {@code legacy/cbl/COMEN01C.cbl} and
 * {@code legacy/cbl/COADM01C.cbl}; its source screen contracts are the symbolic copybooks
 * {@code legacy/cpy-bms/COMEN01.CPY} and {@code legacy/cpy-bms/COADM01.CPY}, which define the
 * header fields, the twelve option lines ({@code OPTN001O}..{@code OPTN012O}, each
 * {@code PIC X(40)}) and the error line ({@code ERRMSGO}, {@code PIC X(78)}).
 *
 * <p>These tests use JUnit&nbsp;5 (Jupiter) with AssertJ and construct the mapper directly
 * ({@code new MenuMapper()}). There is <strong>no</strong> Spring context, database, Testcontainers,
 * or Mockito dependency, so the suite runs headlessly and reproducibly. Because
 * {@link MenuMapper} lives in the same package as this test, it is referenced without an import.</p>
 *
 * <p>The behavioral contract proven here mirrors the mapper's documented responsibilities:</p>
 * <ul>
 *   <li><strong>Header population</strong> &mdash; {@code transactionName}, {@code title01},
 *       {@code title02} and {@code programName} are passed through verbatim, and
 *       {@code currentDate}/{@code currentTime} are rendered from a single {@code now} instant via
 *       {@link DateUtils#formatDateMmDdYy(java.time.LocalDate)} ({@code MM/dd/uu}) and
 *       {@link DateUtils#formatTimeHhMmSs(java.time.LocalTime)} ({@code HH:mm:ss}).</li>
 *   <li><strong>Defensive immutable copy</strong> of the option list &mdash; the response is
 *       decoupled from later mutation of the caller's list, and the returned list rejects
 *       mutation.</li>
 *   <li><strong>Null-safety</strong> &mdash; a {@code null} option list is normalized to an empty
 *       (still unmodifiable) list rather than throwing, while a {@code null} {@code now} is rejected
 *       with a {@link NullPointerException} per the mapper's {@code Objects.requireNonNull} guard.</li>
 *   <li><strong>Error-line passthrough</strong> &mdash; the {@code errorMessage} argument is carried
 *       through unchanged.</li>
 * </ul>
 *
 * <p>All timing is derived from a fixed {@link #NOW} instant so the assertions are fully
 * deterministic on any run date. The expected date/time are precomputed with the <em>same</em>
 * {@link DateUtils} calls the production mapper uses and are additionally cross-checked against the
 * known literal renderings for {@link #NOW}.</p>
 */
class MenuMapperTest {

    /** Class under test; a plain, stateless component instantiable without a Spring context. */
    private final MenuMapper mapper = new MenuMapper();

    /** Fixed instant driving every header date/time assertion: 2022-07-19 23:15:58. */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /**
     * Expected header date, precomputed with the SAME {@link DateUtils} call the mapper performs so
     * the expectation tracks the production formatting logic exactly.
     */
    private static final String EXPECTED_DATE = DateUtils.formatDateMmDdYy(NOW.toLocalDate());

    /** Expected header time, precomputed with the SAME {@link DateUtils} call the mapper performs. */
    private static final String EXPECTED_TIME = DateUtils.formatTimeHhMmSs(NOW.toLocalTime());

    /** Deterministic literal rendering of {@link #NOW} in {@code MM/dd/uu} form, used as a guard. */
    private static final String LITERAL_DATE = "07/19/22";

    /** Deterministic literal rendering of {@link #NOW} in {@code HH:mm:ss} form, used as a guard. */
    private static final String LITERAL_TIME = "23:15:58";

    /**
     * Illustrative option labels. Contents are irrelevant to the mapper (it hard-codes no menu
     * text); they merely have to survive the mapping unchanged, in order.
     */
    private static final List<String> OPTIONS =
            List.of("01. Account View", "02. Account Update", "03. Card List");

    // Main Menu header constants (transaction CM00 / program COMEN01C). title01 and title02 are
    // deliberately DISTINCT so a positional argument swap in the mapper would fail the assertions.
    private static final String MAIN_TRAN = "CM00";
    private static final String MAIN_TITLE01 = "AWS Mainframe Modernization";
    private static final String MAIN_TITLE02 = "CardDemo Main Menu";
    private static final String MAIN_PGM = "COMEN01C";

    // Admin Menu header constants (transaction CA00 / program COADM01C).
    private static final String ADMIN_TRAN = "CA00";
    private static final String ADMIN_TITLE01 = "AWS Mainframe Modernization";
    private static final String ADMIN_TITLE02 = "CardDemo Admin Menu";
    private static final String ADMIN_PGM = "COADM01C";

    // ------------------------------------------------------------------------
    // Phase 2 - Happy-path mapping (both menus)
    // ------------------------------------------------------------------------

    /**
     * Main Menu happy path: options are copied through in order, the header date/time are rendered
     * from {@link #NOW}, and every caller-supplied header constant is passed through verbatim.
     */
    @Test
    void toMainMenuResponse_populatesHeaderAndCopiesOptions() {
        MainMenuResponse main = mapper.toMainMenuResponse(
                OPTIONS, null, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        assertThat(main).isNotNull();
        assertThat(main.menuOptions()).containsExactlyElementsOf(OPTIONS);
        assertThat(main.currentDate()).isEqualTo(EXPECTED_DATE).isEqualTo(LITERAL_DATE);
        assertThat(main.currentTime()).isEqualTo(EXPECTED_TIME).isEqualTo(LITERAL_TIME);
        assertThat(main.transactionName()).isEqualTo(MAIN_TRAN);
        assertThat(main.title01()).isEqualTo(MAIN_TITLE01);
        assertThat(main.title02()).isEqualTo(MAIN_TITLE02);
        assertThat(main.programName()).isEqualTo(MAIN_PGM);
        assertThat(main.errorMessage()).isNull();
    }

    /**
     * Admin Menu happy path: field-by-field identical to the Main Menu assembly but exercised
     * against {@link AdminMenuResponse} with the admin header constants.
     */
    @Test
    void toAdminMenuResponse_populatesHeaderAndCopiesOptions() {
        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                OPTIONS, null, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        assertThat(admin).isNotNull();
        assertThat(admin.menuOptions()).containsExactlyElementsOf(OPTIONS);
        assertThat(admin.currentDate()).isEqualTo(EXPECTED_DATE).isEqualTo(LITERAL_DATE);
        assertThat(admin.currentTime()).isEqualTo(EXPECTED_TIME).isEqualTo(LITERAL_TIME);
        assertThat(admin.transactionName()).isEqualTo(ADMIN_TRAN);
        assertThat(admin.title01()).isEqualTo(ADMIN_TITLE01);
        assertThat(admin.title02()).isEqualTo(ADMIN_TITLE02);
        assertThat(admin.programName()).isEqualTo(ADMIN_PGM);
        assertThat(admin.errorMessage()).isNull();
    }

    // ------------------------------------------------------------------------
    // Phase 3 - Defensive immutable copy (the key behavior)
    // ------------------------------------------------------------------------

    /**
     * Main Menu independence: passing a mutable list and then mutating it (add/remove/clear) after
     * assembly must leave the response snapshot untouched, proving the mapper copied rather than
     * aliased the caller's list. A mapper that stored the reference directly would fail here.
     */
    @Test
    void toMainMenuResponse_defensivelyCopiesInput_soLaterMutationIsIgnored() {
        List<String> mutable = new ArrayList<>(OPTIONS);

        MainMenuResponse main = mapper.toMainMenuResponse(
                mutable, null, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        mutable.add("99. Injected After Build");
        mutable.remove(0);
        mutable.clear();

        assertThat(main.menuOptions()).containsExactlyElementsOf(OPTIONS);
    }

    /**
     * Admin Menu independence: same defensive-copy guarantee as the Main Menu, exercised against
     * {@link AdminMenuResponse}.
     */
    @Test
    void toAdminMenuResponse_defensivelyCopiesInput_soLaterMutationIsIgnored() {
        List<String> mutable = new ArrayList<>(OPTIONS);

        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                mutable, null, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        mutable.add("99. Injected After Build");
        mutable.remove(0);
        mutable.clear();

        assertThat(admin.menuOptions()).containsExactlyElementsOf(OPTIONS);
    }

    /**
     * Main Menu immutability: the returned option list rejects structural mutation with an
     * {@link UnsupportedOperationException} (holds for both {@code List.copyOf} and
     * {@code Collections.unmodifiableList} implementations).
     */
    @Test
    void toMainMenuResponse_returnedOptionsAreUnmodifiable() {
        MainMenuResponse main = mapper.toMainMenuResponse(
                new ArrayList<>(OPTIONS), null, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        assertThatThrownBy(() -> main.menuOptions().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Admin Menu immutability: same unmodifiable-result guarantee as the Main Menu, exercised
     * against {@link AdminMenuResponse}.
     */
    @Test
    void toAdminMenuResponse_returnedOptionsAreUnmodifiable() {
        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                new ArrayList<>(OPTIONS), null, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        assertThatThrownBy(() -> admin.menuOptions().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Null / empty handling and error-message passthrough
    // ------------------------------------------------------------------------

    /**
     * Main Menu null-option normalization: a {@code null} option list must be turned into a
     * non-null, empty list rather than propagating as a {@link NullPointerException}.
     */
    @Test
    void toMainMenuResponse_nullOptions_becomeEmptyNonNullList() {
        MainMenuResponse main = mapper.toMainMenuResponse(
                null, null, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        assertThat(main.menuOptions()).isNotNull().isEmpty();
    }

    /**
     * Admin Menu null-option normalization: same non-null empty-list guarantee as the Main Menu.
     */
    @Test
    void toAdminMenuResponse_nullOptions_becomeEmptyNonNullList() {
        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                null, null, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        assertThat(admin.menuOptions()).isNotNull().isEmpty();
    }

    /**
     * Main Menu empty-option handling: an empty input list yields an empty result that is itself
     * still unmodifiable.
     */
    @Test
    void toMainMenuResponse_emptyOptions_remainEmptyAndUnmodifiable() {
        MainMenuResponse main = mapper.toMainMenuResponse(
                new ArrayList<>(), null, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        assertThat(main.menuOptions()).isEmpty();
        assertThatThrownBy(() -> main.menuOptions().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Admin Menu empty-option handling: same empty-and-unmodifiable guarantee as the Main Menu.
     */
    @Test
    void toAdminMenuResponse_emptyOptions_remainEmptyAndUnmodifiable() {
        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                new ArrayList<>(), null, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        assertThat(admin.menuOptions()).isEmpty();
        assertThatThrownBy(() -> admin.menuOptions().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Main Menu error-line passthrough: the supplied {@code errorMessage} ({@code ERRMSGO}) is
     * carried through to the response unchanged.
     */
    @Test
    void toMainMenuResponse_passesThroughErrorMessage() {
        String message = "No options available";

        MainMenuResponse main = mapper.toMainMenuResponse(
                OPTIONS, message, NOW, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM);

        assertThat(main.errorMessage()).isEqualTo(message);
    }

    /**
     * Admin Menu error-line passthrough: same {@code errorMessage} passthrough as the Main Menu.
     */
    @Test
    void toAdminMenuResponse_passesThroughErrorMessage() {
        String message = "No options available";

        AdminMenuResponse admin = mapper.toAdminMenuResponse(
                OPTIONS, message, NOW, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM);

        assertThat(admin.errorMessage()).isEqualTo(message);
    }

    // ------------------------------------------------------------------------
    // Contract guard - a null `now` instant is rejected (Objects.requireNonNull)
    // ------------------------------------------------------------------------

    /**
     * Main Menu required-instant guard: a {@code null} {@code now} must be rejected with a
     * {@link NullPointerException} (the mapper's {@code Objects.requireNonNull(now, ...)} contract),
     * because the header date/time cannot be rendered without it.
     */
    @Test
    void toMainMenuResponse_nullNow_throwsNullPointerException() {
        assertThatThrownBy(() -> mapper.toMainMenuResponse(
                OPTIONS, null, null, MAIN_TRAN, MAIN_TITLE01, MAIN_TITLE02, MAIN_PGM))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Admin Menu required-instant guard: same {@code null}-{@code now} rejection as the Main Menu.
     */
    @Test
    void toAdminMenuResponse_nullNow_throwsNullPointerException() {
        assertThatThrownBy(() -> mapper.toAdminMenuResponse(
                OPTIONS, null, null, ADMIN_TRAN, ADMIN_TITLE01, ADMIN_TITLE02, ADMIN_PGM))
                .isInstanceOf(NullPointerException.class);
    }
}
