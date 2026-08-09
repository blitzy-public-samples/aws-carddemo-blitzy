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
package com.carddemo.user.mapper;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Pure unit tests for {@link UserMapper} verifying field-by-field
 *     entity/DTO mapping for the admin user-CRUD flows. Confirms the password
 *     is never set by ``toEntity``, never touched by ``apply``, and never
 *     exposed by ``toResponse``/``toResponseList``, that the user id is
 *     immutable under ``apply``, that ``toResponseList`` is null-safe, and that
 *     the ``SEC-USR-TYPE`` code ('A'/'U') passes through unchanged.
 */
class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper();
    }

    @Test
    @DisplayName("toEntity maps id/first/last/type and leaves the password unset (null)")
    void toEntityMapsFieldsAndLeavesPasswordNull() {
        AddUserRequestDto request = new AddUserRequestDto();
        request.setUserId("USER0001");
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setUserType("U");

        SecurityUser entity = userMapper.toEntity(request);

        assertThat(entity).isNotNull();
        assertThat(entity.getSecUsrId()).isEqualTo("USER0001");
        assertThat(entity.getSecUsrFname()).isEqualTo("John");
        assertThat(entity.getSecUsrLname()).isEqualTo("Doe");
        assertThat(entity.getSecUsrType()).isEqualTo("U");
        assertThat(entity.getSecUsrPwd()).isNull();
    }

    @Test
    @DisplayName("apply updates name/type in place and leaves id and password untouched")
    void applyUpdatesMutableFieldsOnly() {
        SecurityUser entity = new SecurityUser();
        entity.setSecUsrId("ADMIN001");
        entity.setSecUsrFname("Old");
        entity.setSecUsrLname("Name");
        entity.setSecUsrPwd("$2a$hashvalue");
        entity.setSecUsrType("U");

        UpdateUserRequestDto request = new UpdateUserRequestDto();
        request.setFirstName("New");
        request.setLastName("Person");
        request.setUserType("A");

        userMapper.apply(request, entity);

        assertThat(entity.getSecUsrFname()).isEqualTo("New");
        assertThat(entity.getSecUsrLname()).isEqualTo("Person");
        assertThat(entity.getSecUsrType()).isEqualTo("A");
        assertThat(entity.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(entity.getSecUsrPwd()).isEqualTo("$2a$hashvalue");
    }

    @Test
    @DisplayName("toResponse maps all four response fields and exposes no password")
    void toResponseMapsFieldsWithoutPassword() {
        SecurityUser entity = new SecurityUser();
        entity.setSecUsrId("USER0002");
        entity.setSecUsrFname("Jane");
        entity.setSecUsrLname("Smith");
        entity.setSecUsrPwd("$2a$anotherhash");
        entity.setSecUsrType("A");

        UserResponseDto response = userMapper.toResponse(entity);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("USER0002");
        assertThat(response.getFirstName()).isEqualTo("Jane");
        assertThat(response.getLastName()).isEqualTo("Smith");
        assertThat(response.getUserType()).isEqualTo("A");
    }

    @Test
    @DisplayName("toResponseList returns an empty list for null input")
    void toResponseListNullInputYieldsEmptyList() {
        assertThat(userMapper.toResponseList(null)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("toResponseList returns an empty list for empty input")
    void toResponseListEmptyInputYieldsEmptyList() {
        assertThat(userMapper.toResponseList(List.of())).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("toResponseList maps multiple entities preserving order and fields")
    void toResponseListMapsMultipleEntitiesInOrder() {
        SecurityUser admin = new SecurityUser();
        admin.setSecUsrId("ADMIN001");
        admin.setSecUsrFname("Ada");
        admin.setSecUsrLname("Admin");
        admin.setSecUsrType("A");

        SecurityUser user = new SecurityUser();
        user.setSecUsrId("USER0003");
        user.setSecUsrFname("Uma");
        user.setSecUsrLname("User");
        user.setSecUsrType("U");

        List<UserResponseDto> result = userMapper.toResponseList(List.of(admin, user));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getUserId()).isEqualTo("ADMIN001");
        assertThat(result.get(0).getUserType()).isEqualTo("A");
        assertThat(result.get(1).getUserId()).isEqualTo("USER0003");
        assertThat(result.get(1).getUserType()).isEqualTo("U");
    }

    @Test
    @DisplayName("SEC-USR-TYPE code 'A'/'U' passes through unchanged (no ROLE_ mapping)")
    void userTypeCodePassesThroughRaw() {
        AddUserRequestDto adminRequest = new AddUserRequestDto();
        adminRequest.setUserId("ADMIN002");
        adminRequest.setFirstName("Root");
        adminRequest.setLastName("Admin");
        adminRequest.setUserType("A");

        SecurityUser entity = userMapper.toEntity(adminRequest);
        assertThat(entity.getSecUsrType()).isEqualTo("A");
        assertThat(entity.getSecUsrType()).doesNotContain("ROLE");

        UserResponseDto response = userMapper.toResponse(entity);
        assertThat(response.getUserType()).isEqualTo("A");
    }
    @Test
    @DisplayName("toEntity folds the user id to its canonical upper-case stored form")
    void toEntityFoldsUserIdToCanonicalForm() {
        // The id this mapper writes IS the primary key the sign-on path reads by, so an
        // un-normalized value here produces a user that cannot authenticate
        // [app/cbl/COSGN00C.cbl:L132].
        AddUserRequestDto request = new AddUserRequestDto();
        request.setUserId("  qat0001 ");
        request.setFirstName("Qa");
        request.setLastName("One");
        request.setUserType("U");

        SecurityUser entity = userMapper.toEntity(request);

        assertThat(entity.getSecUsrId()).isEqualTo("QAT0001");
    }
}
