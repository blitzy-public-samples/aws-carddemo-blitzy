-- Corrected test data with proper column names matching actual schema
-- This file is used by load-test-data.sh when text files are in fixed-width format

BEGIN;

-- Clear existing data
TRUNCATE TABLE transaction CASCADE;
TRUNCATE TABLE card CASCADE;
TRUNCATE TABLE account CASCADE;
TRUNCATE TABLE customer CASCADE;

-- Insert customers (3 sample records)
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line1, address_line2, address_line3, state_code, country_code, zip_code, phone_number1, phone_number2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_indicator, fico_credit_score) VALUES
('000000001', 'John', 'A', 'Doe', '123 Main St', 'Apt 1', '', 'CA', 'USA', '12345', '555-1234', '555-5678', '123456789', 'DL123456', '1980-01-15', '1234567890', 'Y', 750),
('000000002', 'Jane', 'B', 'Smith', '456 Oak Ave', 'Suite 200', '', 'NY', 'USA', '67890', '555-2345', '555-6789', '987654321', 'DL654321', '1985-03-20', '0987654321', 'Y', 680),
('000000003', 'Bob', 'C', 'Johnson', '789 Pine Rd', '', '', 'TX', 'USA', '54321', '555-3456', '555-7890', '456789123', 'DL789123', '1975-07-10', '4567891230', 'Y', 720);

-- Insert accounts (3 sample records)
INSERT INTO account (account_id, customer_id, account_status, current_balance, credit_limit, cash_credit_limit, account_open_date, account_expiration_date, account_reissue_date, current_cycle_credit, current_cycle_debit, account_group_id) VALUES
('00000000001', '000000001', 'A', 1500.00, 5000.00, 1000.00, '2020-01-01', '2025-01-01', '2025-01-01', 0.00, 1500.00, '0000000001'),
('00000000002', '000000002', 'A', 2500.00, 10000.00, 2000.00, '2019-06-15', '2024-06-15', '2024-06-15', 0.00, 2500.00, '0000000001'),
('00000000003', '000000003', 'A', 500.00, 3000.00, 500.00, '2021-03-10', '2026-03-10', '2026-03-10', 0.00, 500.00, '0000000001');

-- Insert cards (3 sample records)
INSERT INTO card (card_number, account_id, cvv_code, embossed_name, card_expiration_date, card_status) VALUES
('4111111111111111', '00000000001', '123', 'JOHN A DOE', '2025-12-31', 'A'),
('4222222222222222', '00000000002', '456', 'JANE B SMITH', '2024-11-30', 'A'),
('4333333333333333', '00000000003', '789', 'BOB C JOHNSON', '2026-10-31', 'A');

-- Insert transactions (5 sample records)
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, transaction_source, transaction_description, transaction_amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_number, transaction_timestamp) VALUES
('00000000001', '01', '01', 'POS', 'Grocery Store Purchase', 125.50, 'MERCH001', 'SuperMart', 'Los Angeles', '90001', '4111111111111111', '2024-01-15 10:30:00'),
('00000000002', '01', '02', 'POS', 'Gas Station Purchase', 45.00, 'MERCH002', 'Gas & Go', 'New York', '10001', '4222222222222222', '2024-01-16 14:20:00'),
('00000000003', '01', '03', 'POS', 'Restaurant Purchase', 85.75, 'MERCH003', 'Fine Dining', 'Houston', '77001', '4333333333333333', '2024-01-17 19:45:00'),
('00000000004', '01', '01', 'ONL', 'Online Shopping', 250.00, 'MERCH004', 'E-Shop', 'Seattle', '98101', '4111111111111111', '2024-01-18 09:15:00'),
('00000000005', '02', '01', 'ATM', 'ATM Withdrawal', 100.00, 'ATM001', 'Bank ATM', 'San Francisco', '94101', '4222222222222222', '2024-01-19 16:30:00');

COMMIT;

-- Display counts
SELECT 'Customer' as table_name, COUNT(*) as record_count FROM customer
UNION ALL
SELECT 'Account', COUNT(*) FROM account
UNION ALL
SELECT 'Card', COUNT(*) FROM card
UNION ALL
SELECT 'Transaction', COUNT(*) FROM transaction;
