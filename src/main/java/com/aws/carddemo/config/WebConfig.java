package com.aws.carddemo.config;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

/**
 * Spring MVC / Thymeleaf configuration for the CardDemo server-rendered screens
 * (BMS 24x80 {@literal ->} Thymeleaf).
 *
 * <p>The migrated web tier reproduces the original 3270/BMS screen contract one-for-one: every
 * legacy BMS map becomes a server-rendered Thymeleaf page rather than a REST/JSON endpoint or a
 * single-page application. The nine {@code @Controller} classes in the sibling
 * {@code com.aws.carddemo.web.controller} package return logical view names that are identical to
 * the original BMS map names &mdash; {@code COSGN00}, {@code COMEN01}, {@code COADM01},
 * {@code COACTVW}, {@code COACTUP}, {@code COCRDLI}, {@code COCRDSL}, {@code COCRDUP},
 * {@code COTRN00}, {@code COTRN01}, {@code COTRN02}, {@code COBIL00}, {@code CORPT00},
 * {@code COUSR00}, {@code COUSR01}, {@code COUSR02}, {@code COUSR03}. Spring Boot's Thymeleaf
 * auto-configuration resolves each of those names to
 * {@code src/main/resources/templates/<viewName>.html}, so this class deliberately does
 * <strong>not</strong> declare a {@code ViewResolver} or {@code TemplateEngine} &mdash; overriding
 * the auto-configured beans would only risk breaking template resolution.</p>
 *
 * <p>The one customization this class contributes is a <strong>fixed US locale</strong>. AWS
 * CardDemo is a US-only application and internationalization is explicitly out of scope for this
 * migration (see {@code docs/decision-log.md} and Technical Specification &sect;0.3.4). Pinning the
 * locale to {@link Locale#US} keeps date and number rendering aligned with the legacy US
 * conventions and guarantees that no request-driven locale negotiation is introduced. No
 * {@code MessageSource}/resource-bundle localization and no locale-change interceptor are wired,
 * because adding them would be feature expansion beyond the preserved COBOL behavior.</p>
 *
 * <p><strong>Origin:</strong> net-new infrastructure. Unlike the domain, service, repository, web
 * and batch classes produced by this migration, this configuration has no single originating COBOL
 * program or copybook; it exists solely to host the cross-cutting MVC concerns that support the
 * BMS&rarr;Thymeleaf screen contract. Migration rationale is recorded in
 * {@code docs/decision-log.md}, not in code comments.</p>
 *
 * <p>The class implements {@link WebMvcConfigurer} (whose methods are all {@code default}) to
 * document its role as the project's MVC customization hook and to provide a single, well-known
 * extension point should further server-rendered concerns need registering. No REST, CORS, JSON
 * message converters, static-resource handlers, or view-controller mappings are configured here, as
 * none are required by the server-rendered screen contract.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Provides the application's {@link LocaleResolver} as a {@link FixedLocaleResolver} pinned to
     * {@link Locale#US}.
     *
     * <p>The bean name must be {@code localeResolver}: Spring MVC's {@code DispatcherServlet}
     * looks the resolver up by this well-known name, and declaring it replaces Boot's default
     * {@code AcceptHeaderLocaleResolver}. Because AWS CardDemo is US-only and no i18n is in scope,
     * the locale is fixed rather than negotiated from the {@code Accept-Language} request header,
     * so every request renders dates, numbers and currency using US conventions &mdash; matching
     * the behavior of the original mainframe screens.</p>
     *
     * @return a {@link FixedLocaleResolver} that always resolves {@link Locale#US}
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.US);
    }
}
