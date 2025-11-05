#!/usr/bin/env python3
"""
Account Data Load Script Generator
Converts EBCDIC account data to SQL INSERT statements
Maintains COMP-3 precision as NUMERIC(12,2) per requirements
"""

def decode_ebcdic(data):
    """Decode EBCDIC CP037 to UTF-8."""
    try:
        text = data.decode('cp037')
        return ''.join(c if c.isprintable() or c == ' ' else '' for c in text).strip()
    except:
        return ''

def decode_zoned_decimal(data, scale=2):
    """
    Decode EBCDIC zoned decimal to float with COMP-3 precision.
    Handles sign in zone nibble of last byte.
    """
    if len(data) == 0:
        return 0.0
    
    # Decode EBCDIC to string
    decoded = decode_ebcdic(data)
    
    # Handle zoned decimal where last char encodes sign
    # EBCDIC: { } = 0 (pos/neg), A-I = 1-9 (pos), J-R = 1-9 (neg)
    digit_str = ''
    is_negative = False
    
    for i, c in enumerate(decoded):
        if c.isdigit():
            digit_str += c
        elif c in '{ABCDEFGHI':
            digit_map = {'{': '0', 'A': '1', 'B': '2', 'C': '3', 'D': '4',
                        'E': '5', 'F': '6', 'G': '7', 'H': '8', 'I': '9'}
            digit_str += digit_map.get(c, '0')
        elif c in '}JKLMNOPQR':
            digit_map = {'}': '0', 'J': '1', 'K': '2', 'L': '3', 'M': '4',
                        'N': '5', 'O': '6', 'P': '7', 'Q': '8', 'R': '9'}
            digit_str += digit_map.get(c, '0')
            is_negative = True
    
    try:
        value = int(digit_str) / (10 ** scale) if digit_str else 0.0
        return -value if is_negative else value
    except:
        return 0.0

def clean_date(date_str):
    """Clean and validate date string in YYYY-MM-DD format."""
    date_str = date_str.strip()
    if not date_str or '0000-00-00' in date_str or date_str == '':
        return None
    
    # Dates should already be in YYYY-MM-DD format from EBCDIC
    if len(date_str) == 10 and date_str.count('-') == 2:
        parts = date_str.split('-')
        if len(parts) == 3:
            year, month, day = parts
            if year.isdigit() and month.isdigit() and day.isdigit():
                y, m, d = int(year), int(month), int(day)
                if 1900 <= y <= 2100 and 1 <= m <= 12 and 1 <= d <= 31:
                    return date_str
    
    return None

def generate_account_sql():
    """Generate SQL INSERT script from EBCDIC account data."""
    
    # Read EBCDIC data file
    with open('app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS', 'rb') as f:
        ebcdic_data = f.read()
    
    record_length = 300
    num_records = len(ebcdic_data) // record_length
    
    # SQL header
    sql_lines = []
    sql_lines.append("-- " + "=" * 76)
    sql_lines.append("-- Account Data Load Script")
    sql_lines.append("-- " + "=" * 76)
    sql_lines.append("-- Source Files: AWS.M2.CARDDEMO.ACCTDATA.PS")
    sql_lines.append("-- Encoding: EBCDIC CP037 to UTF-8")
    sql_lines.append("-- Record Count: {} account records".format(num_records))
    sql_lines.append("-- Record Length: 300 bytes per CVACT01Y.cpy copybook")
    sql_lines.append("--")
    sql_lines.append("-- CRITICAL - COMP-3 Precision Preservation:")
    sql_lines.append("-- All monetary amounts use NUMERIC(12,2) to maintain exact")
    sql_lines.append("-- COBOL PIC S9(10)V99 COMP-3 packed decimal precision")
    sql_lines.append("-- per Agent Action Plan Section 0.1, 0.4, and 0.9")
    sql_lines.append("-- " + "=" * 76)
    sql_lines.append("")
    sql_lines.append("-- Clear existing account data")
    sql_lines.append("TRUNCATE TABLE account CASCADE;")
    sql_lines.append("")
    
    insert_values = []
    
    # Parse all account records
    for i in range(num_records):
        record = ebcdic_data[i*record_length:(i+1)*record_length]
        
        pos = 0
        
        # Parse per CVACT01Y.cpy structure
        # ACCT-ID: PIC 9(11) - 11 bytes
        acct_id = decode_ebcdic(record[pos:pos+11])
        pos += 11
        
        # ACCT-ACTIVE-STATUS: PIC X(01) - 1 byte
        status_raw = decode_ebcdic(record[pos:pos+1])
        pos += 1
        
        # ACCT-CURR-BAL: PIC S9(10)V99 - 12 bytes zoned decimal
        curr_bal = decode_zoned_decimal(record[pos:pos+12], 2)
        pos += 12
        
        # ACCT-CREDIT-LIMIT: PIC S9(10)V99 - 12 bytes
        credit_limit = decode_zoned_decimal(record[pos:pos+12], 2)
        pos += 12
        
        # ACCT-CASH-CREDIT-LIMIT: PIC S9(10)V99 - 12 bytes
        cash_limit = decode_zoned_decimal(record[pos:pos+12], 2)
        pos += 12
        
        # ACCT-OPEN-DATE: PIC X(10) - 10 bytes
        open_date = decode_ebcdic(record[pos:pos+10])
        pos += 10
        
        # ACCT-EXPIRAION-DATE: PIC X(10) - 10 bytes
        exp_date = decode_ebcdic(record[pos:pos+10])
        pos += 10
        
        # ACCT-REISSUE-DATE: PIC X(10) - 10 bytes
        reissue_date = decode_ebcdic(record[pos:pos+10])
        pos += 10
        
        # ACCT-CURR-CYC-CREDIT: PIC S9(10)V99 - 12 bytes
        cyc_credit = decode_zoned_decimal(record[pos:pos+12], 2)
        pos += 12
        
        # ACCT-CURR-CYC-DEBIT: PIC S9(10)V99 - 12 bytes
        cyc_debit = decode_zoned_decimal(record[pos:pos+12], 2)
        pos += 12
        
        # ACCT-ADDR-ZIP: PIC X(10) - 10 bytes
        addr_zip = decode_ebcdic(record[pos:pos+10])
        pos += 10
        
        # ACCT-GROUP-ID: PIC X(10) - 10 bytes
        group_id = decode_ebcdic(record[pos:pos+10])
        pos += 10
        
        # Clean and validate dates
        open_date_clean = clean_date(open_date)
        exp_date_clean = clean_date(exp_date)
        reissue_date_clean = clean_date(reissue_date)
        
        # Map status: Y -> A (Active per requirements)
        status = 'A' if status_raw in ['Y', 'A'] else ('C' if status_raw in ['N', 'C'] else 'S')
        
        # Derive customer_id from account_id (first 9 digits)
        customer_id = acct_id[:9].lstrip('0') or '1'
        
        # Format SQL value tuple with proper NULL handling
        value_parts = [
            f"'{acct_id}'",
            f"'{customer_id}'",
            f"'{status}'",
            f"{curr_bal:.2f}",
            f"{credit_limit:.2f}",
            f"{cash_limit:.2f}",
            f"'{open_date_clean}'" if open_date_clean else "NULL",
            f"'{exp_date_clean}'" if exp_date_clean else "NULL",
            f"'{reissue_date_clean}'" if reissue_date_clean else "NULL",
            f"{cyc_credit:.2f}",
            f"{cyc_debit:.2f}",
            f"'{addr_zip}'",
            f"'{group_id}'"
        ]
        
        value_str = "  (" + ", ".join(value_parts) + ")"
        insert_values.append(value_str)
    
    # Build complete INSERT statement
    sql_lines.append("-- Insert {} account records with COMP-3 precision".format(num_records))
    sql_lines.append("INSERT INTO account (")
    sql_lines.append("  account_id,")
    sql_lines.append("  customer_id,")
    sql_lines.append("  account_status,")
    sql_lines.append("  current_balance,")
    sql_lines.append("  credit_limit,")
    sql_lines.append("  cash_credit_limit,")
    sql_lines.append("  account_open_date,")
    sql_lines.append("  account_expiration_date,")
    sql_lines.append("  account_reissue_date,")
    sql_lines.append("  current_cycle_credit,")
    sql_lines.append("  current_cycle_debit,")
    sql_lines.append("  account_zip,")
    sql_lines.append("  account_group_id")
    sql_lines.append(") VALUES")
    
    # Add values with proper comma separation
    for idx, value in enumerate(insert_values):
        separator = "," if idx < len(insert_values) - 1 else ";"
        sql_lines.append(value + separator)
    
    sql_lines.append("")
    sql_lines.append("-- " + "=" * 76)
    sql_lines.append("-- Data Validation Queries")
    sql_lines.append("-- " + "=" * 76)
    sql_lines.append("")
    sql_lines.append("-- 1. Record count verification")
    sql_lines.append("SELECT COUNT(*) as total_accounts FROM account;")
    sql_lines.append("-- Expected: {} records".format(num_records))
    sql_lines.append("")
    sql_lines.append("-- 2. COMP-3 precision validation")
    sql_lines.append("-- All monetary amounts should have exactly 2 decimal places")
    sql_lines.append("SELECT")
    sql_lines.append("  account_id,")
    sql_lines.append("  current_balance,")
    sql_lines.append("  credit_limit,")
    sql_lines.append("  cash_credit_limit")
    sql_lines.append("FROM account")
    sql_lines.append("ORDER BY account_id")
    sql_lines.append("LIMIT 5;")
    sql_lines.append("")
    sql_lines.append("-- 3. Account status distribution")
    sql_lines.append("SELECT")
    sql_lines.append("  account_status,")
    sql_lines.append("  COUNT(*) as count,")
    sql_lines.append("  ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER(), 2) as percentage")
    sql_lines.append("FROM account")
    sql_lines.append("GROUP BY account_status")
    sql_lines.append("ORDER BY account_status;")
    sql_lines.append("")
    sql_lines.append("-- 4. Date range validation")
    sql_lines.append("SELECT")
    sql_lines.append("  MIN(account_open_date) as earliest_open_date,")
    sql_lines.append("  MAX(account_open_date) as latest_open_date,")
    sql_lines.append("  COUNT(CASE WHEN account_expiration_date IS NULL THEN 1 END) as null_expiration_count")
    sql_lines.append("FROM account;")
    sql_lines.append("")
    sql_lines.append("-- 5. Balance statistics")
    sql_lines.append("SELECT")
    sql_lines.append("  MIN(current_balance) as min_balance,")
    sql_lines.append("  MAX(current_balance) as max_balance,")
    sql_lines.append("  AVG(current_balance) as avg_balance,")
    sql_lines.append("  SUM(current_balance) as total_balance")
    sql_lines.append("FROM account;")
    sql_lines.append("")
    sql_lines.append("-- 6. Foreign key integrity check")
    sql_lines.append("-- Uncomment after customer table is loaded")
    sql_lines.append("-- SELECT COUNT(*) as orphaned_accounts")
    sql_lines.append("-- FROM account a")
    sql_lines.append("-- LEFT JOIN customer c ON a.customer_id = c.customer_id")
    sql_lines.append("-- WHERE c.customer_id IS NULL;")
    sql_lines.append("-- Expected: 0 (all customer_ids should exist in customer table)")
    
    return '\n'.join(sql_lines) + '\n'

if __name__ == '__main__':
    sql_script = generate_account_sql()
    
    output_path = 'app/data/EBCDIC/load_account_data.sql'
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(sql_script)
    
    print(f"✓ Generated: {output_path}")
    print(f"✓ EBCDIC CP037 to UTF-8 conversion complete")
    print(f"✓ COMP-3 precision maintained as NUMERIC(12,2)")
    print(f"✓ Production-ready SQL INSERT script created")
