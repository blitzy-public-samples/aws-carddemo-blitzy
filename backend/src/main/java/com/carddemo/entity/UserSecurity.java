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

package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * JPA entity representing user security and authentication data.
 * 
 * Transformed from COBOL copybook CSUSR01Y.cpy (SEC-USER-DATA with RECLN 80 bytes).
 * Replaces RACF security with Spring Security UserDetailsService implementation.
 * 
 * This entity implements Spring Security's UserDetails interface for JWT-based
 * authentication and serves as the authentication source for the CardDemo application.
 * 
 * COBOL Structure Mapping:
 * - SEC-USR-ID PIC X(08)    → userId (8-char primary key)
 * - SEC-USR-FNAME PIC X(20) → firstName (20 chars)
 * - SEC-USR-LNAME PIC X(20) → lastName (20 chars)
 * - SEC-USR-PWD PIC X(08)   → password (BCrypt hash, 60 chars)
 * - SEC-USR-TYPE PIC X(01)  → userType ('R' or 'A')
 * - SEC-USR-FILLER PIC X(23) → NOT MAPPED (unused padding)
 * 
 * CRITICAL SECURITY NOTE:
 * - COBOL stores passwords in PLAIN TEXT (8 characters) - INSECURE
 * - Java stores BCrypt hashed passwords (60 characters) - SECURE
 * - Password field length increased from 8 to 60 for BCrypt hash storage
 * - Use BCryptPasswordEncoder with strength 12 per Section 0.2 security requirements
 * 
 * Role Mapping (per Section 0.2):
 * - 'R' (Regular User) → ROLE_USER
 * - 'A' (Administrative User) → ROLE_USER + ROLE_ADMIN (hierarchical)
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see org.springframework.security.core.userdetails.UserDetails
 */
@Entity
@Table(name = "user_security")
@NoArgsConstructor
@AllArgsConstructor
public class UserSecurity implements UserDetails, Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * User ID - 8 character unique identifier.
     * Maps to COBOL SEC-USR-ID PIC X(08).
     * Primary key for user_security table.
     */
    @Id
    @Column(name = "user_id", length = 8, nullable = false)
    private String userId;
    
    /**
     * User first name - 20 character field.
     * Maps to COBOL SEC-USR-FNAME PIC X(20).
     */
    @Column(name = "first_name", length = 20, nullable = false)
    private String firstName;
    
    /**
     * User last name - 20 character field.
     * Maps to COBOL SEC-USR-LNAME PIC X(20).
     */
    @Column(name = "last_name", length = 20, nullable = false)
    private String lastName;
    
    /**
     * User password - BCrypt encrypted hash (60 characters).
     * 
     * CRITICAL: Maps to COBOL SEC-USR-PWD PIC X(08) but with enhanced security.
     * - COBOL: Plain text 8 characters (INSECURE)
     * - Java: BCrypt hash 60 characters (SECURE)
     * 
     * BCrypt hash format: $2a$12$[22 character salt][31 character hash]
     * Example: "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
     * 
     * Use BCryptPasswordEncoder with strength 12 for all password operations.
     * 
     * @JsonIgnore prevents password exposure in REST API responses (CRITICAL security)
     */
    @Column(name = "password", length = 60, nullable = false)
    @JsonIgnore
    private String password;
    
    /**
     * User type - 1 character role indicator.
     * Maps to COBOL SEC-USR-TYPE PIC X(01).
     * 
     * Valid values:
     * - 'R': Regular User (ROLE_USER authority)
     * - 'A': Administrative User (ROLE_USER + ROLE_ADMIN authorities)
     */
    @Column(name = "user_type", length = 1, nullable = false)
    private String userType;
    
    // ===== Standard Getters and Setters =====
    
    /**
     * Gets the user ID (8 characters).
     * 
     * @return user ID string
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Sets the user ID (8 characters).
     * 
     * @param userId user ID string (max 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    /**
     * Gets the user's first name (20 characters).
     * 
     * @return first name string
     */
    public String getFirstName() {
        return firstName;
    }
    
    /**
     * Sets the user's first name (20 characters).
     * 
     * @param firstName first name string (max 20 characters)
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }
    
    /**
     * Gets the user's last name (20 characters).
     * 
     * @return last name string
     */
    public String getLastName() {
        return lastName;
    }
    
    /**
     * Sets the user's last name (20 characters).
     * 
     * @param lastName last name string (max 20 characters)
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    /**
     * Gets the BCrypt encrypted password (60 characters).
     * 
     * CRITICAL: This returns the BCrypt hash, not the plain text password.
     * Format: $2a$12$[salt][hash]
     * 
     * @return BCrypt password hash
     */
    public String getPassword() {
        return password;
    }
    
    /**
     * Sets the BCrypt encrypted password (60 characters).
     * 
     * CRITICAL: This should ONLY be set with BCrypt hashed passwords.
     * Use BCryptPasswordEncoder.encode(plainPassword) before calling this method.
     * 
     * Never store plain text passwords. Migration from COBOL requires:
     * 1. Read plain text password from VSAM USRSEC file
     * 2. Hash using BCryptPasswordEncoder with strength 12
     * 3. Store BCrypt hash using this setter
     * 
     * @param password BCrypt encrypted password hash (60 characters)
     */
    public void setPassword(String password) {
        this.password = password;
    }
    
    /**
     * Gets the user type (1 character).
     * 
     * @return user type: 'R' (Regular) or 'A' (Admin)
     */
    public String getUserType() {
        return userType;
    }
    
    /**
     * Sets the user type (1 character).
     * 
     * @param userType user type: 'R' (Regular) or 'A' (Admin)
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }
    
    // ===== UserDetails Interface Implementation =====
    
    /**
     * Returns the username for authentication.
     * 
     * UserDetails interface method implementation.
     * Maps to userId field for Spring Security authentication.
     * 
     * @return username (userId)
     */
    @Override
    public String getUsername() {
        return userId;
    }
    
    /**
     * Returns the user's authorities (roles).
     * 
     * UserDetails interface method implementation.
     * Maps COBOL user type to Spring Security roles per Section 0.2:
     * 
     * - 'R' (Regular User) → [ROLE_USER]
     * - 'A' (Administrative User) → [ROLE_USER, ROLE_ADMIN]
     * 
     * Admin users inherit regular user privileges (hierarchical roles).
     * 
     * @return collection of granted authorities based on user type
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if ("A".equals(userType)) {
            // Administrative user has both USER and ADMIN roles (hierarchical)
            return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
            );
        } else {
            // Regular user has only USER role
            // Default to ROLE_USER for any other value including 'R'
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
    }
    
    /**
     * Indicates whether the user's account has expired.
     * 
     * UserDetails interface method implementation.
     * No account expiration logic exists in COBOL implementation.
     * 
     * @return true (accounts never expire)
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    
    /**
     * Indicates whether the user is locked or unlocked.
     * 
     * UserDetails interface method implementation.
     * No account locking logic exists in COBOL implementation.
     * 
     * @return true (accounts never locked)
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }
    
    /**
     * Indicates whether the user's credentials have expired.
     * 
     * UserDetails interface method implementation.
     * No password expiration logic exists in COBOL implementation.
     * 
     * @return true (credentials never expire)
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
    
    /**
     * Indicates whether the user is enabled or disabled.
     * 
     * UserDetails interface method implementation.
     * No disabled user status exists in COBOL implementation.
     * 
     * @return true (all users are enabled)
     */
    @Override
    public boolean isEnabled() {
        return true;
    }
    
    // ===== Object Override Methods =====
    
    /**
     * Determines equality based on user ID (primary key).
     * 
     * Two UserSecurity objects are equal if they have the same userId.
     * This follows the JPA entity best practice of using primary key for equality.
     * 
     * @param o object to compare
     * @return true if objects are equal based on userId
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserSecurity that = (UserSecurity) o;
        return userId != null && userId.equals(that.userId);
    }
    
    /**
     * Returns hash code based on user ID (primary key).
     * 
     * Hash code is computed from userId to ensure consistency with equals().
     * This follows JPA entity best practice for hashCode implementation.
     * 
     * @return hash code based on userId
     */
    @Override
    public int hashCode() {
        return userId != null ? userId.hashCode() : 0;
    }
    
    /**
     * Returns string representation of UserSecurity entity.
     * 
     * CRITICAL SECURITY: Password field is masked as "********" to prevent
     * accidental exposure in logs or console output. This is essential for
     * PCI-DSS compliance and security best practices.
     * 
     * Format includes all non-sensitive fields for debugging purposes.
     * 
     * @return string representation with masked password
     */
    @Override
    public String toString() {
        return "UserSecurity{" +
                "userId='" + userId + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", password='********'" +
                ", userType='" + userType + '\'' +
                '}';
    }
}
