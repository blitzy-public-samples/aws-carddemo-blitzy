package com.aws.carddemo.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.exception.RecordNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Full-stack render guard for the shared error screen (review finding #25).
 *
 * <p><strong>Finding #25.</strong> The {@link com.aws.carddemo.exception.GlobalExceptionHandler}
 * {@code @ControllerAdvice} returns {@code ModelAndView("error")} for every mapped FILE STATUS /
 * CICS RESP condition. Before the fix there was no {@code templates/error.html}, so the logical view
 * name {@code "error"} fell through to Spring Boot's generic <em>Whitelabel</em> error page instead
 * of the CardDemo terminal chrome. The fix added {@code templates/error.html}; this test proves it
 * actually renders.</p>
 *
 * <p><strong>Why this test exists separately.</strong>
 * {@link com.aws.carddemo.exception.GlobalExceptionHandlerTest} exhaustively proves the advice
 * selects the {@code "error"} view, the right model attributes and the right HTTP status for every
 * exception type - but it runs on a {@code standaloneSetup} whose default
 * {@code InternalResourceViewResolver} does <em>not</em> execute the real Thymeleaf template, so it
 * cannot prove the template renders. Conversely, after the GROUP A inline-re-display fixes every
 * business {@code NOTFND} now re-displays its own screen (COBOL {@code 1000-SEND-MAP} parity), so no
 * business route reaches the full-page handler with a not-found any more (the card-detail test that
 * used to cover this - {@code CardControllerIT.cardDetailNotFoundReDisplaysInline} - is now an inline
 * check). This test therefore drives a genuine mapped exception through the fully wired application
 * (real Thymeleaf, the shared {@code fragments/bms-palette} include, Spring Security) using a
 * throwaway test-only controller and asserts the rendered body is the {@code error.html} terminal
 * chrome - the {@code "Application Error"} title and the {@code PF3=Return to Sign On} recovery
 * link - proving {@code error.html}, not the Whitelabel page, is returned.</p>
 *
 * <p>The throwaway controller ({@link BoomConfig.BoomController}) is contributed only for this test
 * via a nested {@link TestConfiguration} and is not part of the production application; it is the
 * full-context analogue of {@code GlobalExceptionHandlerTest}'s {@code BoomController}.</p>
 *
 * @see com.aws.carddemo.exception.GlobalExceptionHandler
 * @see com.aws.carddemo.exception.GlobalExceptionHandlerTest
 */
@AutoConfigureMockMvc
class ErrorViewIT extends com.aws.carddemo.AbstractPostgresIntegrationTest {

    /**
     * Route of the throwaway controller used only to raise a mapped exception.
     *
     * <p>Deliberately nested under {@code /admin/**} so it is authorized by the production
     * {@link com.aws.carddemo.config.SecurityConfig} rule
     * {@code requestMatchers("/admin/**").hasAuthority(ROLE_ADMIN)} rather than being rejected by the
     * terminal {@code anyRequest().denyAll()}. The production security chain fails closed for every
     * un-enumerated path, so an un-prefixed test path would be denied (HTTP 403) before the controller
     * ran and the mapped exception would never be raised. Nesting under the already-enumerated admin
     * namespace lets the request reach the controller (with an admin principal) without weakening or
     * modifying production security. The {@code __test-only__} segment marks the path as test scaffolding.</p>
     */
    private static final String ROUTE_BOOM = "/admin/__test-only__/boom-notfound";

    /** The not-found message the throwaway controller raises; the template must echo it. */
    private static final String BOOM_MESSAGE = "Did not find this record (error-view render guard)";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Drives a mapped {@link RecordNotFoundException} through the fully wired application and asserts
     * the real {@code error.html} terminal chrome renders (not the Whitelabel page). The advice maps
     * {@link RecordNotFoundException} to HTTP {@code 404} + view {@code "error"}; the rendered body
     * must carry the static CardDemo chrome and the raised message on the {@code ERRMSGO} error line.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void globalExceptionHandlerRendersRealErrorTemplateNotWhitelabel() throws Exception {
        mockMvc.perform(get(ROUTE_BOOM))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().string(containsString("Application Error")))
                .andExpect(content().string(containsString("The request could not be completed")))
                .andExpect(content().string(containsString("PF3=Return to Sign On")))
                .andExpect(content().string(containsString(BOOM_MESSAGE)));
    }

    /**
     * Test-only configuration contributing a throwaway controller that always raises a mapped
     * exception, so the {@link com.aws.carddemo.exception.GlobalExceptionHandler} advice renders the
     * shared error view deterministically.
     */
    @TestConfiguration
    static class BoomConfig {

        /**
         * Throwaway controller: a single endpoint that always raises {@link RecordNotFoundException}.
         *
         * <p>Registered exactly once as a Spring bean by virtue of being a stereotype-annotated
         * member class of this {@link TestConfiguration} (Spring's configuration-class member
         * processing detects the {@code @Controller} candidate). It is deliberately <em>not</em>
         * also declared via an {@code @Bean} factory method: doing both would register the
         * {@code boom()} handler twice and fail context startup with an ambiguous-mapping error.</p>
         */
        @Controller
        static class BoomController {

            /** Always throws so the advice renders {@code templates/error.html}. */
            @GetMapping(ROUTE_BOOM)
            void boom() {
                throw new RecordNotFoundException(BOOM_MESSAGE);
            }
        }
    }
}
