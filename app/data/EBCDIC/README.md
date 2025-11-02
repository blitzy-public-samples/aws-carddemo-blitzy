# EBCDIC Test Data Conversion for CardDemo Migration

This directory contains EBCDIC-encoded test data files from the mainframe CardDemo application and utilities to convert them to PostgreSQL SQL INSERT statements.

## Overview

Per Agent Action Plan Section 0.4, all 12 EBCDIC .PS files must be converted from EBCDIC CP037 encoding to UTF-8 and transformed into SQL INSERT statements for PostgreSQL database initialization.

### EBCDIC Source Files (12 files, 161KB total)

| File | Size | Records | Record Length | Description |
|------|------|---------|---------------|-------------|
| AWS.M2.CARDDEMO.CUSTDATA.PS | 25000 bytes | ~50 | 500 bytes | Customer data (CVCUS01Y.cpy) |
| AWS.M2.CARDDEMO.ACCTDATA.PS | 15000 bytes | ~50 | 300 bytes | Account data (CVACT01Y.cpy) |
| AWS.M2.CARDDEMO.ACCDATA.PS | 15000 bytes | ~50 | 300 bytes | Account data variant |
| AWS.M2.CARDDEMO.CARDDATA.PS | 7500 bytes | ~50 | 150 bytes | Card data (CVACT02Y.cpy) |
| AWS.M2.CARDDEMO.DALYTRAN.PS | 105000 bytes | ~300+ | 350 bytes | Daily transaction data (CVTRA05Y.cpy) |
| AWS.M2.CARDDEMO.DALYTRAN.PS.INIT | 350 bytes | 1 | 350 bytes | Transaction initialization |
| AWS.M2.CARDDEMO.CARDXREF.PS | 2500 bytes | ~50 | 50 bytes | Card cross-reference (CVACT03Y.cpy) |
| AWS.M2.CARDDEMO.TCATBALF.PS | 2500 bytes | ~50 | 50 bytes | Transaction category balance (CVTRA01Y.cpy) |
| AWS.M2.CARDDEMO.TRANTYPE.PS | 420 bytes | 7 | 60 bytes | Transaction type reference |
| AWS.M2.CARDDEMO.TRANCATG.PS | 1080 bytes | 18 | 60 bytes | Transaction category reference |
| AWS.M2.CARDDEMO.DISCGRP.PS | 2550 bytes | 51 | 50 bytes | Discount group configuration |
| AWS.M2.CARDDEMO.USRSEC.PS | 800 bytes | 10 | 80 bytes | User security data (CSUSR01Y.cpy) |

## Conversion Process

### Prerequisites

- Python 3.9 or later
- PostgreSQL 15+ database
- Required Python package: `bcrypt`

```bash
pip install bcrypt
```

### Step 1: Convert EBCDIC to SQL

Run the Python conversion utility:

```bash
# Convert all files at once
python3 convert_ebcdic_to_sql.py --all

# Or convert individual files
python3 convert_ebcdic_to_sql.py \
  --input AWS.M2.CARDDEMO.CUSTDATA.PS \
  --output load_customer_data.sql \
  --table customer
```

This will:
1. Read EBCDIC .PS files in binary mode
2. Convert from EBCDIC CP037 to UTF-8
3. Parse fixed-width records per COBOL copybook layouts
4. Unpack COMP-3 packed decimal fields with exact precision
5. Hash passwords using BCrypt (strength 12) for USRSEC data
6. Generate SQL INSERT statements
7. Validate with SHA-256 checksums for zero data loss

### Step 2: Load Data into PostgreSQL

```bash
# Ensure database schema is created first
psql -d carddemo -f backend/src/main/resources/db/migration/V1__create_customer_table.sql
# ... (V1-V8 migrations)

# Load all EBCDIC test data
psql -d carddemo -f load_all_ebcdic_data.sql
```

## Critical Conversion Requirements

### 1. EBCDIC CP037 Encoding

All .PS files are in EBCDIC CP037 encoding (IBM mainframe native format). Python `codecs.decode('cp037')` or `iconv -f CP037 -t UTF-8` must be used for conversion.

**Character Mapping:**
- EBCDIC 0x40 → Space
- EBCDIC 0xF0-0xF9 → Digits 0-9
- EBCDIC 0xC1-0xC9, 0xD1-0xD9, 0xE2-0xE9 → Letters A-Z

### 2. COMP-3 Packed Decimal (CRITICAL)

Per Section 0.1 and 0.9, COBOL COMP-3 fields require exact precision preservation:

**Example: PIC S9(10)V99 COMP-3 (7 bytes)**
- Packed BCD format: 2 digits per byte
- Last nibble = sign (0xC positive, 0xD negative)
- Scale: 2 decimal places
- Target: PostgreSQL NUMERIC(12, 2)
- Rounding: HALF_UP (COBOL default)

**Fields Using COMP-3:**
- Account: current_balance, credit_limit, cash_credit_limit, cycle amounts
- Transaction: transaction_amount
- Transaction category balance: category_balance

### 3. BCrypt Password Hashing (SECURITY CRITICAL)

User security data (USRSEC.PS) contains plaintext passwords that MUST be hashed:

```python
import bcrypt
hashed = bcrypt.hashpw(plaintext.encode('utf-8'), bcrypt.gensalt(rounds=12))
```

**Requirements:**
- BCrypt strength: 12 rounds (per Section 0.5)
- Hash format: $2b$12$... (~60 characters)
- Store in password_hash VARCHAR(100) column
- Never store plaintext passwords

### 4. Fixed-Width Record Parsing

Each file has fixed record lengths defined by COBOL copybooks:

```
Customer (500 bytes):
  customer_id:     bytes 0-9   (PIC 9(09))
  first_name:      bytes 9-34  (PIC X(25))
  last_name:       bytes 59-84 (PIC X(25))
  ...

Account (300 bytes):
  account_id:      bytes 0-11  (PIC 9(11))
  customer_id:     bytes 11-20 (PIC 9(09))
  current_balance: bytes 21-27 (PIC S9(10)V99 COMP-3)
  ...
```

## Data Validation (Section 0.9 Compliance)

### Zero Data Loss Verification

1. **Record Count Validation:**
   ```sql
   SELECT COUNT(*) FROM customer; -- Expected: ~50
   SELECT COUNT(*) FROM transaction; -- Expected: ~300+
   ```

2. **Checksum Validation:**
   - Source EBCDIC file SHA-256 checksum computed
   - Checksums logged in SQL file comments
   - Verify no data corruption during conversion

3. **Field-by-Field Comparison:**
   - Compare first 5 records manually
   - Verify all fields extracted correctly
   - Validate date formats (YYYY-MM-DD)
   - Check numeric field precision

4. **COMP-3 Precision Validation:**
   ```sql
   SELECT account_id, current_balance, SCALE(current_balance)
   FROM account
   WHERE SCALE(current_balance) != 2;
   -- Expected: 0 rows (all must have scale=2)
   ```

5. **Foreign Key Integrity:**
   ```sql
   SELECT COUNT(*) FROM account a
   LEFT JOIN customer c ON a.customer_id = c.customer_id
   WHERE c.customer_id IS NULL;
   -- Expected: 0 rows (no orphans)
   ```

## Troubleshooting

### Issue: EBCDIC Characters Not Converting

**Symptom:** Garbled text after conversion

**Solution:** Verify using CP037 codec:
```python
data.decode('cp037')  # Not 'cp500' or other EBCDIC variants
```

### Issue: COMP-3 Precision Loss

**Symptom:** Monetary amounts have incorrect decimal places

**Solution:** Use Python `Decimal` with explicit scale:
```python
from decimal import Decimal, ROUND_HALF_UP
value = Decimal(str(unpacked_value))
value = value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
```

### Issue: BCrypt Hash Not Valid

**Symptom:** Authentication fails, hash format incorrect

**Solution:** Verify BCrypt rounds and format:
```python
# Correct: $2b$12$... (60 chars)
hashed = bcrypt.hashpw(pwd.encode('utf-8'), bcrypt.gensalt(rounds=12))
```

### Issue: Record Count Mismatch

**Symptom:** Fewer records loaded than expected

**Solution:** Check record length calculation:
```python
record_count = len(file_data) // record_length
# Ensure no partial records at end of file
```

## Integration with Backend Migration

This EBCDIC test data complements ASCII test data in `app/data/ASCII/` and integrates with the backend migration strategy:

1. **V1-V8 Flyway Migrations:** Create PostgreSQL schema
2. **V9 Flyway Migration:** Load ASCII reference data
3. **EBCDIC Data Loading:** Load EBCDIC test data using this directory's scripts
4. **Migration Validation:** Verify functional equivalence with mainframe data

Target integration point:
- `backend/src/main/resources/db/migration/V9__load_reference_data.sql` (reference data)
- Separate test data loading scripts for transactional data
- Coordinate with backend migration agent per Section 0.4

## Files Generated

After conversion, this directory contains:

- `load_customer_data.sql` - Customer INSERT statements
- `load_account_data.sql` - Account INSERT statements with COMP-3 precision
- `load_card_data.sql` - Card INSERT statements
- `load_transaction_data.sql` - Transaction INSERT statements (~300+ records)
- `load_user_security_data.sql` - User data with BCrypt hashed passwords
- `load_xref_data.sql` - Cross-reference data
- `load_reference_data.sql` - Reference data (trantype, trancatg, discgrp)
- `load_all_ebcdic_data.sql` - Master script executing all in dependency order
- `convert_ebcdic_to_sql.py` - Python conversion utility

## References

- Agent Action Plan Section 0.4: EBCDIC file transformation requirements
- Agent Action Plan Section 0.9: Zero data loss and precision requirements
- COBOL Copybooks: `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVACT01Y.cpy`, etc.
- V1-V9 Flyway Migrations: `backend/src/main/resources/db/migration/`

## Support

For issues with EBCDIC conversion:
1. Verify Python 3.9+ and bcrypt library installed
2. Check EBCDIC encoding (must be CP037)
3. Validate COMP-3 unpacking logic
4. Review SHA-256 checksums for data integrity
5. Consult Agent Action Plan Section 0.4 for requirements
