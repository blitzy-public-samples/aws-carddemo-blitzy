/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.repository;

import com.aws.carddemo.account.domain.Account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository providing key-based persistence access for the
 * {@link Account} aggregate of the CardDemo Account Management microservice
 * (Feature&nbsp;F-003).
 *
 * <p>This interface is the modern, relational re-expression of the legacy VSAM
 * KSDS random access to the {@code ACCTFILE} dataset (defined with
 * {@code KEYS(11 0)}). It sits in the persistence layer of the module's layered
 * architecture ({@code controller -> service -> repository -> domain}) and is
 * constructor-injected into {@code AccountService}. Spring Data JPA generates
 * the runtime proxy implementation at startup, so no implementation class is
 * required; because this package lives under the
 * {@code com.aws.carddemo.account} component-scan root of the
 * {@code @SpringBootApplication}, the repository is discovered automatically and
 * <strong>no {@code @EnableJpaRepositories} declaration is needed</strong>.</p>
 *
 * <h2>Legacy behavior re-homed (extraction-by-reference; nothing is ported)</h2>
 * <ul>
 *   <li><strong>Read-by-key (account inquiry, {@code CAVW}).</strong> The
 *       inherited {@link JpaRepository#findById(Object)} corresponds to the keyed
 *       {@code EXEC CICS READ DATASET(ACCTFILE) RIDFLD(acct-id) INTO(ACCOUNT-RECORD)}
 *       in {@code 9300-GETACCTDATA-BYACCT} of {@code app/cbl/COACTVWC.cbl}
 *       (L774&ndash;L807). A present row yields a populated {@link java.util.Optional}
 *       (the legacy {@code DFHRESP(NORMAL)} path); an absent row yields an empty
 *       {@code Optional} (the legacy {@code DFHRESP(NOTFND)} path). Translating that
 *       empty result into an HTTP&nbsp;404 is the responsibility of the service and
 *       exception layers &mdash; <em>this repository never throws for a missing
 *       key</em>.</li>
 *   <li><strong>Update (account update, {@code CAUP}).</strong> The inherited
 *       {@link JpaRepository#save(Object)} corresponds to the
 *       {@code EXEC CICS READ ... UPDATE} followed by {@code EXEC CICS REWRITE}
 *       in {@code 9600-WRITE-PROCESSING} of {@code app/cbl/COACTUPC.cbl}
 *       (L3888&ndash;L4105).</li>
 *   <li><strong>Concurrency control.</strong> The legacy field-by-field
 *       before-image comparison in {@code 9700-CHECK-CHANGE-IN-REC} of
 *       {@code COACTUPC.cbl} (L4109&ndash;L4192) is replaced by JPA optimistic
 *       locking. Because {@link Account} declares a {@code @Version} column,
 *       {@code save} performs the version check automatically and raises
 *       {@code org.springframework.orm.ObjectOptimisticLockingFailureException}
 *       on a stale version, which the {@code GlobalExceptionHandler} maps to
 *       HTTP&nbsp;409. No code is required here for this &mdash; it is inherited
 *       behavior of {@code save} combined with the entity's {@code @Version}
 *       field.</li>
 * </ul>
 *
 * <p>Every data-access operation this slice needs ({@code findById},
 * {@code save}, plus {@code findAll}, {@code existsById}, etc.) is already
 * provided by {@link JpaRepository}; the slice requires no derived query methods,
 * so the interface body is intentionally empty.</p>
 *
 * <h2>Identifier type</h2>
 * <p>The id type parameter is {@link String}, matching {@code Account}'s
 * {@code @Id private String accountId}. The account key is the zero-padded
 * 11-digit {@code VARCHAR(11)} value (for example {@code 00000000001}); a numeric
 * id type would silently drop the leading zeros and break join compatibility with
 * the 11-digit {@code CARD-ACCT-ID} and {@code XREF-ACCT-ID} keys carried on the
 * card and cross-reference records. It must therefore never be {@code Long} or
 * {@code Integer}.</p>
 *
 * <h2>Security</h2>
 * <p>All access flows through Spring Data with bound parameters; this slice adds
 * no custom queries, so there is no SQL-injection surface by construction. Should
 * a query method ever be added, it must be a derived (method-name) query or a
 * {@code @Query} using named or positional bound parameters &mdash; never
 * string-concatenated SQL.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
}
