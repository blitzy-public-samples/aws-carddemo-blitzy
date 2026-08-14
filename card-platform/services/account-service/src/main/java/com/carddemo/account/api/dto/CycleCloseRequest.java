package com.carddemo.account.api.dto;

/**
 * Request body of the billing-cycle close operation, which declares no member at all.
 *
 * <p>The operation takes its whole input from the path. {@code app/cbl/CBACT04C.cbl:L353-L354}
 * moves zero into both billing-cycle accumulators of the account the batch program is holding, and
 * nothing in those two statements reads a submitted value. There is therefore no member to declare
 * here, and this record exists to say so in a form the framework enforces.
 *
 * <p>Saying so is the point. The route requires {@code application/json} as its cross-site request
 * forgery control, {@code config/SecurityConfig} and {@code config/CrossSiteRequestFilter} carry the
 * rest of that control, and {@code src/main/resources/openapi.yaml} publishes the body as an object
 * with {@code additionalProperties: false}. Reading no body at all left that published refusal
 * unenforced: an object carrying any property was accepted, and both accumulators were zeroed for a
 * caller that had described the request wrongly. Binding this record makes the published schema the
 * one the service applies, because {@code config/RequestJsonStrictnessConfig} refuses an undeclared
 * property and {@code api/AccountApiExceptionHandler} answers that refusal 400.
 *
 * <p>An empty object and an absent body are both accepted, because both describe the same request.
 * The route declares the body optional for that reason.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public record CycleCloseRequest() {
}
