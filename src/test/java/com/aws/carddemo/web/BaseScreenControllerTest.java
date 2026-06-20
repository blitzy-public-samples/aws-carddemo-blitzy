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
package com.aws.carddemo.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpSession;

/**
 * Pure JUnit 5 unit tests for {@link BaseScreenController}, the abstract base shared by all 17
 * migrated CICS online programs. This is the most foundational test in the {@code web} package: it
 * exercises — in complete isolation, with no Spring context and no MockMvc — the three
 * cross-cutting pseudo-conversational concerns that every concrete controller inherits (Agent
 * Action Plan &sect;0.6.5):
 *
 * <ul>
 *   <li><b>AID / PF-key resolution</b> ({@link BaseScreenController#resolveAid(String)}) — the HTTP
 *       {@code pfKey} token is translated to a {@link CardWorkArea.Aid}, the modern stand-in for
 *       the legacy {@code CCARD-AID} 88-levels ({@code ENTER}, {@code CLEAR}, {@code PA1}, {@code
 *       PA2}, {@code PFK01}–{@code PFK12}) defined in {@code legacy/app/cpy/CVCRD01Y.cpy}. The
 *       screens submit short tokens ({@code PF3}), whereas the AID constants use the 5-character
 *       COBOL codes ({@code PFK03}), so the {@code PFn -> PFKnn} mapping and the ENTER fall-through
 *       are pinned here because every screen's PF-key behavior depends on them.
 *   <li><b>Next-program routing</b> ({@link BaseScreenController#redirectFor(String)}) — the legacy
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} transfer is reproduced as a Spring MVC
 *       redirect. All 17 program-to-URL mappings plus the {@code null}/unknown fall-back to the
 *       main menu are asserted.
 *   <li><b>COMMAREA session state</b> ({@link BaseScreenController#getCommarea(HttpSession)},
 *       {@link BaseScreenController#storeCommarea(HttpSession, CardDemoCommarea)}, {@link
 *       BaseScreenController#clearCommarea(HttpSession)}) — the {@code CARDDEMO-COMMAREA} ({@code
 *       legacy/app/cpy/COCOM01Y.cpy}) carried across CICS {@code RECEIVE}/{@code SEND} cycles now
 *       lives in the {@link HttpSession}. Get-or-create idempotency, store round-trip, and
 *       clear-then-recreate are verified.
 * </ul>
 *
 * <p>Because {@link BaseScreenController} is {@code abstract} and its members are {@code
 * protected}, a same-package {@link Harness} subclass exposes them through public pass-throughs so
 * the behavior can be invoked directly. The session helpers are tested with a lightweight {@link
 * MockHttpSession} (the only Spring type used) rather than a real servlet container.
 */
@DisplayName("BaseScreenController shared online-navigation behavior")
class BaseScreenControllerTest {

  /**
   * Concrete subclass that makes the {@code protected} {@link BaseScreenController} members
   * callable from the test. {@link BaseScreenController} is abstract purely to prevent direct
   * instantiation (it declares no abstract methods), so the harness only needs public
   * pass-throughs. Placing it in the same package, {@code com.aws.carddemo.web}, grants access to
   * the protected members.
   */
  private static final class Harness extends BaseScreenController {

    CardWorkArea.Aid aid(String pfKey) {
      return resolveAid(pfKey);
    }

    String redirect(String program) {
      return redirectFor(program);
    }

    CardDemoCommarea commarea(HttpSession session) {
      return getCommarea(session);
    }

    void store(HttpSession session, CardDemoCommarea commarea) {
      storeCommarea(session, commarea);
    }

    void clear(HttpSession session) {
      clearCommarea(session);
    }
  }

  private Harness controller;

  @BeforeEach
  void setUp() {
    controller = new Harness();
  }

  // ===== AID / PF-key resolution (resolveAid) — CCARD-AID 88-levels, CVCRD01Y ==================

  /**
   * Each screen-submitted token resolves to its expected {@link CardWorkArea.Aid}. JUnit's implicit
   * String-to-enum conversion maps the second CSV column to the enum constant. Covers the
   * case-insensitive named keys, the {@code PFn -> PFKnn} zero-padded mapping, the out-of-range
   * {@code PF0}/{@code PF13} fall-through to {@code ENTER}, and non-PF garbage falling through to
   * {@code ENTER}. The {@code null}, empty, all-blank, and surrounding-whitespace inputs are
   * covered by dedicated tests below because {@code @CsvSource} trims values and renders {@code
   * null} awkwardly.
   */
  @ParameterizedTest(name = "resolveAid(\"{0}\") -> {1}")
  @CsvSource({
    "ENTER, ENTER",
    "enter, ENTER",
    "CLEAR, CLEAR",
    "clear, CLEAR",
    "PA1, PA1",
    "PA2, PA2",
    "PF1, PFK01",
    "PF3, PFK03",
    "PF12, PFK12",
    "PF0, ENTER",
    "PF13, ENTER",
    "FOO, ENTER",
    "XYZ, ENTER"
  })
  @DisplayName("resolveAid maps each token to the expected AID")
  void resolveAid_mapsTokenToExpectedAid(String input, CardWorkArea.Aid expected) {
    assertEquals(expected, controller.aid(input));
  }

  @Test
  @DisplayName("resolveAid(null) -> ENTER")
  void resolveAid_null_returnsEnter() {
    assertEquals(CardWorkArea.Aid.ENTER, controller.aid(null));
  }

  @Test
  @DisplayName("resolveAid(\"\") -> ENTER")
  void resolveAid_empty_returnsEnter() {
    assertEquals(CardWorkArea.Aid.ENTER, controller.aid(""));
  }

  @Test
  @DisplayName("resolveAid(\"   \") -> ENTER")
  void resolveAid_blank_returnsEnter() {
    assertEquals(CardWorkArea.Aid.ENTER, controller.aid("   "));
  }

  @Test
  @DisplayName("resolveAid(\" pf12 \") -> PFK12 (trims and upper-cases before matching)")
  void resolveAid_whitespaceAndLowercase_returnsPfk12() {
    assertEquals(CardWorkArea.Aid.PFK12, controller.aid(" pf12 "));
  }

  // ===== Next-program routing (redirectFor) — CICS XCTL equivalent, all 17 programs ===========

  /**
   * Every legacy online program name maps to its Spring MVC redirect view, reproducing the {@code
   * EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} transfer. All 17 mappings from the production {@code
   * PROGRAM_TO_URL} table are asserted; the {@code null}/unknown fall-backs are covered by the
   * dedicated tests below.
   */
  @ParameterizedTest(name = "redirectFor(\"{0}\") -> {1}")
  @CsvSource({
    "COSGN00C, redirect:/login",
    "COMEN01C, redirect:/menu",
    "COADM01C, redirect:/admin",
    "COACTVWC, redirect:/account-view",
    "COACTUPC, redirect:/account-update",
    "COCRDLIC, redirect:/card-list",
    "COCRDSLC, redirect:/card-view",
    "COCRDUPC, redirect:/card-update",
    "COTRN00C, redirect:/transaction-list",
    "COTRN01C, redirect:/transaction-view",
    "COTRN02C, redirect:/transaction-add",
    "CORPT00C, redirect:/report",
    "COBIL00C, redirect:/bill-pay",
    "COUSR00C, redirect:/user-list",
    "COUSR01C, redirect:/user-add",
    "COUSR02C, redirect:/user-update",
    "COUSR03C, redirect:/user-delete"
  })
  @DisplayName("redirectFor maps each program to its redirect view")
  void redirectFor_mapsProgramToRedirect(String program, String expected) {
    assertEquals(expected, controller.redirect(program));
  }

  @Test
  @DisplayName("redirectFor(unknown program) -> redirect:/menu")
  void redirectFor_unknownProgram_returnsMenu() {
    assertEquals("redirect:/menu", controller.redirect("ZZZ"));
  }

  @Test
  @DisplayName("redirectFor(null) -> redirect:/menu")
  void redirectFor_null_returnsMenu() {
    assertEquals("redirect:/menu", controller.redirect(null));
  }

  // ===== COMMAREA session-state helpers — CARDDEMO-COMMAREA, COCOM01Y =========================

  @Test
  @DisplayName("getCommarea creates-and-caches: a second call returns the same instance")
  void getCommarea_getOrCreate_returnsSameInstance() {
    HttpSession session = new MockHttpSession();

    CardDemoCommarea first = controller.commarea(session);
    assertNotNull(first);

    CardDemoCommarea second = controller.commarea(session);
    assertSame(first, second);
  }

  @Test
  @DisplayName("storeCommarea round-trips: getCommarea returns the stored instance")
  void storeCommarea_thenGet_returnsStoredInstance() {
    HttpSession session = new MockHttpSession();
    CardDemoCommarea mine = new CardDemoCommarea();

    controller.store(session, mine);

    assertSame(mine, controller.commarea(session));
  }

  @Test
  @DisplayName("clearCommarea removes state: getCommarea then yields a brand-new instance")
  void clearCommarea_thenGet_createsNewInstance() {
    HttpSession session = new MockHttpSession();
    CardDemoCommarea mine = new CardDemoCommarea();
    controller.store(session, mine);

    controller.clear(session);

    CardDemoCommarea fresh = controller.commarea(session);
    assertNotNull(fresh);
    assertNotSame(mine, fresh);
  }

  @Test
  @DisplayName("storeCommarea uses the documented session key 'cardDemoCommarea'")
  void storeCommarea_usesDocumentedSessionKey() {
    HttpSession session = new MockHttpSession();
    CardDemoCommarea mine = new CardDemoCommarea();

    controller.store(session, mine);

    assertSame(mine, session.getAttribute("cardDemoCommarea"));
  }
}
