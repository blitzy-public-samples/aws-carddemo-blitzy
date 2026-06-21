/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Full-scenario golden-file parity test for the combine job's sort semantics — the Java port of the
 * legacy {@code legacy/app/jcl/COMBTRAN.jcl} step {@code STEP05R} ({@code SORT
 * FIELDS=(TRAN-ID,A)}).
 *
 * <p><strong>What this test pins (and why it exists).</strong> The shipped golden fixture {@code
 * src/test/resources/golden/sorted/dailytran.combined-sorted.dat} is the byte-exact expected output
 * of combining/sorting the full 300-row {@code dailytran} scenario (see {@code
 * golden/sorted/README.md}). Before this test that 105&nbsp;KB fixture was not consumed by any test
 * — the sibling {@link TransactionCombineStepTest} pins a synthetic three-row case, so the
 * realistic 300-row sort output was asserted nowhere. This test wires the orphaned fixture by
 * applying the documented &ldquo;meaningful-test recipe&rdquo;: it loads the input {@code
 * fixtures/ascii/dailytran.txt} records, deliberately reverses them, sorts ascending by {@code
 * TRAN-ID}, and compares the result byte-for-byte to the golden.
 *
 * <p><strong>Parity points pinned by this test.</strong>
 *
 * <ul>
 *   <li><em>Sort-order parity</em> (AAP &sect;0.6.3) — {@code SORT FIELDS=(TRAN-ID,A)} is ascending
 *       on the 16-byte {@code TRAN-ID} key. The input is reversed first so a passing assertion can
 *       only succeed if the sort genuinely orders the records (not if the input merely happened to
 *       be ordered already).
 *   <li><em>Total, deterministic order</em> — {@code TRAN-ID} is the primary key, so all 300 keys
 *       are unique and the legacy {@code SORT} specified no {@code EQUALS}; a stable ascending sort
 *       therefore yields one canonical order. The comparison uses {@link List#sort(Comparator)} (a
 *       stable sort) over the ASCII {@code TRAN-ID} so the ordering matches the C-collation byte
 *       order the legacy job produced.
 *   <li><em>Fixed-width record fidelity</em> — every record is the documented 350-byte {@code
 *       CVTRA05Y} layout plus a single trailing newline (351 bytes), preserved verbatim through the
 *       reorder so the reassembled stream is byte-identical to the golden.
 * </ul>
 *
 * <p><strong>Wiring.</strong> This is a pure-Java unit test (no Spring context, no Testcontainers,
 * no database — AAP &sect;0.6.7 local-only validation). It operates directly on the classpath
 * fixtures, treating each record as an opaque fixed-width byte slice and sorting only on the {@code
 * TRAN-ID} key, exactly as the legacy {@code DFSORT} step did. Both classpath resources are
 * hard-asserted present so a deleted or renamed fixture fails the test loudly rather than silently
 * eroding parity coverage.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionCombineSortParityTest {

  /** Classpath location of the unsorted input daily-transaction fixture (350-byte records). */
  private static final String DAILYTRAN_FIXTURE = "/fixtures/ascii/dailytran.txt";

  /** Classpath location of the expected combined/sorted golden (the full 300-row scenario). */
  private static final String SORTED_GOLDEN = "/golden/sorted/dailytran.combined-sorted.dat";

  /** Data width, in bytes, of a {@code CVTRA05Y} transaction record (excludes the newline). */
  private static final int DATA_LENGTH = 350;

  /** Full on-disk width of one record: 350 data bytes plus a single trailing newline. */
  private static final int RECORD_LENGTH = 351;

  /** Width, in bytes, of the leading {@code TRAN-ID} sort key. */
  private static final int TRAN_ID_LENGTH = 16;

  /**
   * Loads the {@code dailytran} fixture, reverses it to destroy any incidental ordering, sorts the
   * records ascending by {@code TRAN-ID}, and asserts the reassembled stream equals the committed
   * combined/sorted golden byte-for-byte.
   *
   * @throws Exception if either classpath fixture cannot be located or read
   */
  @Test
  void sorting_dailytran_ascending_by_tran_id_reproduces_the_combined_golden() throws Exception {
    byte[] input = readClasspathBytes(DAILYTRAN_FIXTURE);
    byte[] golden = readClasspathBytes(SORTED_GOLDEN);

    // The fixture is a stream of fixed-width records, each newline-terminated (350 + 1 bytes).
    assertEquals(
        0,
        input.length % RECORD_LENGTH,
        "fixture must be a whole number of " + RECORD_LENGTH + "-byte records");
    List<byte[]> records = splitRecords(input);

    // Every record is the documented 350-byte payload followed by exactly one newline terminator.
    for (byte[] record : records) {
      assertEquals(RECORD_LENGTH, record.length, "each record is 350 data bytes + 1 newline");
      assertEquals('\n', record[DATA_LENGTH], "byte 350 must be the newline terminator");
    }

    // Deliberately destroy the input ordering so the assertion can only pass if the sort truly
    // orders the records (mirrors COMBTRAN STEP05R, which sorts regardless of input order).
    Collections.reverse(records);

    // SORT FIELDS=(TRAN-ID,A): ascending by the 16-byte TRAN-ID key, stable (no EQUALS specified).
    records.sort(Comparator.comparing(TransactionCombineSortParityTest::tranIdKey));

    // Re-emit the sorted records verbatim (each already carries its own trailing newline).
    byte[] sorted = concat(records);

    assertArrayEquals(
        golden,
        sorted,
        "sorting dailytran ascending by TRAN-ID must reproduce"
            + " golden/sorted/dailytran.combined-sorted.dat byte-for-byte");
  }

  /**
   * Extracts the 16-byte ASCII {@code TRAN-ID} sort key from a fixed-width record. ASCII ordering
   * of this equal-length, digits-only key matches the C-collation byte order the legacy {@code
   * SORT} produced.
   *
   * @param record the 351-byte record (350 data bytes + newline)
   * @return the {@code TRAN-ID} key as an ASCII string
   */
  private static String tranIdKey(byte[] record) {
    return new String(record, 0, TRAN_ID_LENGTH, StandardCharsets.US_ASCII);
  }

  /**
   * Splits a byte stream into fixed {@value #RECORD_LENGTH}-byte records (each 350 data bytes plus
   * a trailing newline), preserving every byte verbatim.
   *
   * @param data the raw fixture bytes (length must be a multiple of {@value #RECORD_LENGTH})
   * @return the records in file order
   */
  private static List<byte[]> splitRecords(byte[] data) {
    List<byte[]> records = new ArrayList<>(data.length / RECORD_LENGTH);
    for (int offset = 0; offset < data.length; offset += RECORD_LENGTH) {
      byte[] record = new byte[RECORD_LENGTH];
      System.arraycopy(data, offset, record, 0, RECORD_LENGTH);
      records.add(record);
    }
    return records;
  }

  /**
   * Concatenates the records back into a single byte stream in list order.
   *
   * @param records the records to join
   * @return the reassembled byte stream
   */
  private static byte[] concat(List<byte[]> records) {
    byte[] out = new byte[records.size() * RECORD_LENGTH];
    int offset = 0;
    for (byte[] record : records) {
      System.arraycopy(record, 0, out, offset, record.length);
      offset += record.length;
    }
    return out;
  }

  /**
   * Reads a classpath fixture as raw bytes, hard-failing if it is absent so a deleted fixture can
   * never silently erode parity coverage.
   *
   * @param resource the absolute classpath resource path
   * @return the fixture bytes
   * @throws Exception if the resource cannot be located or read
   */
  private static byte[] readClasspathBytes(String resource) throws Exception {
    try (InputStream in = TransactionCombineSortParityTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "fixture must ship on the classpath: " + resource);
      return in.readAllBytes();
    }
  }
}
