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
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;

import org.springframework.batch.item.file.BufferedReaderFactory;
import org.springframework.core.io.Resource;

/**
 * Spring Batch {@link BufferedReaderFactory} that frames an input stream into <strong>fixed-length
 * records by position</strong> rather than by line terminator, so a COBOL {@code RECFM=F(B)}
 * sequential file is read faithfully whether or not it carries in-band newlines.
 *
 * <h2>Why this exists</h2>
 * The default {@link org.springframework.batch.item.file.DefaultBufferedReaderFactory} returns a
 * plain {@link BufferedReader} whose {@link BufferedReader#readLine() readLine()} splits on
 * {@code CR}/{@code LF}. That is wrong for a true fixed-block dataset: a file written as back-to-back
 * 350-byte record images with <em>no</em> line terminators is returned as a single over-length
 * "line", so a downstream {@code LineMapper} decodes only the first record and every subsequent
 * record is <strong>silently lost</strong>. Wiring this factory into a
 * {@link org.springframework.batch.item.file.FlatFileItemReader} via
 * {@code setBufferedReaderFactory(...)} makes each {@code readLine()} return exactly one
 * fixed-length record, closing that gap while preserving the reader's streaming and restart
 * behavior (one {@code readLine()} == one record == one line-count increment).
 *
 * <h2>Framing contract</h2>
 * The returned reader's {@code readLine()} mirrors, in streaming form, the semantics of
 * {@link com.aws.carddemo.common.util.FixedWidthCodec#readFixedLengthRecords(String, int)}:
 * <ul>
 *   <li><strong>Inter-record framing is skipped.</strong> Any run of {@code CR}/{@code LF} at a
 *       record boundary is consumed and discarded, so a one-record-per-line file (LF- or
 *       CRLF-terminated) and a contiguous fixed-block file (no terminators) both yield identical
 *       records. Bytes inside the {@code recordLength} window are never treated as framing, so an
 *       embedded {@code 0x0A} within a record is preserved.</li>
 *   <li><strong>Each returned record is exactly {@code recordLength} characters.</strong> No
 *       trimming is performed; the caller's {@code LineMapper} decodes fields from the slice.</li>
 *   <li><strong>End of stream returns {@code null}.</strong> A blank trailing remainder (framing or
 *       spaces only) is tolerated and discarded; a <em>non-blank</em> remainder shorter than
 *       {@code recordLength} is a malformed partial record and raises {@link IllegalStateException}
 *       so a truncated input fails fast rather than loading a corrupt record.</li>
 * </ul>
 *
 * <h2>Encoding</h2>
 * The stream is decoded with the {@code encoding} the {@code FlatFileItemReader} supplies (the
 * caller configures {@code ISO-8859-1} so zoned-decimal overpunch bytes survive for the amount
 * decode). This factory performs no charset work beyond honoring that encoding.
 *
 * <h2>Statelessness and reuse</h2>
 * The factory itself is immutable and stateless (it holds only the {@code recordLength}); a fresh
 * stateful reader is produced per {@link #create(Resource, String)} call, so a single factory
 * instance is safe to reuse across restarts and steps.
 *
 * @see com.aws.carddemo.common.util.FixedWidthCodec#readFixedLengthRecords(String, int)
 * @see DailyTransactionFileItemReader
 * @see org.springframework.batch.item.file.FlatFileItemReader#setBufferedReaderFactory(BufferedReaderFactory)
 */
public class FixedLengthBufferedReaderFactory implements BufferedReaderFactory {

    /** Fixed record width, in characters, that each {@code readLine()} of the produced reader returns. */
    private final int recordLength;

    /**
     * Creates a factory that frames input into {@code recordLength}-character records.
     *
     * @param recordLength the fixed record width in characters; must be {@code > 0}
     * @throws IllegalArgumentException if {@code recordLength <= 0}
     */
    public FixedLengthBufferedReaderFactory(int recordLength) {
        if (recordLength <= 0) {
            throw new IllegalArgumentException("recordLength must be > 0 but was " + recordLength);
        }
        this.recordLength = recordLength;
    }

    /**
     * Opens {@code resource} and wraps it in a {@link BufferedReader} whose {@code readLine()} returns
     * one fixed-length record per call.
     *
     * @param resource the input resource to read; never {@code null} for a real launch
     * @param encoding the charset name used to decode the bytes (for example {@code ISO-8859-1})
     * @return a fixed-length-framing {@link BufferedReader} positioned at the start of {@code resource}
     * @throws UnsupportedEncodingException if {@code encoding} is not a supported charset
     * @throws IOException                  if the resource's input stream cannot be opened
     */
    @Override
    public BufferedReader create(Resource resource, String encoding)
            throws UnsupportedEncodingException, IOException {
        return new FixedLengthBufferedReader(
                new InputStreamReader(resource.getInputStream(), encoding), recordLength);
    }

    /**
     * A {@link BufferedReader} whose {@link #readLine()} returns exactly {@code recordLength}
     * characters per call, framing by position and discarding inter-record {@code CR}/{@code LF}.
     */
    static final class FixedLengthBufferedReader extends BufferedReader {

        /** Fixed record width returned by each {@link #readLine()}. */
        private final int recordLength;

        /**
         * Wraps the underlying character stream.
         *
         * @param in           the decoded character stream over the resource's bytes
         * @param recordLength the fixed record width in characters; assumed already validated {@code > 0}
         */
        FixedLengthBufferedReader(Reader in, int recordLength) {
            super(in);
            this.recordLength = recordLength;
        }

        /**
         * Returns the next fixed-length record, or {@code null} at end of stream.
         *
         * <p>Leading {@code CR}/{@code LF} framing is skipped, then exactly {@code recordLength}
         * characters are read verbatim. A blank trailing remainder is treated as end of stream; a
         * non-blank short remainder raises {@link IllegalStateException}.</p>
         *
         * @return the next {@code recordLength}-character record, or {@code null} at end of stream
         * @throws IOException           if the underlying stream cannot be read
         * @throws IllegalStateException if a non-blank remainder shorter than {@code recordLength}
         *                               is encountered before end of stream
         */
        @Override
        public String readLine() throws IOException {
            // Skip inter-record framing (CR/LF) sitting at a record boundary.
            int first;
            do {
                first = read();
            } while (first == '\n' || first == '\r');
            if (first == -1) {
                return null;
            }
            char[] buffer = new char[recordLength];
            buffer[0] = (char) first;
            int total = 1;
            while (total < recordLength) {
                int read = read(buffer, total, recordLength - total);
                if (read == -1) {
                    break;
                }
                total += read;
            }
            if (total < recordLength) {
                String remainder = new String(buffer, 0, total);
                if (remainder.isBlank()) {
                    return null;
                }
                throw new IllegalStateException(
                        "Trailing partial fixed-length record: expected " + recordLength
                                + " characters but only " + total + " remained before end of stream");
            }
            return new String(buffer, 0, recordLength);
        }
    }
}
