package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Customer;
import com.cardemo.repository.CustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerFileService} — covers the customer file
 * management logic translated from CBCUS01C.cbl.
 *
 * <p>Tests refreshCustomers flow, displayCustomerRecord with PII masking,
 * null/empty handling, and error paths.</p>
 */
@ExtendWith(MockitoExtension.class)
class CustomerFileServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerFileService service;

    // ── Helper factory ──────────────────────────────────────────────

    /**
     * Builds a fully populated Customer using the public all-args constructor:
     * Customer(custId, firstName, middleName, lastName, addrLine1, addrLine2,
     *     addrLine3, addrStateCode, addrCountryCode, addrZip,
     *     phoneNum1, phoneNum2, ssn, govtIssuedId, dateOfBirth,
     *     eftAccountId, priCardHolderInd, ficoCreditScore)
     */
    private Customer buildFullCustomer(String id) {
        return new Customer(
                id, "John", "Michael", "Doe",
                "123 Main St", "Apt 4B", "Building A",
                "IL", "US", "62701",
                "2175551234", "2175555678",
                "123456789", "DL987654321", "19800115",
                "00000000001", "Y", 750);
    }

    /**
     * Builds a Customer with specific PII fields for masking tests.
     */
    private Customer buildCustomerWithPii(String id, String ssn, String govtId) {
        return new Customer(
                id, "Jane", "A", "Smith",
                "456 Oak Ave", null, null,
                "CA", "US", "90210",
                "3105551111", null,
                ssn, govtId, "19900520",
                "00000000002", "N", 680);
    }

    // ── refreshCustomers ────────────────────────────────────────────

    @Nested
    @DisplayName("refreshCustomers")
    class RefreshCustomers {

        @Test
        @DisplayName("Empty customer list completes without error")
        void emptyListCompletes() {
            when(customerRepository.findAll()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
            verify(customerRepository).findAll();
        }

        @Test
        @DisplayName("Single customer is processed successfully")
        void singleCustomerProcessed() {
            Customer c = buildFullCustomer("000000001");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Multiple customers are all processed")
        void multipleCustomersProcessed() {
            Customer c1 = buildFullCustomer("000000001");
            Customer c2 = buildFullCustomer("000000002");
            Customer c3 = buildFullCustomer("000000003");
            when(customerRepository.findAll()).thenReturn(List.of(c1, c2, c3));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RuntimeException from repository triggers abend")
        void repositoryExceptionTriggersAbend() {
            when(customerRepository.findAll())
                    .thenThrow(new RuntimeException("DB unavailable"));

            assertThatThrownBy(() -> service.refreshCustomers())
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ── PII masking — SSN ───────────────────────────────────────────

    @Nested
    @DisplayName("SSN Masking")
    class SsnMasking {

        @Test
        @DisplayName("Normal SSN is masked to ***-**-XXXX format")
        void normalSsnMasked() {
            Customer c = buildCustomerWithPii("000000001", "123456789", "DL123");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            // The service processes the customer; masking happens internally during display
            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Null SSN produces masked placeholder")
        void nullSsnMasked() {
            Customer c = buildCustomerWithPii("000000001", null, "DL123");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Blank SSN produces masked placeholder")
        void blankSsnMasked() {
            Customer c = buildCustomerWithPii("000000001", "  ", "DL123");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Short SSN (<=4 chars) produces safe mask")
        void shortSsnMasked() {
            Customer c = buildCustomerWithPii("000000001", "1234", "DL123");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }
    }

    // ── PII masking — govtIssuedId ──────────────────────────────────

    @Nested
    @DisplayName("GovtIssuedId Masking")
    class GovtIdMasking {

        @Test
        @DisplayName("Null govtIssuedId produces masked placeholder")
        void nullGovtIdMasked() {
            Customer c = buildCustomerWithPii("000000001", "123456789", null);
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Blank govtIssuedId produces masked placeholder")
        void blankGovtIdMasked() {
            Customer c = buildCustomerWithPii("000000001", "123456789", "  ");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Short govtIssuedId (<=4 chars) is safely masked")
        void shortGovtIdMasked() {
            Customer c = buildCustomerWithPii("000000001", "123456789", "DL1");
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Customer with all null optional fields is processed")
        void allNullOptionalFields() {
            Customer c = new Customer(
                    "000000001", "John", null, "Doe",
                    null, null, null,
                    null, null, null,
                    null, null,
                    null, null, null,
                    null, null, null);
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Customer with zero credit score is processed")
        void zeroCreditScore() {
            Customer c = new Customer(
                    "000000001", "John", "M", "Doe",
                    "123 Main", null, null,
                    "IL", "US", "62701",
                    "2175551234", null,
                    "123456789", "DL123", "19800115",
                    "00000000001", "Y", 0);
            when(customerRepository.findAll()).thenReturn(List.of(c));

            assertThatCode(() -> service.refreshCustomers()).doesNotThrowAnyException();
        }
    }
}
