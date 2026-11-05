/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.cardemo.common.enums.UserType;

/**
 * JPA {@link AttributeConverter} that maps the {@link UserType} enum to and
 * from its single-character database representation.
 *
 * <p>This converter preserves the original COBOL storage format defined in
 * {@code CSUSR01Y.cpy} field {@code SEC-USR-TYPE PIC X(01)}:</p>
 * <ul>
 *   <li>{@link UserType#ADMIN} ↔ {@code 'A'} (COBOL 88-level
 *       {@code CDEMO-USRTYP-ADMIN VALUE 'A'} from {@code COCOM01Y.cpy})</li>
 *   <li>{@link UserType#USER} ↔ {@code 'U'} (COBOL 88-level
 *       {@code CDEMO-USRTYP-USER VALUE 'U'} from {@code COCOM01Y.cpy})</li>
 * </ul>
 *
 * <p>The {@code VARCHAR(1)} column in the {@code user_security} table
 * (defined in {@code V1__create_schema.sql}) stores exactly one character,
 * matching the original COBOL layout. Using {@code @Enumerated(EnumType.STRING)}
 * would have stored the full enum name ({@code "ADMIN"} = 5 chars), exceeding
 * the column width and causing a {@code DataException} at runtime.</p>
 *
 * <p>This converter uses {@link UserType#getCode()} for the enum-to-database
 * direction and {@link UserType#fromCode(char)} for the database-to-enum
 * direction, ensuring clean bidirectional mapping with no magic strings.</p>
 *
 * @see UserType
 * @see UserSecurity
 */
@Converter
public class UserTypeConverter implements AttributeConverter<UserType, String> {

    /**
     * Converts a {@link UserType} enum value to its single-character
     * database representation.
     *
     * <p>Maps {@link UserType#ADMIN} → {@code "A"} and
     * {@link UserType#USER} → {@code "U"}, using the enum's
     * {@link UserType#getCode()} method.</p>
     *
     * @param attribute the {@link UserType} enum value to convert;
     *                  may be {@code null} for records with no assigned role
     * @return the single-character string representation ({@code "A"} or
     *         {@code "U"}), or {@code null} if the input is {@code null}
     */
    @Override
    public String convertToDatabaseColumn(UserType attribute) {
        if (attribute == null) {
            return null;
        }
        return String.valueOf(attribute.getCode());
    }

    /**
     * Converts a single-character database value back to the corresponding
     * {@link UserType} enum constant.
     *
     * <p>Maps {@code "A"} → {@link UserType#ADMIN} and
     * {@code "U"} → {@link UserType#USER}, using the enum's
     * {@link UserType#fromCode(char)} static factory method.</p>
     *
     * @param dbData the single-character string from the database column;
     *               may be {@code null} if the column value is SQL NULL
     * @return the corresponding {@link UserType} enum constant, or
     *         {@code null} if the input is {@code null}
     * @throws IllegalArgumentException if the character does not match
     *         any known {@link UserType} code
     */
    @Override
    public UserType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        return UserType.fromCode(dbData.charAt(0));
    }
}
