package com.carddemo.account;

import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.entity.OutboxEventEntity;

import java.lang.reflect.RecordComponent;
import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that nothing this service can hand to a logger carries a cardholder field, an account
 * identifier or a monetary amount.
 *
 * <p>No COBOL program renders a record for a log. This class has no ancestor.
 *
 * <p>{@link CustomerDataRequest} is the highest-value target on the platform. It is a record, so the
 * compiler writes a {@code toString} that prints every component unless the record overrides it, and
 * its components are the whole of a cardholder: the three Social Security Number parts of
 * {@code app/cpy/CVCUS01Y.cpy:L17}, the government-issued identifier of
 * {@code app/cpy/CVCUS01Y.cpy:L18}, a date of birth, a full postal address, two telephone numbers,
 * an electronic funds transfer account identifier, three names, the customer identifier and a credit
 * score.
 *
 * <p>Every assertion looks for the value rather than the field name, because a rendering that named
 * a field and withheld its value must pass.
 */
@DisplayName("Diagnostic redaction, the account service")
class DiagnosticRedactionTest {

    /** Every cardholder value the request fixture carries, each distinct from the others. */
    private static final List<String> CARDHOLDER_VALUES = List.of(
            "987654321",                    // customerId
            "GRACE",                        // firstName
            "BREWSTER",                     // middleName
            "HOPPERTON",                    // lastName
            "4741 WESTGATE TERRACE",        // addressLine1
            "APARTMENT 12B",                // addressLine2
            "ARLINGTON",                    // addressCity
            "22203",                        // addressZip
            "7035551212",                   // phoneNumber1
            "7035553434",                   // phoneNumber2
            "078",                          // socialSecurityPart1
            "05",                           // socialSecurityPart2
            "1120",                         // socialSecurityPart3
            "D18604253097654",              // governmentIssuedId
            "19061209",                     // dateOfBirth
            "8812345678",                   // eftAccountId
            "0742");                        // ficoCreditScore

    @Nested
    @DisplayName("CustomerDataRequest")
    class CustomerDataRequestDiagnostics {

        @Test
        @DisplayName("not one cardholder value reaches the rendering")
        void notOneCardholderValueReachesTheRendering() {
            String rendered = fullyPopulatedRequest().toString();

            for (String value : CARDHOLDER_VALUES) {
                assertThat(rendered)
                        .withFailMessage("the cardholder value '%s' reached a log line", value)
                        .doesNotContain(value);
            }
        }

        @Test
        @DisplayName("the three Social Security parts do not reappear joined either")
        void theSocialSecurityPartsDoNotReappearJoined() {
            String rendered = fullyPopulatedRequest().toString();

            assertThat(rendered).doesNotContain("078051120");
            assertThat(rendered).doesNotContain("078-05-1120");
        }

        @Test
        @DisplayName("the rendering names the class and reports each component as withheld")
        void theRenderingNamesTheClassTheFlagAndTheCount() {
            String rendered = fullyPopulatedRequest().toString();

            assertThat(rendered).startsWith("CustomerDataRequest[").endsWith("]");
            assertThat(rendered)
                    .withFailMessage("a component value reached the rendering")
                    .doesNotContain("=Y,")
                    .doesNotContain("=Y]");
        }

        @Test
        @DisplayName("every component of the record is named and withheld, and none is disclosed")
        void theWithheldCountEqualsEveryComponentButTheFlag() {
            List<String> components = Arrays.stream(CustomerDataRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            String rendered = fullyPopulatedRequest().toString();

            assertThat(components).contains("primaryCardHolderIndicator");
            for (String component : components) {
                assertThat(rendered)
                        .withFailMessage("a component was added without a place in this rendering, "
                                + "and %s is missing from it", component)
                        .contains(component + "=" + CustomerDataRequest.WITHHELD);
            }
            assertThat(components.size())
                    .withFailMessage("a component was added or removed, and the record now declares "
                            + "%d components", components.size())
                    .isEqualTo(countOccurrences(rendered, CustomerDataRequest.WITHHELD));
        }

        /**
         * Counts how many times one value appears in a rendering.
         *
         * @param text  the rendering to read
         * @param value the value to count
         * @return the number of occurrences
         */
        private int countOccurrences(String text, String value) {
            int count = 0;
            int from = text.indexOf(value);
            while (from >= 0) {
                count++;
                from = text.indexOf(value, from + value.length());
            }
            return count;
        }

        @Test
        @DisplayName("redaction changes no accessor, so validation still reads every field")
        void redactionChangesNoAccessor() {
            CustomerDataRequest request = fullyPopulatedRequest();

            assertThat(request.socialSecurityPart1()).isEqualTo("078");
            assertThat(request.socialSecurityPart2()).isEqualTo("05");
            assertThat(request.socialSecurityPart3()).isEqualTo("1120");
            assertThat(request.governmentIssuedId()).isEqualTo("D18604253097654");
            assertThat(request.dateOfBirth()).isEqualTo("19061209");
            assertThat(request.eftAccountId()).isEqualTo("8812345678");
            assertThat(request.addressLine1()).isEqualTo("4741 WESTGATE TERRACE");
            assertThat(request.phoneNumber1()).isEqualTo("7035551212");
            assertThat(request.ficoCreditScore()).isEqualTo("0742");
        }

        @Test
        @DisplayName("the wire payload still carries every cardholder component")
        void theWirePayloadStillCarriesEveryComponent() throws Exception {
            ObjectMapper mapper = JsonMapper.builder().build();
            CustomerDataRequest request = fullyPopulatedRequest();

            String json = mapper.writeValueAsString(request);

            for (String value : CARDHOLDER_VALUES) {
                assertThat(json)
                        .withFailMessage("redaction reached the wire and dropped '%s'", value)
                        .contains(value);
            }
            assertThat(json).doesNotContain("redacted");
            assertThat(mapper.readValue(json, CustomerDataRequest.class)).isEqualTo(request);
        }

        @Test
        @DisplayName("equals and hashCode still read every component")
        void equalsAndHashCodeStillReadEveryComponent() {
            CustomerDataRequest one = fullyPopulatedRequest();
            CustomerDataRequest same = fullyPopulatedRequest();
            CustomerDataRequest otherName = new CustomerDataRequest("987654321", "ADA", "BREWSTER",
                    "HOPPERTON", "4741 WESTGATE TERRACE", "APARTMENT 12B", "ARLINGTON", "VA", "USA",
                    "22203", "7035551212", "7035553434", "078", "05", "1120", "D18604253097654",
                    "19061209", "8812345678", "Y", "0742");

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one)
                    .withFailMessage("the override reached equality and collapsed two cardholders")
                    .isNotEqualTo(otherName);
        }

        /** Builds a request carrying every component, so no assertion passes by absence. */
        private CustomerDataRequest fullyPopulatedRequest() {
            return new CustomerDataRequest("987654321", "GRACE", "BREWSTER", "HOPPERTON",
                    "4741 WESTGATE TERRACE", "APARTMENT 12B", "ARLINGTON", "VA", "USA", "22203",
                    "7035551212", "7035553434", "078", "05", "1120", "D18604253097654", "19061209",
                    "8812345678", "Y", "0742");
        }
    }

    @Nested
    @DisplayName("AccountEntity, CustomerEntity and OutboxEventEntity")
    class EntityDiagnostics {

        /** Eleven digits, the width {@code ACCT-ID PIC 9(11)} declares. */
        private static final String ACCOUNT_ID = "98765432109";

        /** Nine digits, the width {@code CUST-ID PIC 9(09)} declares. */
        private static final String CUSTOMER_ID = "123456789";

        @Test
        @DisplayName("the account rendering withholds the identifier and keeps the status flag")
        void theAccountRenderingWithholdsTheIdentifier() {
            AccountEntity account = new AccountEntity();
            account.setAccountId(ACCOUNT_ID);
            account.setActiveStatus("Y");
            account.setCurrentBalance(new BigDecimal("4321.99"));
            account.setCreditLimit(new BigDecimal("5000.00"));

            String rendered = account.toString();

            assertThat(rendered)
                    .withFailMessage("the account identifier reached a log line")
                    .doesNotContain(ACCOUNT_ID);
            assertThat(rendered)
                    .withFailMessage("a monetary column reached a log line")
                    .doesNotContain("4321.99").doesNotContain("5000.00");
            assertThat(rendered).isEqualTo("AccountEntity[accountId=" + EventEnvelope.WITHHELD
                    + ", activeStatus=" + EventEnvelope.WITHHELD + "]");
            assertThat(account.getAccountId()).isEqualTo(ACCOUNT_ID);
        }

        /**
         * Renders one customer row carrying a cardholder name, an address and a credit score.
         *
         * <p>The customer identifier is stable and names one cardholder across every table of this
         * platform, so the rendering reports it as withheld beside every other column.
         */
        @Test
        @DisplayName("the customer rendering withholds the identifier and every other column")
        void theCustomerRenderingWithholdsTheIdentifier() {
            CustomerEntity customer = new CustomerEntity();
            customer.setCustomerId(CUSTOMER_ID);
            customer.setFirstName("Aniya");
            customer.setLastName("Von");
            customer.setAddressLine1("742 Evergreen Terrace");
            customer.setSocialSecurityNumber("111223333");
            customer.setFicoCreditScore(new BigDecimal("720"));

            String rendered = customer.toString();

            assertThat(rendered)
                    .withFailMessage("the customer identifier reached a log line")
                    .doesNotContain(CUSTOMER_ID);
            assertThat(rendered)
                    .withFailMessage("a cardholder value reached a log line")
                    .doesNotContain("Aniya").doesNotContain("Von")
                    .doesNotContain("742 Evergreen Terrace").doesNotContain("111223333")
                    .doesNotContain("720");
            assertThat(rendered)
                    .isEqualTo("CustomerEntity[customerId=" + EventEnvelope.WITHHELD + "]");
            assertThat(customer.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(customer.getFirstName()).isEqualTo("Aniya");
        }

        @Test
        @DisplayName("the outbox rendering withholds the aggregate identifier and the payload")
        void theOutboxRenderingWithholdsTheAggregateIdentifier() {
            String payload = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"creditLimit\":\"5000.00\"}";
            OutboxEventEntity event = new OutboxEventEntity(
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    "AccountStateChanged", payload, ACCOUNT_ID,
                    Instant.parse("2024-01-15T10:30:00Z"));

            String rendered = event.toString();

            assertThat(rendered)
                    .withFailMessage("the aggregate identifier reached a log line")
                    .doesNotContain(ACCOUNT_ID);
            assertThat(rendered)
                    .withFailMessage("the payload reached a log line")
                    .doesNotContain(payload).doesNotContain("creditLimit");
            assertThat(rendered).contains("eventId=11111111-2222-3333-4444-555555555555");
            assertThat(rendered).contains("eventType=AccountStateChanged");
            assertThat(rendered).contains("published=false");
            assertThat(rendered).contains("aggregateId=" + EventEnvelope.WITHHELD);
            assertThat(event.getAggregateId()).isEqualTo(ACCOUNT_ID);
            assertThat(event.getPayload()).isEqualTo(payload);
        }
    }
}
