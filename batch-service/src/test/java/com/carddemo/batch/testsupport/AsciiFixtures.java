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
package com.carddemo.batch.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Access to the committed legacy fixed-width data fixtures.
 *
 * :purpose: Let batch tests assert byte-identical output against the ACTUAL
 *     ``app/data/ASCII/*.txt`` records the legacy loader jobs consumed, instead of
 *     against expectations invented inside the test. The files are read from the
 *     repository working tree (they are legacy reference data, not classpath
 *     resources) using ISO-8859-1 so every byte maps to exactly one character and
 *     the fixed-width column offsets hold.
 * :output: Static helpers returning fixture lines and the field primitives needed
 *     to slice them (right-trim, right-pad, zoned-decimal decode).
 */
public final class AsciiFixtures {

    /** Repository-relative directory holding the legacy ASCII fixtures. */
    private static final String FIXTURE_DIR = "app/data/ASCII";

    /**
     * :purpose: Prevent instantiation of this static helper.
     */
    private AsciiFixtures() {
        throw new AssertionError("AsciiFixtures is a static utility");
    }

    /**
     * Reads every record of a committed fixture file.
     *
     * :param fileName: the fixture file name, for example ``carddata.txt``.
     * :output: the fixture lines in file order, without line terminators.
     * :raises UncheckedIOException: when the fixture cannot be read.
     */
    public static List<String> lines(String fileName) {
        Path fixture = repositoryRoot().resolve(FIXTURE_DIR).resolve(fileName);
        try {
            return Files.readAllLines(fixture, StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read the legacy fixture " + fixture, ex);
        }
    }

    /**
     * Locates the repository root from the module working directory.
     *
     * :output: the first ancestor of ``user.dir`` (inclusive) that contains the
     *     fixture directory.
     * :raises IllegalStateException: when no ancestor carries the fixtures.
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(FIXTURE_DIR))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Unable to locate " + FIXTURE_DIR + " above " + System.getProperty("user.dir"));
    }

    /**
     * Removes trailing spaces, as a COBOL field read into a trimmed Java value.
     *
     * :param value: the fixed-width field slice.
     * :output: the value without trailing spaces.
     */
    public static String rtrim(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Left-justifies and space-pads a value, as a COBOL ``PIC X(n)`` MOVE.
     *
     * :param value: the value to pad.
     * :param width: the target field width.
     * :output: the padded value of exactly ``width`` characters.
     */
    public static String padRight(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Decodes a zoned-decimal field whose trailing character carries the sign.
     *
     * :param field: the fixed-width zoned-decimal slice, for example ``00000001940{``.
     * :output: the decoded value at scale two.
     * :raises IllegalArgumentException: when the trailing character is not a known
     *     overpunch or digit.
     */
    public static BigDecimal decodeZoned(String field) {
        String positive = "{ABCDEFGHI";
        String negative = "}JKLMNOPQR";
        char last = field.charAt(field.length() - 1);
        String digits = field.substring(0, field.length() - 1);
        boolean isNegative;
        int lastDigit;
        if (positive.indexOf(last) >= 0) {
            isNegative = false;
            lastDigit = positive.indexOf(last);
        } else if (negative.indexOf(last) >= 0) {
            isNegative = true;
            lastDigit = negative.indexOf(last);
        } else if (last >= '0' && last <= '9') {
            isNegative = false;
            lastDigit = last - '0';
        } else {
            throw new IllegalArgumentException("Unknown zoned-decimal overpunch '" + last + "'");
        }
        BigDecimal magnitude = new BigDecimal(new BigInteger(digits + lastDigit)).movePointLeft(2);
        return isNegative ? magnitude.negate() : magnitude;
    }
}
