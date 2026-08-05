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
package com.carddemo.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserListResponseDto;
import com.carddemo.common.dto.UserResponseDto;
import com.carddemo.common.dto.UserWriteResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.security.SessionPrincipalIndex;
import com.carddemo.user.mapper.UserMapper;
import com.carddemo.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * :purpose: Pure Mockito unit tests for {@link UserService} verifying spec-literal
 *     fidelity of the administrator-only user-CRUD flows re-platformed from the legacy
 *     COBOL programs ``COUSR00C``/``COUSR01C``/``COUSR02C``/``COUSR03C``: field-validation
 *     order, byte-exact outcome messages, domain exception types, credential encoding, and
 *     collaborator interactions. Every collaborator is a Mockito mock; no application
 *     context is started and no external infrastructure is required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — COUSR00C–COUSR03C admin user CRUD (spec-literal fidelity)")
class UserServiceTest {

    // ---- verbatim COBOL messages (byte-exact; see COUSR00C-COUSR03C.cbl) ----
    private static final String MSG_FIRST_EMPTY = "First Name can NOT be empty...";
    private static final String MSG_LAST_EMPTY = "Last Name can NOT be empty...";
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";
    private static final String MSG_PWD_EMPTY = "Password can NOT be empty...";
    private static final String MSG_TYPE_EMPTY = "User Type can NOT be empty...";
    private static final String MSG_DUP = "User ID already exist...";
    private static final String MSG_TYPE_INVALID = "User Type must be A or U";
    private static final String MSG_ADD_ERR = "Unable to Add User...";
    private static final String MSG_NOT_FOUND = "User ID NOT found...";
    private static final String MSG_LOOKUP_ERR = "Unable to lookup User...";
    private static final String MSG_UPDATE_ERR = "Unable to Update User...";
    private static final String MSG_NO_CHANGE = "Please modify to update ...";
    private static final String MSG_INVALID_SEL = "Invalid selection. Valid values are U and D";
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SessionPrincipalIndex sessionPrincipalIndex;

    @InjectMocks
    private UserService userService;

    private SecurityUser storedUser;

    /**
     * :purpose: Build a fresh managed-user fixture before each test so mutations never
     *     leak across tests. No mock stubbing is performed here (strict-stubbing safe).
     */
    @BeforeEach
    void setUp() {
        storedUser = user("USER0001", "John", "Doe", "U", "$2a$storedHash");
    }

    // ---- private in-class builders (no separate fixture files per scope discipline) ----

    private static AddUserRequestDto addReq(String userId, String first, String last, String type) {
        AddUserRequestDto dto = new AddUserRequestDto();
        dto.setUserId(userId);
        dto.setFirstName(first);
        dto.setLastName(last);
        dto.setUserType(type);
        return dto;
    }

    /**
     * :purpose: Carry the raw password on the request DTO, which is where it now travels: a
     *     credential must never be accepted as a query parameter because a URL is recorded
     *     verbatim by access logs and tracing spans.
     * :param dto: the request fixture to complete.
     * :param password: the raw password to carry.
     * :returns: the same fixture, for inline use.
     */
    private static AddUserRequestDto withPwd(AddUserRequestDto dto, String password) {
        dto.setPassword(password);
        return dto;
    }

    /**
     * :purpose: Carry the raw password on the update request DTO.
     * :param dto: the request fixture to complete.
     * :param password: the raw password to carry.
     * :returns: the same fixture, for inline use.
     */
    private static UpdateUserRequestDto withPwd(UpdateUserRequestDto dto, String password) {
        dto.setPassword(password);
        return dto;
    }

    private static UpdateUserRequestDto updReq(String first, String last, String type) {
        UpdateUserRequestDto dto = new UpdateUserRequestDto();
        dto.setFirstName(first);
        dto.setLastName(last);
        dto.setUserType(type);
        return dto;
    }

    private static SecurityUser user(String id, String first, String last, String type, String pwd) {
        SecurityUser entity = new SecurityUser();
        entity.setSecUsrId(id);
        entity.setSecUsrFname(first);
        entity.setSecUsrLname(last);
        entity.setSecUsrType(type);
        entity.setSecUsrPwd(pwd);
        return entity;
    }

    private static UserResponseDto respDto(String id, String first, String last, String type) {
        UserResponseDto dto = new UserResponseDto();
        dto.setUserId(id);
        dto.setFirstName(first);
        dto.setLastName(last);
        dto.setUserType(type);
        return dto;
    }

    private static List<SecurityUser> usersList(int count) {
        List<SecurityUser> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(user(String.format("USER%04d", i), "First" + i, "Last" + i, "U", "$2a$hash" + i));
        }
        return list;
    }

    private static List<UserResponseDto> dtosList(int count) {
        List<UserResponseDto> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(respDto(String.format("USER%04d", i), "First" + i, "Last" + i, "U"));
        }
        return list;
    }

    // ================================================================
    // getUser (COUSR02C/COUSR03C keyed read)
    // ================================================================

    @Test
    @DisplayName("getUser returns the mapped DTO for an existing user id")
    void getUser_found_returnsDto() {
        UserResponseDto expected = respDto("USER0001", "John", "Doe", "U");
        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(userMapper.toResponse(storedUser)).thenReturn(expected);

        UserResponseDto result = userService.getUser("USER0001");

        assertThat(result).isSameAs(expected);
        verify(userMapper).toResponse(storedUser);
    }

    @Test
    @DisplayName("getUser throws RecordNotFoundException with 'User ID NOT found...' when absent")
    void getUser_notFound_throwsRecordNotFound() {
        when(userRepository.findBySecUsrId("MISSING1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser("MISSING1"))
                .isExactlyInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);

        verify(userMapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("getUser wraps a data-access failure as CardDemoException 'Unable to lookup User...'")
    void getUser_lookupError_throwsCardDemoException() {
        when(userRepository.findBySecUsrId("USER0001"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> userService.getUser("USER0001"))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_LOOKUP_ERR);

        verify(userMapper, never()).toResponse(any());
    }

    // ================================================================
    // addUser (COUSR01C / CU01) — order First -> Last -> UserID -> Password -> Type
    // ================================================================

    @Test
    @DisplayName("addUser encodes the password, persists the hash (never plaintext), and returns the DTO")
    void addUser_success_encodesPasswordAndSaves() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");
        SecurityUser entity = user("USER0001", "John", "Doe", "U", null);
        SecurityUser savedUser = user("USER0001", "John", "Doe", "U", "$2a$hash");
        UserResponseDto expected = respDto("USER0001", "John", "Doe", "U");

        when(userRepository.existsBySecUsrId("USER0001")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(entity);
        when(passwordEncoder.encode("rawPass")).thenReturn("$2a$hash");
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);

        UserWriteResponseDto result = userService.addUser(withPwd(request, "rawPass"));

        // COUSR01C reports 'User <id> has been added ...' on the screen, so the write
        // response carries that verbatim message alongside the persisted projection.
        assertThat(result.getUserId()).isEqualTo(savedUser.getSecUsrId());
        assertThat(result.getMessage()).isEqualTo("User USER0001 has been added ...");
        verify(passwordEncoder).encode("rawPass");

        ArgumentCaptor<SecurityUser> captor = ArgumentCaptor.forClass(SecurityUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSecUsrPwd()).isEqualTo("$2a$hash");
        assertThat(captor.getValue().getSecUsrPwd()).isNotEqualTo("rawPass");
    }

    @Test
    @DisplayName("addUser rejects an empty first name with 'First Name can NOT be empty...'")
    void addUser_firstNameEmpty_throwsFirstEmpty() {
        AddUserRequestDto request = addReq("USER0001", "", "Doe", "U");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_FIRST_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser rejects an empty last name with 'Last Name can NOT be empty...'")
    void addUser_lastNameEmpty_throwsLastEmpty() {
        AddUserRequestDto request = addReq("USER0001", "John", "  ", "U");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_LAST_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser rejects an empty user id with 'User ID can NOT be empty...'")
    void addUser_userIdEmpty_throwsUserIdEmpty() {
        AddUserRequestDto request = addReq("", "John", "Doe", "U");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_USERID_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser rejects an empty password with 'Password can NOT be empty...'")
    void addUser_passwordEmpty_throwsPasswordEmpty() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_PWD_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser rejects an empty user type with 'User Type can NOT be empty...'")
    void addUser_userTypeEmpty_throwsTypeEmpty() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TYPE_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser rejects a duplicate user id with 'User ID already exist...' and never saves")
    void addUser_duplicateId_throwsAlreadyExist() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");
        when(userRepository.existsBySecUsrId("USER0001")).thenReturn(true);

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_DUP);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder, userMapper);
    }

    @Test
    @DisplayName("addUser validates First before Last (both empty reports First first)")
    void addUser_orderFirstBeforeLast_firstReportedFirst() {
        AddUserRequestDto request = addReq("USER0001", "", "", "U");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_FIRST_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser wraps a save failure as CardDemoException 'Unable to Add User...'")
    void addUser_saveError_throwsUnableToAdd() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");
        SecurityUser entity = user("USER0001", "John", "Doe", "U", null);

        when(userRepository.existsBySecUsrId("USER0001")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(entity);
        when(passwordEncoder.encode("rawPass")).thenReturn("$2a$hash");
        when(userRepository.saveAndFlush(any(SecurityUser.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_ADD_ERR);

        verify(userMapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("addUser rejects a user type outside {A,U} with 'User Type must be A or U' and never saves")
    void addUser_unknownUserType_throwsInvalidType() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "X");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TYPE_INVALID);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser reports the empty-type literal before the value-set edit")
    void addUser_blankUserType_reportsEmptyLiteralNotValueSet() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", " ");

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TYPE_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("addUser translates a concurrent primary-key violation to 'User ID already exist...'")
    void addUser_concurrentDuplicateKey_throwsAlreadyExist() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");
        SecurityUser entity = user("USER0001", "John", "Doe", "U", null);

        when(userRepository.existsBySecUsrId("USER0001")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(entity);
        when(passwordEncoder.encode("rawPass")).thenReturn("$2a$hash");
        // The writer that lost the race sees the constraint, not a generic failure.
        when(userRepository.saveAndFlush(any(SecurityUser.class)))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_DUP);
    }

    @Test
    @DisplayName("addUser reports 'Unable to Add User...' for an integrity failure that is NOT a duplicate key")
    void addUser_otherIntegrityFailure_throwsUnableToAdd() {
        AddUserRequestDto request = addReq("USER0001", "John", "Doe", "U");
        SecurityUser entity = user("USER0001", "John", "Doe", "U", null);

        when(userRepository.existsBySecUsrId("USER0001")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(entity);
        when(passwordEncoder.encode("rawPass")).thenReturn("$2a$hash");
        when(userRepository.saveAndFlush(any(SecurityUser.class)))
                .thenThrow(new DataIntegrityViolationException("check constraint violated"));

        assertThatThrownBy(() -> userService.addUser(withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_ADD_ERR);
    }

    // ================================================================
    // updateUser (COUSR02C / CU02) — order UserID -> First -> Last -> Password -> Type
    // ================================================================

    @Test
    @DisplayName("updateUser persists and re-hashes the password when every field changed")
    void updateUser_changedFields_persistsAndReHashesPassword() {
        UpdateUserRequestDto request = updReq("Johnny", "Doer", "A");
        SecurityUser savedUser = user("USER0001", "Johnny", "Doer", "A", "$2a$new");
        UserResponseDto expected = respDto("USER0001", "Johnny", "Doer", "A");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("newRaw", "$2a$storedHash")).thenReturn(false);
        when(passwordEncoder.encode("newRaw")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);

        UserWriteResponseDto result = userService.updateUser("USER0001", withPwd(request, "newRaw"));

        assertThat(result.getUserId()).isEqualTo(savedUser.getSecUsrId());
        assertThat(result.getMessage()).isEqualTo("User USER0001 has been updated ...");
        verify(passwordEncoder).encode("newRaw");

        ArgumentCaptor<SecurityUser> captor = ArgumentCaptor.forClass(SecurityUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSecUsrPwd()).isEqualTo("$2a$new");
    }

    @Test
    @DisplayName("updateUser saves but does NOT re-hash when only a non-password field changed")
    void updateUser_changedNonPasswordOnly_doesNotReHash() {
        UpdateUserRequestDto request = updReq("Johnny", "Doe", "U");
        SecurityUser savedUser = user("USER0001", "Johnny", "Doe", "U", "$2a$storedHash");
        UserResponseDto expected = respDto("USER0001", "Johnny", "Doe", "U");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("samePass", "$2a$storedHash")).thenReturn(true);
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);

        UserWriteResponseDto result = userService.updateUser("USER0001", withPwd(request, "samePass"));

        assertThat(result.getUserId()).isEqualTo(savedUser.getSecUsrId());
        assertThat(result.getMessage()).isEqualTo("User USER0001 has been updated ...");
        verify(userRepository).saveAndFlush(any(SecurityUser.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser with no change does NOT persist and reports 'Please modify to update ...'")
    void updateUser_noChange_doesNotPersist_surfacesPleaseModify() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("samePass", "$2a$storedHash")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "samePass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_NO_CHANGE);

        verify(userRepository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("updateUser rejects an empty user id with 'User ID can NOT be empty...'")
    void updateUser_userIdEmpty_throwsUserIdEmpty() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");

        assertThatThrownBy(() -> userService.updateUser("", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_USERID_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects an empty first name with 'First Name can NOT be empty...'")
    void updateUser_firstNameEmpty_throwsFirstEmpty() {
        UpdateUserRequestDto request = updReq("", "Doe", "U");

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_FIRST_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects an empty last name with 'Last Name can NOT be empty...'")
    void updateUser_lastNameEmpty_throwsLastEmpty() {
        UpdateUserRequestDto request = updReq("John", "", "U");

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_LAST_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects an empty password with 'Password can NOT be empty...'")
    void updateUser_passwordEmpty_throwsPasswordEmpty() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_PWD_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser rejects an empty user type with 'User Type can NOT be empty...'")
    void updateUser_userTypeEmpty_throwsTypeEmpty() {
        UpdateUserRequestDto request = updReq("John", "Doe", "");

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TYPE_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser validates UserID before First (both empty reports UserID — differs from add)")
    void updateUser_orderUserIdBeforeFirst_userIdReportedFirst() {
        UpdateUserRequestDto request = updReq("", "Doe", "U");

        assertThatThrownBy(() -> userService.updateUser("", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_USERID_EMPTY);

        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser throws RecordNotFoundException 'User ID NOT found...' when the user is absent")
    void updateUser_notFound_throwsNotFound() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");
        when(userRepository.findBySecUsrId("MISSING1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser("MISSING1", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder, userMapper);
    }

    @Test
    @DisplayName("updateUser maps a concurrent modification to the legacy conflict outcome")
    void updateUser_optimisticLockFailure_throwsConflict() {
        UpdateUserRequestDto request = updReq("Johnny", "Doer", "A");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("newRaw", "$2a$storedHash")).thenReturn(false);
        when(passwordEncoder.encode("newRaw")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(SecurityUser.class, "USER0001"));

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "newRaw")))
                .isInstanceOf(OptimisticLockConflictException.class)
                .hasMessage("Record changed by some one else. Please review");

        verify(userMapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("updateUser rejects a user type outside {A,U} with 'User Type must be A or U'")
    void updateUser_unknownUserType_throwsInvalidType() {
        UpdateUserRequestDto request = updReq("Johnny", "Doer", "Z");

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "rawPass")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TYPE_INVALID);

        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    @DisplayName("updateUser wraps a save failure as CardDemoException 'Unable to Update User...'")
    void updateUser_saveError_throwsUnableToUpdate() {
        UpdateUserRequestDto request = updReq("Johnny", "Doer", "A");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("newRaw", "$2a$storedHash")).thenReturn(false);
        when(passwordEncoder.encode("newRaw")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "newRaw")))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_UPDATE_ERR);

        verify(userMapper, never()).toResponse(any());
    }

    // ================================================================
    // deleteUser (COUSR03C / CU03)
    // ================================================================

    @Test
    @DisplayName("deleteUser deletes the entity when the user id exists")
    void deleteUser_found_deletes() {
        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));

        userService.deleteUser("USER0001");

        verify(userRepository).delete(storedUser);
    }

    @Test
    @DisplayName("deleteUser throws RecordNotFoundException 'User ID NOT found...' when absent and never deletes")
    void deleteUser_notFound_throwsNotFound() {
        when(userRepository.findBySecUsrId("MISSING1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser("MISSING1"))
                .isExactlyInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND);

        verify(userRepository, never()).delete(any(SecurityUser.class));
    }

    @Test
    @DisplayName("deleteUser reuses the legacy 'Unable to Update User...' message on a delete failure")
    void deleteUser_repositoryError_reusesUnableToUpdateMessage() {
        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(userRepository).delete(storedUser);

        assertThatThrownBy(() -> userService.deleteUser("USER0001"))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_UPDATE_ERR);
    }

    // ================================================================
    // Live-session revocation on privilege change and deletion
    // (no legacy analogue: the 3270 re-read USRSEC on every transaction,
    //  so maintenance took effect immediately)
    // ================================================================

    @Test
    @DisplayName("deleteUser revokes every live session of the deleted user as USER_DELETED")
    void deleteUser_found_revokesLiveSessions() {
        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));

        userService.deleteUser("USER0001");

        verify(sessionPrincipalIndex).revokeSessions("USER0001", "USER_DELETED");
    }

    @Test
    @DisplayName("deleteUser does NOT revoke sessions when the user id does not exist")
    void deleteUser_notFound_doesNotRevokeSessions() {
        when(userRepository.findBySecUsrId("MISSING1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser("MISSING1"))
                .isExactlyInstanceOf(RecordNotFoundException.class);

        verifyNoInteractions(sessionPrincipalIndex);
    }

    @Test
    @DisplayName("deleteUser does NOT revoke sessions when the delete itself fails")
    void deleteUser_repositoryError_doesNotRevokeSessions() {
        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(userRepository).delete(storedUser);

        assertThatThrownBy(() -> userService.deleteUser("USER0001"))
                .isExactlyInstanceOf(CardDemoException.class);

        verifyNoInteractions(sessionPrincipalIndex);
    }

    @Test
    @DisplayName("updateUser revokes sessions as ROLE_CHANGED when SEC-USR-TYPE changes")
    void updateUser_roleChanged_revokesLiveSessions() {
        UpdateUserRequestDto request = updReq("John", "Doe", "A");
        SecurityUser savedUser = user("USER0001", "John", "Doe", "A", "$2a$storedHash");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("samePass", "$2a$storedHash")).thenReturn(true);
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);


        userService.updateUser("USER0001", withPwd(request, "samePass"));

        verify(sessionPrincipalIndex).revokeSessions("USER0001", "ROLE_CHANGED");
    }

    @Test
    @DisplayName("updateUser revokes sessions as CREDENTIAL_CHANGED when only the password changes")
    void updateUser_passwordChangedOnly_revokesLiveSessions() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");
        SecurityUser savedUser = user("USER0001", "John", "Doe", "U", "$2a$new");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("newRaw", "$2a$storedHash")).thenReturn(false);
        when(passwordEncoder.encode("newRaw")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);


        userService.updateUser("USER0001", withPwd(request, "newRaw"));

        verify(sessionPrincipalIndex).revokeSessions("USER0001", "CREDENTIAL_CHANGED");
    }

    @Test
    @DisplayName("updateUser reports ROLE_CHANGED once when the role and the password both change")
    void updateUser_roleAndPasswordChanged_reportsRoleChangedOnly() {
        UpdateUserRequestDto request = updReq("Johnny", "Doer", "A");
        SecurityUser savedUser = user("USER0001", "Johnny", "Doer", "A", "$2a$new");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("newRaw", "$2a$storedHash")).thenReturn(false);
        when(passwordEncoder.encode("newRaw")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);


        userService.updateUser("USER0001", withPwd(request, "newRaw"));

        verify(sessionPrincipalIndex).revokeSessions("USER0001", "ROLE_CHANGED");
        verify(sessionPrincipalIndex, never()).revokeSessions(any(), eq("CREDENTIAL_CHANGED"));
    }

    @Test
    @DisplayName("updateUser does NOT revoke sessions when only the name changes (no authority impact)")
    void updateUser_nameChangedOnly_doesNotRevokeSessions() {
        UpdateUserRequestDto request = updReq("Johnny", "Doe", "U");
        SecurityUser savedUser = user("USER0001", "Johnny", "Doe", "U", "$2a$storedHash");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("samePass", "$2a$storedHash")).thenReturn(true);
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);


        userService.updateUser("USER0001", withPwd(request, "samePass"));

        verifyNoInteractions(sessionPrincipalIndex);
    }

    @Test
    @DisplayName("updateUser does NOT revoke sessions when nothing changed")
    void updateUser_noChange_doesNotRevokeSessions() {
        UpdateUserRequestDto request = updReq("John", "Doe", "U");

        when(userRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("samePass", "$2a$storedHash")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser("USER0001", withPwd(request, "samePass")))
                .isExactlyInstanceOf(CardDemoException.class);

        verifyNoInteractions(sessionPrincipalIndex);
    }

    @Test
    @DisplayName("addUser never revokes sessions (a new user has none)")
    void addUser_doesNotRevokeSessions() {
        AddUserRequestDto request = addReq("USER0009", "New", "User", "U");
        SecurityUser savedUser = user("USER0009", "New", "User", "U", "$2a$new");

        when(userRepository.existsBySecUsrId("USER0009")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(user("USER0009", "New", "User", "U", null));
        when(passwordEncoder.encode("rawPass")).thenReturn("$2a$new");
        when(userRepository.saveAndFlush(any(SecurityUser.class))).thenReturn(savedUser);


        userService.addUser(withPwd(request, "rawPass"));

        verifyNoInteractions(sessionPrincipalIndex);
    }

    // ================================================================
    // listUsers / paging (COUSR00C / CU00) — 10 users per page
    // ================================================================

    @Test
    @DisplayName("listUsers first page requests page 0 size 10 and returns the mapped users")
    void listUsers_firstPage_returnsTenMapped() {
        List<UserResponseDto> tenDtos = dtosList(10);
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(usersList(10)));
        when(userMapper.toResponseList(anyList())).thenReturn(tenDtos);

        UserListResponseDto result = userService.listUsers(0);

        assertThat(result.getUsers()).isSameAs(tenDtos).hasSize(10);
        assertThat(result.getPageNumber()).isZero();
        assertThat(result.getMessage()).isNull();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAllByOrderBySecUsrIdAsc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("listUsers uses the requested page number with page size 10")
    void listUsers_middlePage_usesRequestedPageNumber() {
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(usersList(10)));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(10));

        userService.listUsers(2);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAllByOrderBySecUsrIdAsc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("listUsers returns fewer rows for a partial last page")
    void listUsers_lastPartialPage_returnsFewer() {
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(usersList(3)));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(3));

        UserListResponseDto result = userService.listUsers(4);

        assertThat(result.getUsers()).hasSize(3);
    }

    @Test
    @DisplayName("listUsersFrom positions the browse inclusively at the entered search key")
    void listUsersFrom_searchKey_browsesGreaterThanEqual() {
        List<UserResponseDto> dtos = dtosList(10);
        when(userRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0003"), any(Pageable.class)))
                .thenReturn(usersList(10));
        when(userMapper.toResponseList(anyList())).thenReturn(dtos);

        UserListResponseDto result = userService.listUsersFrom("  USER0003  ");

        assertThat(result.getUsers()).isSameAs(dtos).hasSize(10);
        assertThat(result.getPageNumber()).isZero();
        assertThat(result.isNextPage()).isTrue();
        assertThat(result.getMessage()).isNull();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository)
                .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0003"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        verify(userRepository, never()).findAllByOrderBySecUsrIdAsc(any(Pageable.class));
    }

    @Test
    @DisplayName("listUsersFrom with no match carries the bottom banner instead of failing")
    void listUsersFrom_noMatch_carriesBottomBanner() {
        when(userRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("ZZZZZZZZ"), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(userMapper.toResponseList(anyList())).thenReturn(List.of());

        UserListResponseDto result = userService.listUsersFrom("ZZZZZZZZ");

        assertThat(result.getUsers()).isEmpty();
        assertThat(result.isNextPage()).isFalse();
        assertThat(result.getMessage()).isEqualTo(MSG_REACHED_BOTTOM);
    }

    @Test
    @DisplayName("listUsersFrom with a blank key restarts the browse at the first page")
    void listUsersFrom_blankKey_listsFirstPage() {
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(usersList(10)));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(10));

        UserListResponseDto result = userService.listUsersFrom("   ");

        assertThat(result.getUsers()).hasSize(10);
        assertThat(result.getPageNumber()).isZero();
        verify(userRepository).findAllByOrderBySecUsrIdAsc(any(Pageable.class));
        verify(userRepository, never())
                .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("pageForward returns the next ascending slice greater than the last id")
    void pageForward_hasNext_returnsNextBatch() {
        List<UserResponseDto> dtos = dtosList(10);
        when(userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(eq("USER0010"), any(Pageable.class)))
                .thenReturn(usersList(10));
        when(userMapper.toResponseList(anyList())).thenReturn(dtos);

        UserListResponseDto result = userService.pageForward("USER0010");

        assertThat(result.getUsers()).isSameAs(dtos).hasSize(10);
        assertThat(result.isNextPage()).isTrue();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository)
                .findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(eq("USER0010"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("pageForward at the bottom throws 'You are already at the bottom of the page...'")
    void pageForward_atBottom_throwsBoundary() {
        when(userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(eq("ZZZZZZZZ"), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> userService.pageForward("ZZZZZZZZ"))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_ALREADY_BOTTOM);

        verify(userMapper, never()).toResponseList(any());
    }

    @Test
    @DisplayName("pageBackward reverses the descending slice to ascending id order for display")
    void pageBackward_hasPrev_returnsReversedAscending() {
        SecurityUser u2 = user("USER0002", "A", "B", "U", "$2a$p2");
        SecurityUser u3 = user("USER0003", "C", "D", "U", "$2a$p3");
        SecurityUser u4 = user("USER0004", "E", "F", "U", "$2a$p4");
        when(userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(eq("USER0005"), any(Pageable.class)))
                .thenReturn(Arrays.asList(u4, u3, u2));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(3));

        userService.pageBackward("USER0005");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SecurityUser>> captor = ArgumentCaptor.forClass(List.class);
        verify(userMapper).toResponseList(captor.capture());
        assertThat(captor.getValue())
                .extracting(SecurityUser::getSecUsrId)
                .containsExactly("USER0002", "USER0003", "USER0004");
    }

    @Test
    @DisplayName("pageBackward at the top throws 'You are already at the top of the page...'")
    void pageBackward_atTop_throwsBoundary() {
        when(userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(eq("USER0000"), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> userService.pageBackward("USER0000"))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_ALREADY_TOP);

        verify(userMapper, never()).toResponseList(any());
    }

    // ================================================================
    // resolveSelection (COUSR00C PROCESS-ENTER-KEY row selection)
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {"U", "u"})
    @DisplayName("resolveSelection routes 'U'/'u' to the update action code \"U\"")
    void resolveSelection_U_and_u_routeToUpdate(String flag) {
        assertThat(userService.resolveSelection(flag)).isEqualTo("U");
        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "d"})
    @DisplayName("resolveSelection routes 'D'/'d' to the delete action code \"D\"")
    void resolveSelection_D_and_d_routeToDelete(String flag) {
        assertThat(userService.resolveSelection(flag)).isEqualTo("D");
        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X", "A", "Z", "1"})
    @DisplayName("resolveSelection rejects any other flag with 'Invalid selection. Valid values are U and D'")
    void resolveSelection_invalid_throwsInvalidSelection(String flag) {
        assertThatThrownBy(() -> userService.resolveSelection(flag))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_INVALID_SEL);
        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    // ================================================================
    // COUSR00C paging banners reaching the client (WS-MESSAGE -> ERRMSGO)
    // ================================================================

    /**
     * :purpose: An empty first page reports the verbatim ``'You are at the top of the
     *  page...'`` banner on the response rather than only in the log.
     */
    @Test
    @DisplayName("listUsers on an empty first page carries 'You are at the top of the page...'")
    void listUsers_emptyFirstPage_carriesTopOfPageBanner() {
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userMapper.toResponseList(anyList())).thenReturn(List.of());

        UserListResponseDto result = userService.listUsers(0);

        assertThat(result.getUsers()).isEmpty();
        assertThat(result.getMessage()).isEqualTo("You are at the top of the page...");
    }

    /**
     * :purpose: An empty page past the first reports the verbatim ``'You have reached the
     *  bottom of the page...'`` banner on the response.
     */
    @Test
    @DisplayName("listUsers on an empty later page carries 'You have reached the bottom of the page...'")
    void listUsers_emptyLaterPage_carriesBottomBanner() {
        when(userRepository.findAllByOrderBySecUsrIdAsc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userMapper.toResponseList(anyList())).thenReturn(List.of());

        UserListResponseDto result = userService.listUsers(7);

        assertThat(result.getUsers()).isEmpty();
        assertThat(result.getMessage()).isEqualTo("You have reached the bottom of the page...");
    }

    /**
     * :purpose: A negative page index has no legacy analogue and reports the same top
     *  boundary the PF7 key reports, never an unexpected server error.
     */
    @Test
    @DisplayName("listUsers with a negative page reports 'You are already at the top of the page...'")
    void listUsers_negativePage_reportsTopBoundary() {
        assertThatThrownBy(() -> userService.listUsers(-1))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage("You are already at the top of the page...");
        verifyNoInteractions(userRepository, userMapper, passwordEncoder);
    }

    /**
     * :purpose: A short backward page means the browse reached the first record, so
     *  ``pageBackward`` reports the verbatim ``'You have reached the top of the page...'``
     *  banner on the response.
     */
    @Test
    @DisplayName("pageBackward on a short page carries 'You have reached the top of the page...'")
    void pageBackward_shortPage_carriesReachedTopBanner() {
        when(userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(eq("USER0004"), any(Pageable.class)))
                .thenReturn(usersList(3));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(3));

        UserListResponseDto result = userService.pageBackward("USER0004");

        assertThat(result.getUsers()).hasSize(3);
        assertThat(result.getMessage()).isEqualTo("You have reached the top of the page...");
        assertThat(result.isNextPage()).isTrue();
    }

    /**
     * :purpose: A short forward page means the browse reached end-of-file, so
     *  ``pageForward`` reports the bottom banner and clears the forward-page flag.
     */
    @Test
    @DisplayName("pageForward on a short page carries the bottom banner and clears nextPage")
    void pageForward_shortPage_carriesBottomBanner() {
        when(userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(eq("USER0001"), any(Pageable.class)))
                .thenReturn(usersList(4));
        when(userMapper.toResponseList(anyList())).thenReturn(dtosList(4));

        UserListResponseDto result = userService.pageForward("USER0001");

        assertThat(result.getUsers()).hasSize(4);
        assertThat(result.getMessage()).isEqualTo("You have reached the bottom of the page...");
        assertThat(result.isNextPage()).isFalse();
    }
}
