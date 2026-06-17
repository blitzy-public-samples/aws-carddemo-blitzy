package com.carddemo.service;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.entity.User;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.UserMapper;
import com.carddemo.repository.UserRepository;
import com.carddemo.util.CardDemoConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link UserService}, the Java replacement for the four admin-only CICS
 * user-administration programs {@code COUSR00C} (list), {@code COUSR01C} (add), {@code COUSR02C}
 * (update), and {@code COUSR03C} (delete).
 *
 * <h2>What this test pins (AAP &sect;0.6.7 &mdash; credential/normalization parity)</h2>
 * <p>The legacy sign-on program {@code COSGN00C} and the migrated authentication paths
 * ({@code AuthService#signon}, {@code CustomUserDetailsService#loadUserByUsername}) normalize the
 * entered user id with {@code trim().toUpperCase(Locale.ROOT)} before the keyed lookup. For a user
 * created or modified here to remain reachable at sign-on, {@link UserService} MUST apply the
 * <em>identical</em> {@code trim() + toUpperCase(Locale.ROOT)} to the {@code userId} on
 * <strong>every</strong> id path (create / update / delete / get). A whitespace-padded id that was
 * only upper-cased (the pre-fix behaviour) would be persisted but unreachable at authentication.</p>
 *
 * <p>Conversely, the <strong>password</strong> follows a deliberately <em>different</em> rule: it is
 * upper-cased only and is <strong>never trimmed</strong>, mirroring {@code AuthService#signon}, which
 * upper-cases but does not trim the password. Trimming the credential here would change the bytes fed
 * to BCrypt relative to the sign-on path, so a password with leading/trailing spaces would no longer
 * authenticate. These tests therefore assert both halves of the contract together:</p>
 * <ol>
 *   <li><strong>Id normalization = trim + UPPER.</strong> On create/update/delete/get the value the
 *       service hands {@code existsById}/{@code findById}/{@code deleteById}/{@code save} is captured
 *       and asserted equal to the trimmed, upper-cased form ({@code "  usr0001  "} &rarr;
 *       {@code "USR0001"}).</li>
 *   <li><strong>Password normalization = UPPER only, no trim.</strong> The value the service hands
 *       {@link PasswordEncoder#encode(CharSequence)} is captured and asserted to preserve surrounding
 *       whitespace ({@code " s3cret "} &rarr; {@code " S3CRET "}).</li>
 * </ol>
 *
 * <h2>Test character</h2>
 * <p>This is a <strong>pure unit test</strong>: {@code @ExtendWith(MockitoExtension.class)} with no
 * Spring context and no database. {@link UserService}'s three constructor collaborators
 * &mdash; {@link UserRepository}, {@link UserMapper}, and {@link PasswordEncoder} &mdash; are
 * {@code @Mock}ed and wired via {@code @InjectMocks}. {@code MockitoExtension} runs in strict-stubbing
 * mode, so each test stubs only the collaborators it actually exercises.</p>
 *
 * @see UserService#createUser(UserCreateRequest)
 * @see UserService#updateUser(String, UserUpdateRequest)
 * @see UserService#deleteUser(String)
 * @see UserService#getUser(String)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — userId trim+uppercase normalization parity (AAP §0.6.7)")
class UserServiceTest {

    /** A user id as a careless caller might submit it: lower-case, padded with surrounding spaces. */
    private static final String RAW_ID = "  usr0001  ";
    /** The canonical, sign-on-reachable form: trimmed then upper-cased with {@link Locale#ROOT}. */
    private static final String NORM_ID = "USR0001";

    /** A password carrying significant leading/trailing spaces (8 chars, within the DTO's max). */
    private static final String RAW_PWD = " s3cret ";
    /** The credential the encoder must receive: upper-cased but with surrounding spaces preserved. */
    private static final String UPPER_PWD_NO_TRIM = " S3CRET ";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    // -----------------------------------------------------------------------------------------------
    // createUser (COUSR01C)
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("createUser trims+uppercases the id before existsById and save, but only uppercases the password")
    void createUser_normalizesIdTrimUpper_andPasswordUpperOnly() {
        UserCreateRequest request =
                new UserCreateRequest(RAW_ID, "John", "Smith", RAW_PWD, "U");

        // The real UserMapper builds a fresh entity from the request and never sets the password.
        when(userMapper.toEntity(any(UserCreateRequest.class))).thenAnswer(inv -> {
            UserCreateRequest r = inv.getArgument(0);
            return new User(r.userId(), r.firstName(), r.lastName(), null, r.userType());
        });
        when(userRepository.existsById(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("BCRYPT_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        UserResponse response = userService.createUser(request);

        // (1) The duplicate-key probe must use the trimmed + upper-cased id, not the raw input.
        ArgumentCaptor<String> existsIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).existsById(existsIdCaptor.capture());
        assertThat(existsIdCaptor.getValue())
                .as("existsById must receive the trimmed + upper-cased id")
                .isEqualTo(NORM_ID);

        // (2) The persisted entity's id must be the normalized form.
        ArgumentCaptor<User> saveCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getUserId())
                .as("persisted userId must be trimmed + upper-cased")
                .isEqualTo(NORM_ID);

        // (3) The credential handed to BCrypt must be upper-cased ONLY (surrounding spaces preserved).
        ArgumentCaptor<String> encodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(encodeCaptor.capture());
        assertThat(encodeCaptor.getValue())
                .as("password must be upper-cased but NOT trimmed (sign-on parity)")
                .isEqualTo(UPPER_PWD_NO_TRIM);

        // The returned projection carries the normalized id and never the credential.
        assertThat(response.userId()).isEqualTo(NORM_ID);
    }

    @Test
    @DisplayName("createUser detects a duplicate using the normalized id and never saves or encodes")
    void createUser_duplicateUsesNormalizedId_throwsAndDoesNotPersist() {
        UserCreateRequest request =
                new UserCreateRequest(RAW_ID, "John", "Smith", RAW_PWD, "U");

        // The duplicate-key guard runs against existsById BEFORE the mapper/encoder/save are touched,
        // so only existsById is stubbed here (strict stubbing would flag any unused collaborator stub).
        when(userRepository.existsById(anyString())).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(MessageService.USER_ALREADY_EXISTS);

        // The duplicate probe used the normalized id.
        ArgumentCaptor<String> existsIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).existsById(existsIdCaptor.capture());
        assertThat(existsIdCaptor.getValue()).isEqualTo(NORM_ID);

        // No write and no credential hashing occurred on the reject path.
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    // -----------------------------------------------------------------------------------------------
    // getUser (single-user read; keyed access)
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("getUser looks up by the trimmed + upper-cased id")
    void getUser_normalizesIdBeforeFindById() {
        User stored = new User(NORM_ID, "John", "Smith", "BCRYPT_HASH", "U");
        when(userRepository.findById(anyString())).thenReturn(Optional.of(stored));
        when(userMapper.toResponse(any(User.class))).thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        UserResponse response = userService.getUser(RAW_ID);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findById(idCaptor.capture());
        assertThat(idCaptor.getValue())
                .as("findById must receive the trimmed + upper-cased id")
                .isEqualTo(NORM_ID);
        assertThat(response.userId()).isEqualTo(NORM_ID);
    }

    @Test
    @DisplayName("getUser not-found queries the normalized id yet echoes the caller's original id")
    void getUser_notFound_queriesNormalizedButEchoesOriginal() {
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser(RAW_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(ex -> ((ResourceNotFoundException) ex).getIdentifier())
                .as("the not-found exception echoes the caller's original (un-normalized) id")
                .isEqualTo(RAW_ID);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findById(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(NORM_ID);
    }

    // -----------------------------------------------------------------------------------------------
    // updateUser (COUSR02C)
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("updateUser loads by the normalized id and encodes a supplied password upper-only (no trim)")
    void updateUser_normalizesIdAndPasswordUpperOnly() {
        User stored = new User(NORM_ID, "John", "Smith", "OLD_HASH", "U");
        UserUpdateRequest request = new UserUpdateRequest("Jane", "Doe", RAW_PWD, "A");

        when(userRepository.findById(anyString())).thenReturn(Optional.of(stored));
        when(passwordEncoder.encode(anyString())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        userService.updateUser(RAW_ID, request);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findById(idCaptor.capture());
        assertThat(idCaptor.getValue())
                .as("findById must receive the trimmed + upper-cased id")
                .isEqualTo(NORM_ID);

        ArgumentCaptor<String> encodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(encodeCaptor.capture());
        assertThat(encodeCaptor.getValue())
                .as("update password must be upper-cased but NOT trimmed (sign-on parity)")
                .isEqualTo(UPPER_PWD_NO_TRIM);

        // The mapper applies the editable profile fields; the service is responsible for the password.
        verify(userMapper).applyUpdate(any(UserUpdateRequest.class), any(User.class));
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("updateUser with a blank password leaves the existing hash untouched")
    void updateUser_blankPassword_doesNotEncode() {
        User stored = new User(NORM_ID, "John", "Smith", "OLD_HASH", "U");
        // A null password exercises the service's defensive "only change when supplied" guard.
        UserUpdateRequest request = new UserUpdateRequest("Jane", "Doe", null, "A");

        when(userRepository.findById(anyString())).thenReturn(Optional.of(stored));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        userService.updateUser(RAW_ID, request);

        verify(passwordEncoder, never()).encode(anyString());
        verify(userMapper).applyUpdate(any(UserUpdateRequest.class), any(User.class));
        verify(userRepository).save(any(User.class));
    }

    // -----------------------------------------------------------------------------------------------
    // deleteUser (COUSR03C)
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("deleteUser checks existence and deletes by the trimmed + upper-cased id")
    void deleteUser_normalizesIdBeforeExistsAndDelete() {
        when(userRepository.existsById(anyString())).thenReturn(true);

        userService.deleteUser(RAW_ID);

        ArgumentCaptor<String> existsIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).existsById(existsIdCaptor.capture());
        assertThat(existsIdCaptor.getValue())
                .as("existsById must receive the trimmed + upper-cased id")
                .isEqualTo(NORM_ID);

        ArgumentCaptor<String> deleteIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).deleteById(deleteIdCaptor.capture());
        assertThat(deleteIdCaptor.getValue())
                .as("deleteById must receive the trimmed + upper-cased id")
                .isEqualTo(NORM_ID);
    }

    @Test
    @DisplayName("deleteUser not-found queries the normalized id, echoes the original, and never deletes")
    void deleteUser_notFound_queriesNormalizedButEchoesOriginal_andDoesNotDelete() {
        when(userRepository.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(RAW_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(ex -> ((ResourceNotFoundException) ex).getIdentifier())
                .as("the not-found exception echoes the caller's original (un-normalized) id")
                .isEqualTo(RAW_ID);

        ArgumentCaptor<String> existsIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).existsById(existsIdCaptor.capture());
        assertThat(existsIdCaptor.getValue()).isEqualTo(NORM_ID);
        verify(userRepository, never()).deleteById(anyString());
    }

    // -----------------------------------------------------------------------------------------------
    // listUsers (COUSR00C) — page-size-7 browse parity
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("listUsers pins the legacy page size of 7 and maps every row to a credential-free response")
    void listUsers_pinsPageSizeSeven_andMapsContent() {
        // A page of 3 users out of a total of 20, requested with the parity page size.
        List<User> users = List.of(
                new User("USER0001", "Alice", "Anderson", "BCRYPT_HASH", "U"),
                new User("USER0002", "Bob", "Brown", "BCRYPT_HASH", "U"),
                new User("ADMIN001", "Carol", "Clark", "BCRYPT_HASH", "A"));
        Page<User> page = new PageImpl<>(users, PageRequest.of(0, CardDemoConstants.PAGE_SIZE), 20);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);
        // Faithful page-envelope conversion mirroring the real UserMapper.toPageResponse: every row is
        // projected through the credential-free UserResponse view.
        when(userMapper.toPageResponse(any())).thenAnswer(inv -> {
            Page<User> p = inv.getArgument(0);
            return PageResponse.from(p, u ->
                    new UserResponse(u.getUserId(), u.getFirstName(), u.getLastName(), u.getUserType()));
        });

        PageResponse<UserResponse> resp = userService.listUsers(0);

        // The Pageable handed to the repository pins the legacy fixed window of 7 rows (COUSR00C).
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        assertThat(CardDemoConstants.PAGE_SIZE).isEqualTo(7);
        assertThat(used.getPageSize()).isEqualTo(CardDemoConstants.PAGE_SIZE);
        assertThat(used.getPageNumber()).isEqualTo(0);

        // The envelope reflects the source page and carries credential-free rows.
        assertThat(resp.size()).isEqualTo(7);
        assertThat(resp.page()).isEqualTo(0);
        assertThat(resp.totalElements()).isEqualTo(20L);
        assertThat(resp.content()).hasSize(3);
        assertThat(resp.content())
                .extracting(UserResponse::userId)
                .containsExactly("USER0001", "USER0002", "ADMIN001");
        UserResponse firstRow = resp.content().get(0);
        assertThat(firstRow.firstName()).isEqualTo("Alice");
        assertThat(firstRow.lastName()).isEqualTo("Anderson");
        assertThat(firstRow.userType()).isEqualTo("U");
    }

    // -----------------------------------------------------------------------------------------------
    // Structural PII suppression — UserResponse can never carry a password (AAP §0.6.8 / §0.7.1)
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("UserResponse declares no password component, so a credential can never be serialized")
    void userResponse_hasNoPasswordComponent_structural() {
        Set<String> components = Arrays.stream(UserResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // The response projection exposes exactly the four non-sensitive attributes...
        assertThat(components).containsExactlyInAnyOrder("userId", "firstName", "lastName", "userType");
        // ...and carries no password / hash / secret of any name.
        assertThat(components).doesNotContain("password", "pwd", "passwordHash", "hash", "secret");
    }

    // -----------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------

    /**
     * Mirrors the real {@code UserMapper.toResponse}: projects the persisted entity onto the
     * credential-free {@link UserResponse} (never reading the password hash).
     */
    private static UserResponse toResponseLikeMapper(User u) {
        return new UserResponse(u.getUserId(), u.getFirstName(), u.getLastName(), u.getUserType());
    }
}
