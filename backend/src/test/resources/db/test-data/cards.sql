-- Test data for CardRepositoryTest
-- This script creates sample card records matching the 150-byte COBOL CARD-RECORD structure
-- from CVACT02Y.cpy copybook for VSAM CARDDAT KSDS file migration testing

-- Clean up existing test data
DELETE FROM card WHERE card_number IN (
  '4000123456789010', '4000123456789011', '4000123456789012',
  '4000123456789013', '4000123456789014', '4000123456789015',
  '4000123456789016', '4000123456789017',
  '5000234567890120', '5000234567890121', '5000234567890122'
);

-- Insert test card records
-- Note: account_id values must match existing accounts from accounts.sql
-- Card entity columns: card_number (PK), account_id, cvv_code, embossed_name, expiration_date, active_status
-- Status codes from CardStatus enum: 'Y'=ACTIVE, 'E'=EXPIRED, 'B'=BLOCKED, 'N'=INACTIVE, 'C'=CLOSED, 'P'=PENDING
INSERT INTO card (
  card_number, account_id, embossed_name,
  expiration_date, active_status, cvv_code
) VALUES
  -- Test Card 1: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- Used in testFindById_ValidCardNumber, testFindByAccountId_ValidAccount
  -- Validates CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11), CARD-CVV-CD PIC 9(03)
  ('4000123456789010', 10000000001, 'JOHN Q SMITH',
   '2025-12-31', 'Y', '123'),
  
  -- Test Card 2: Account 10000000001 (Customer 1000000001) - EXPIRED card
  -- Used in testFindByActiveStatus_Expired
  -- Validates COBOL 88-level CARD-EXPIRED VALUE 'E'
  ('4000123456789011', 10000000001, 'JOHN Q SMITH',
   '2024-06-30', 'E', '456'),
  
  -- Test Card 3: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- Additional active card for same account
  ('4000123456789012', 10000000001, 'JOHN Q SMITH',
   '2026-03-31', 'Y', '789'),
  
  -- Test Card 4: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- For pagination testing (7 cards per page per BMS screen requirement)
  ('4000123456789013', 10000000001, 'JOHN Q SMITH',
   '2026-06-30', 'Y', '321'),
  
  -- Test Card 5: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- For pagination testing
  ('4000123456789014', 10000000001, 'JOHN Q SMITH',
   '2026-09-30', 'Y', '654'),
  
  -- Test Card 6: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- For pagination testing
  ('4000123456789015', 10000000001, 'JOHN Q SMITH',
   '2027-03-31', 'Y', '987'),
  
  -- Test Card 7: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- For pagination testing (7th card to trigger pagination)
  ('4000123456789016', 10000000001, 'JOHN Q SMITH',
   '2027-06-30', 'Y', '135'),
  
  -- Test Card 8: Account 10000000001 (Customer 1000000001) - ACTIVE card
  -- 8th card to test page 2 in pagination
  ('4000123456789017', 10000000001, 'JOHN Q SMITH',
   '2027-09-30', 'Y', '246'),
  
  -- Test Card 9: Account 10000000002 (Customer 1000000002) - ACTIVE card
  -- Used for different account testing
  ('5000234567890120', 10000000002, 'JANE M DOE',
   '2025-09-30', 'Y', '234'),
  
  -- Test Card 10: Account 10000000002 (Customer 1000000002) - EXPIRED card
  -- Additional expired card for testing
  ('5000234567890121', 10000000002, 'JANE M DOE',
   '2023-12-31', 'E', '567'),
  
  -- Test Card 11: Account 10000000002 (Customer 1000000002) - BLOCKED card
  -- Used in testFindByActiveStatus_Blocked
  -- Validates COBOL 88-level CARD-BLOCKED VALUE 'B'
  ('5000234567890122', 10000000002, 'JANE M DOE',
   '2027-01-31', 'B', '890');
