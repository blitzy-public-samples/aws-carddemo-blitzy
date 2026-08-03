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
package com.carddemo.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Guard the insert-not-merge contract that keeps a colliding transaction id
 *     from silently overwriting an existing financial record. ``tran_id`` is an
 *     application-assigned key drawn from ``transaction_id_seq``, so without
 *     {@link Persistable} Spring Data reports a freshly built {@link Transaction} as
 *     already persisted and ``save`` degrades to a ``merge``/UPDATE — the legacy
 *     ``WRITE`` would have raised DUPKEY/DUPREC instead (``COTRN02C``, ``COBIL00C``).
 */
class TransactionInsertContractTest {

    @Test
    @DisplayName("Transaction is Persistable and exposes tran_id as its identifier")
    void transactionIsPersistableWithTranIdAsIdentifier() {
        Transaction transaction = new Transaction();
        transaction.setTranId("0000000000000900");

        assertThat(transaction).isInstanceOf(Persistable.class);
        assertThat(((Persistable<?>) transaction).getId()).isEqualTo("0000000000000900");
    }

    @Test
    @DisplayName("A newly built transaction reports isNew(), forcing an INSERT")
    void freshTransactionIsNew() {
        Transaction transaction = new Transaction();
        assertThat(transaction.isNew()).isTrue();

        transaction.setTranId("0000000000000900");
        assertThat(transaction.isNew())
                .as("an assigned id must NOT make the instance look already-persisted")
                .isTrue();
    }

    @Test
    @DisplayName("A loaded or inserted transaction stops reporting isNew(), so updates still merge")
    void persistedTransactionIsNotNew() throws Exception {
        Transaction transaction = new Transaction();
        transaction.setTranId("0000000000000900");

        Method markPersisted = Transaction.class.getDeclaredMethod("markPersisted");
        markPersisted.setAccessible(true);
        markPersisted.invoke(transaction);

        assertThat(transaction.isNew()).isFalse();
    }

    @Test
    @DisplayName("The persisted marker is @Transient and driven by @PostLoad/@PostPersist")
    void persistedMarkerIsTransientAndLifecycleDriven() throws Exception {
        assertThat(Transaction.class.getDeclaredField("persisted")
                .isAnnotationPresent(jakarta.persistence.Transient.class)).isTrue();

        Method markPersisted = Transaction.class.getDeclaredMethod("markPersisted");
        assertThat(markPersisted.isAnnotationPresent(jakarta.persistence.PostLoad.class)).isTrue();
        assertThat(markPersisted.isAnnotationPresent(jakarta.persistence.PostPersist.class)).isTrue();
    }
}
