-- Test data for CustomerRepositoryTest
-- This script creates sample customer records matching the 500-byte COBOL CUSTOMER-RECORD structure
-- from CVCUS01Y.cpy copybook for VSAM CUSTDAT KSDS file migration testing

-- Clean up existing test data
DELETE FROM customer WHERE customer_id >= 1000000001 AND customer_id <= 1000000010;

-- Insert test customer records
INSERT INTO customer (
  customer_id, first_name, middle_name, last_name,
  address_line_1, address_line_2, address_line_3,
  state_code, country_code, zip_code,
  phone_number_1, phone_number_2,
  ssn, government_issued_id, date_of_birth,
  eft_account_id, primary_card_holder_indicator, fico_credit_score
) VALUES
  -- Test Customer 1: John Q Smith (used in primary test cases)
  (1000000001, 'John', 'Q', 'Smith', '123 Main St', 'Apt 4B', '', 
   'TX', 'USA', '75001', '214-555-0100', '', 
   '123456789', 'DL-TX-12345678', '1980-05-15',
   'EFT1234567', 'Y', 720),
   
  -- Test Customer 2: Jane M Doe (used for sequential read and search tests)
  (1000000002, 'Jane', 'M', 'Doe', '456 Oak Ave', '', '',
   'CA', 'USA', '90001', '310-555-0200', '310-555-0201',
   '234567890', 'DL-CA-87654321', '1975-08-22',
   'EFT2345678', 'Y', 780);
