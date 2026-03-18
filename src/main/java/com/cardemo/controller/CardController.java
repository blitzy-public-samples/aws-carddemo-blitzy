/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.controller;

import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.Card;
import com.cardemo.service.online.CreditCardDetailService;
import com.cardemo.service.online.CreditCardListService;
import com.cardemo.service.online.CreditCardUpdateService;
import com.cardemo.service.online.CreditCardUpdateService.CardUpdateRequest;

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for credit card operations — combines three CICS transactions
 * (CCLI, CCDL, CCUP) from COBOL programs COCRDLIC.cbl, COCRDSLC.cbl, and
 * COCRDUPC.cbl into a single REST resource at {@code /api/cards}.
 *
 * <p>This controller is the HTTP entry point for all credit card management
 * operations in the CardDemo application. It delegates all business logic to
 * the three injected service classes, handling only HTTP request/response
 * mapping and exception-to-HTTP-status translation.</p>
 *
 * <h2>Endpoint Mapping (← COBOL CICS Transactions)</h2>
 * <table>
 *   <tr><th>Endpoint</th><th>COBOL Source</th><th>Transaction</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code GET /api/cards}</td>
 *     <td>COCRDLIC.cbl — 0000-MAIN → 9000-READ-FORWARD / 9100-READ-BACKWARDS,
 *         9500-FILTER-RECORDS</td>
 *     <td>CCLI</td>
 *     <td>Paginated credit card list with account/card number filtering</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET /api/cards/{num}}</td>
 *     <td>COCRDSLC.cbl — 0000-MAIN → 9100-GETCARD-BYACCTCARD /
 *         9150-GETCARD-BYACCT</td>
 *     <td>CCDL</td>
 *     <td>Card detail view with primary key and AIX (alternate index) lookup</td>
 *   </tr>
 *   <tr>
 *     <td>{@code PUT /api/cards/{num}}</td>
 *     <td>COCRDUPC.cbl — 0000-MAIN → 1200-EDIT-MAP-INPUTS →
 *         9000-READ-DATA → REWRITE</td>
 *     <td>CCUP</td>
 *     <td>Card update with field validation and optimistic locking</td>
 *   </tr>
 * </table>
 *
 * <h2>BMS Map References</h2>
 * <ul>
 *   <li><strong>COCRDLI.bms</strong> — List screen with 7 repeating rows
 *       (CRDSEL1..7, ACCTNO1..7, CRDNUM1..7, CRDSTS1..7), page navigation</li>
 *   <li><strong>COCRDSL.bms</strong> — Detail/selection screen with ACCTSID,
 *       CARDSID, CRDNAME, EXPMON/EXPYEAR</li>
 *   <li><strong>COCRDUP.bms</strong> — Update screen with ACCTSID (POS=7,45
 *       LEN=11), CARDSID (POS=8,45 LEN=16), CRDNAME (LEN=50)</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Delegation only</strong> — All business logic resides in the
 *       three service classes. This controller contains zero business logic.</li>
 *   <li><strong>Stateless REST</strong> — The CICS pseudo-conversational model
 *       is mapped to stateless HTTP endpoints with no server-side session
 *       state.</li>
 *   <li><strong>No feature expansion</strong> — Only the three endpoints
 *       corresponding to the three COBOL programs are provided. No additional
 *       CRUD operations (create/delete) exist because the original COBOL
 *       application did not support them.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>{@link RecordNotFoundException} → HTTP 404 Not Found (VSAM STATUS '23',
 *       DFHRESP(NOTFND))</li>
 *   <li>{@link ValidationException} → HTTP 400 Bad Request (field validation
 *       from 1200-EDIT-MAP-INPUTS paragraphs)</li>
 *   <li>{@link OptimisticLockException} → HTTP 409 Conflict (CICS READ UPDATE →
 *       REWRITE concurrent modification)</li>
 * </ul>
 *
 * @see CreditCardListService
 * @see CreditCardDetailService
 * @see CreditCardUpdateService
 * @see Card
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs request entry/exit, error conditions (404, 400, 409), and
     * delegation tracing per AAP observability requirements.
     */
    private static final Logger logger = LoggerFactory.getLogger(CardController.class);

    /**
     * Credit card list service (← COCRDLIC.cbl, CICS transaction CCLI).
     * Provides paginated card browsing with account/card number filtering,
     * mapping COBOL STARTBR/READNEXT/READPREV and 9500-FILTER-RECORDS to
     * JPA {@link Page} pagination.
     */
    private final CreditCardListService creditCardListService;

    /**
     * Credit card detail service (← COCRDSLC.cbl, CICS transaction CCDL).
     * Provides card detail view with primary key read on CARDDAT and AIX
     * read on CARDAIX for account-based lookup, mapping paragraphs
     * 9100-GETCARD-BYACCTCARD and 9150-GETCARD-BYACCT.
     */
    private final CreditCardDetailService creditCardDetailService;

    /**
     * Credit card update service (← COCRDUPC.cbl, CICS transaction CCUP).
     * Provides card update with field validation (1230-EDIT-NAME through
     * 1260-EDIT-EXPIRY-YEAR) and JPA {@code @Version} optimistic locking
     * replacing CICS READ UPDATE → REWRITE pattern.
     */
    private final CreditCardUpdateService creditCardUpdateService;

    /**
     * Constructs the {@code CardController} with all required service dependencies.
     *
     * <p>Uses constructor injection (not {@code @Autowired} field injection)
     * for testability, immutability, and clear dependency declaration. All
     * three service dependencies are final and set once at construction time.
     * Each service maps to one COBOL CICS program.</p>
     *
     * @param creditCardListService   the card list service (← COCRDLIC.cbl)
     * @param creditCardDetailService the card detail service (← COCRDSLC.cbl)
     * @param creditCardUpdateService the card update service (← COCRDUPC.cbl)
     */
    public CardController(
            CreditCardListService creditCardListService,
            CreditCardDetailService creditCardDetailService,
            CreditCardUpdateService creditCardUpdateService) {
        this.creditCardListService = creditCardListService;
        this.creditCardDetailService = creditCardDetailService;
        this.creditCardUpdateService = creditCardUpdateService;
    }

    /**
     * Lists credit cards with optional filtering — maps to COCRDLIC.cbl
     * (CICS transaction CCLI).
     *
     * <p>This endpoint translates the COBOL card browse flow:</p>
     * <ol>
     *   <li>0000-MAIN (line 298) — entry point</li>
     *   <li>9000-READ-FORWARD / 9100-READ-BACKWARDS — STARTBR/READNEXT/READPREV
     *       browse on CARDDAT dataset, now mapped to JPA {@link Page} pagination</li>
     *   <li>9500-FILTER-RECORDS — account/card number filter enforcement
     *       ({@code CARD-ACCT-ID = WS-ACCTFILTER}) mapped to JPA query
     *       parameters</li>
     * </ol>
     *
     * <p>The COBOL BMS map COCRDLI displays 7 repeating rows per screen
     * (CRDSEL1..7). In the REST API, the default page size is 10 records
     * per page. The {@code size} parameter is accepted for API completeness
     * but the service layer controls the actual page size.</p>
     *
     * @param accountId optional account ID filter, maps to WS-ACCTFILTER
     *                  for account-based filtering via CARDAIX (VSAM AIX);
     *                  {@code null} to list all cards
     * @param cardNum   optional card number filter, maps to WS-CARDFILTER
     *                  for card number partial match; {@code null} for no filter
     * @param page      zero-based page number, maps to PF7 (backward) /
     *                  PF8 (forward) navigation; defaults to 0
     * @param size      requested page size (default 10); accepted for API
     *                  completeness — the service controls actual pagination
     * @return HTTP 200 with {@link Page} of {@link Card} entities;
     *         HTTP 400 if filter parameters are invalid
     */
    @GetMapping
    public ResponseEntity<?> listCards(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String cardNum,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        logger.info("Card list request: accountId='{}', cardNum='{}', page={}, size={}",
                accountId, cardNum, page, size);

        try {
            // Delegate to CreditCardListService.listCards — maps to COBOL
            // STARTBR/READNEXT/READPREV browse pattern with 9500-FILTER-RECORDS
            Page<Card> cards = creditCardListService.listCards(accountId, cardNum, page);

            logger.debug("Card list returned {} records (page {} of {})",
                    cards.getNumberOfElements(), cards.getNumber(), cards.getTotalPages());

            return ResponseEntity.ok(cards);

        } catch (ValidationException e) {
            // Maps to invalid filter parameter errors — e.g., malformed
            // account ID or card number filter values
            logger.warn("Validation failed for card list: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Retrieves credit card detail — maps to COCRDSLC.cbl
     * (CICS transaction CCDL).
     *
     * <p>This endpoint translates the COBOL card detail/selection flow:</p>
     * <ol>
     *   <li>0000-MAIN (line 248) — entry point</li>
     *   <li>9100-GETCARD-BYACCTCARD — primary key read on CARDDAT:
     *       {@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
     *       → {@code cardRepository.findById(num)}</li>
     *   <li>9150-GETCARD-BYACCT — AIX read on CARDAIX:
     *       {@code EXEC CICS READ DATASET('CARDAIX') RIDFLD(WS-CARD-RID-ACCTID)}
     *       → {@code cardRepository.findByAccountId(accountId)}</li>
     * </ol>
     *
     * <p>The card number is a 16-character string ({@code CARD-NUM PIC X(16)}),
     * not a numeric value. The path variable is treated as a string.</p>
     *
     * @param num       card number (16-character string), maps to CARDSID in
     *                  COCRDSL.bms; used as RIDFLD for primary key read
     * @param accountId optional account ID for AIX-based lookup via CARDAIX;
     *                  {@code null} for primary key lookup only
     * @return HTTP 200 with {@link Card} entity detail;
     *         HTTP 404 if the card is not found (VSAM STATUS '23',
     *         DFHRESP(NOTFND))
     */
    @GetMapping("/{num}")
    public ResponseEntity<?> viewCardDetail(
            @PathVariable String num,
            @RequestParam(required = false) String accountId) {

        logger.info("Card detail request: cardNum='{}', accountId='{}'", num, accountId);

        try {
            // Delegate to CreditCardDetailService.viewCardDetail — maps to
            // COBOL paragraphs 9100-GETCARD-BYACCTCARD (primary key) and
            // 9150-GETCARD-BYACCT (AIX read) from COCRDSLC.cbl
            Card card = creditCardDetailService.viewCardDetail(num, accountId);

            logger.debug("Card detail retrieved for cardNum='{}'", num);
            return ResponseEntity.ok(card);

        } catch (RecordNotFoundException e) {
            // Maps to COBOL DID-NOT-FIND-ACCTCARD-COMBO condition from
            // COCRDSLC.cbl — DFHRESP(NOTFND) on CARDDAT or CARDAIX read
            logger.warn("Card not found: cardNum='{}', accountId='{}': {}",
                    num, accountId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Updates a credit card — maps to COCRDUPC.cbl
     * (CICS transaction CCUP).
     *
     * <p>This endpoint translates the COBOL card update flow:</p>
     * <ol>
     *   <li>0000-MAIN (line 367) — entry point</li>
     *   <li>1200-EDIT-MAP-INPUTS — comprehensive field validation:
     *     <ul>
     *       <li>1230-EDIT-NAME — embossed name alphabetic check</li>
     *       <li>1240-EDIT-CARDSTATUS — active status 'Y' or 'N' only</li>
     *       <li>1250-EDIT-EXPIRY-MON — month validation (01–12)</li>
     *       <li>1260-EDIT-EXPIRY-YEAR — 4-digit year, not expired</li>
     *     </ul>
     *   </li>
     *   <li>9000-READ-DATA — READ UPDATE with lock:
     *       {@code EXEC CICS READ UPDATE DATASET('CARDDAT')} → JPA
     *       {@code @Version} optimistic locking</li>
     *   <li>REWRITE — {@code cardRepository.save(card)} with version check</li>
     * </ol>
     *
     * <p>The card number path variable is a 16-character string
     * ({@code CARD-NUM PIC X(16)}). The request body contains the updatable
     * fields from the COCRDUP.bms map.</p>
     *
     * @param num     card number (16-character string), maps to CARDSID in
     *                COCRDUP.bms; identifies the card to update
     * @param request the update request containing fields to modify:
     *                embossedName (PIC X(50)), activeStatus ('Y'/'N'),
     *                expiryMonth (01–12), expiryYear (4-digit year)
     * @return HTTP 200 with updated {@link Card} entity;
     *         HTTP 400 if validation fails (invalid fields);
     *         HTTP 404 if the card is not found;
     *         HTTP 409 if concurrent modification detected (optimistic lock)
     */
    @PutMapping("/{num}")
    public ResponseEntity<?> updateCard(
            @PathVariable String num,
            @RequestBody CardUpdateRequest request) {

        logger.info("Card update request: cardNum='{}'", num);

        try {
            // Delegate to CreditCardUpdateService.updateCard — maps to COBOL
            // 1200-EDIT-MAP-INPUTS (validation) → 9000-READ-DATA (read with
            // lock) → REWRITE from COCRDUPC.cbl
            Card updatedCard = creditCardUpdateService.updateCard(num, request);

            logger.info("Card updated successfully: cardNum='{}'", num);
            return ResponseEntity.ok(updatedCard);

        } catch (RecordNotFoundException e) {
            // Maps to DFHRESP(NOTFND) on CARDDAT read in COCRDUPC.cbl —
            // the card identified by the path variable does not exist
            logger.warn("Card not found for update: cardNum='{}': {}",
                    num, e.getMessage());
            return ResponseEntity.notFound().build();

        } catch (ValidationException e) {
            // Maps to validation failures from 1200-EDIT-MAP-INPUTS:
            // 1230-EDIT-NAME, 1240-EDIT-CARDSTATUS, 1250-EDIT-EXPIRY-MON,
            // 1260-EDIT-EXPIRY-YEAR — field-level errors returned as 400
            logger.warn("Validation failed for card update: cardNum='{}': {}",
                    num, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (OptimisticLockException e) {
            // Maps to CICS READ UPDATE → REWRITE concurrent access failure —
            // two users attempted to update the same card simultaneously.
            // The JPA @Version field on Card entity triggers this exception.
            logger.error("Concurrent modification on card update: cardNum='{}'",
                    num);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error",
                            "Card was modified by another user. Please refresh and retry."));
        }
    }
}
