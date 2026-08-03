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
package com.carddemo.user.repository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.user.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Testcontainers integration test verifying the VSAM ``USRSEC`` ->
 *     PostgreSQL ``security_users`` user-management migration through the Spring
 *     Data JPA {@code UserRepository}. Extends {@link AbstractIntegrationTest}
 *     to boot the user-service context against a singleton ``postgres:18``
 *     container (PostgreSQL only -- the user-service is stateless, no Redis);
 *     under the ``test`` profile the schema comes from the owning
 *     modules' committed Flyway migrations and Hibernate only validates the mapping
 *     (``ddl-auto: validate``); each scenario starts from an empty ``security_users``
 *     table and persists its own fixtures. Covers the keyed
 *     lookup, the existence check, the ascending ten-per-page listing, the
 *     forward (PF8) and backward (PF7) keyset cursors with boundary emptiness,
 *     and the count, update, and delete operations.
 */
class UserRepositoryTest extends AbstractIntegrationTest {

    /**
     * :purpose: Opaque ``{bcrypt}``-prefixed password placeholder stored on every
     *     fixture -- the same stored form the seed and ``UserService`` write; the
     *     value is never verified by this repository test.
     */
    private static final String PLACEHOLDER_PWD =
            "{bcrypt}$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm";

    /**
     * :purpose: Repository under test -- the Spring Data JPA re-platforming of
     *     the keyed and browse ``USRSEC`` VSAM access.
     */
    @Autowired
    private UserRepository userRepository;

    /**
     * :purpose: Start every scenario from an empty ``security_users`` table
     *     (the booted context commits each repository call and is not rolled
     *     back per test), keeping the fixtures deterministic across methods.
     */
    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    /**
     * :purpose: Build an unmanaged ``SecurityUser`` fixture via the no-arg
     *     constructor and setters.
     * :param id: the eight-character user id.
     * :param fname: the first name.
     * :param lname: the last name.
     * :param type: the single-character user type (``A`` or ``U``).
     * :output: a transient ``SecurityUser`` ready to persist.
     */
    private SecurityUser newUser(String id, String fname, String lname, String type) {
        SecurityUser user = new SecurityUser();
        user.setSecUsrId(id);
        user.setSecUsrFname(fname);
        user.setSecUsrLname(lname);
        user.setSecUsrPwd(PLACEHOLDER_PWD);
        user.setSecUsrType(type);
        return user;
    }

    /**
     * :purpose: Persist ids ``USER0001``..``USER000n`` (fixed eight-character,
     *     zero-padded, lexicographically sortable) so the paging and cursor
     *     assertions are deterministic.
     * :param count: the number of sequential users to persist.
     */
    private void seedUsers(int count) {
        for (int i = 1; i <= count; i++) {
            userRepository.save(newUser(String.format("USER%04d", i),
                    "FNAME" + i, "LNAME" + i, (i % 2 == 0) ? "A" : "U"));
        }
    }

    @Test
    @DisplayName("save then findBySecUsrId returns the user with every column intact")
    void saveThenFindByIdReturnsAllFields() {
        userRepository.save(newUser("USER0001", "LAWRENCE", "THOMAS", "U"));

        Optional<SecurityUser> found = userRepository.findBySecUsrId("USER0001");
        assertThat(found).isPresent();
        SecurityUser got = found.get();
        assertThat(got.getSecUsrId()).isEqualTo("USER0001");
        assertThat(got.getSecUsrFname()).isEqualTo("LAWRENCE");
        assertThat(got.getSecUsrLname()).isEqualTo("THOMAS");
        assertThat(got.getSecUsrType()).isEqualTo("U");
        assertThat(got.getSecUsrPwd()).isEqualTo(PLACEHOLDER_PWD);
    }

    @Test
    @DisplayName("existsBySecUsrId is true for a persisted id and false for an unknown id")
    void existsBySecUsrIdReflectsPresence() {
        userRepository.save(newUser("ADMIN001", "MARGARET", "GOLD", "A"));

        assertThat(userRepository.existsBySecUsrId("ADMIN001")).isTrue();
        assertThat(userRepository.existsBySecUsrId("NOPE9999")).isFalse();
    }

    @Test
    @DisplayName("findBySecUsrId returns an empty Optional for an unknown id")
    void findBySecUsrIdEmptyForUnknown() {
        assertThat(userRepository.findBySecUsrId("NOPE9999")).isEmpty();
    }

    @Test
    @DisplayName("findAllByOrderBySecUsrIdAsc returns 10 users per page ascending by id")
    void ascendingPageOrderingTenPerPage() {
        seedUsers(12);

        Page<SecurityUser> page0 = userRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(0, 10));
        assertThat(page0.getContent()).hasSize(10);
        assertThat(page0.getTotalElements()).isEqualTo(12L);
        assertThat(page0.getContent()).extracting(SecurityUser::getSecUsrId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");

        Page<SecurityUser> page1 = userRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(1, 10));
        assertThat(page1.getContent()).extracting(SecurityUser::getSecUsrId)
                .containsExactly("USER0011", "USER0012");
    }

    @Test
    @DisplayName("findBySecUsrIdGreaterThanOrderBySecUsrIdAsc returns the next forward block (PF8)")
    void forwardCursorReturnsNextBlockAscending() {
        seedUsers(12);

        List<SecurityUser> next = userRepository
                .findBySecUsrIdGreaterThanOrderBySecUsrIdAsc("USER0010", PageRequest.of(0, 10));
        assertThat(next).extracting(SecurityUser::getSecUsrId)
                .containsExactly("USER0011", "USER0012");
    }

    @Test
    @DisplayName("findBySecUsrIdLessThanOrderBySecUsrIdDesc returns the prior block descending (PF7)")
    void backwardCursorReturnsPriorBlockDescending() {
        seedUsers(12);

        List<SecurityUser> prev = userRepository
                .findBySecUsrIdLessThanOrderBySecUsrIdDesc("USER0003", PageRequest.of(0, 10));
        assertThat(prev).extracting(SecurityUser::getSecUsrId)
                .containsExactly("USER0002", "USER0001");
    }

    @Test
    @DisplayName("cursor past the last id and before the first id both return empty")
    void cursorBoundariesReturnEmpty() {
        seedUsers(12);

        assertThat(userRepository
                .findBySecUsrIdGreaterThanOrderBySecUsrIdAsc("USER0012", PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(userRepository
                .findBySecUsrIdLessThanOrderBySecUsrIdDesc("USER0001", PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("save updates an existing user and the change is read back")
    void saveUpdatesExistingUser() {
        userRepository.save(newUser("USER0001", "LAWRENCE", "THOMAS", "U"));

        SecurityUser user = userRepository.findBySecUsrId("USER0001").orElseThrow();
        user.setSecUsrLname("SMITH");
        user.setSecUsrType("A");
        userRepository.save(user);

        SecurityUser reread = userRepository.findBySecUsrId("USER0001").orElseThrow();
        assertThat(reread.getSecUsrLname()).isEqualTo("SMITH");
        assertThat(reread.getSecUsrType()).isEqualTo("A");
    }

    @Test
    @DisplayName("deleteById removes the user so exists is false and find is empty")
    void deleteByIdRemovesUser() {
        userRepository.save(newUser("USER0001", "LAWRENCE", "THOMAS", "U"));
        assertThat(userRepository.existsBySecUsrId("USER0001")).isTrue();

        userRepository.deleteById("USER0001");

        assertThat(userRepository.existsBySecUsrId("USER0001")).isFalse();
        assertThat(userRepository.findBySecUsrId("USER0001")).isEmpty();
    }

    @Test
    @DisplayName("count reflects the seeded rows and increments when a new user is added")
    void countReflectsSeededRowsAndAddIncrement() {
        seedUsers(5);
        assertThat(userRepository.count()).isEqualTo(5L);

        userRepository.save(newUser("USER0006", "NEW", "USER", "U"));
        assertThat(userRepository.count()).isEqualTo(6L);
    }
}
