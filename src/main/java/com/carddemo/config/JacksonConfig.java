package com.carddemo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central Jackson JSON serialization configuration for the CardDemo REST API.
 *
 * <p><strong>Purpose &amp; authority.</strong> This class is the JSON-contract guardian for the
 * COBOL&rarr;Java migration's <em>financial parity</em> requirement (AAP &sect;0.3.1
 * "{@code JacksonConfig.java (BigDecimal / LocalDate serialization)}", &sect;0.1.2 data-type
 * transformation rules, and &sect;0.7.1 financial-arithmetic parity). The legacy AWS CardDemo
 * application rendered its data through BMS 3270 character screens; that presentation tier is
 * <em>retired</em> in the Spring Boot monolith, so there was never a JSON serializer to "convert."
 * This is therefore pure framework infrastructure with no COBOL predecessor. Its sole job is to
 * guarantee that the migrated Java data types cross the REST/JSON boundary in a form that
 * faithfully represents the original COBOL fixed-point and date semantics.</p>
 *
 * <h2>What must serialize correctly, and why</h2>
 * <p>The COBOL&rarr;Java type mapping (AAP &sect;0.1.2) dictates the following JSON rules, each of
 * which is enforced by the {@link #jsonCustomizer()} bean below:</p>
 * <ul>
 *   <li><strong>Monetary values</strong> &mdash; packed-decimal money fields
 *       ({@code PIC S9(n)V99 COMP-3}; e.g. the account balance {@code ACCT-CURR-BAL S9(10)V99} and
 *       the transaction amount {@code TRAN-AMT S9(09)V99}) become {@link java.math.BigDecimal},
 *       persisted as {@code NUMERIC(12,2)}. On the wire they MUST render as JSON <em>numbers</em>
 *       (never quoted strings) in <em>plain decimal notation</em> with the two-decimal scale
 *       preserved &mdash; for example {@code 1234.50}, and never {@code 1.23450E3} (scientific
 *       notation) or {@code "1234.5"} (a string that also loses the trailing-zero scale). This is
 *       guaranteed by enabling {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN} (the
 *       non-deprecated replacement for the long-deprecated
 *       {@code SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN}; see {@link #jsonCustomizer()} for
 *       the rationale). Reproducing the COBOL fixed-point representation exactly is the heart of the
 *       migration's financial parity guarantee.</li>
 *   <li><strong>Dates</strong> &mdash; {@code PIC X(10)} date fields become {@link java.time.LocalDate}
 *       and MUST render as the ISO-8601 calendar string {@code "yyyy-MM-dd"} (e.g.
 *       {@code "2024-01-15"}).</li>
 *   <li><strong>Timestamps</strong> &mdash; the 26-character timestamp fields {@code TRAN-ORIG-TS}
 *       and {@code TRAN-PROC-TS} become {@link java.time.LocalDateTime} and MUST render as an
 *       ISO-8601 string (e.g. {@code "2024-01-15T10:30:00"}), <em>never</em> a numeric epoch value
 *       or a {@code [year, month, day, ...]} array. This is guaranteed by registering the JSR-310
 *       {@link JavaTimeModule} and disabling {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}.</li>
 *   <li><strong>Null suppression</strong> &mdash; absent values are omitted from responses via
 *       {@link JsonInclude.Include#NON_NULL}, keeping the REST contracts clean.</li>
 * </ul>
 *
 * <h2>Relationship to {@code application.yml}</h2>
 * <p>The shared {@code src/main/resources/application.yml} already declares two of these guarantees
 * declaratively under its {@code spring.jackson} block:</p>
 * <pre>
 *   spring:
 *     jackson:
 *       default-property-inclusion: non_null          # &rarr; JsonInclude.Include.NON_NULL
 *       serialization:
 *         write-dates-as-timestamps: false             # &rarr; disable WRITE_DATES_AS_TIMESTAMPS
 * </pre>
 * <p>This class makes those two guarantees explicit in code <em>and</em> adds the one rule that the
 * YAML block cannot express &mdash; plain-notation {@link java.math.BigDecimal} output via
 * {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN}. The code and the YAML are deliberately
 * kept in agreement; that {@code application.yml} block even carries a comment delegating final
 * BigDecimal serialization to this file.</p>
 *
 * <h2>Why a customizer rather than a replacement {@code ObjectMapper}</h2>
 * <p>This configuration contributes a {@link Jackson2ObjectMapperBuilderCustomizer} bean instead of
 * defining a competing {@code @Bean ObjectMapper}. Spring Boot applies every such customizer to the
 * <em>single</em> auto-configured {@code ObjectMapper} that backs Spring MVC's
 * {@code MappingJackson2HttpMessageConverter} &mdash; the same mapper used by every
 * {@code @RestController} and by the sibling {@code GlobalExceptionHandler}. The approach is
 * therefore purely <em>additive</em>: it layers the four CardDemo rules on top of Boot's sensible
 * defaults rather than discarding them, and it avoids the {@code NoUniqueBeanDefinitionException}
 * risk and the silent loss of auto-configuration that a wholesale {@code ObjectMapper} replacement
 * would invite.</p>
 *
 * <h2>Explicitly avoided choices</h2>
 * <ul>
 *   <li>{@link SerializationFeature#WRITE_NUMBERS_AS_STRINGS} is <strong>never</strong> enabled:
 *       monetary values must remain JSON numbers, not quoted strings.</li>
 *   <li>No full replacement {@code ObjectMapper} bean is defined (see above).</li>
 *   <li>No CVV/SSN field-level masking lives here. Sensitive-field suppression (the card CVV is
 *       never serialized; the SSN is exposed as last-four at most) is enforced in the {@code dto/} +
 *       {@code mapper/} layer and reinforced by {@code logback-spring.xml} per AAP &sect;0.6.8. This
 *       class is concerned only with data <em>type</em> and <em>format</em>, never with field-level
 *       redaction.</li>
 *   <li>{@link JsonInclude.Include#NON_NULL} is used deliberately &mdash; not {@code NON_ABSENT} or
 *       {@code NON_EMPTY} &mdash; to match the {@code application.yml} contract exactly.</li>
 * </ul>
 *
 * @see Jackson2ObjectMapperBuilderCustomizer
 * @see JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN
 * @see SerializationFeature#WRITE_DATES_AS_TIMESTAMPS
 * @see JavaTimeModule
 */
@Configuration
public class JacksonConfig {

    /**
     * Contributes the CardDemo JSON-serialization rules to Spring Boot's auto-configured
     * {@code ObjectMapper}.
     *
     * <p>Spring Boot collects every {@link Jackson2ObjectMapperBuilderCustomizer} bean and applies
     * it to the {@code Jackson2ObjectMapperBuilder} that produces the single web {@code ObjectMapper}.
     * The customizer returned here applies four rules, in order:</p>
     * <ol>
     *   <li><strong>JSR-310 support (additive)</strong> &mdash;
     *       {@code modulesToInstall(new JavaTimeModule())} ensures {@link java.time.LocalDate} and
     *       {@link java.time.LocalDateTime} are understood by Jackson.
     *       <p>The <em>additive</em> {@code modulesToInstall(..)} variant is chosen deliberately over
     *       the otherwise-similar {@code modules(..)} variant. Per Spring's contract,
     *       {@code modules(Module...)} sets a <em>complete, replacing</em> module list and thereby
     *       <em>disables</em> Boot's auto-detection of the other well-known Jackson modules on the
     *       classpath (notably {@code jackson-module-parameter-names}, required for binding immutable
     *       {@code record}-based DTOs by constructor-parameter name, and {@code jackson-datatype-jdk8}).
     *       {@code modulesToInstall(Module...)} instead registers the JSR-310 module <em>after</em>
     *       that auto-detection, preserving every default module while still documenting the JSR-310
     *       requirement explicitly. This keeps the customization strictly additive, exactly as a
     *       customizer is intended to be.</p></li>
     *   <li><strong>ISO date/time output</strong> &mdash;
     *       disabling {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} makes {@code java.time}
     *       values serialize as ISO-8601 strings rather than numeric timestamps or arrays. This
     *       mirrors {@code application.yml}'s {@code write-dates-as-timestamps: false}.</li>
     *   <li><strong>Plain BigDecimal output</strong> &mdash;
     *       enabling {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN} makes monetary
     *       {@link java.math.BigDecimal} values serialize in plain decimal notation with their scale
     *       intact (e.g. {@code 1234.50}, {@code 0.00}) instead of scientific notation. This is the
     *       rule that cannot be expressed through the {@code application.yml} {@code jackson} block
     *       and is the core financial-parity guarantee of this class.
     *       <p>The databind-level constant {@code SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN}
     *       has been <em>deprecated since Jackson 2.5</em>; its deprecation javadoc explicitly
     *       directs callers to the streaming {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN}
     *       used here, which is the exact generator feature the databind variant delegated to.
     *       Output is therefore identical, but this class targets the non-deprecated constant so the
     *       module compiles warning-free and stays future-proof. The feature was intentionally not
     *       migrated to {@code StreamWriteFeature}, so {@code JsonGenerator.Feature} remains its
     *       canonical, non-deprecated home in jackson-core 2.15.x.</p></li>
     *   <li><strong>Null suppression</strong> &mdash;
     *       {@link JsonInclude.Include#NON_NULL} omits null-valued properties from output, matching
     *       {@code application.yml}'s {@code default-property-inclusion: non_null}.</li>
     * </ol>
     *
     * @return a customizer that applies CardDemo's BigDecimal, {@code java.time}, and null-inclusion
     *         JSON rules to the shared web {@code ObjectMapper}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            // (1) JSR-310 (java.time) support, registered additively so Spring Boot's
            //     auto-detected well-known modules (parameter-names, jdk8, ...) are preserved.
            builder.modulesToInstall(new JavaTimeModule());

            // (2) LocalDate -> "yyyy-MM-dd" and LocalDateTime -> "yyyy-MM-ddTHH:mm:ss" ISO strings,
            //     never numeric epochs or [y, m, d, ...] arrays.
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // (3) BigDecimal money in plain decimal notation with scale preserved
            //     (e.g. 1234.50, 0.00) -- never scientific notation (1.23450E3). This is the
            //     financial-parity guarantee and the one rule application.yml cannot express.
            //
            //     NOTE ON THE FEATURE CONSTANT: the equivalent databind constant
            //     SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN has been DEPRECATED since Jackson
            //     2.5, and its own deprecation javadoc directs callers to the streaming-generator
            //     constant used here. The databind variant is a thin delegate that merely enables
            //     this very generator feature under the hood, so the JSON output is byte-for-byte
            //     identical -- we simply target the non-deprecated, future-proof constant directly.
            //     (JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN is NOT deprecated in the
            //     BOM-managed jackson-core 2.15.4, and was deliberately NOT moved to
            //     StreamWriteFeature, so it remains the canonical home for this behavior.)
            //     Jackson2ObjectMapperBuilder.featuresToEnable(Object...) accepts any Jackson
            //     feature enum, including JsonGenerator.Feature.
            builder.featuresToEnable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

            // (4) Omit null-valued properties from JSON responses (matches application.yml's
            //     default-property-inclusion: non_null). NON_NULL exactly -- not NON_ABSENT/NON_EMPTY.
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        };
    }
}
