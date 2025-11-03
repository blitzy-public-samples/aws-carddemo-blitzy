-- Test data for CardRepositoryTest and AccountXrefRepositoryTest
-- This script creates sample card records matching the 150-byte COBOL CARD-RECORD structure
-- from CVACT02Y.cpy copybook for VSAM CARDDAT KSDS file migration testing

-- Clean up existing test data
DELETE FROM card WHERE card_number IN (
  '4000123456789010', '4000123456789011', '4000123456789012',
  '5000234567890120', '5000234567890121'
);

-- Insert test card records
-- Note: account_id values must match existing accounts from accounts.sql
-- Card entity columns: card_number (PK), account_id, cvv_code, embossed_name, expiration_date, active_status
INSERT INTO card (
  card_number, account_id, embossed_name,
  expiration_date, active_status, cvv_code
) VALUES
  -- Test Card 1: Account 10000000021 (Customer 100000001 - 9 digits) - Active card
  -- Used in AccountXrefRepositoryTest (VALID_CARD_NUM_1)
  ('4000123456789010', 10000000021, 'JOHN Q SMITH',
   '2027-12-31', 'Y', '123'),
  
  -- Test Card 2: Account 10000000021 (Customer 100000001 - 9 digits) - Active card
  -- Additional card for same account (multiple cards per account test)
  ('4000123456789011', 10000000021, 'JOHN Q SMITH',
   '2028-06-30', 'Y', '456'),
  
  -- Test Card 3: Account 10000000021 (Customer 100000001 - 9 digits) - Active card
  -- Third card for same account (multiple cards per account test)
  ('4000123456789012', 10000000021, 'JOHN Q SMITH',
   '2026-09-30', 'Y', '789'),
  
  -- Test Card 4: Account 10000000023 (Customer 100000002 - 9 digits) - Active card
  -- Used in AccountXrefRepositoryTest (VALID_CARD_NUM_2)
  ('5000234567890120', 10000000023, 'JANE M DOE',
   '2027-03-31', 'Y', '234'),
  
  -- Test Card 5: Account 10000000023 (Customer 100000002 - 9 digits) - Active card
  -- Additional card for customer 2
  ('5000234567890121', 10000000023, 'JANE M DOE',
   '2028-12-31', 'Y', '567');
