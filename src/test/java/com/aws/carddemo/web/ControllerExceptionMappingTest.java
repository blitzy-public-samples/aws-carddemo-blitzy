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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.exception.GlobalExceptionHandler;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.exception.ValidationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.AccountViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest WebMvcTest} slice test
 * that verifies the production {@link GlobalExceptionHandler} ({@code @ControllerAdvice}) maps each
 * business exception thrown from the controller layer to the correct HTTP status and RFC&nbsp;7807
 * {@link org.springframework.http.ProblemDetail ProblemDetail} body. This is the
 * <strong>FILE&nbsp;STATUS / CICS&nbsp;RESP / abend &rarr; typed-exception parity</strong> required
 * by the Agent Action Plan (AAP &sect;0.6.4 the FILE STATUS-to-exception table; &sect;0.6.6 the
 * online abend that "maps to a {@code @ControllerAdvice} handler"). The legacy behavioural sources
 * are {@code legacy/app/cbl/CBTRN02C.cbl} (the per-file two-byte FILE STATUS branching and the
 * {@code 9910-DISPLAY-IO-STATUS} / {@code 9999-ABEND-PROGRAM} paragraphs) and {@code
 * legacy/app/cbl/COACTVWC.cbl} (the online {@code ABEND-ROUTINE} that defaulted to {@code
 * 'UNEXPECTED ABEND OCCURRED.'} and issued {@code EXEC CICS ABEND ABCODE('9999')}).
 *
 * <h2>Why this design</h2>
 *
 * <ul>
 *   <li><strong>The only handler-activating test in the package.</strong> This is the single test
 *       in {@code com.aws.carddemo.web} that exercises {@link GlobalExceptionHandler} end to end
 *       (request &rarr; controller &rarr; thrown exception &rarr; advice &rarr; JSON body). The
 *       other web slice tests assert controller routing/view contracts and deliberately leave the
 *       advice out.
 *   <li><strong>Exactly one advice instance.</strong> {@code @WebMvcTest} auto-detects
 *       {@code @ControllerAdvice} beans, and this test <em>also</em> needs the advice explicitly.
 *       To guarantee a single {@link GlobalExceptionHandler} (and avoid an auto-detected {@code +}
 *       {@code @Import} duplicate that could make {@code @ExceptionHandler} resolution ambiguous),
 *       the slice both {@linkplain WebMvcTest#excludeFilters() excludes} the advice from the
 *       component scan <em>and</em> {@linkplain Import imports} exactly one. The net result is one,
 *       and only one, advice bean in the context.
 *   <li><strong>A non-admin controller with an authorized user.</strong> {@link
 *       AccountViewController} carries no {@code @PreAuthorize}, and the class-level {@link
 *       WithMockUser @WithMockUser(roles = "USER")} supplies an authenticated standard user. The
 *       request therefore passes Spring Security (the {@code /account-view} route is gated only by
 *       {@code anyRequest().authenticated()} in {@link SecurityConfig}, not by an admin matcher)
 *       and reaches the controller, where the mocked {@link AccountViewService} throws each
 *       business exception. This deliberately avoids Spring Security's framework {@code
 *       AccessDeniedException} so that only the {@code CardDemoException} subtypes are exercised
 *       and {@link GlobalExceptionHandler} maps them cleanly. (The {@code AuthorizationException}
 *       case below is the <em>custom</em> {@link
 *       com.aws.carddemo.exception.AuthorizationException}, not Spring Security's {@code
 *       AccessDeniedException}.)
 * </ul>
 *
 * <h2>Request shape and assertion strategy</h2>
 *
 * <p>Every test stubs the read-only {@link AccountViewService#processAccountView} call to throw,
 * then performs the same {@code POST /account-view} with {@code pfKey=ENTER}. CSRF is required on
 * every POST because {@link SecurityConfig} keeps Spring Security's CSRF protection at its enabled
 * default. The thrown types are all {@link RuntimeException} subtypes ({@code CardDemoException
 * extends RuntimeException}), so {@code willThrow(...)} compiles without checked-exception
 * handling. The {@link org.springframework.http.ProblemDetail ProblemDetail} is serialized with its
 * custom properties flattened to the JSON top level (Spring's {@code ProblemDetail} Jackson mixin),
 * so {@code $.field}, {@code $.entity}, {@code $.abendCode}, {@code $.fileStatus}, {@code $.file},
 * and {@code $.operation} are top-level members rather than nested under {@code $.properties}.
 *
 * <p>The HTTP status, {@code $.status}, {@code $.title}, and the documented custom properties are
 * the authoritative, exact assertions. The {@code $.detail} checks use {@code containsString} on
 * substrings drawn from the exception message / {@link IoStatusException#getDisplayMessage()},
 * because the handler composes {@code detail} from the exception and a substring match is robust to
 * incidental punctuation.
 */
@WebMvcTest(
    controllers = AccountViewController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = GlobalExceptionHandler.class))
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class ControllerExceptionMappingTest {

  /** Account-view screen path mapped by {@link AccountViewController#doAccountView}. */
  private static final String ACCOUNT_VIEW_PATH = "/account-view";

  /**
   * Attention-identifier request parameter sent on every POST. {@code ENTER} resolves (via {@code
   * BaseScreenController.resolveAid}) to the default attention identifier, so the controller always
   * reaches the mocked service rather than short-circuiting on a PF-key branch.
   */
  private static final String PF_KEY_PARAM = "pfKey";

  /** The {@code ENTER} attention-identifier token. */
  private static final String PF_KEY_ENTER = "ENTER";

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COACTVWC} business logic. Each test stubs its {@code
   * processAccountView(...)} call to throw a specific {@link
   * com.aws.carddemo.exception.CardDemoException} subtype, so the request propagates that exception
   * to {@link GlobalExceptionHandler} for mapping.
   */
  @MockitoBean private AccountViewService accountViewService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be supplied as a mock. It is never invoked here
   * because {@link WithMockUser} provides the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * A {@link ValidationException} (the pervasive COBOL field-edit soft error) maps to
   * <strong>HTTP&nbsp;400 (Bad Request)</strong>. The {@code (fieldName, message)} constructor
   * records the offending field, which the handler surfaces as the {@code "field"} property to
   * drive cursor placement, and forwards the message verbatim as the {@code detail}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void validationException_mapsToBadRequest() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any()))
        .willThrow(new ValidationException("acctId", "Account ID must be numeric"));

    mockMvc
        .perform(post(ACCOUNT_VIEW_PATH).param(PF_KEY_PARAM, PF_KEY_ENTER).with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Validation Error"))
        .andExpect(jsonPath("$.field").value("acctId"))
        .andExpect(jsonPath("$.detail", containsString("Account ID")));
  }

  /**
   * A {@link RecordNotFoundException} (the online FILE STATUS {@code '23'} record-not-found branch)
   * maps to <strong>HTTP&nbsp;404 (Not Found)</strong>. The non-sensitive logical {@code "entity"}
   * is surfaced, but the raw lookup {@code key} is <em>never</em> echoed in the response body.
   *
   * <p><strong>Why {@code $.key} must not exist.</strong> The production {@link
   * GlobalExceptionHandler} deliberately omits the lookup key from the body for security hygiene
   * (AAP &sect;0.7.2; CWE-532 information disclosure) and returns the fixed, key-free detail {@code
   * "Record not found"}; the companion unit test {@code
   * com.aws.carddemo.exception.GlobalExceptionHandlerTest} pins this exact contract with {@code
   * containsEntry("entity", ...)} and {@code doesNotContainKey("key")}. This test mirrors that
   * leak-free contract end to end: it asserts {@code $.entity == "Account"} exactly and that {@code
   * $.key} is absent, actively verifying that the sensitive identifier never reaches the caller.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void recordNotFoundException_mapsToNotFound() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any()))
        .willThrow(new RecordNotFoundException("Account", "123"));

    mockMvc
        .perform(post(ACCOUNT_VIEW_PATH).param(PF_KEY_PARAM, PF_KEY_ENTER).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Record Not Found"))
        .andExpect(jsonPath("$.entity").value("Account"))
        .andExpect(jsonPath("$.key").doesNotExist());
  }

  /**
   * A custom {@link AuthorizationException} (the COBOL {@code CDEMO-USRTYP} admin-only gate) maps
   * to <strong>HTTP&nbsp;403 (Forbidden)</strong>. The no-argument constructor carries the
   * preserved legacy text {@link AuthorizationException#ADMIN_ONLY_MESSAGE} ({@code "No access -
   * Admin Only option..."}, the {@code COMEN01C} denial message), which the handler returns as the
   * {@code detail} for screen-message parity.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void authorizationException_mapsToForbidden() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any()))
        .willThrow(new AuthorizationException());

    mockMvc
        .perform(post(ACCOUNT_VIEW_PATH).param(PF_KEY_PARAM, PF_KEY_ENTER).with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.title").value("Authorization Denied"))
        .andExpect(jsonPath("$.detail", containsString("Admin Only")));
  }

  /**
   * An {@link IoStatusException} (the online abend equivalent of an unexpected FILE STATUS) maps to
   * <strong>HTTP&nbsp;500 (Internal Server Error)</strong>. The diagnostic context is surfaced as
   * properties: the preserved online CICS abend code {@code "9999"} ({@code EXEC CICS ABEND
   * ABCODE('9999')}), the four-character {@linkplain IoStatusException#getFormattedStatus()
   * formatted status} (the raw {@code "35"} zero-padded to {@code "0035"} by the {@code
   * 9910-DISPLAY-IO-STATUS} rendering), and the failing file and operation. The {@code detail}
   * carries the byte-faithful operator line {@code "FILE STATUS IS: NNNN..."}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void ioStatusException_mapsToInternalServerError() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any()))
        .willThrow(new IoStatusException("ACCTFILE", "READ", "35"));

    mockMvc
        .perform(post(ACCOUNT_VIEW_PATH).param(PF_KEY_PARAM, PF_KEY_ENTER).with(csrf()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.title").value("I/O Error"))
        .andExpect(jsonPath("$.abendCode").value("9999"))
        .andExpect(jsonPath("$.fileStatus").value("0035"))
        .andExpect(jsonPath("$.file").value("ACCTFILE"))
        .andExpect(jsonPath("$.operation").value("READ"))
        .andExpect(jsonPath("$.detail", containsString("FILE STATUS IS")));
  }

  /**
   * Spring MVC's {@link org.springframework.web.servlet.resource.NoResourceFoundException} — raised
   * when an authenticated request targets a URL mapped by no controller handler and no static
   * resource — maps to <strong>HTTP&nbsp;404 (Not Found)</strong> via {@link
   * GlobalExceptionHandler} rather than falling through to the unexpected-error catch-all as an
   * HTTP&nbsp;500 abend (QA Defect&nbsp;#4). The {@code @ControllerAdvice}'s {@code
   * ExceptionHandlerExceptionResolver} runs before the framework's {@code
   * DefaultHandlerExceptionResolver}, so this handler wins and writes the RFC&nbsp;7807 body. Being
   * a <em>client</em> error, the response carries the {@code "Resource Not Found"} title and
   * <em>no</em> {@code abendCode} property.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void noResourceFound_mapsToNotFound() throws Exception {
    mockMvc
        .perform(get("/no-such-route-xyz"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Resource Not Found"))
        .andExpect(jsonPath("$.abendCode").doesNotExist());
  }

  /**
   * Spring MVC's {@link org.springframework.web.HttpRequestMethodNotSupportedException} — raised
   * when the {@code /account-view} route (which supports {@code GET} and {@code POST}) is invoked
   * with an unsupported method such as {@code PUT} — maps to <strong>HTTP&nbsp;405 (Method Not
   * Allowed)</strong> via {@link GlobalExceptionHandler}, carrying an {@code Allow} response header
   * that enumerates the supported methods (RFC&nbsp;7231 &sect;6.5.5), rather than surfacing as an
   * HTTP&nbsp;500 abend (QA Defect&nbsp;#4). Being a <em>client</em> error, the body carries the
   * {@code "Method Not Allowed"} title and <em>no</em> {@code abendCode} property.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void methodNotSupported_mapsToMethodNotAllowed() throws Exception {
    mockMvc
        .perform(put(ACCOUNT_VIEW_PATH).with(csrf()))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(header().string("Allow", containsString("POST")))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.title").value("Method Not Allowed"))
        .andExpect(jsonPath("$.abendCode").doesNotExist());
  }
}
