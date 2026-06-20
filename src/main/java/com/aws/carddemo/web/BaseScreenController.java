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

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Map;

/**
 * Abstract base class for the 17 migrated CICS online programs, centralizing the cross-cutting
 * pseudo-conversational concerns that every legacy online transaction shares (Agent Action Plan
 * &sect;0.6.5):
 *
 * <ul>
 *   <li><b>COMMAREA session state</b> — the legacy {@code CARDDEMO-COMMAREA} ({@code COCOM01Y}) was
 *       passed across CICS {@code RECEIVE}/{@code SEND} cycles. In the modernized application it
 *       lives in the {@link HttpSession} and is read/created/cleared through {@link
 *       #getCommarea(HttpSession)}, {@link #storeCommarea(HttpSession, CardDemoCommarea)}, and
 *       {@link #clearCommarea(HttpSession)}.
 *   <li><b>AID / PF-key resolution</b> — the HTTP {@code pfKey} request parameter submitted by the
 *       Thymeleaf screens is translated to a {@link CardWorkArea.Aid} (the {@code CCARD-AID}
 *       88-levels) by {@link #resolveAid(String)}.
 *   <li><b>Next-program routing (XCTL equivalent)</b> — the COBOL {@code EXEC CICS XCTL
 *       PROGRAM(CDEMO-TO-PROGRAM)} transfer is reproduced by {@link #redirectFor(String)}, which
 *       maps each legacy program name to the Spring MVC GET endpoint that re-displays that screen.
 * </ul>
 *
 * <p><strong>This is intentionally NOT a Spring bean.</strong> The class carries no
 * {@code @Controller}/{@code @Component} stereotype and is never instantiated or component-scanned
 * on its own; the 17 concrete controllers {@code extends BaseScreenController} and supply their own
 * {@code @Controller} annotation. Keeping the shared logic here keeps those controllers thin and
 * uniform and is the single source of truth for online navigation parity.
 *
 * <p><strong>Stateless by contract.</strong> The class holds only immutable constants and an
 * immutable lookup map; it declares no mutable instance fields, so it is safe to extend by the
 * singleton-scoped controller beans without introducing shared mutable state.
 *
 * <p>Strict layering is preserved: this type depends only on the {@code dto} package, the servlet
 * {@link HttpSession}, and the JDK. It references no domain, repository, or persistence types.
 */
public abstract class BaseScreenController {

  /**
   * Attribute key under which the {@link CardDemoCommarea} is stored in the {@link HttpSession}.
   * This is the modernized home of the pseudo-conversational COMMAREA (AAP &sect;0.6.5).
   */
  protected static final String SESSION_COMMAREA_KEY = "cardDemoCommarea";

  /**
   * Single source of truth for online routing: each legacy COBOL online program name is mapped to
   * the Spring MVC GET endpoint that re-displays the corresponding screen on first entry. This
   * mirrors the legacy CSD transaction/program registry and the {@code CCARD-NEXT-PROG} / {@code
   * CDEMO-TO-PROGRAM} targets of {@code EXEC CICS XCTL PROGRAM(...)}.
   */
  private static final Map<String, String> PROGRAM_TO_URL =
      Map.ofEntries(
          Map.entry("COSGN00C", "/login"),
          Map.entry("COMEN01C", "/menu"),
          Map.entry("COADM01C", "/admin"),
          Map.entry("COACTVWC", "/account-view"),
          Map.entry("COACTUPC", "/account-update"),
          Map.entry("COCRDLIC", "/card-list"),
          Map.entry("COCRDSLC", "/card-view"),
          Map.entry("COCRDUPC", "/card-update"),
          Map.entry("COTRN00C", "/transaction-list"),
          Map.entry("COTRN01C", "/transaction-view"),
          Map.entry("COTRN02C", "/transaction-add"),
          Map.entry("CORPT00C", "/report"),
          Map.entry("COBIL00C", "/bill-pay"),
          Map.entry("COUSR00C", "/user-list"),
          Map.entry("COUSR01C", "/user-add"),
          Map.entry("COUSR02C", "/user-update"),
          Map.entry("COUSR03C", "/user-delete"));

  // ===== COMMAREA session-state helpers (pseudo-conversational state; AAP §0.6.5) ===============

  /**
   * Returns the {@link CardDemoCommarea} held in the supplied session, creating and storing a fresh
   * one when none is present.
   *
   * <p>This get-or-create behavior mirrors CICS treating an empty COMMAREA ({@code EIBCALEN = 0})
   * as a first entry: the very first request in a session has no stored state, so a new, empty
   * communication area is allocated and registered under {@link #SESSION_COMMAREA_KEY}. Subsequent
   * calls within the same session return the same instance, preserving the navigation/role state
   * carried between screens.
   *
   * @param session the current HTTP session standing in for the pseudo-conversational COMMAREA
   * @return the existing communication area, or a newly created-and-stored one on first entry
   */
  protected CardDemoCommarea getCommarea(HttpSession session) {
    Object attribute = session.getAttribute(SESSION_COMMAREA_KEY);
    if (attribute instanceof CardDemoCommarea existing) {
      return existing;
    }
    CardDemoCommarea created = new CardDemoCommarea();
    session.setAttribute(SESSION_COMMAREA_KEY, created);
    return created;
  }

  /**
   * Stores the communication area into the session, replacing any previously held value.
   * Controllers call this after mutating navigation/role state so the next request observes the
   * update — the modernized equivalent of returning the COMMAREA on the CICS {@code SEND}/return.
   *
   * @param session the current HTTP session
   * @param commarea the communication area to persist as session state
   */
  protected void storeCommarea(HttpSession session, CardDemoCommarea commarea) {
    session.setAttribute(SESSION_COMMAREA_KEY, commarea);
  }

  /**
   * Removes the communication area from the session. Used on sign-off (the {@code COSGN00C} PF3
   * thank-you path) and logout, mirroring the legacy flow that abandons the pseudo-conversational
   * state when the user leaves the application.
   *
   * @param session the current HTTP session
   */
  protected void clearCommarea(HttpSession session) {
    session.removeAttribute(SESSION_COMMAREA_KEY);
  }

  // ===== AID / PF-key resolution (HTTP pfKey -> CardWorkArea.Aid; AAP §0.6.5) ===================

  /**
   * Resolves the HTTP {@code pfKey} request parameter submitted by the Thymeleaf screens to the
   * corresponding {@link CardWorkArea.Aid} (the legacy {@code CCARD-AID} attention identifier).
   *
   * <p>The screens submit short tokens — {@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2},
   * and {@code PF1}–{@code PF12} — whereas the {@link CardWorkArea.Aid} constants for the function
   * keys use the 5-character COBOL codes {@code PFK01}–{@code PFK12}. The HTTP token {@code "PF3"}
   * therefore does <em>not</em> equal the code {@code "PFK03"}, so a direct {@code
   * CardWorkArea.Aid.fromCode(pfKey)} call would fail; this method performs the {@code PFn ->
   * PFKnn} translation explicitly (for example {@code "PF3" -> PFK03}, {@code "PF12" -> PFK12}).
   *
   * <p>Resolution rules:
   *
   * <ul>
   *   <li>{@code null} or blank input returns {@link CardWorkArea.Aid#ENTER}, mirroring a plain
   *       ENTER key press (the default action).
   *   <li>The value is trimmed and upper-cased using {@link Locale#ROOT} before matching.
   *   <li>{@code ENTER}, {@code CLEAR}, {@code PA1}, {@code PA2} map to their like-named constants.
   *   <li>{@code PFn} with {@code 1 <= n <= 12} maps to {@code PFKnn} (zero-padded to two digits).
   *   <li>Any unrecognized value — an out-of-range {@code PFn}, a non-numeric suffix, or unknown
   *       text — falls through to {@link CardWorkArea.Aid#ENTER}, mirroring COBOL behavior when no
   *       {@code CCARD-AID} 88-level matches.
   * </ul>
   *
   * @param pfKey the raw {@code pfKey} request-parameter value, possibly {@code null} or blank
   * @return the resolved attention identifier; never {@code null}
   */
  protected CardWorkArea.Aid resolveAid(String pfKey) {
    if (pfKey == null || pfKey.isBlank()) {
      return CardWorkArea.Aid.ENTER;
    }
    String token = pfKey.trim().toUpperCase(Locale.ROOT);
    return switch (token) {
      case "ENTER" -> CardWorkArea.Aid.ENTER;
      case "CLEAR" -> CardWorkArea.Aid.CLEAR;
      case "PA1" -> CardWorkArea.Aid.PA1;
      case "PA2" -> CardWorkArea.Aid.PA2;
      default -> {
        if (token.startsWith("PF")) {
          try {
            int number = Integer.parseInt(token.substring(2));
            if (number >= 1 && number <= 12) {
              yield CardWorkArea.Aid.valueOf(String.format("PFK%02d", number));
            }
          } catch (NumberFormatException ignored) {
            // Non-numeric PF suffix (e.g. "PFX"): fall through to the ENTER default below.
          }
        }
        yield CardWorkArea.Aid.ENTER;
      }
    };
  }

  // ===== Next-program routing: redirectFor (CICS XCTL equivalent; AAP §0.4.2, §0.6.5) ===========

  /**
   * Produces the Spring MVC redirect view name for the next online program to transfer to,
   * reproducing the legacy {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} transfer.
   *
   * <p>Online services return the next COBOL program name (the {@code CCARD-NEXT-PROG} / {@code
   * CDEMO-TO-PROGRAM} value); this method maps that name to its GET endpoint via {@link
   * #PROGRAM_TO_URL} and prefixes {@code "redirect:"}. The GET endpoint for each program
   * re-displays that screen on first entry, so the resulting redirect lands the user on the next
   * screen exactly as the XCTL would have.
   *
   * <p>To keep navigation resilient, a {@code null} or unknown program name does not throw; it
   * falls back to the main menu ({@code "redirect:/menu"}).
   *
   * @param program the next online program name, possibly {@code null}
   * @return a Spring MVC redirect view name (for example {@code "redirect:/menu"})
   */
  protected String redirectFor(String program) {
    String url = (program == null) ? null : PROGRAM_TO_URL.get(program);
    if (url != null) {
      return "redirect:" + url;
    }
    return "redirect:/menu";
  }
}
