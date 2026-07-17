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
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link RejectCode}, the authoritative closed enumeration of the
 * batch daily-transaction posting reject reason codes produced by the legacy
 * COBOL program {@code CBTRN02C} (paragraph {@code 1500-VALIDATE-TRAN} and its
 * sub-paragraphs {@code 1500-A-LOOKUP-XREF} / {@code 1500-B-LOOKUP-ACCT}).
 *
 * <p>This is a behavioral-parity contract (AAP &sect;0.7.1 H4, &sect;0.9.2,
 * &sect;0.9.6). The tests lock down the four numeric codes, the reject
 * description literals verbatim, the closed four-member set, and the
 * {@code fromCode}/{@code findByCode} reverse lookups. The
 * character-for-character description strings are what keep the 350-byte
 * {@code DALYREJS} reject-file record byte-faithful to the legacy output.
 *
 * <p>COBOL evidence (verified against {@code app/cbl/CBTRN02C.cbl}):
 * <ul>
 *   <li>100 &mdash; {@code MOVE 100 / 'INVALID CARD NUMBER FOUND'} at L385-387,
 *       set in {@code 1500-A-LOOKUP-XREF} on {@code READ XREF-FILE ... INVALID KEY}.</li>
 *   <li>101 &mdash; {@code MOVE 101 / 'ACCOUNT RECORD NOT FOUND'} at L397-399,
 *       set in {@code 1500-B-LOOKUP-ACCT} on {@code READ ACCOUNT-FILE ... INVALID KEY}.</li>
 *   <li>102 &mdash; {@code MOVE 102 / 'OVERLIMIT TRANSACTION'} at L410-412, the
 *       {@code ELSE} of {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} (L407).</li>
 *   <li>103 &mdash; {@code MOVE 103 / 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}
 *       at L417-419, the {@code ELSE} of
 *       {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} (L414).</li>
 * </ul>
 *
 * <p>Evaluation order is documented here for traceability but is
 * <em>deliberately not asserted</em> by this test: {@code RejectCode} is a
 * passive value type that carries codes/descriptions and lookup only, with no
 * ordering logic. In {@code CBTRN02C}, {@code 1500-A-LOOKUP-XREF} runs first and
 * a 100 short-circuits the account lookup ({@code IF WS-VALIDATION-FAIL-REASON = 0}
 * at L372), while the credit-limit (102) and expiration (103) checks are two
 * sequential independent {@code IF}s so 103 overwrites 102 when both trip. That
 * ordering / short-circuit behavior is exercised by the
 * {@code com.aws.carddemo.batch} posting tests, not here.
 *
 * <p>Code {@code 109} ({@code MOVE 109} at L556, inside
 * {@code 2800-UPDATE-ACCOUNT-REC} on a posting-phase {@code REWRITE INVALID KEY})
 * is an I/O failure &mdash; not a pre-post validation reject reason &mdash; and is
 * intentionally excluded from the enum; {@link #fromCodeRejects109BecauseItIsNotAValidationRejectCode()}
 * asserts that exclusion explicitly.
 */
class RejectCodeTest {

    @Test
    @DisplayName("100 = INVALID_CARD_NUMBER carrying 'INVALID CARD NUMBER FOUND' (CBTRN02C L385-387)")
    void code100IsInvalidCardNumber() {
        assertThat(RejectCode.INVALID_CARD_NUMBER.getCode()).isEqualTo(100);
        assertThat(RejectCode.INVALID_CARD_NUMBER.getDescription())
                .isEqualTo("INVALID CARD NUMBER FOUND");
    }

    @Test
    @DisplayName("101 = ACCOUNT_NOT_FOUND carrying 'ACCOUNT RECORD NOT FOUND' (CBTRN02C L397-399)")
    void code101IsAccountNotFound() {
        assertThat(RejectCode.ACCOUNT_NOT_FOUND.getCode()).isEqualTo(101);
        assertThat(RejectCode.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    @Test
    @DisplayName("102 = OVER_CREDIT_LIMIT carrying 'OVERLIMIT TRANSACTION' (CBTRN02C L410-412)")
    void code102IsOverlimit() {
        assertThat(RejectCode.OVER_CREDIT_LIMIT.getCode()).isEqualTo(102);
        // "OVERLIMIT" is a single word in the COBOL literal — preserved verbatim.
        assertThat(RejectCode.OVER_CREDIT_LIMIT.getDescription())
                .isEqualTo("OVERLIMIT TRANSACTION");
    }

    @Test
    @DisplayName("103 = ACCOUNT_EXPIRED carrying 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' (CBTRN02C L417-419)")
    void code103IsAfterExpiration() {
        assertThat(RejectCode.ACCOUNT_EXPIRED.getCode()).isEqualTo(103);
        // "ACCT EXPIRATION" is abbreviated in the COBOL literal — preserved verbatim.
        assertThat(RejectCode.ACCOUNT_EXPIRED.getDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    @DisplayName("The enum is a closed set of exactly the four codes {100, 101, 102, 103}")
    void enumIsClosedSetOfExactlyFour() {
        assertThat(RejectCode.values()).hasSize(4);
        assertThat(Arrays.stream(RejectCode.values()).map(RejectCode::getCode))
                .containsExactlyInAnyOrder(100, 101, 102, 103);
    }

    @ParameterizedTest
    @EnumSource(RejectCode.class)
    @DisplayName("fromCode(getCode()) round-trips to the same constant for every reject code")
    void fromCodeRoundTripsEveryConstant(RejectCode rejectCode) {
        assertThat(RejectCode.fromCode(rejectCode.getCode())).isSameAs(rejectCode);
    }

    @Test
    @DisplayName("fromCode(109) throws — 109 is a posting REWRITE I/O error (CBTRN02C L556), not a validation reject")
    void fromCodeRejects109BecauseItIsNotAValidationRejectCode() {
        assertThatThrownBy(() -> RejectCode.fromCode(109))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("109");
    }

    @Test
    @DisplayName("fromCode throws IllegalArgumentException for any code outside {100, 101, 102, 103}")
    void fromCodeRejectsUnknownCodes() {
        assertThatThrownBy(() -> RejectCode.fromCode(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RejectCode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RejectCode.fromCode(104))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RejectCode.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findByCode returns the constant for each known code and empty for 109/unknown codes")
    void findByCodeReturnsPresentForKnownAndEmptyForUnknown() {
        assertThat(RejectCode.findByCode(100)).contains(RejectCode.INVALID_CARD_NUMBER);
        assertThat(RejectCode.findByCode(101)).contains(RejectCode.ACCOUNT_NOT_FOUND);
        assertThat(RejectCode.findByCode(102)).contains(RejectCode.OVER_CREDIT_LIMIT);
        assertThat(RejectCode.findByCode(103)).contains(RejectCode.ACCOUNT_EXPIRED);

        // 109 (posting-phase REWRITE I/O error) and other out-of-set codes are absent.
        assertThat(RejectCode.findByCode(109)).isEmpty();
        assertThat(RejectCode.findByCode(0)).isEmpty();
        assertThat(RejectCode.findByCode(999)).isEmpty();
    }
}
