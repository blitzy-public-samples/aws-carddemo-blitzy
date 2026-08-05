/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
package com.carddemo.card.config;

import com.carddemo.common.crypto.CryptoConverter;
import com.carddemo.common.crypto.PiiAtRestInitializer;
import com.carddemo.common.crypto.PiiAtRestInitializer.PiiColumn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.List;

/**
 * :purpose: Enforce the at-rest encryption contract on the sensitive ``cards`` column
 *     that card-service owns. The Flyway seed ``V3__seed_cards.sql`` derives its
 *     values from the legacy fixture ``app/data/ASCII/carddata.txt`` and inserts them
 *     with raw SQL, which bypasses the JPA {@link CryptoConverter}; a ciphertext
 *     literal cannot be committed to the migration because the AES key is
 *     environment-specific. This configuration therefore registers the shared sweep
 *     that encrypts any remaining plaintext with the key of the environment it starts
 *     in, satisfying AAP 0.6.7 for the card verification value.
 * :output: A single {@link PiiAtRestInitializer} bean covering ``card_cvv_cd``.
 */
@Configuration(proxyBeanMethods = false)
public class PiiAtRestConfig {

    /**
     * :purpose: Register the idempotent at-rest encryption sweep for the card
     *     verification value.
     * :param dataSource: the service datasource; the JdbcTemplate is built directly
     *     from it so the sweep does not depend on the JdbcTemplate auto-configuration.
     * :returns: the configured {@link PiiAtRestInitializer}.
     */
    @Bean
    public PiiAtRestInitializer cardPiiAtRestInitializer(DataSource dataSource) {
        return new PiiAtRestInitializer(new JdbcTemplate(dataSource), new CryptoConverter(), List.of(
                new PiiColumn("cards", "card_num", "card_cvv_cd")));
    }
}
