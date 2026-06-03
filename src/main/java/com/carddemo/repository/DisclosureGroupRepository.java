package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DisclosureGroup} interest-rate reference data.
 *
 * <p>Replaces VSAM keyed access to the original {@code DISCGRP} file. The underlying
 * {@code disclosure_groups} table maps the 50-byte {@code DIS-GROUP-RECORD} layout from
 * {@code app/cpy/CVTRA02Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68), lines 4-10, whose key is the
 * concatenation of the account-group id, transaction-type code, and transaction-category
 * code:</p>
 * <pre>
 *   05 DIS-GROUP-KEY.
 *     10 DIS-ACCT-GROUP-ID  PIC X(10).   --&gt; accountGroupId (group_id VARCHAR(10))
 *     10 DIS-TRAN-TYPE-CD   PIC X(02).   --&gt; tranTypeCd     (type_cd  CHAR(2))
 *     10 DIS-TRAN-CAT-CD    PIC 9(04).   --&gt; tranCatCd      (cat_cd   CHAR(4))
 *   05 DIS-INT-RATE         PIC S9(04)V99. --&gt; disIntRate
 * </pre>
 *
 * <p><strong>Composite primary key (PR-15).</strong> Because the COBOL record is keyed on three
 * fields, the {@link DisclosureGroup} entity declares an {@code @EmbeddedId} of type
 * {@link DisclosureGroupId} (field order {@code group_id}, {@code type_cd}, {@code cat_cd},
 * mirroring the COBOL key concatenation). This interface therefore extends
 * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}: the inherited {@code findById},
 * {@code existsById}, and {@code deleteById} signatures all bind to the
 * {@link DisclosureGroupId} composite-key type.</p>
 *
 * <p><strong>PR-02 &mdash; DISCGRP DEFAULT fallback (CRITICAL).</strong> This repository is the
 * persistence foundation for the interest-rate lookup performed by {@code CBACT04C}
 * ({@code 1200-GET-INTEREST-RATE}, {@code app/cbl/CBACT04C.cbl} L415-L440) and its Java port
 * {@code InterestCalculationTasklet}. The original COBOL first reads {@code DISCGRP} keyed on the
 * account's group id; if the read returns file status {@code '23'} (record not found), it
 * retries with the literal group id {@code "DEFAULT"} before failing. The standard inherited
 * {@code findById(DisclosureGroupId)} supports <em>both</em> the exact lookup and the
 * {@code "DEFAULT"} fallback by simply constructing the appropriate key. The preferred
 * idiom chains the two reads with {@link java.util.Optional#or(java.util.function.Supplier)}
 * and surfaces a total miss via {@code orElseThrow} (AAP &sect;0.6.11):</p>
 * <pre>{@code
 *   DisclosureGroupId key = new DisclosureGroupId(groupId, typeCd, catCd);
 *   Optional<DisclosureGroup> dg = disclosureGroupRepository.findById(key)
 *       .or(() -> disclosureGroupRepository.findById(
 *           new DisclosureGroupId("DEFAULT", typeCd, catCd)));
 *   BigDecimal rate = dg
 *       .map(DisclosureGroup::getDisIntRate)
 *       .orElseThrow(() -> new DiscloseGroupNotFoundException(
 *           "No DEFAULT entry for type=" + typeCd + " cat=" + catCd));
 * }</pre>
 * <p>The {@code DFHRESP(NOTFND)} / status {@code '23'} branch maps to {@code Optional.empty()};
 * a miss on both the exact key and the {@code "DEFAULT"} key surfaces as a
 * {@link com.carddemo.exception.DiscloseGroupNotFoundException} in the service/tasklet layer.
 * The {@code "DEFAULT"} rows are seeded by {@code V3__seed_reference_data.sql}.</p>
 *
 * <p>This is immutable reference/lookup data, so the inherited {@code JpaRepository} operations
 * satisfy every consumer and no custom finder methods are required (AAP &sect;0.4.1.5). The
 * CICS/VSAM access verbs this interface supersedes:</p>
 * <ul>
 *   <li>{@code findById(DisclosureGroupId)} &mdash; replaces {@code EXEC CICS READ} with
 *       {@code RIDFLD} on the concatenated {@code (group, type, category)} key, including the
 *       {@code "DEFAULT"} fallback read.</li>
 *   <li>{@code findAll()} &mdash; replaces the sequential full-file scan during reference-data
 *       enumeration.</li>
 *   <li>{@code save(DisclosureGroup)} / {@code saveAll(Iterable)} &mdash; replace
 *       {@code EXEC CICS WRITE} during the initial reference-data load.</li>
 *   <li>{@code existsById(DisclosureGroupId)}, {@code count()},
 *       {@code deleteById(DisclosureGroupId)} &mdash; standard inherited maintenance
 *       operations.</li>
 * </ul>
 *
 * <p><strong>No optimistic locking.</strong> This lookup table carries no {@code @Version}
 * column (AAP &sect;0.3.3 restricts {@code @Version} to {@code Account}, {@code Card},
 * {@code Customer}, and {@code Transaction}): the rows are seeded once and are not subject to
 * concurrent modification.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic. Spring Data
 * uses standard {@code org.springframework.*} imports here (PR-28 reserves the
 * {@code jakarta.*} namespace for the entity's persistence annotations).</p>
 *
 * @see com.carddemo.entity.DisclosureGroup
 * @see com.carddemo.entity.DisclosureGroupId
 * @see com.carddemo.exception.DiscloseGroupNotFoundException
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
