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
package com.aws.carddemo.batch.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.exception.RejectCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Anchors the golden fixture {@code src/test/resources/golden/reject/expected-reject-101.dat} to the
 * production reject-trailer encoding, resolving QA finding <strong>F2</strong> (the fixture was an
 * <em>orphaned</em> 80-byte stub that no test asserted).
 *
 * <h2>Why this fixture is trailer-only (80 bytes, not 430)</h2>
 * <p>A full DALYREJS reject record is 430 bytes: the 350-byte {@code DALYTRAN} image
 * ({@code REJECT-TRAN-DATA}) followed by an 80-byte {@code VALIDATION-TRAILER} (a 4-digit reason code
 * at offset 350 and a 76-character reason description at offset 354). The fixtures for reject reasons
 * {@code 100}, {@code 102}, and {@code 103} are complete 430-byte records produced end-to-end by
 * {@code DailyTransactionPostingJobTest}. Reject reason <strong>101</strong>
 * ({@code ACCOUNT RECORD NOT FOUND}) is, however, <strong>unreachable end-to-end</strong>: the real
 * foreign key {@code fk_card_xref_account} guarantees that any cross-reference row resolves to an
 * existing account, so the {@code 1500-B-LOOKUP-ACCT} account-miss branch can never fire against the
 * migrated schema (see {@code docs/decision-log.md} D39 and
 * {@code src/test/resources/golden/reject/README.md} &sect;5). There is therefore no producible 350-byte
 * body for a reason-101 reject, and the golden is supplied as the <strong>80-byte trailer only</strong>.</p>
 *
 * <h2>What this test proves (and how it complements the existing coverage)</h2>
 * <p>{@code DailyTransactionPostingProcessorTest} already proves the <em>decision</em> — that the
 * processor emits {@link RejectCode#ACCOUNT_NOT_FOUND} when the account lookup misses (the only place
 * reason 101 can be driven, via mocking). This test proves the <em>fixture</em>: that the trailer-only
 * golden is byte-for-byte identical to the trailer the production reject writer
 * ({@code DailyTransactionPostingWriter}) would serialize for a reason-101 reject, reconstructed here
 * with the same {@link FixedWidthCodec} at the same absolute offsets and the same {@link RejectCode}
 * values. That makes the previously orphaned fixture a real, consumed golden and guards it against
 * drift in either the codec's fixed-width encoding or the {@code RejectCode} reason text.</p>
 *
 * <p>This is a pure JUnit&nbsp;5 unit test: no Spring context, database, or Testcontainers.</p>
 *
 * @see RejectCode#ACCOUNT_NOT_FOUND
 * @see FixedWidthCodec
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
class ExpectedReject101FixtureTest {

    /** Classpath location of the trailer-only golden fixture for reject reason 101. */
    private static final String FIXTURE = "golden/reject/expected-reject-101.dat";

    /** Full DALYREJS record length (350-byte body + 80-byte trailer). */
    private static final int DALYREJS_RECORD_LENGTH = 430;

    /** Trailer length in bytes ({@code VALIDATION-TRAILER}); the size of this fixture. */
    private static final int TRAILER_LENGTH = 80;

    /** Absolute offset of the 4-digit reason code within the 430-byte record. */
    private static final int REASON_CODE_OFFSET = 350;

    /** Absolute offset of the 76-character reason description within the 430-byte record. */
    private static final int REASON_DESC_OFFSET = 354;

    /** {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at offset 350 (4-digit, zero-padded numeric). */
    private static final FieldDef F_REASON_CODE =
            FieldDef.numeric("VALIDATION-FAIL-REASON", REASON_CODE_OFFSET, 4);

    /** {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at offset 354 (left-justified, space-padded). */
    private static final FieldDef F_REASON_DESC =
            FieldDef.alphanumeric("VALIDATION-FAIL-REASON-DESC", REASON_DESC_OFFSET, 76);

    /**
     * The fixture is exactly the 80-byte validation trailer — not a full 430-byte record — because a
     * reason-101 reject has no producible 350-byte body under the {@code fk_card_xref_account}
     * constraint (D39).
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("F2: expected-reject-101.dat is exactly the 80-byte trailer (no 350-byte body)")
    void fixtureIsTrailerOnly() throws IOException {
        byte[] fixture = readFixture();
        assertThat(fixture)
                .as("reason 101 is unreachable end-to-end (D39); its golden is the trailer only")
                .hasSize(TRAILER_LENGTH);
    }

    /**
     * The fixture bytes equal the trailer the production reject writer would emit for
     * {@link RejectCode#ACCOUNT_NOT_FOUND}, reconstructed with the same {@link FixedWidthCodec} and the
     * same absolute trailer offsets, then sliced from the 430-byte record image.
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("F2: the trailer is byte-for-byte the production encoding of RejectCode 101")
    void trailerMatchesProductionRejectEncoding() throws IOException {
        String producedRecord = FixedWidthCodec.of(DALYREJS_RECORD_LENGTH)
                .put(F_REASON_CODE, RejectCode.ACCOUNT_NOT_FOUND.getCode())
                .put(F_REASON_DESC, RejectCode.ACCOUNT_NOT_FOUND.getDescription())
                .build();
        String expectedTrailer = producedRecord.substring(REASON_CODE_OFFSET, DALYREJS_RECORD_LENGTH);
        assertThat(expectedTrailer)
                .as("reconstructed trailer must itself be 80 bytes")
                .hasSize(TRAILER_LENGTH);

        String fixture = new String(readFixture(), StandardCharsets.ISO_8859_1);
        assertThat(fixture)
                .as("fixture must equal the production reason-101 trailer byte-for-byte")
                .isEqualTo(expectedTrailer);
    }

    /**
     * The trailer decomposes into the documented fields: a zero-padded {@code 0101} reason code
     * followed by the {@code ACCOUNT RECORD NOT FOUND} description, left-justified and space-padded to
     * 76 characters.
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("F2: trailer decomposes into reason code 0101 + space-padded description")
    void trailerFieldsAreCorrectlyEncoded() throws IOException {
        String fixture = new String(readFixture(), StandardCharsets.ISO_8859_1);

        String reasonCode = fixture.substring(0, 4);
        assertThat(reasonCode)
                .as("4-digit zero-padded reason code for RejectCode 101")
                .isEqualTo("0101");

        String description = fixture.substring(4);
        String expectedDescription = RejectCode.ACCOUNT_NOT_FOUND.getDescription();
        assertThat(description)
                .as("description field is 76 characters (PIC X(76))")
                .hasSize(TRAILER_LENGTH - 4);
        assertThat(description.stripTrailing())
                .as("left-justified reason description text")
                .isEqualTo(expectedDescription);
        assertThat(description)
                .as("left-justified, space-padded to the field width")
                .isEqualTo(expectedDescription
                        + " ".repeat(TRAILER_LENGTH - 4 - expectedDescription.length()));
    }

    /**
     * Reads the trailer-only golden fixture from the classpath as raw bytes.
     *
     * @return the fixture bytes
     * @throws IOException if the resource cannot be read
     */
    private static byte[] readFixture() throws IOException {
        return new ClassPathResource(FIXTURE).getContentAsByteArray();
    }
}
