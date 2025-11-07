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
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * User list response DTO containing paginated user data.
 * 
 * Transforms COBOL COUSR00C.cbl WS-USER-DATA array structure (10 records per page)
 * to Spring Data Page abstraction while preserving exact pagination semantics.
 * 
 * COBOL Source: app/cbl/COUSR00C.cbl
 * - Line 57: USER-REC OCCURS 10 TIMES (matches pageSize default of 10)
 * - Line 71: CDEMO-CU00-NEXT-PAGE-FLG (maps to hasNext field)
 * - Line 70: CDEMO-CU00-PAGE-NUM (maps to currentPage field)
 * 
 * Usage: Response for GET /api/admin/users endpoint used by React UserListComponent
 * displaying 10 users per page with PF7/PF8 scroll navigation replaced by 
 * previous/next page controls.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserListResponse {

    /**
     * List of user summaries with masked passwords for security.
     * Corresponds to COBOL WS-USER-DATA with USER-REC OCCURS 10 TIMES.
     */
    @JsonProperty("users")
    private List<UserSummaryDTO> users;

    /**
     * Total number of users matching filter criteria across all pages.
     * Used for pagination UI to show "Showing X of Y users".
     */
    @JsonProperty("totalElements")
    private Long totalElements;

    /**
     * Total number of pages available for pagination control.
     * Calculated as: totalElements / pageSize (rounded up).
     */
    @JsonProperty("totalPages")
    private Integer totalPages;

    /**
     * Current page number (zero-based) matching Spring Data Pageable page number.
     * Maps to COBOL CDEMO-CU00-PAGE-NUM (but COBOL uses 1-based indexing).
     */
    @JsonProperty("currentPage")
    private Integer currentPage;

    /**
     * Number of records per page.
     * Default: 10 (matching COBOL USER-REC OCCURS 10 TIMES).
     */
    @JsonProperty("pageSize")
    private Integer pageSize;

    /**
     * Indicates if there are more pages after the current page.
     * Replaces COBOL CDEMO-CU00-NEXT-PAGE-FLG and Spring Data Page.hasNext().
     */
    @JsonProperty("hasNext")
    private Boolean hasNext;

    /**
     * Indicates if there are pages before the current page.
     * Used for backward navigation (replaces PF7 key functionality).
     */
    @JsonProperty("hasPrevious")
    private Boolean hasPrevious;

    /**
     * Field name used for sorting results (e.g., "userId", "firstName").
     * Echo of request parameter for display in UI.
     */
    @JsonProperty("sortedBy")
    private String sortedBy;

    /**
     * Sort direction: "ASC" or "DESC".
     * Echo of request parameter for display in UI.
     */
    @JsonProperty("sortDirection")
    private String sortDirection;

    /**
     * Static factory method to convert Spring Data Page to UserListResponse.
     * 
     * This method transforms the Spring Data Page&lt;User&gt; object (from JPA repository)
     * into our response DTO, mapping each User entity to UserSummaryDTO with
     * password masking for security.
     * 
     * @param page Spring Data Page object containing User entities
     * @return UserListResponse with all pagination metadata and user summaries
     */
    public static UserListResponse fromPage(org.springframework.data.domain.Page<?> page) {
        if (page == null) {
            return UserListResponse.builder()
                    .users(List.of())
                    .totalElements(0L)
                    .totalPages(0)
                    .currentPage(0)
                    .pageSize(10)
                    .hasNext(false)
                    .hasPrevious(false)
                    .build();
        }

        // Extract sort information if available
        String sortedBy = null;
        String sortDirection = null;
        if (page.getSort().isSorted()) {
            org.springframework.data.domain.Sort.Order order = 
                page.getSort().iterator().next();
            sortedBy = order.getProperty();
            sortDirection = order.getDirection().name();
        }

        return UserListResponse.builder()
                .users(page.getContent().stream()
                        .map(user -> UserSummaryDTO.fromEntity(user))
                        .collect(Collectors.toList()))
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .currentPage(page.getNumber())  // Zero-based in Spring Data
                .pageSize(page.getSize())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .sortedBy(sortedBy)
                .sortDirection(sortDirection)
                .build();
    }

    /**
     * Nested DTO class representing a summary view of a user.
     * 
     * Corresponds to COBOL SEC-USER-DATA record in CSUSR01Y.cpy:
     * - SEC-USR-ID (PIC X(08)) → userId
     * - SEC-USR-FNAME (PIC X(20)) → firstName
     * - SEC-USR-LNAME (PIC X(20)) → lastName
     * - SEC-USR-TYPE (PIC X(01)) → userType
     * 
     * Note: SEC-USR-PWD is intentionally excluded (masked for security).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserSummaryDTO {

        /**
         * User ID (unique identifier).
         * COBOL: SEC-USR-ID PIC X(08)
         */
        @JsonProperty("userId")
        private String userId;

        /**
         * User's first name.
         * COBOL: SEC-USR-FNAME PIC X(20)
         */
        @JsonProperty("firstName")
        private String firstName;

        /**
         * User's last name.
         * COBOL: SEC-USR-LNAME PIC X(20)
         */
        @JsonProperty("lastName")
        private String lastName;

        /**
         * User type: 'A' (Admin) or 'U' (Regular User).
         * COBOL: SEC-USR-TYPE PIC X(01)
         * Maps to enum: UserType.ADMIN ('A') or UserType.USER ('U')
         */
        @JsonProperty("userType")
        private String userType;

        /**
         * Optional: User's full name for display purposes.
         * Constructed as: firstName + " " + lastName
         */
        @JsonProperty("fullName")
        private String fullName;

        /**
         * Optional: Human-readable user type label.
         * "Administrator" for 'A', "Regular User" for 'U'
         */
        @JsonProperty("userTypeLabel")
        private String userTypeLabel;

        /**
         * Static factory method to convert User entity to UserSummaryDTO.
         * 
         * This method intentionally excludes the password field for security.
         * Password masking ensures sensitive data is never exposed in API responses.
         * 
         * @param user User entity object (can be generic Object for flexibility)
         * @return UserSummaryDTO with all fields except password
         */
        public static UserSummaryDTO fromEntity(Object user) {
            if (user == null) {
                return null;
            }

            // Use reflection to extract fields from the user entity
            // This provides flexibility to work with different User entity implementations
            try {
                String userId = getFieldValue(user, "userId", String.class);
                String firstName = getFieldValue(user, "firstName", String.class);
                String lastName = getFieldValue(user, "lastName", String.class);
                String userType = getFieldValue(user, "userType", String.class);

                // Construct full name
                String fullName = null;
                if (firstName != null && lastName != null) {
                    fullName = firstName.trim() + " " + lastName.trim();
                }

                // Convert user type to label
                String userTypeLabel = null;
                if (userType != null) {
                    userTypeLabel = "A".equalsIgnoreCase(userType.trim()) 
                        ? "Administrator" 
                        : "Regular User";
                }

                return UserSummaryDTO.builder()
                        .userId(userId)
                        .firstName(firstName)
                        .lastName(lastName)
                        .userType(userType)
                        .fullName(fullName)
                        .userTypeLabel(userTypeLabel)
                        .build();

            } catch (Exception e) {
                // Log error but don't expose internal details
                // In production, this should use proper logging framework (SLF4J)
                System.err.println("Error converting User entity to UserSummaryDTO: " 
                    + e.getMessage());
                return null;
            }
        }

        /**
         * Helper method to extract field value from an entity using reflection.
         * 
         * This supports both direct field access and getter methods (JavaBean convention).
         * 
         * @param obj The entity object
         * @param fieldName Name of the field to extract
         * @param type Expected type of the field
         * @param <T> Generic type parameter
         * @return Field value or null if not found
         */
        private static <T> T getFieldValue(Object obj, String fieldName, Class<T> type) {
            try {
                // Try getter method first (e.g., getUserId())
                String getterName = "get" + 
                    fieldName.substring(0, 1).toUpperCase() + 
                    fieldName.substring(1);
                java.lang.reflect.Method getter = 
                    obj.getClass().getMethod(getterName);
                Object value = getter.invoke(obj);
                return type.cast(value);
            } catch (Exception e) {
                try {
                    // Try direct field access as fallback
                    java.lang.reflect.Field field = 
                        obj.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    return type.cast(value);
                } catch (Exception ex) {
                    // Field not found or not accessible
                    return null;
                }
            }
        }
    }

    /**
     * Returns the number of users in the current page.
     * Convenience method for UI display.
     * 
     * @return Number of users in current page (0 to pageSize)
     */
    public int getUserCount() {
        return users != null ? users.size() : 0;
    }

    /**
     * Calculates the starting record number for the current page (1-based).
     * Used for UI display: "Showing records 11-20 of 50"
     * 
     * @return Starting record number (1-based)
     */
    public long getStartRecord() {
        if (totalElements == null || totalElements == 0) {
            return 0;
        }
        return (long) currentPage * pageSize + 1;
    }

    /**
     * Calculates the ending record number for the current page (1-based).
     * Used for UI display: "Showing records 11-20 of 50"
     * 
     * @return Ending record number (1-based)
     */
    public long getEndRecord() {
        if (totalElements == null || totalElements == 0) {
            return 0;
        }
        long end = (long) (currentPage + 1) * pageSize;
        return Math.min(end, totalElements);
    }

    /**
     * Checks if the current page is the first page.
     * Replaces COBOL logic: CDEMO-CU00-PAGE-NUM = 1
     * 
     * @return true if this is the first page (page 0)
     */
    public boolean isFirstPage() {
        return currentPage != null && currentPage == 0;
    }

    /**
     * Checks if the current page is the last page.
     * Replaces COBOL NEXT-PAGE-FLG = 'N' logic
     * 
     * @return true if this is the last page (no more pages after)
     */
    public boolean isLastPage() {
        return hasNext != null && !hasNext;
    }
}
