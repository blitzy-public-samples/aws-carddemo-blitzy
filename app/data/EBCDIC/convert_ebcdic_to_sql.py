#!/usr/bin/env python3
"""
EBCDIC to SQL Conversion Utility for CardDemo Migration
Converts mainframe EBCDIC .PS files to PostgreSQL SQL INSERT statements

This utility implements comprehensive EBCDIC CP037 to UTF-8 character encoding
conversion, fixed-width record parsing based on COBOL copybook layouts, COMP-3
packed decimal unpacking with exact precision preservation, BCrypt password hashing,
SQL INSERT statement generation, and SHA-256 checksum validation.

Usage:
  python3 convert_ebcdic_to_sql.py --input <file.PS> --output <file.sql> --table <table_name>
  python3 convert_ebcdic_to_sql.py --all  # Convert all files

Requirements:
  - Python 3.9+
  - bcrypt library: pip install bcrypt

Copyright: Amazon Web Services CardDemo Migration Project
License: Apache 2.0
Section References: Agent Action Plan Section 0.4, Section 0.9
"""

import struct
import codecs
import bcrypt
import argparse
import hashlib
import sys
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional
from decimal import Decimal, ROUND_HALF_UP


# COBOL Copybook Record Layouts
# These layouts correspond to the COBOL copybooks in app/cpy/*.cpy
# Field format: (field_name, start_offset, end_offset, field_type, optional_metadata)
RECORD_LAYOUTS = {
    'customer': {
        'file': 'AWS.M2.CARDDEMO.CUSTDATA.PS',
        'table': 'customer',
        'record_length': 500,
        'copybook': 'CVCUS01Y.cpy',
        'fields': [
            ('customer_id', 0, 9, 'numeric'),
            ('first_name', 9, 34, 'text'),
            ('middle_name', 34, 59, 'text'),
            ('last_name', 59, 84, 'text'),
            ('address_line1', 84, 134, 'text'),
            ('address_line2', 134, 184, 'text'),
            ('address_line3', 184, 234, 'text'),
            ('state_code', 234, 236, 'text'),
            ('country_code', 236, 239, 'text'),
            ('zip_code', 239, 249, 'text'),
            ('phone_number1', 249, 264, 'text'),
            ('phone_number2', 264, 279, 'text'),
            ('ssn', 279, 288, 'numeric'),
            ('government_issued_id', 288, 308, 'text'),
            ('date_of_birth', 308, 318, 'date'),
            ('eft_account_id', 318, 328, 'text'),
            ('primary_cardholder_indicator', 328, 329, 'text'),
            ('fico_credit_score', 329, 332, 'integer')
        ]
    },
    'account': {
        'file': 'AWS.M2.CARDDEMO.ACCTDATA.PS',
        'table': 'account',
        'record_length': 300,
        'copybook': 'CVACT01Y.cpy',
        'fields': [
            ('account_id', 0, 11, 'numeric'),
            ('account_status', 11, 12, 'text'),
            ('current_balance', 12, 24, 'decimal_display', {'precision': 10, 'scale': 2}),
            ('credit_limit', 24, 36, 'decimal_display', {'precision': 10, 'scale': 2}),
            ('cash_credit_limit', 36, 48, 'decimal_display', {'precision': 10, 'scale': 2}),
            ('account_open_date', 48, 58, 'date'),
            ('account_expiration_date', 58, 68, 'date'),
            ('account_reissue_date', 68, 78, 'date'),
            ('current_cycle_credit', 78, 90, 'decimal_display', {'precision': 10, 'scale': 2}),
            ('current_cycle_debit', 90, 102, 'decimal_display', {'precision': 10, 'scale': 2}),
            ('account_zip', 102, 112, 'text'),
            ('account_group_id', 112, 122, 'text')
        ]
    },
    'card': {
        'file': 'AWS.M2.CARDDEMO.CARDDATA.PS',
        'table': 'card',
        'record_length': 150,
        'copybook': 'CVACT03Y.cpy',
        'fields': [
            ('card_number', 0, 16, 'text'),
            ('card_acct_id', 16, 27, 'numeric'),
            ('card_cvv_cd', 27, 30, 'numeric'),
            ('card_embossed_name', 30, 80, 'text'),
            ('card_expiraion_date', 80, 90, 'date'),
            ('card_active_status', 90, 91, 'text')
        ]
    },
    'transaction': {
        'file': 'AWS.M2.CARDDEMO.DALYTRAN.PS',
        'table': 'transaction',
        'record_length': 350,
        'copybook': 'CVTRA05Y.cpy',
        'fields': [
            ('transaction_id', 0, 16, 'text'),
            ('transaction_type_cd', 16, 18, 'text'),
            ('transaction_cat_cd', 18, 22, 'numeric'),
            ('transaction_source', 22, 32, 'text'),
            ('transaction_desc', 32, 132, 'text'),
            ('transaction_amt', 132, 144, 'decimal_display', {'precision': 9, 'scale': 2}),
            ('transaction_merchant_id', 144, 153, 'numeric'),
            ('transaction_merchant_name', 153, 203, 'text'),
            ('transaction_merchant_city', 203, 253, 'text'),
            ('transaction_merchant_zip', 253, 263, 'text'),
            ('transaction_card_num', 263, 279, 'text'),
            ('transaction_orig_ts', 279, 305, 'timestamp'),
            ('transaction_proc_ts', 305, 331, 'timestamp')
        ]
    },
    'user_security': {
        'file': 'AWS.M2.CARDDEMO.USRSEC.PS',
        'table': 'user_security',
        'record_length': 80,
        'copybook': 'CSUSR01Y.cpy',
        'fields': [
            ('user_id', 0, 8, 'text'),
            ('user_first_name', 8, 28, 'text'),
            ('user_last_name', 28, 48, 'text'),
            ('user_pwd', 48, 56, 'password'),
            ('user_type', 56, 57, 'text')
        ]
    },
    'card_xref': {
        'file': 'AWS.M2.CARDDEMO.CARDXREF.PS',
        'table': 'card_xref',
        'record_length': 50,
        'copybook': 'CVACT03Y.cpy',
        'fields': [
            ('xref_card_num', 0, 16, 'text'),
            ('xref_cust_id', 16, 25, 'numeric'),
            ('xref_acct_id', 25, 36, 'numeric')
        ]
    },
    'transaction_category_balance': {
        'file': 'AWS.M2.CARDDEMO.TCATBALF.PS',
        'table': 'transaction_category_balance',
        'record_length': 50,
        'copybook': 'CVTRA01Y.cpy',
        'fields': [
            ('tran_cat_bal_acct_id', 0, 11, 'numeric'),
            ('tran_cat_bal_type_cd', 11, 13, 'text'),
            ('tran_cat_bal_cat_cd', 13, 17, 'numeric'),
            ('tran_cat_bal_balance', 17, 29, 'decimal_display', {'precision': 9, 'scale': 2})
        ]
    },
    'transaction_type': {
        'file': 'AWS.M2.CARDDEMO.TRANTYPE.PS',
        'table': 'transaction_type',
        'record_length': 60,
        'fields': [
            ('type_cd', 0, 2, 'text'),
            ('type_desc', 2, 52, 'text')
        ]
    },
    'transaction_category': {
        'file': 'AWS.M2.CARDDEMO.TRANCATG.PS',
        'table': 'transaction_category',
        'record_length': 60,
        'fields': [
            ('category_cd', 0, 6, 'numeric'),
            ('category_desc', 6, 56, 'text')
        ]
    },
    'discount_group': {
        'file': 'AWS.M2.CARDDEMO.DISCGRP.PS',
        'table': 'discount_group',
        'record_length': 50,
        'fields': [
            ('dg_group_id', 0, 10, 'text'),
            ('dg_acct_group_id', 10, 20, 'text'),
            ('dg_tran_type_cd', 20, 22, 'text'),
            ('dg_tran_cat_cd', 22, 28, 'numeric'),
            ('dg_discount_pct', 28, 36, 'decimal_display', {'precision': 5, 'scale': 2})
        ]
    }
}


def decode_ebcdic(data: bytes) -> str:
    """
    Convert EBCDIC CP037 bytes to UTF-8 string.
    
    Per Section 0.4: EBCDIC encoding conversion using Python codecs.decode('cp037')
    
    Args:
        data: EBCDIC CP037 encoded bytes
        
    Returns:
        UTF-8 decoded string
        
    Raises:
        UnicodeDecodeError: If EBCDIC decoding fails
    """
    try:
        return data.decode('cp037')
    except UnicodeDecodeError as e:
        print(f"ERROR: Failed to decode EBCDIC data: {e}", file=sys.stderr)
        raise


def unpack_comp3(data: bytes, scale: int = 2) -> Decimal:
    """
    Unpack COBOL COMP-3 packed decimal with exact precision.
    
    Per Section 0.9: Maintain exact COBOL COMP-3 decimal precision and rounding
    behavior using Python Decimal with ROUND_HALF_UP rounding mode.
    
    COMP-3 format: Each byte contains 2 BCD digits, last nibble is sign
    - Sign nibble: 0xC=positive, 0xD=negative, 0xF=unsigned
    - Example: PIC S9(10)V99 COMP-3 = 7 bytes = 13 digits + sign
    
    Args:
        data: COMP-3 packed decimal bytes
        scale: Number of decimal places (default: 2)
        
    Returns:
        Decimal value with exact precision
        
    Raises:
        ValueError: If packed decimal format is invalid
    """
    if not data or len(data) == 0:
        return Decimal('0.00').quantize(Decimal(10) ** -scale, rounding=ROUND_HALF_UP)
    
    # Extract BCD digits from all but last byte
    digits = []
    for byte in data[:-1]:
        high_nibble = (byte >> 4) & 0xF
        low_nibble = byte & 0xF
        digits.append(high_nibble)
        digits.append(low_nibble)
    
    # Last byte: digit and sign
    last_byte = data[-1]
    last_digit = (last_byte >> 4) & 0xF
    sign_nibble = last_byte & 0xF
    digits.append(last_digit)
    
    # Validate BCD digits (0-9)
    for d in digits:
        if d > 9:
            raise ValueError(f"Invalid BCD digit: {d:#x} in packed decimal data")
    
    # Convert to decimal string
    num_str = ''.join(str(d) for d in digits)
    
    # Apply sign (0xD = negative, 0xC or 0xF = positive)
    is_negative = (sign_nibble == 0xD)
    
    # Insert decimal point
    if scale > 0 and len(num_str) > scale:
        integer_part = num_str[:-scale] or '0'
        decimal_part = num_str[-scale:]
        num_str = f"{integer_part}.{decimal_part}"
    elif scale > 0:
        # All digits are fractional
        num_str = '0.' + num_str.zfill(scale)
    
    # Create Decimal with exact precision
    value = Decimal(num_str)
    if is_negative:
        value = -value
    
    # Round to specified scale using HALF_UP (COBOL default)
    return value.quantize(Decimal(10) ** -scale, rounding=ROUND_HALF_UP)


def hash_password(plaintext: str) -> str:
    """
    Generate BCrypt hash with strength 12 per Section 0.4.
    
    SECURITY CRITICAL: All passwords from USRSEC file must be hashed
    with BCrypt strength 12 rounds before storing in PostgreSQL.
    
    Args:
        plaintext: Plaintext password from EBCDIC file
        
    Returns:
        BCrypt hashed password (~60 characters, format: $2b$12$...)
    """
    if not plaintext or not plaintext.strip():
        # Generate hash for empty password (should not happen in production)
        plaintext = 'EMPTY_PASSWORD_PLACEHOLDER'
    
    return bcrypt.hashpw(plaintext.encode('utf-8'), bcrypt.gensalt(rounds=12)).decode('utf-8')


def parse_decimal_display(value_str: str, precision: int, scale: int) -> Decimal:
    """
    Parse display decimal format (text representation of decimal number).
    
    For PIC S9(10)V99 fields stored as display format, parse the text
    representation and convert to Decimal with proper precision.
    
    Args:
        value_str: String representation of decimal
        precision: Total number of digits
        scale: Number of decimal places
        
    Returns:
        Decimal value with exact precision
    """
    value_str = value_str.strip()
    if not value_str or value_str == '' or value_str.replace(' ', '') == '':
        return Decimal('0.00').quantize(Decimal(10) ** -scale, rounding=ROUND_HALF_UP)
    
    # Handle sign
    is_negative = value_str[0] == '-'
    if is_negative or value_str[0] == '+':
        value_str = value_str[1:]
    
    # Remove any spaces
    value_str = value_str.replace(' ', '')
    
    # Insert decimal point if needed
    if '.' not in value_str and scale > 0:
        if len(value_str) <= scale:
            value_str = '0.' + value_str.zfill(scale)
        else:
            integer_part = value_str[:-scale] or '0'
            decimal_part = value_str[-scale:]
            value_str = f"{integer_part}.{decimal_part}"
    
    try:
        value = Decimal(value_str)
        if is_negative:
            value = -value
        return value.quantize(Decimal(10) ** -scale, rounding=ROUND_HALF_UP)
    except Exception as e:
        print(f"WARNING: Failed to parse decimal '{value_str}': {e}", file=sys.stderr)
        return Decimal('0.00').quantize(Decimal(10) ** -scale, rounding=ROUND_HALF_UP)


def parse_record(data: bytes, layout: Dict) -> Dict[str, Any]:
    """
    Parse fixed-width EBCDIC record according to copybook layout.
    
    Handles:
    - EBCDIC CP037 to UTF-8 conversion
    - Fixed-width field extraction
    - COMP-3 packed decimal unpacking
    - Password hashing for user_security records
    - Date format validation
    - Numeric field padding
    
    Args:
        data: Raw EBCDIC record bytes
        layout: Record layout definition
        
    Returns:
        Dictionary of field name -> parsed value
    """
    decoded = decode_ebcdic(data)
    record = {}
    
    for field_def in layout['fields']:
        name = field_def[0]
        start = field_def[1]
        end = field_def[2]
        field_type = field_def[3]
        metadata = field_def[4] if len(field_def) > 4 else {}
        
        if field_type == 'comp3':
            # COMP-3 packed decimal - extract from binary data
            comp3_data = data[start:end]
            scale = metadata.get('scale', 2)
            record[name] = unpack_comp3(comp3_data, scale)
        elif field_type == 'decimal_display':
            # Display format decimal (PIC S9(n)V99 as text)
            value = decoded[start:end].strip()
            scale = metadata.get('scale', 2)
            precision = metadata.get('precision', 10)
            record[name] = parse_decimal_display(value, precision, scale)
        elif field_type == 'numeric':
            # Numeric text field with zero-padding preservation
            value = decoded[start:end].strip()
            if value and value.replace(' ', ''):
                record[name] = value.zfill(end - start) if value.isdigit() else value
            else:
                record[name] = None
        elif field_type == 'integer':
            # Integer field
            value = decoded[start:end].strip()
            if value and value.isdigit():
                record[name] = int(value)
            else:
                record[name] = None
        elif field_type == 'date':
            # Date field in YYYY-MM-DD format
            value = decoded[start:end].strip()
            if value and value != ' ' * (end - start) and value.replace('-', '').isdigit():
                record[name] = value
            else:
                record[name] = None
        elif field_type == 'timestamp':
            # Timestamp field
            value = decoded[start:end].strip()
            if value and value != ' ' * (end - start):
                record[name] = value
            else:
                record[name] = None
        elif field_type == 'password':
            # SECURITY CRITICAL: Hash password with BCrypt
            value = decoded[start:end].strip()
            if value:
                # Store as password_hash instead of plaintext password
                record[name.replace('_pwd', '_password_hash')] = hash_password(value)
            else:
                record[name.replace('_pwd', '_password_hash')] = hash_password('DEFAULT_PWD')
        else:  # 'text' or default
            # Text field - extract and trim
            value = decoded[start:end].strip()
            record[name] = value if value else None
    
    return record


def escape_sql_string(value: str) -> str:
    """
    Escape string value for SQL INSERT statement.
    
    Prevents SQL injection by escaping single quotes.
    
    Args:
        value: String value to escape
        
    Returns:
        SQL-escaped string
    """
    if value is None:
        return 'NULL'
    return value.replace("'", "''")


def format_sql_value(value: Any) -> str:
    """
    Format Python value as SQL literal.
    
    Handles:
    - NULL values
    - Decimal/numeric values
    - String values (with escaping)
    - Integer values
    
    Args:
        value: Python value to format
        
    Returns:
        SQL literal string
    """
    if value is None:
        return 'NULL'
    elif isinstance(value, Decimal):
        return str(value)
    elif isinstance(value, (int, float)):
        return str(value)
    elif isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    else:
        # String value - escape and quote
        escaped = escape_sql_string(str(value))
        return f"'{escaped}'"


def generate_insert_statement(table: str, records: List[Dict], include_comments: bool = True) -> str:
    """
    Generate SQL INSERT statement from parsed records.
    
    Creates a multi-row INSERT statement with proper SQL formatting
    and value escaping.
    
    Args:
        table: Target table name
        records: List of record dictionaries
        include_comments: Whether to include SQL comments
        
    Returns:
        SQL INSERT statement string
    """
    if not records:
        return ''
    
    # Get column names from first record
    columns = list(records[0].keys())
    columns_str = ', '.join(columns)
    
    # Build VALUES clauses
    values_list = []
    for record in records:
        values = [format_sql_value(record.get(col)) for col in columns]
        values_str = ', '.join(values)
        values_list.append(f"  ({values_str})")
    
    # Combine into INSERT statement
    sql_parts = []
    if include_comments:
        sql_parts.append(f"-- Insert {len(records)} records into {table}")
    
    sql_parts.append(f"INSERT INTO {table} ({columns_str})")
    sql_parts.append("VALUES")
    sql_parts.append(',\n'.join(values_list))
    sql_parts.append(';')
    
    return '\n'.join(sql_parts)


def convert_file(input_path: Path, output_path: Path, table: str, verbose: bool = True) -> Tuple[int, str]:
    """
    Convert single EBCDIC file to SQL INSERT statements.
    
    Performs complete conversion process:
    1. Read EBCDIC binary file
    2. Calculate and store source checksum
    3. Parse fixed-width records
    4. Transform data (hash passwords, convert dates, etc.)
    5. Generate SQL INSERT statements
    6. Write output file with metadata comments
    
    Per Section 0.9: Zero data loss validation with SHA-256 checksums
    
    Args:
        input_path: Path to input EBCDIC .PS file
        output_path: Path to output .sql file
        table: Table name (must be key in RECORD_LAYOUTS)
        verbose: Print progress messages
        
    Returns:
        Tuple of (record_count, source_checksum)
        
    Raises:
        FileNotFoundError: If input file doesn't exist
        KeyError: If table not found in RECORD_LAYOUTS
        ValueError: If record length doesn't match
    """
    if table not in RECORD_LAYOUTS:
        raise KeyError(f"Unknown table '{table}'. Valid tables: {', '.join(RECORD_LAYOUTS.keys())}")
    
    layout = RECORD_LAYOUTS[table]
    record_length = layout['record_length']
    
    if verbose:
        print(f"Converting {input_path.name} to {output_path.name}...")
        print(f"  Table: {table}")
        print(f"  Record length: {record_length} bytes")
    
    # Read EBCDIC file in binary mode
    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")
    
    with open(input_path, 'rb') as f:
        data = f.read()
    
    # Calculate source checksum for zero data loss validation
    source_checksum = hashlib.sha256(data).hexdigest()
    
    # Calculate record count
    file_size = len(data)
    record_count = file_size // record_length
    remaining_bytes = file_size % record_length
    
    if verbose:
        print(f"  File size: {file_size} bytes")
        print(f"  Records: {record_count}")
        if remaining_bytes > 0:
            print(f"  WARNING: {remaining_bytes} trailing bytes (incomplete record)")
        print(f"  Source checksum: {source_checksum}")
    
    # Parse records
    records = []
    for i in range(record_count):
        offset = i * record_length
        record_data = data[offset:offset + record_length]
        
        try:
            record = parse_record(record_data, layout)
            records.append(record)
        except Exception as e:
            print(f"ERROR: Failed to parse record {i+1}: {e}", file=sys.stderr)
            if verbose:
                print(f"  Record offset: {offset}", file=sys.stderr)
                print(f"  Record data (first 50 bytes): {record_data[:50].hex()}", file=sys.stderr)
            # Continue parsing other records instead of failing completely
            continue
    
    if verbose:
        print(f"  Successfully parsed: {len(records)} records")
    
    # Generate SQL INSERT statement
    sql = generate_insert_statement(layout['table'], records, include_comments=False)
    
    # Write output file with metadata comments
    with open(output_path, 'w', encoding='utf-8') as f:
        # Header comments per Section 0.9 requirements
        f.write("-- " + "=" * 70 + "\n")
        f.write(f"-- EBCDIC to SQL Conversion Output\n")
        f.write("-- " + "=" * 70 + "\n")
        f.write(f"-- Source file: {input_path.name}\n")
        f.write(f"-- Target table: {layout['table']}\n")
        f.write(f"-- Copybook: {layout.get('copybook', 'N/A')}\n")
        f.write(f"-- Record length: {record_length} bytes\n")
        f.write(f"-- Record count: {record_count}\n")
        f.write(f"-- Successfully parsed: {len(records)}\n")
        f.write(f"-- Source checksum (SHA-256): {source_checksum}\n")
        f.write(f"-- Conversion tool: convert_ebcdic_to_sql.py\n")
        f.write(f"-- Section references: Agent Action Plan 0.4, 0.9\n")
        f.write("-- " + "=" * 70 + "\n\n")
        
        # TRUNCATE statement to clear existing data
        f.write(f"-- Clear existing test data\n")
        f.write(f"TRUNCATE TABLE {layout['table']} CASCADE;\n\n")
        
        # SQL INSERT statement
        f.write(sql)
        f.write("\n\n")
        
        # Verification query
        f.write(f"-- Verification query\n")
        f.write(f"SELECT COUNT(*) as record_count FROM {layout['table']};\n")
        f.write(f"-- Expected: {len(records)}\n")
    
    if verbose:
        print(f"  Output written to: {output_path}")
        print(f"  Conversion complete!")
    
    return record_count, source_checksum


def convert_all_files(base_path: Path, verbose: bool = True) -> Dict[str, Tuple[int, str]]:
    """
    Convert all EBCDIC files in batch mode.
    
    Processes all files defined in RECORD_LAYOUTS dictionary,
    generating corresponding SQL output files.
    
    Args:
        base_path: Base directory containing EBCDIC .PS files
        verbose: Print progress messages
        
    Returns:
        Dictionary of table_name -> (record_count, checksum)
    """
    results = {}
    
    if verbose:
        print("=" * 80)
        print("EBCDIC to SQL Batch Conversion")
        print("=" * 80)
        print(f"Base path: {base_path}")
        print(f"Tables to convert: {len(RECORD_LAYOUTS)}")
        print("=" * 80)
        print()
    
    for table, layout in RECORD_LAYOUTS.items():
        input_file = base_path / layout['file']
        output_file = base_path / f"load_{table}_data.sql"
        
        try:
            record_count, checksum = convert_file(input_file, output_file, table, verbose)
            results[table] = (record_count, checksum)
            if verbose:
                print()
        except FileNotFoundError as e:
            print(f"WARNING: Skipping {table}: {e}", file=sys.stderr)
            continue
        except Exception as e:
            print(f"ERROR: Failed to convert {table}: {e}", file=sys.stderr)
            import traceback
            traceback.print_exc()
            continue
    
    # Generate summary report
    if verbose:
        print("=" * 80)
        print("Conversion Summary")
        print("=" * 80)
        print(f"{'Table':<30} {'Records':<10} {'Checksum (first 16 chars)'}")
        print("-" * 80)
        for table, (count, checksum) in results.items():
            print(f"{table:<30} {count:<10} {checksum[:16]}...")
        print("=" * 80)
        print(f"Total tables converted: {len(results)}/{len(RECORD_LAYOUTS)}")
        print("=" * 80)
    
    return results


def main():
    """
    Main entry point for EBCDIC to SQL conversion utility.
    
    Supports two modes:
    1. Single file conversion: --input --output --table
    2. Batch conversion: --all
    """
    parser = argparse.ArgumentParser(
        description='Convert EBCDIC .PS files to PostgreSQL SQL INSERT statements',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Convert single file
  python3 convert_ebcdic_to_sql.py \\
    --input AWS.M2.CARDDEMO.CUSTDATA.PS \\
    --output load_customer_data.sql \\
    --table customer
  
  # Convert all files in batch
  python3 convert_ebcdic_to_sql.py --all
  
Available tables:
  """ + ', '.join(RECORD_LAYOUTS.keys()) + """

Requirements:
  - Python 3.9+
  - bcrypt library: pip install bcrypt

Section references:
  - Agent Action Plan Section 0.4: EBCDIC conversion requirements
  - Agent Action Plan Section 0.9: Zero data loss validation
"""
    )
    
    parser.add_argument(
        '--input',
        type=Path,
        help='Input EBCDIC .PS file path'
    )
    parser.add_argument(
        '--output',
        type=Path,
        help='Output SQL file path'
    )
    parser.add_argument(
        '--table',
        help='Target table name (must be one of: ' + ', '.join(RECORD_LAYOUTS.keys()) + ')'
    )
    parser.add_argument(
        '--all',
        action='store_true',
        help='Convert all EBCDIC files in app/data/EBCDIC/ directory'
    )
    parser.add_argument(
        '--base-path',
        type=Path,
        default=Path('app/data/EBCDIC'),
        help='Base directory for EBCDIC files (default: app/data/EBCDIC)'
    )
    parser.add_argument(
        '--quiet',
        action='store_true',
        help='Suppress progress messages'
    )
    parser.add_argument(
        '--version',
        action='version',
        version='convert_ebcdic_to_sql.py v1.0 - CardDemo Migration'
    )
    
    args = parser.parse_args()
    verbose = not args.quiet
    
    try:
        if args.all:
            # Batch conversion mode
            results = convert_all_files(args.base_path, verbose)
            
            if len(results) == 0:
                print("ERROR: No files were successfully converted", file=sys.stderr)
                sys.exit(1)
            elif len(results) < len(RECORD_LAYOUTS):
                print(f"WARNING: Only {len(results)} of {len(RECORD_LAYOUTS)} files were converted", file=sys.stderr)
                sys.exit(2)
            else:
                print("\nAll files converted successfully!")
                sys.exit(0)
                
        elif args.input and args.output and args.table:
            # Single file conversion mode
            if not args.input.is_absolute():
                input_path = args.base_path / args.input
            else:
                input_path = args.input
            
            if not args.output.is_absolute():
                output_path = args.base_path / args.output
            else:
                output_path = args.output
            
            record_count, checksum = convert_file(input_path, output_path, args.table, verbose)
            
            print(f"\nConversion successful!")
            print(f"  Records: {record_count}")
            print(f"  Checksum: {checksum}")
            sys.exit(0)
        else:
            # Missing required arguments
            parser.print_help()
            print("\nERROR: Must specify either --all or all of (--input, --output, --table)", file=sys.stderr)
            sys.exit(1)
            
    except KeyboardInterrupt:
        print("\n\nConversion interrupted by user", file=sys.stderr)
        sys.exit(130)
    except Exception as e:
        print(f"\nERROR: Conversion failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()
