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
package com.aws.carddemo.web;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardViewRequest;
import com.aws.carddemo.dto.CardViewResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest}
 * slice test for {@link CardViewController} &mdash; the Java re-platform of the
 * online COBOL program {@code COCRDSLC} (CICS transaction {@code CCDL}, BMS map
 * {@code COCRDSL}), the <em>View Credit Card Detail</em> screen.
 *
 * <p>The suite verifies the REST re-expression of the legacy screen contract
 * without rendering a 3270 terminal (AAP&nbsp;&sect;0.7 hotspots H2/M1/L1):</p>
 * <ul>
 *   <li><strong>Authentication</strong> &mdash; an anonymous request is rejected
 *       ({@code 401}); the endpoint is authenticated but not admin-only.</li>
 *   <li><strong>First-entry screen</strong> &mdash; {@code GET} renders the blank
 *       detail screen with the {@code 'Please enter Account and Card Number'}
 *       prompt (COBOL {@code WS-PROMPT-FOR-INPUT}).</li>
 *   <li><strong>Lookup</strong> &mdash; {@code POST}+{@code ENTER} resolves the
 *       card through {@link CardService#viewCard(Long, String)} and renders its
 *       detail, exercising the <em>real</em> {@link CardMapper} expiry-split
 *       decomposition; a not-found read propagates as {@code 404}.</li>
 *   <li><strong>PF-key semantics</strong> &mdash; {@code PF3} navigates back to
 *       the caller (or the main menu {@code COMEN01C}/{@code CM00}); an unmapped
 *       key redisplays the invalid-key message.</li>
 *   <li><strong>CVV never present</strong> &mdash; the card verification value is
 *       never a response property and never appears anywhere in the body
 *       (AAP&nbsp;&sect;0.7.3, &sect;0.9.3).</li>
 *   <li><strong>Field-contract parity</strong> &mdash; the response record
 *       preserves the {@code COCRDSL} symbolic-map output field set, and a
 *       malformed search key is rejected by Bean Validation ({@code 400}).</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>Only the web layer is loaded: {@link CardViewController} plus the imported
 * {@link SecurityConfig} (so the real security filter chain and RFC&nbsp;7807
 * failure handling apply) and the real {@link CardMapper} (so the entity&rarr;DTO
 * decomposition under test is genuine, not mocked). The {@link CardService}
 * collaborator is a Mockito mock supplied by {@code @MockitoBean}. The
 * {@code GlobalExceptionHandler} {@code @RestControllerAdvice} is auto-detected by
 * the slice, so {@code RecordNotFoundException} maps to {@code 404} and a
 * validation failure maps to {@code 400}, both as {@code application/problem+json}.
 * CSRF is disabled by {@link SecurityConfig} (a stateless HTTP&nbsp;Basic API), so
 * no CSRF token is attached to the {@code POST}s. The suite is fully deterministic
 * and headless &mdash; no database, no Docker, no {@code test} profile &mdash; and
 * contributes to the mandated &ge;80&nbsp;% line-coverage gate.</p>
 */
@WebMvcTest(CardViewController.class)
@Import({SecurityConfig.class, CardMapper.class})
class CardViewControllerTest {

    // ------------------------------------------------------------------------
    // Endpoint + contract constants (mirrors of CardViewController, asserted so a
    // silent change to a caller-visible literal fails this test).
    // ------------------------------------------------------------------------

    /** Base path of the card-view screen &mdash; {@code CardViewController} {@code @RequestMapping}. */
    private static final String VIEW_PATH = "/api/v1/cards/view";

    /** This screen's transaction id &mdash; COBOL {@code CCDL}. */
    private static final String TRANSACTION_ID = "CCDL";

    /** This screen's program id &mdash; COBOL {@code COCRDSLC}. */
    private static final String PROGRAM_ID = "COCRDSLC";

    /** Default back-navigation program (main menu) when no caller context is supplied. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Default back-navigation transaction (main menu) when no caller context is supplied. */
    private static final String MENU_TRANSACTION = "CM00";

    /** First-entry prompt &mdash; COBOL {@code WS-PROMPT-FOR-INPUT}. */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /**
     * COBOL {@code FOUND-CARDS-FOR-ACCOUNT} literal shown on the populated card-detail screen
     * ({@code legacy/cbl/COCRDSLC.cbl} L129-130); the three leading spaces are part of the
     * legacy literal and must be preserved verbatim (F-P9-F).
     */
    private static final String MSG_DISPLAYING_DETAILS = "   Displaying requested details";

    /** Invalid-attention-key message &mdash; COBOL {@code CCDA-MSG-INVALID-KEY}. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Optional request header carrying the calling program (COMMAREA {@code CDEMO-FROM-PROGRAM}). */
    private static final String HEADER_FROM_PROGRAM = "X-CardDemo-From-Program";

    /** Optional request header carrying the calling transaction (COMMAREA {@code CDEMO-FROM-TRANID}). */
    private static final String HEADER_FROM_TRANSACTION = "X-CardDemo-From-Tranid";

    /** Response header naming the program to navigate to next (Java analog of {@code CDEMO-TO-PROGRAM}). */
    private static final String NEXT_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /** Response header naming the transaction to navigate to next (Java analog of {@code CDEMO-TO-TRANID}). */
    private static final String NEXT_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    // ------------------------------------------------------------------------
    // Deterministic card fixture (COBOL CARD-RECORD / CVACT02Y.cpy).
    // ------------------------------------------------------------------------

    /** Fixture owning account id ({@code CARD-ACCT-ID PIC 9(11)}); already 11 digits. */
    private static final long ACCT_ID = 11111111111L;

    /** {@link #ACCT_ID} as the screen's zero-padded {@code %011d} account string ({@code ACCTSIDO}). */
    private static final String ACCT_ID_STR = "11111111111";

    /** Fixture card number ({@code CARD-NUM PIC X(16)}), primary key and search key. */
    private static final String CARD_NUM = "4111111111111111";

    /**
     * Fixture card verification value ({@code CARD-CVV-CD PIC 9(03)}, SENSITIVE).
     *
     * <p>This is an obviously fake, non-secret test value chosen so the leakage
     * scans are free of false positives: {@code "999"} does not occur as a
     * substring of any legitimate found-case response value &mdash; not the card
     * number ({@link #CARD_NUM}), the account string ({@link #ACCT_ID_STR}), the
     * embossed name ({@link #EMBOSSED_NAME}), the split expiry ({@code 2027} /
     * {@code 12}), the header titles, the function-key legend, nor the two-digit
     * {@code MM/dd/uu} / {@code HH:mm:ss} header clock. Its presence anywhere in a
     * response would therefore be a genuine CVV leak.</p>
     */
    private static final String CVV = "999";

    /** Fixture embossed name ({@code CARD-EMBOSSED-NAME PIC X(50)}); non-numeric by design. */
    private static final String EMBOSSED_NAME = "JOHN CARDHOLDER";

    /** Fixture expiration date ({@code CARD-EXPIRAION-DATE PIC X(10)}), {@code YYYY-MM-DD}. */
    private static final String EXPIRATION = "2027-12-31";

    /** Expected split expiry year ({@code EXPYEARO}) decomposed from {@link #EXPIRATION}. */
    private static final String EXPECTED_EXPIRY_YEAR = "2027";

    /** Expected split expiry month ({@code EXPMONO}) decomposed from {@link #EXPIRATION}. */
    private static final String EXPECTED_EXPIRY_MONTH = "12";

    /** Fixture active-status flag ({@code CARD-ACTIVE-STATUS PIC X(01)}). */
    private static final String ACTIVE_STATUS = "Y";

    /** Entry point under test; the real web stack is exercised through it. */
    @Autowired
    private MockMvc mockMvc;

    /** The application {@link ObjectMapper}; used to build request bodies and parse responses. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The card business-logic service, mocked so this slice test asserts only the
     * controller/mapper/serialization contract. {@code @MockitoBean} (never the
     * deprecated {@code MockBean} annotation) is reset by Spring between test
     * methods, so {@code verifyNoInteractions} is reliable per test.
     */
    @MockitoBean
    private CardService cardService;

    // ========================================================================
    // Fixtures + reflection/JSON helpers
    // ========================================================================

    /**
     * Builds a fresh, fully populated fixture {@link Card} (including a non-blank
     * {@link #CVV} so the CVV-leakage assertions have something to detect). A new
     * instance is returned per call so no test can observe another's mutations.
     *
     * @return a new fixture {@link Card}
     */
    private static Card newCard() {
        Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, EXPIRATION, ACTIVE_STATUS);
        card.setVersion(0L);
        return card;
    }

    /**
     * Recursively reports whether any object node reachable from {@code node}
     * declares a field named {@code fieldName}. Used to prove that no serialized
     * response &mdash; at any nesting depth &mdash; exposes a {@code cvv} property.
     *
     * @param node      the JSON tree (or subtree) to inspect; never {@code null}
     * @param fieldName the field name to search for
     * @return {@code true} if the name occurs anywhere in the tree, else {@code false}
     */
    private static boolean containsFieldName(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsFieldName(child, fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Serializes a {@link CardViewRequest} to its JSON request-body form using the
     * application {@link ObjectMapper}.
     *
     * @param request the request DTO to serialize
     * @return the JSON body text
     * @throws Exception if serialization fails
     */
    private String toJson(CardViewRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // ========================================================================
    // A. Authentication
    // ========================================================================

    /**
     * An anonymous request to the authenticated screen is rejected with
     * {@code 401 Unauthorized} by the {@link SecurityConfig} filter chain; the
     * service is never reached.
     */
    @Test
    @DisplayName("A. Unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(get(VIEW_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cardService);
    }

    // ========================================================================
    // B. First-entry (blank) screen
    // ========================================================================

    /**
     * {@code GET} renders the first-entry (blank) detail screen: {@code 200 OK}
     * with the populated header identity/titles/function-keys and the
     * {@link #MSG_PROMPT_FOR_INPUT} prompt, no error, and no resolved card-detail
     * fields (which are {@code null} and therefore omitted under the
     * {@code non_null} inclusion policy). The service is not consulted.
     */
    @Test
    @WithMockUser
    @DisplayName("B. GET renders the blank detail screen with the input prompt")
    void getRendersBlankDetailScreen() throws Exception {
        mockMvc.perform(get(VIEW_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_ID))
                .andExpect(jsonPath("$.title01").isNotEmpty())
                .andExpect(jsonPath("$.title02").isNotEmpty())
                .andExpect(jsonPath("$.currentDate").isNotEmpty())
                .andExpect(jsonPath("$.currentTime").isNotEmpty())
                .andExpect(jsonPath("$.functionKeys").isNotEmpty())
                .andExpect(jsonPath("$.infoMessage").value(MSG_PROMPT_FOR_INPUT))
                // null fields are omitted under the non_null inclusion policy.
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.cardId").doesNotExist())
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cardStatus").doesNotExist())
                .andExpect(jsonPath("$.expiryMonth").doesNotExist())
                .andExpect(jsonPath("$.expiryYear").doesNotExist());

        verifyNoInteractions(cardService);
    }

    // ========================================================================
    // C. POST ENTER - card found
    // ========================================================================

    /**
     * {@code POST}+{@link PfKeyAction#ENTER} with a resolvable account/card key
     * returns {@code 200 OK} carrying the mapped card detail. The controller parses
     * the account text to a {@code Long} and delegates to
     * {@link CardService#viewCard(Long, String)}; the resolved entity is rendered by
     * the <em>real</em> {@link CardMapper}, so this also proves the expiry
     * decomposition ({@code "2027-12-31"} &rarr; year {@code "2027"}, month
     * {@code "12"}) and the {@code %011d} account formatting.
     */
    @Test
    @WithMockUser
    @DisplayName("C. POST ENTER returns the mapped card detail when the card is found")
    void postEnterReturnsCardDetailWhenFound() throws Exception {
        when(cardService.viewCard(ACCT_ID, CARD_NUM)).thenReturn(newCard());

        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(ACCT_ID_STR, CARD_NUM, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_ID))
                // COBOL COCRDSLC emits FOUND-CARDS-FOR-ACCOUNT ('   Displaying requested
                // details', leading spaces included) as INFOMSGO on the card-found screen (F-P9-F).
                .andExpect(jsonPath("$.infoMessage").value(MSG_DISPLAYING_DETAILS))
                .andExpect(jsonPath("$.accountId").value(ACCT_ID_STR))
                .andExpect(jsonPath("$.cardId").value(CARD_NUM))
                .andExpect(jsonPath("$.cardName").value(EMBOSSED_NAME))
                .andExpect(jsonPath("$.cardStatus").value(ACTIVE_STATUS))
                .andExpect(jsonPath("$.expiryYear").value(EXPECTED_EXPIRY_YEAR))
                .andExpect(jsonPath("$.expiryMonth").value(EXPECTED_EXPIRY_MONTH));
    }

    /**
     * A service-level input-edit failure (COBOL {@code 2210-EDIT-ACCOUNT} /
     * {@code 2220-EDIT-CARD}) surfaces as {@link IllegalArgumentException} carrying
     * the edit message. Unlike a not-found read, this is a same-screen field edit,
     * not a server fault, so the controller catches it and redisplays the screen at
     * {@code 200 OK} echoing the operator's search keys and the verbatim message
     * &mdash; the {@code 200 service message} path noted in the field-contract
     * requirement. The response carries no resolved card detail.
     */
    @Test
    @WithMockUser
    @DisplayName("C2. POST ENTER redisplays the screen at 200 with the service edit message")
    void postEnterRedisplaysServiceEditMessage() throws Exception {
        String editMessage = "Account Filter must be a non zero 11 digit number";
        when(cardService.viewCard(ACCT_ID, CARD_NUM))
                .thenThrow(new IllegalArgumentException(editMessage));

        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(ACCT_ID_STR, CARD_NUM, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorMessage").value(editMessage))
                // the operator's search keys are echoed back to the redisplayed screen
                .andExpect(jsonPath("$.accountId").value(ACCT_ID_STR))
                .andExpect(jsonPath("$.cardId").value(CARD_NUM))
                // no resolved card detail is rendered on an edit failure
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.expiryYear").doesNotExist())
                .andExpect(jsonPath("$.expiryMonth").doesNotExist());
    }

    // ========================================================================
    // D. POST ENTER - card not found -> 404
    // ========================================================================

    /**
     * When the service raises {@link RecordNotFoundException} (COBOL/CICS
     * {@code NOTFND} / {@code FILE STATUS '23'}) the controller leaves it uncaught,
     * so {@code GlobalExceptionHandler} maps it to {@code 404 Not Found} as an
     * RFC&nbsp;7807 {@code application/problem+json} body with the {@code "Record
     * Not Found"} title &mdash; preserving the legacy caller-visible outcome.
     */
    @Test
    @WithMockUser
    @DisplayName("D. POST ENTER propagates a not-found read as 404 problem+json")
    void postEnterReturns404WhenCardNotFound() throws Exception {
        when(cardService.viewCard(ACCT_ID, CARD_NUM))
                .thenThrow(RecordNotFoundException.of("Card", CARD_NUM));

        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(ACCT_ID_STR, CARD_NUM, PfKeyAction.ENTER))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Record Not Found"));
    }

    // ========================================================================
    // E. CVV never present (MANDATORY)
    // ========================================================================

    /**
     * The card verification value must never leak onto the view screen
     * (AAP&nbsp;&sect;0.7.3, &sect;0.9.3). Three independent guards are asserted for
     * the found case:
     * <ol>
     *   <li><strong>Static</strong> &mdash; {@link CardViewResponse} declares no
     *       {@code cvv} record component, so the type itself cannot carry it.</li>
     *   <li><strong>Structural</strong> &mdash; the serialized JSON tree contains
     *       no field named {@code cvv} at any depth.</li>
     *   <li><strong>Value</strong> &mdash; the fixture {@link #CVV} string does not
     *       appear anywhere in the raw response body.</li>
     * </ol>
     */
    @Test
    @WithMockUser
    @DisplayName("E. CVV is never a response property and never appears in the body")
    void cardVerificationValueNeverPresentInResponse() throws Exception {
        when(cardService.viewCard(ACCT_ID, CARD_NUM)).thenReturn(newCard());

        MvcResult result = mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(ACCT_ID_STR, CARD_NUM, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andReturn();

        // 1. Static: the response type cannot even model a CVV.
        List<String> components = Arrays.stream(CardViewResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContain("cvv");

        String body = result.getResponse().getContentAsString();

        // 2. Structural: no field named "cvv" anywhere in the JSON tree.
        JsonNode root = objectMapper.readTree(body);
        assertThat(containsFieldName(root, "cvv"))
                .as("response JSON must not expose a cvv property")
                .isFalse();

        // 3. Value: the fixture CVV string must not appear anywhere in the body.
        assertThat(body)
                .as("response body must not contain the card verification value")
                .doesNotContain(CVV);
    }

    // ========================================================================
    // F. POST PF3 - back navigation
    // ========================================================================

    /**
     * {@code PF3} reproduces the COBOL {@code EXEC CICS XCTL} back to the calling
     * program: when the caller supplied its from-program / from-transaction on the
     * request headers, the response carries them verbatim on the navigation
     * headers, and the service is never consulted (the exit key performs no read).
     */
    @Test
    @WithMockUser
    @DisplayName("F1. POST PF3 navigates back to the calling program from the from-headers")
    void postPf3NavigatesToCallerWhenFromHeadersPresent() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_FROM_PROGRAM, "COCRDLIC")
                        .header(HEADER_FROM_TRANSACTION, "CCLI")
                        .content(toJson(new CardViewRequest(null, null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, "COCRDLIC"))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, "CCLI"));

        verifyNoInteractions(cardService);
    }

    /**
     * {@code PF3} with no caller context defaults the navigation target to the main
     * menu ({@code COMEN01C} / {@code CM00}), exactly as the COBOL
     * {@code IF CDEMO-FROM-PROGRAM = LOW-VALUES OR SPACES} default. The service is
     * not consulted.
     */
    @Test
    @WithMockUser
    @DisplayName("F2. POST PF3 defaults navigation to the main menu when no from-headers are present")
    void postPf3NavigatesToMainMenuWhenNoFromHeaders() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(null, null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, MENU_PROGRAM))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, MENU_TRANSACTION));

        verifyNoInteractions(cardService);
    }

    // ========================================================================
    // G. Other / unmapped attention key
    // ========================================================================

    /**
     * An explicit attention key other than {@code ENTER} or {@code PF3} redisplays
     * the screen at {@code 200 OK} with the {@link #MSG_INVALID_KEY} message (COBOL
     * {@code CCDA-MSG-INVALID-KEY}) and performs no read.
     */
    @Test
    @WithMockUser
    @DisplayName("G. POST with an unmapped key redisplays the invalid-key message")
    void postUnmappedKeyRedisplaysInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest(ACCT_ID_STR, CARD_NUM, PfKeyAction.PF5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY));

        verifyNoInteractions(cardService);
    }

    // ========================================================================
    // H. Field-contract parity + request validation
    // ========================================================================

    /**
     * Field-contract parity: {@link CardViewResponse} preserves &mdash; in order
     * &mdash; the output field set of the {@code COCRDSL} symbolic map
     * ({@code CCRDSLAO}), and carries no {@code cvv} component. This is the record's
     * static contract, so it needs no HTTP round-trip.
     */
    @Test
    @DisplayName("H1. Response record preserves the COCRDSL output field contract")
    void responseFieldContractMatchesCopybook() {
        List<String> components = Arrays.stream(CardViewResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly(
                "transactionName",  // TRNNAMEO
                "title01",          // TITLE01O
                "currentDate",      // CURDATEO
                "programName",      // PGMNAMEO
                "title02",          // TITLE02O
                "currentTime",      // CURTIMEO
                "accountId",        // ACCTSIDO
                "cardId",           // CARDSIDO
                "cardName",         // CRDNAMEO
                "cardStatus",       // CRDSTCDO
                "expiryMonth",      // EXPMONO
                "expiryYear",       // EXPYEARO
                "infoMessage",      // INFOMSGO
                "errorMessage",     // ERRMSGO
                "functionKeys");    // FKEYSO
        assertThat(components).doesNotContain("cvv");
    }

    /**
     * A malformed account search key (non-numeric, violating the request DTO's
     * {@code @Pattern}) is rejected by Bean Validation before the handler body runs,
     * so {@code GlobalExceptionHandler} returns {@code 400 Bad Request} as
     * {@code application/problem+json} with the {@code "Validation Failed"} title,
     * and the service is never consulted.
     */
    @Test
    @WithMockUser
    @DisplayName("H2. POST ENTER with a malformed account key returns 400 problem+json")
    void postEnterWithMalformedAccountIdReturns400() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new CardViewRequest("12A", CARD_NUM, PfKeyAction.ENTER))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verifyNoInteractions(cardService);
    }
}
