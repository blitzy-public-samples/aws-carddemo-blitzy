/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.io;

import com.blitzy.carddemo.interest.model.DisclosureGroup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link DisclosureGroupRepository} &mdash; ports CBACT04C
 * {@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE}
 * ({@code app/cbl/CBACT04C.cbl:L415-460}). Covers BR-06, zero-rate, BR-08, BR-17 (AAP &sect;0.6.3).
 *
 * <p>This is a characterization/unit test and is the <strong>primary correctness mechanism</strong>
 * for the disclosure-group rate lookup: per AAP &sect;0.7, functional equivalence with the COBOL
 * source is established through this JUnit&nbsp;5 suite plus static reasoning, <em>never</em> by
 * running against a live mainframe. The tests lock the <em>observable contract</em> of the production
 * repository; they neither redefine nor "improve" it (AAP &sect;0.7 minimal-change rule).</p>
 *
 * <h2>Self-contained by design (AAP &sect;0.7 self-contained-tests rule)</h2>
 * <p>The only external input is the sibling test resource {@code src/test/resources/fixtures/discgrp.txt}
 * &mdash; a verbatim copy of {@code app/data/ASCII/discgrp.txt}. It is loaded from the <em>test
 * classpath</em> (Maven copies {@code src/test/resources/**} to {@code target/test-classes}), so the
 * suite runs entirely under {@code mvn test} with no dependency on {@code app/data/ASCII/}, no absolute
 * filesystem paths, and no network access.</p>
 *
 * <h2>Fixture characterization (drives every expected value; verified by direct byte inspection)</h2>
 * <p>{@code discgrp.txt} = 51 records of a 50-byte {@code CVTRA02Y} DIS-GROUP-RECORD (US-ASCII, LF).
 * Three groups &times; 17 {@code (type/cat)} rows each: {@code A000000000} (lines 1-17),
 * {@code "DEFAULT   "} (lines 18-34), {@code "ZEROAPR   "} (lines 35-51). Field offsets (0-based):
 * {@code groupId[0,10)} {@code tranTypeCd[10,12)} {@code tranCatCd[12,16)} {@code intRate S9(04)V99[16,22)}.
 * The trailing overpunch byte {@code '{'} = +0. Decoded rates exercised here:</p>
 * <ul>
 *   <li>{@code A000000000 / 01 / 0001} &rarr; raw {@code 00150{} &rarr; {@code 15.00} (line 1)</li>
 *   <li>{@code DEFAULT     / 01 / 0001} &rarr; raw {@code 00150{} &rarr; {@code 15.00} (line 18)</li>
 *   <li>{@code ZEROAPR     / 01 / 0001} &rarr; raw {@code 00000{} &rarr; {@code 0.00} (line 35)</li>
 *   <li>{@code (type 99 / cat 9999)} is <strong>absent in every group, including DEFAULT</strong>
 *       &rarr; forces the fatal abend path.</li>
 * </ul>
 *
 * <h2>Production API bound here (do NOT assume variants)</h2>
 * <p>{@code DisclosureGroupRepository.load(Path)} materializes the fixed-width file into an in-memory
 * map keyed by the composite key {@code group(10)+type(2)+cat(4)}; {@code lookup(group,type,cat)}
 * performs the DEFAULT fallback <em>internally</em> (exact hit &rarr; DEFAULT re-read &rarr; fatal
 * {@link RuntimeException}). The repository pads the group id to width 10 when assembling the key, so
 * callers pass logical tokens (e.g. {@code "ZEROAPR"}) and this test never assembles keys itself.
 * {@link DisclosureGroup} is a Java {@code record}; its accessor for the rate is {@link
 * DisclosureGroup#intRate()} (no {@code get} prefix), returning a scale-2 {@link BigDecimal}.</p>
 *
 * <p>Because this test lives in the same package as {@code DisclosureGroupRepository}
 * ({@code com.blitzy.carddemo.interest.io}), that class is referenced directly and is intentionally
 * <em>not</em> imported; only the cross-package model {@link DisclosureGroup} is imported. Pure JDK +
 * JUnit&nbsp;5 Jupiter; no third-party libraries.</p>
 */
public class DisclosureGroupRepositoryTest {

    /**
     * Shared, read-only repository loaded once from the classpath fixture. The disclosure-group table
     * is immutable after {@link DisclosureGroupRepository#load(Path)}, so a single {@code @BeforeAll}
     * load is both correct and efficient across all test methods.
     */
    private static DisclosureGroupRepository repository;

    /**
     * Loads the DISCGRP fixture from the test classpath and builds the repository once for all tests.
     *
     * <p>Mirrors {@code OPEN INPUT DISCGRP-FILE} ({@code app/cbl/CBACT04C.cbl:L272}). The fixture is
     * resolved via {@link Class#getResource(String)} using the classpath-absolute name
     * {@code "/fixtures/discgrp.txt"} (leading slash = classpath root), so the test is robust
     * regardless of the process working directory and never touches {@code app/data/ASCII/}.</p>
     *
     * @throws Exception if the fixture URL cannot be converted to a {@link Path}
     *                   ({@link URL#toURI()} is a checked call)
     */
    @BeforeAll
    static void loadRepository() throws Exception {
        // Self-contained: read the in-repo fixture copy from the test classpath
        // (Maven copies src/test/resources/** to target/test-classes). No external/app-data dependency.
        URL fixtureUrl = DisclosureGroupRepositoryTest.class.getResource("/fixtures/discgrp.txt");
        assertNotNull(fixtureUrl, "fixture /fixtures/discgrp.txt must be on the test classpath");
        Path fixture = Path.of(fixtureUrl.toURI());
        repository = DisclosureGroupRepository.load(fixture);   // ports DISCGRP OPEN INPUT (CBACT04C L272)
    }

    @Test
    @DisplayName("BR-06: direct key lookup (group+type+cat) returns the matching rate")
    void lookup_directHit_returnsExactRate() {
        // BR-06: DISCGRP key = ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD (CBACT04C L210-212;
        // FD-DISCGRP-KEY L78-81). Direct read 1200-GET-INTEREST-RATE, status '00' (L416, L422).
        DisclosureGroup g = repository.lookup("A000000000", "01", "0001");

        // scale-2 equality: BigDecimal.equals compares scale too, so this asserts BOTH the value AND
        // the scale-2 decimal contract in one check (BR-18 decimal fidelity).
        assertEquals(new BigDecimal("15.00"), g.intRate());
        // Hardening: prove this was a genuine DIRECT hit (not the DEFAULT fallback) and that the
        // decoded rate is scale 2 (i.e. 15.00, not 15.0 / 15).
        assertEquals("A000000000", g.groupId());
        assertEquals(2, g.intRate().scale());
    }

    @Test
    @DisplayName("Zero-rate path: ZEROAPR group returns 0.00 (repository still returns the group)")
    void lookup_zeroRate_returnsZero() {
        // Zero-rate path: ZEROAPR/01/0001 raw '00000{' -> 0.00. The repository RETURNS the 0.00 rate;
        // the "skip write when rate = 0" guard (IF DIS-INT-RATE NOT = 0, CBACT04C L214, BR-07) lives in
        // the SERVICE, NOT this repository -- so the repo must return 0.00 here, not throw/skip.
        // The caller passes "ZEROAPR" (7 chars); production lookup pads it to width 10 ("ZEROAPR   ")
        // when assembling the key, matching the stored record -- do NOT pre-pad in the test.
        DisclosureGroup g = repository.lookup("ZEROAPR", "01", "0001");

        assertEquals(new BigDecimal("0.00"), g.intRate());
        // Confirms 0.00 is scale 2 (not the scale-0 literal 0).
        assertEquals(2, g.intRate().scale());
    }

    @Test
    @DisplayName("BR-08: unknown/blank group falls back to DEFAULT group rate")
    void lookup_unknownGroup_fallsBackToDefault() {
        // BR-08: group not found (COBOL status '23') -> MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID + 1200-A
        // re-read (CBACT04C L422, L436-439, L443-460). A BLANK group id mirrors the real golden-master
        // scenario: every acctdata.txt row has a blank ACCT-GROUP-ID, so the exact key misses and the
        // DEFAULT rows supply the rate.
        DisclosureGroup g = repository.lookup("          ", "01", "0001");   // 10 spaces = blank group

        assertEquals(new BigDecimal("15.00"), g.intRate());   // DEFAULT/01/0001 = 15.00 (fixture line 18)
        // REQUIRED: proves the FALLBACK actually fired (returned row is the DEFAULT group), distinguishing
        // a genuine DEFAULT fallback from a coincidental direct hit -- this is what locks BR-08.
        assertEquals("DEFAULT", g.groupId().trim());
    }

    @Test
    @DisplayName("BR-17: missing rate even under DEFAULT throws a fatal RuntimeException")
    void lookup_missingEvenUnderDefault_throws() {
        // BR-17: when the (type,cat) is absent even under the DEFAULT group, 1200-A-GET-DEFAULT-INT-RATE
        // abends via 9999-ABEND-PROGRAM (CALL 'CEE3ABD') -> Java fatal RuntimeException
        // (CBACT04C L452-459, L628-632). type '99' / cat '9999' exists in NO group (A000000000, DEFAULT,
        // ZEROAPR), so exact miss -> DEFAULT miss -> throw. Assert on the RuntimeException supertype so
        // the test stays robust to whatever specific unchecked subclass the production throws, and does
        // not couple to the exception message text.
        assertThrows(RuntimeException.class,
                () -> repository.lookup("A000000000", "99", "9999"));
    }
}
