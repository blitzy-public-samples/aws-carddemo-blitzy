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
package com.aws.carddemo.batch.reader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link FixedLengthBufferedReaderFactory}, the streaming fixed-length framer wired
 * into {@link DailyTransactionFileItemReader}. These are pure in-memory tests (no PostgreSQL, no
 * Spring context): a {@link ByteArrayResource} feeds the factory and the produced reader's
 * {@code readLine()} output is collected. They assert the same framing contract as
 * {@code FixedWidthCodecTest.readFixedLengthRecords*} but exercise the streaming reader path used by
 * the loader, guaranteeing the two mechanisms stay behaviorally identical.
 */
class FixedLengthBufferedReaderFactoryTest {

    @Test
    void framesContiguousStreamWithNoDelimiters() throws IOException {
        // A true fixed-block stream with no terminators: the pre-fix default line reader returned
        // this as one over-length line and the loader dropped every record after the first.
        assertThat(frame("AAAAABBBBB", 5)).containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void skipsLfFraming() throws IOException {
        assertThat(frame("AAAAA\nBBBBB\n", 5)).containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void skipsCrlfFraming() throws IOException {
        assertThat(frame("AAAAA\r\nBBBBB\r\n", 5)).containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void handlesMissingTrailingNewline() throws IOException {
        assertThat(frame("AAAAA\nBBBBB", 5)).containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void returnsSingleRecordForExactLength() throws IOException {
        assertThat(frame("AAAAA", 5)).containsExactly("AAAAA");
    }

    @Test
    void returnsEmptyForEmptyStream() throws IOException {
        assertThat(frame("", 5)).isEmpty();
    }

    @Test
    void returnsEmptyForFramingOnlyStream() throws IOException {
        assertThat(frame("\n\r\n\n", 5)).isEmpty();
    }

    @Test
    void toleratesBlankTrailingRemainder() throws IOException {
        assertThat(frame("AAAAA  ", 5)).containsExactly("AAAAA");
        assertThat(frame("AAAAA\n  \n", 5)).containsExactly("AAAAA");
    }

    @Test
    void preservesRecordBytesVerbatim() throws IOException {
        List<String> records = frame("AB CDE F  ", 5);
        assertThat(records).containsExactly("AB CD", "E F  ");
        assertThat(records.get(0)).hasSize(5);
        assertThat(records.get(1)).hasSize(5);
    }

    @Test
    void throwsOnNonBlankShortRemainder() {
        // A truncated, non-blank remainder must fail fast during the read rather than yield a
        // corrupt short record.
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> frame("AAAAABB", 5));
        assertThat(ex.getMessage()).contains("Trailing partial fixed-length record");
    }

    @Test
    void framesTwoContiguous350ByteRecords() throws IOException {
        // Two 350-character records concatenated with NO delimiter frame into exactly two records
        // of width 350 -- the contiguous DALYTRAN parity case.
        String recordOne = "1".repeat(350);
        String recordTwo = "2".repeat(350);

        List<String> contiguous = frame(recordOne + recordTwo, 350);
        assertThat(contiguous).containsExactly(recordOne, recordTwo);

        List<String> lfFramed = frame(recordOne + "\n" + recordTwo + "\n", 350);
        assertThat(lfFramed).containsExactly(recordOne, recordTwo);
    }

    @Test
    void constructorRejectsNonPositiveRecordLength() {
        assertThrows(IllegalArgumentException.class, () -> new FixedLengthBufferedReaderFactory(0));
        assertThrows(IllegalArgumentException.class, () -> new FixedLengthBufferedReaderFactory(-1));
    }

    /**
     * Frames {@code content} through the factory-produced reader and collects every record its
     * {@code readLine()} yields until end of stream.
     *
     * @param content      the raw input text (encoded as ISO-8859-1 bytes for the resource)
     * @param recordLength the fixed record width
     * @return the framed records in order
     * @throws IOException if the reader cannot be created or read
     */
    private static List<String> frame(String content, int recordLength) throws IOException {
        Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1));
        List<String> records = new ArrayList<>();
        try (BufferedReader reader = new FixedLengthBufferedReaderFactory(recordLength)
                .create(resource, StandardCharsets.ISO_8859_1.name())) {
            String line;
            while ((line = reader.readLine()) != null) {
                records.add(line);
            }
        }
        return records;
    }
}
