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
package com.aws.carddemo.repository;

/**
 * Closed Spring Data interface projection for the {@code COUSR00C} user-list browse.
 *
 * <p>The list screen ({@code COUSR00C}, transaction {@code CU00}) renders only the user id, first
 * name, last name and user type. Selecting the whole {@link com.aws.carddemo.domain.UserSecurity}
 * entity would also load {@code sec_usr_pwd} — the BCrypt password hash — which the screen never
 * displays. This projection restricts the generated {@code SELECT} to exactly the four displayed
 * columns ({@code sec_usr_id}, {@code sec_usr_fname}, {@code sec_usr_lname}, {@code sec_usr_type}),
 * so the credential hash is never read into the application for a list view. Repository keyset
 * browse methods declaring this return type emit a projected select-list rather than the full
 * entity row.
 */
public interface UserListProjection {

  /**
   * The user id (primary key {@code sec_usr_id}, char(8)).
   *
   * @return the user id
   */
  String getSecUsrId();

  /**
   * The user's first name ({@code sec_usr_fname}).
   *
   * @return the first name
   */
  String getSecUsrFname();

  /**
   * The user's last name ({@code sec_usr_lname}).
   *
   * @return the last name
   */
  String getSecUsrLname();

  /**
   * The user type ({@code sec_usr_type}; {@code 'A'} admin / {@code 'U'} standard).
   *
   * @return the user type code
   */
  String getSecUsrType();
}
