package com.carddemo.dto;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Generic, immutable pagination envelope that wraps a Spring Data {@link Page}
 * into a stable, framework-light JSON contract for every paginated REST response
 * in the CardDemo application (card lists, transaction lists, user lists, and so on).
 *
 * <h2>Purpose</h2>
 * <p>Spring Data's {@link Page} is a rich, framework-specific abstraction. Serializing it
 * directly leaks Hibernate/Spring internals and produces an unstable wire format that varies
 * across Spring versions. {@code PageResponse} is a deliberately small, decoupled
 * <em>tier-0</em> Data Transfer Object that exposes only the seven pagination attributes
 * REST clients actually need, yielding a predictable JSON shape:</p>
 *
 * <pre>{@code
 * {
 *   "content": [ ... ],
 *   "page": 0,
 *   "size": 7,
 *   "totalElements": 50,
 *   "totalPages": 8,
 *   "first": true,
 *   "last": false
 * }
 * }</pre>
 *
 * <p>Because this type is a Java&nbsp;17 {@code record}, Jackson (Spring Boot&nbsp;3.2 /
 * Jackson&nbsp;2.15+) serializes it natively: each record component name becomes a JSON key,
 * so no {@code @JsonProperty} annotations are required.</p>
 *
 * <h2>Tier-0 dependency contract</h2>
 * <p>This class intentionally depends on nothing from the {@code com.carddemo} application
 * tree. The only non-JDK type it references is {@link org.springframework.data.domain.Page}
 * (supplied by {@code spring-boot-starter-data-jpa}). Keeping it dependency-light lets any
 * layer — controllers, services, or mappers — reuse it as the single pagination contract
 * without creating package cycles.</p>
 *
 * <h2>Legacy lineage and page size</h2>
 * <p>The pagination concept originates in the legacy CICS program {@code COCRDLIC}, whose
 * 3270 card-list screen browsed exactly seven rows at a time
 * ({@code WS-MAX-SCREEN-LINES VALUE 7}; {@code STARTBR}/{@code READNEXT}). That fixed page
 * size of <strong>7</strong> is preserved in the migrated system, but it is enforced by the
 * <em>callers</em> that build {@code PageRequest.of(pageIndex, 7)} — never hardcoded here.
 * This envelope faithfully <em>reflects</em> whatever {@link Page#getSize() size} the source
 * {@link Page} reports; it neither clamps nor defaults the value, so it remains a fully
 * general-purpose contract reusable by any paginated endpoint.</p>
 *
 * @param <T>           the element type carried in {@link #content()}
 * @param content       the page's elements; mirrors {@link Page#getContent()}
 * @param page          the zero-based page index; mirrors {@link Page#getNumber()}
 * @param size          the page size actually used (e.g. {@code 7} for the legacy browses);
 *                      mirrors {@link Page#getSize()}
 * @param totalElements the total number of elements across all pages; mirrors
 *                      {@link Page#getTotalElements()}
 * @param totalPages    the total number of pages available; mirrors
 *                      {@link Page#getTotalPages()}
 * @param first         {@code true} if this is the first page; mirrors {@link Page#isFirst()}
 * @param last          {@code true} if this is the last page; mirrors {@link Page#isLast()}
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    /**
     * Maps a Spring Data {@link Page} of element type {@code T} onto a {@code PageResponse}
     * of the same element type, copying across every pagination attribute verbatim.
     *
     * <p>This is the primary, required factory used by controllers and services that already
     * hold a {@code Page} whose element type matches the desired API response type.</p>
     *
     * @param page the source page; must not be {@code null}
     * @param <T>  the element type carried by both the source page and the resulting envelope
     * @return an immutable {@code PageResponse} reflecting the source page's content and metadata
     * @throws NullPointerException if {@code page} is {@code null}
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        Objects.requireNonNull(page, "page must not be null");
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /**
     * Maps a Spring Data {@link Page} of element type {@code S} onto a {@code PageResponse}
     * of element type {@code T}, transforming each element with the supplied {@code mapper}
     * while preserving all pagination metadata.
     *
     * <p>This convenience factory lets a controller convert a {@code Page<Entity>} returned by
     * a repository directly into a {@code PageResponse<Dto>} in a single call, for example:</p>
     *
     * <pre>{@code
     * Page<Card> cards = cardRepository.findByCardAcctId(acctId, PageRequest.of(0, 7));
     * PageResponse<CardListItem> body = PageResponse.from(cards, cardMapper::toListItem);
     * }</pre>
     *
     * <p>The element transformation is delegated to {@link Page#map(Function)}, which applies
     * {@code mapper} to each element and returns a new {@code Page<T>} carrying the original
     * pagination metadata. The result is then funnelled through {@link #from(Page)} so the two
     * factories share a single mapping path.</p>
     *
     * @param page   the source page; must not be {@code null}
     * @param mapper the element-conversion function from {@code S} to {@code T}; must not be
     *               {@code null}
     * @param <S>    the element type carried by the source page
     * @param <T>    the element type carried by the resulting envelope
     * @return an immutable {@code PageResponse} whose content is the mapped elements and whose
     *         metadata mirrors the source page
     * @throws NullPointerException if {@code page} or {@code mapper} is {@code null}
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        Page<T> mapped = page.map(mapper);
        return from(mapped);
    }
}
