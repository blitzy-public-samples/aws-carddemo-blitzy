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
import com.aws.carddemo.dto.screen.SignonScreen;
import com.aws.carddemo.service.online.SignonService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>sign-on / login</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COSGN00C} (CICS transaction {@code CC00}, BMS mapset {@code
 * COSGN00}; source {@code legacy/app/cbl/COSGN00C.cbl} + {@code legacy/app/bms/COSGN00.bms}).
 *
 * <p>This is the application entry point and the root of online navigation (Agent Action Plan
 * &sect;0.4.1, &sect;0.6.5): it (re)displays the sign-on screen, validates the operator-supplied
 * credentials, and routes an administrator to the admin menu ({@code COADM01C}) or a standard user
 * to the main menu ({@code COMEN01C}), carrying the {@link CardDemoCommarea} session state across
 * the pseudo-conversational cycle.
 *
 * <p>The controller is a thin presentation-layer adapter: it owns only the HTTP request/response
 * and the COMMAREA session state, and delegates all sign-on business logic &mdash; the {@code
 * USRSEC} lookup, password verification, role assignment, COMMAREA population, and menu routing
 * &mdash; to {@link SignonService}, which reproduces the {@code COSGN00C} paragraphs with 100%
 * behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and
 * next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COSGN00C MAIN-PARA} paint-vs-process
 * split).</strong> The single legacy transaction is split across the two idempotent HTTP verbs the
 * {@code signon} screen uses:
 *
 * <ul>
 *   <li><b>{@code GET /} and {@code GET /login}</b> &rarr; {@link #showSignon(HttpSession, Model)}
 *       is the first-entry paint, mirroring the COBOL {@code EIBCALEN = 0} branch ({@code COSGN00C}
 *       L80-83) that clears the map and sends a blank sign-on screen. Both paths render here so the
 *       {@code SecurityConfig} {@code loginPage("/login")} and the permitAll root {@code "/"} both
 *       land on the sign-on screen.
 *   <li><b>{@code POST /signon}</b> &rarr; {@code doSignon} is the re-entry process, mirroring the
 *       COBOL {@code EVALUATE EIBAID} branch (L85-99): the submitted attention identifier is
 *       resolved and the service performs the ENTER-key validation/routing or the PF3 exit.
 * </ul>
 *
 * <p><strong>Controller-driven authentication (DESIGN RATIONALE).</strong> Authentication is
 * performed here by the controller/service rather than by Spring Security's {@code formLogin}
 * processing filter, because the legacy sign-on behavior (the {@code USRSEC} read, the role
 * assignment from {@code SEC-USR-TYPE}, the COMMAREA population, and the menu routing) lives in
 * {@link SignonService} / the COBOL program and must run verbatim for parity. Spring Security is
 * reused only for the <em>resulting</em> {@link SecurityContext} and the downstream method-security
 * / URL gating: on a successful authentication this controller builds an authenticated {@link
 * UsernamePasswordAuthenticationToken} (principal = the resolved user id, credentials erased) with
 * the role derived from the COMMAREA, installs it into the {@link SecurityContextHolder}, and
 * persists it to the session through an {@link HttpSessionSecurityContextRepository} so the
 * redirect-following request is recognized as authenticated. The raw password is verified inside
 * {@link SignonService} via the injected {@code PasswordEncoder} against the stored BCrypt hash
 * (AAP &sect;0.6.6); this controller never inspects, stores, or logs it.
 *
 * <p><strong>INTEGRATION REQUIREMENT.</strong> {@code SecurityConfig} (package {@code
 * com.aws.carddemo.config}) MUST {@code permitAll()} {@code POST /signon} for controller-driven
 * sign-on to function, otherwise an unauthenticated caller is blocked before {@link SignonService}
 * can validate the credentials; CSRF must stay enabled (the Thymeleaf form auto-includes the CSRF
 * token). This cross-package coordination item cannot be fixed from the {@code web} package and is
 * not modified here.
 *
 * <p><strong>Screen-binding contract.</strong> The {@link SignonScreen} DTO is the authoritative
 * form-backing object and is bound under the model attribute {@value #ATTR_SIGNON_FORM}; its
 * canonical fields are {@code userId} and {@code passwd} (the masked, non-display password). The
 * {@code passwd} field is never echoed back into the rendered view (the template uses {@code
 * type="password"} and never pre-fills) and is never logged, preserving credential hygiene (AAP
 * &sect;0.6.6).
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} / {@code
 * dto.screen} packages, the {@code service.online} service, the Spring web and Spring Security
 * stereotypes/APIs, the servlet API, and the JDK. It references no domain, repository, or
 * persistence types, and it does not catch the {@code CardDemoException} family &mdash;
 * unrecoverable failures propagate to the application-wide {@code GlobalExceptionHandler} (the
 * modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class SignonController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code signon} Thymeleaf template &mdash; the web equivalent
   * of the BMS {@code COSGN00} / {@code COSGN0A} map. Used for the first-entry paint and the
   * post-process redisplay.
   */
  private static final String VIEW_SIGNON = "signon";

  /**
   * Model attribute / form-backing-object name bound by the {@code signon} template's {@code
   * th:object}. The template binds the authoritative {@link SignonScreen} under {@code signonForm}.
   */
  private static final String ATTR_SIGNON_FORM = "signonForm";

  /**
   * Sign-on business-logic service migrated from {@code COSGN00C}. Injected by constructor so the
   * dependency is explicit and final and the controller stays trivially unit-testable with a mocked
   * service.
   */
  private final SignonService signonService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param signonService the sign-on business-logic service (the migrated {@code COSGN00C}
   *     paragraphs); must not be {@code null}
   */
  public SignonController(SignonService signonService) {
    this.signonService = signonService;
  }

  /**
   * Displays the sign-on screen on first entry, reproducing the COBOL {@code MAIN-PARA} {@code
   * EIBCALEN = 0} first-paint branch ({@code COSGN00C} L80-83): clear the map, position the cursor
   * on the user id field, and {@code PERFORM SEND-SIGNON-SCREEN}.
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. The screen is then prepared via {@link
   * SignonService#prepareInitialScreen(SignonScreen)} &mdash; the service's dedicated first-paint
   * entry point &mdash; which populates the header/title/date/time and clears the message line.
   *
   * <p><strong>Why {@code prepareInitialScreen} and not {@code processSignon(..., ENTER)}.</strong>
   * The COBOL first paint ({@code EIBCALEN = 0}) shows a <em>blank</em> screen with no message; it
   * does not {@code PERFORM PROCESS-ENTER-KEY}. Routing the initial GET through the ENTER-key path
   * would instead run the blank-field validation and surface a spurious {@code "Please enter User
   * ID ..."} message on the very first display &mdash; a behavioral regression. {@link
   * SignonService#prepareInitialScreen(SignonScreen)} is the service's documented "convenience
   * entry point for the controller's initial GET" and reproduces the {@code EIBCALEN = 0} branch
   * exactly, so it is used here to preserve parity (AAP &sect;0.1.1, the highest-priority
   * directive).
   *
   * <p>Both {@code GET /} and {@code GET /login} map here so the {@code SecurityConfig} {@code
   * loginPage("/login")} and the permitAll root {@code "/"} both render the sign-on screen.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code signon} template renders from
   * @return the {@code signon} view name (the BMS {@code SEND-SIGNON-SCREEN} equivalent)
   */
  @GetMapping({"/", "/login"})
  public String showSignon(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    // EIBCALEN = 0 first entry: the program context is the initial ENTER context (COSGN00C L80-83).
    commarea.setPgmEnter();

    SignonScreen screen = new SignonScreen();
    // Service's dedicated first-paint entry point (COBOL EIBCALEN = 0 branch): paints the header
    // and clears the message WITHOUT running the ENTER-key blank-field validation, so the initial
    // screen is blank exactly as the legacy first paint (no spurious message) -- parity, AAP 0.1.1.
    signonService.prepareInitialScreen(screen);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_SIGNON_FORM, screen);
    return VIEW_SIGNON;
  }

  /**
   * Processes a sign-on submission on re-entry, reproducing the COBOL {@code MAIN-PARA} {@code
   * EVALUATE EIBAID} branch ({@code COSGN00C} L85-99) together with {@code PROCESS-ENTER-KEY} and
   * {@code READ-USER-SEC-FILE}.
   *
   * <p>The submitted screen is bound as the {@value #ATTR_SIGNON_FORM} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). {@link
   * SignonService#processSignon(SignonScreen, CardDemoCommarea, CardWorkArea.Aid)} then performs
   * the legacy dispatch and returns the next program to transfer to:
   *
   * <ul>
   *   <li><b>{@code null}</b> &rarr; redisplay the sign-on screen. This is an authentication /
   *       validation failure (blank field, wrong password, user not found, unable to verify) or the
   *       PF3 thank-you exit; the {@link SignonScreen} carries the service-set message in its
   *       {@code errMsg} and is placed back on the model.
   *   <li><b>non-{@code null}</b> &rarr; a successful authentication; the value is the menu program
   *       to route to ({@code "COADM01C"} or {@code "COMEN01C"}). The controller establishes the
   *       Spring Security context (see below) and issues the {@link
   *       BaseScreenController#redirectFor(String)} redirect that replaces {@code EXEC CICS XCTL}.
   * </ul>
   *
   * <p>On success, the role is derived from the COMMAREA the service populated ({@link
   * CardDemoCommarea#isAdmin()} &rarr; {@code ROLE_ADMIN}, otherwise {@code ROLE_USER}); an
   * authenticated {@link UsernamePasswordAuthenticationToken} (principal = the resolved user id,
   * credentials {@code null}) is installed in the {@link SecurityContextHolder} and persisted to
   * the session via an {@link HttpSessionSecurityContextRepository}, so the redirect-following
   * request is recognized as authenticated by the security filter chain.
   *
   * @param screen the submitted sign-on screen bound from the request form ({@value
   *     #ATTR_SIGNON_FORM}), carrying the operator's {@code userId} and {@code passwd}
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param request the current request, used to persist the security context to the session
   * @param response the current response, used to persist the security context to the session
   * @param model the Spring MVC model used when the sign-on screen is redisplayed
   * @return a {@code redirect:} view name for the dispatched menu program on success, or the {@code
   *     signon} view name to redisplay the screen
   */
  @PostMapping("/signon")
  public String doSignon(
      @ModelAttribute(ATTR_SIGNON_FORM) SignonScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    // Re-entry into the program (the COBOL EIBCALEN > 0 / EVALUATE EIBAID path).
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = signonService.processSignon(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next == null) {
      // Redisplay: an auth/validation failure or the PF3 thank-you exit. The screen carries the
      // service-set errMsg; the password is never re-populated (credential hygiene, AAP 0.6.6).
      model.addAttribute(ATTR_SIGNON_FORM, screen);
      return VIEW_SIGNON;
    }

    // Successful authentication (the EXEC CICS XCTL equivalent). Establish the Spring Security
    // context from the role the service captured into the COMMAREA, so subsequent authenticated
    // requests (the redirect target and beyond) are recognized by the security filter chain.
    String role = commarea.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER";
    var authorities = List.of(new SimpleGrantedAuthority(role));
    // Principal = the authenticated user id; credentials are erased (null), never the raw password.
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(commarea.getUserId(), null, authorities);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    // Persist the context to the session so the redirect-following request is authenticated.
    SecurityContextRepository repository = new HttpSessionSecurityContextRepository();
    repository.saveContext(context, request, response);

    // XCTL equivalent: redirect to the next program's GET endpoint (COADM01C -> /admin,
    // COMEN01C -> /menu) via the shared program-name -> URL routing table.
    return redirectFor(next);
  }
}
