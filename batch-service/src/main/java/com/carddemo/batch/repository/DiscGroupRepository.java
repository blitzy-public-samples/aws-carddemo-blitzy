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
package com.carddemo.batch.repository;

import com.carddemo.common.domain.DiscGroup;
import com.carddemo.common.domain.DiscGroupId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DiscGroup} disclosure-group entity
 * (composite key via {@link DiscGroupId}).
 *
 * :purpose: Replaces VSAM DISCGRP keyed access for the interest-rate lookup in
 *     the interest-calculation batch job (``CBACT04C``). The lookup uses the
 *     inherited ``findById(DiscGroupId)``; the ``DEFAULT`` account-group
 *     fallback is applied by the service layer.
 * :output: An ``Optional`` disclosure-group row for a fully specified key.
 */
@Repository
public interface DiscGroupRepository extends JpaRepository<DiscGroup, DiscGroupId> {
}
