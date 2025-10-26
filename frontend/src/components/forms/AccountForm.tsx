/**
 * AccountForm Component
 * 
 * Reusable React TypeScript form component for account and customer data entry and validation.
 * Converted from BMS map: COACTUP.bms (Account Update Screen)
 * 
 * This component provides comprehensive input fields for:
 * - Account Information: account number, status, credit limits, dates, balances, group
 * - Customer Details: customer ID, SSN, DOB, FICO, name, address, phones, government ID
 * 
 * Features:
 * - Formik for complex form state management with 50+ nested fields
 * - Yup validation schemas matching BMS field attributes
 * - Material-UI components for consistent styling
 * - Real-time validation with comprehensive error messages
 * - Support for both view and update modes
 * - BigDecimal precision handling for all financial amounts (2 decimal places)
 * 
 * BMS Field Mappings:
 * - ACCTSID (11 digits) → acctId
 * - ACSTTUS (Y/N) → acctActiveStatus
 * - ACRDLIM/ACSHLIM (financial) → credit limits with 2 decimals
 * - Date fields (OPNYEAR/MON/DAY) → combined YYYY-MM-DD format
 * - SSN fields (ACTSSN1/2/3) → single XXX-XX-XXXX format
 * - Phone fields (ACSPH1A/B/C) → single XXX-XXX-XXXX format
 * 
 * @module components/forms/AccountForm
 */

import React from 'react';
import { Formik, Form, FormikHelpers } from 'formik';
import * as yup from 'yup';
import {
  TextField,
  Select,
  MenuItem,
  Button,
  FormControl,
  InputLabel,
  Grid,
  Box,
  Typography,
  Divider,
  FormHelperText
} from '@mui/material';
import { AccountStatus } from '../../types/account';

/**
 * Props interface for AccountForm component
 * 
 * @interface AccountFormProps
 */
export interface AccountFormProps {
  /**
   * Initial values for form fields (optional for create mode)
   * Partial<Account> allows pre-populating form with existing account data
   */
  initialValues?: Partial<AccountFormData>;
  
  /**
   * Callback invoked when form is submitted with validated data
   * Can be async for API calls
   * 
   * @param values - Validated form data
   */
  onSubmit: (values: AccountFormData) => void | Promise<void>;
  
  /**
   * Callback invoked when user cancels form editing
   */
  onCancel: () => void;
  
  /**
   * Form mode: 'view' (read-only) or 'update' (editable)
   * Default: 'update'
   */
  mode?: 'view' | 'update';
}

/**
 * Complete form data structure matching BMS COACTUP map fields
 * 
 * Contains all account and customer fields with proper TypeScript types.
 * Field names match backend AccountDto and CustomerDto for API compatibility.
 * 
 * @interface AccountFormData
 */
export interface AccountFormData {
  // ===== Account Information =====
  // Source: BMS COACTUP.bms account section fields
  
  /**
   * Account ID (11 digits, required)
   * Source: ACCTSID DFHMDF LENGTH=11, IC (initial cursor)
   */
  acctId: number;
  
  /**
   * Account active status (Y/N, required)
   * Source: ACSTTUS DFHMDF LENGTH=1
   * Valid values: 'Y' = Active, 'N' = Closed
   */
  acctActiveStatus: string;
  
  /**
   * Credit limit (max 9999999999.99, 2 decimals, required)
   * Source: ACRDLIM DFHMDF LENGTH=15, FSET, UNPROT
   */
  acctCreditLimit: number;
  
  /**
   * Cash credit limit (max 9999999999.99, 2 decimals, required)
   * Source: ACSHLIM DFHMDF LENGTH=15, FSET, UNPROT
   */
  acctCashCreditLimit: number;
  
  /**
   * Account open date (YYYY-MM-DD format, required)
   * Source: OPNYEAR/OPNMON/OPNDAY DFHMDF combined
   */
  acctOpenDate: string;
  
  /**
   * Account expiration date (YYYY-MM-DD format, required, must be future)
   * Source: EXPYEAR/EXPMON/EXPDAY DFHMDF combined
   */
  acctExpirationDate: string;
  
  /**
   * Account reissue date (YYYY-MM-DD format, optional)
   * Source: RISYEAR/RISMON/RISDAY DFHMDF combined
   */
  acctReissueDate: string;
  
  /**
   * Current balance (2 decimals, read-only)
   * Source: ACURBAL DFHMDF LENGTH=15, FSET, UNPROT
   */
  acctCurrBal: number;
  
  /**
   * Current cycle credit amount (2 decimals, read-only)
   * Source: ACRCYCR DFHMDF LENGTH=15, FSET, UNPROT
   */
  acctCurrCycCredit: number;
  
  /**
   * Current cycle debit amount (2 decimals, read-only)
   * Source: ACRCYDB DFHMDF LENGTH=15, FSET, UNPROT
   */
  acctCurrCycDebit: number;
  
  /**
   * Account group ID (max 10 characters, optional)
   * Source: AADDGRP DFHMDF LENGTH=10, UNPROT
   */
  acctGroupId: string;
  
  // ===== Customer Information =====
  // Source: BMS COACTUP.bms customer details section
  
  /**
   * Customer ID (9 digits, required)
   * Source: ACSTNUM DFHMDF LENGTH=9, UNPROT
   */
  custId: number;
  
  /**
   * Customer SSN (XXX-XX-XXXX format, required)
   * Source: ACTSSN1/ACTSSN2/ACTSSN3 DFHMDF combined (3+2+4 digits)
   */
  custSsn: string;
  
  /**
   * Customer date of birth (YYYY-MM-DD format, required, must be past)
   * Source: DOBYEAR/DOBMON/DOBDAY DFHMDF combined
   */
  custDobYyyyMmDd: string;
  
  /**
   * Customer FICO credit score (300-850 range, 3 digits, optional)
   * Source: ACSTFCO DFHMDF LENGTH=3, UNPROT
   */
  custFicoScore: number;
  
  /**
   * Customer first name (max 25 characters, required)
   * Source: ACSFNAM DFHMDF LENGTH=25, UNPROT, HILIGHT=UNDERLINE
   */
  custFirstName: string;
  
  /**
   * Customer middle name (max 25 characters, optional)
   * Source: ACSMNAM DFHMDF LENGTH=25, UNPROT
   */
  custMiddleName: string;
  
  /**
   * Customer last name (max 25 characters, required)
   * Source: ACSLNAM DFHMDF LENGTH=25, UNPROT, HILIGHT=UNDERLINE
   */
  custLastName: string;
  
  /**
   * Customer address line 1 (max 50 characters, required)
   * Source: ACSADL1 DFHMDF LENGTH=50, UNPROT, HILIGHT=UNDERLINE
   */
  custAddrLine1: string;
  
  /**
   * Customer address line 2 (max 50 characters, optional)
   * Source: ACSADL2 DFHMDF LENGTH=50, UNPROT
   */
  custAddrLine2: string;
  
  /**
   * Customer address line 3 (max 50 characters, optional)
   * Not in BMS but included for completeness
   */
  custAddrLine3: string;
  
  /**
   * Customer city (max 50 characters, required)
   * Source: ACSCITY DFHMDF LENGTH=50, UNPROT, HILIGHT=UNDERLINE
   */
  custAddrCity: string;
  
  /**
   * Customer state code (2 uppercase letters, required)
   * Source: ACSSTTE DFHMDF LENGTH=2, UNPROT, HILIGHT=UNDERLINE
   */
  custAddrStateCd: string;
  
  /**
   * Customer ZIP code (5 digits, required)
   * Source: ACSZIPC DFHMDF LENGTH=5, UNPROT, HILIGHT=UNDERLINE
   */
  custAddrZip: string;
  
  /**
   * Customer country code (3 uppercase letters, required)
   * Source: ACSCTRY DFHMDF LENGTH=3, UNPROT, HILIGHT=UNDERLINE
   */
  custAddrCountryCd: string;
  
  /**
   * Customer phone number 1 (XXX-XXX-XXXX format, optional)
   * Source: ACSPH1A/ACSPH1B/ACSPH1C DFHMDF combined (3+3+4 digits)
   */
  custPhoneNum1: string;
  
  /**
   * Customer phone number 2 (XXX-XXX-XXXX format, optional)
   * Source: ACSPH2A/ACSPH2B/ACSPH2C DFHMDF combined (3+3+4 digits)
   */
  custPhoneNum2: string;
  
  /**
   * Customer government-issued ID reference (max 20 characters, optional)
   * Source: ACSGOVT DFHMDF LENGTH=20, UNPROT, HILIGHT=UNDERLINE
   */
  custGovtIssuedId: string;
  
  /**
   * Customer EFT account ID (max 10 characters, optional)
   * Source: ACSEFTC DFHMDF LENGTH=10, UNPROT, HILIGHT=UNDERLINE
   */
  custEftAccountId: string;
  
  /**
   * Primary cardholder flag (Y/N, required)
   * Source: ACSPFLG DFHMDF LENGTH=1, UNPROT, HILIGHT=UNDERLINE
   */
  custPrimaryCardholderFlag: string;
}

/**
 * Default initial values for form fields
 * Provides empty/default values for all required and optional fields
 */
const defaultInitialValues: AccountFormData = {
  acctId: 0,
  acctActiveStatus: AccountStatus.ACTIVE,
  acctCreditLimit: 0,
  acctCashCreditLimit: 0,
  acctOpenDate: '',
  acctExpirationDate: '',
  acctReissueDate: '',
  acctCurrBal: 0,
  acctCurrCycCredit: 0,
  acctCurrCycDebit: 0,
  acctGroupId: '',
  custId: 0,
  custSsn: '',
  custDobYyyyMmDd: '',
  custFicoScore: 0,
  custFirstName: '',
  custMiddleName: '',
  custLastName: '',
  custAddrLine1: '',
  custAddrLine2: '',
  custAddrLine3: '',
  custAddrCity: '',
  custAddrStateCd: '',
  custAddrZip: '',
  custAddrCountryCd: '',
  custPhoneNum1: '',
  custPhoneNum2: '',
  custGovtIssuedId: '',
  custEftAccountId: '',
  custPrimaryCardholderFlag: AccountStatus.ACTIVE,
};

/**
 * Comprehensive Yup validation schema matching BMS COACTUP field attributes
 * 
 * Validation rules enforce:
 * - Required fields from BMS UNPROT (unprotected/editable) fields
 * - Numeric precision (11 digits for acctId, 9 for custId, 2 decimals for amounts)
 * - String length constraints (25 chars for names, 50 for addresses)
 * - Pattern matching (SSN XXX-XX-XXXX, phone XXX-XXX-XXXX, state 2 letters, ZIP 5 digits)
 * - Date validations (past for DOB, future for expiration)
 * - Range constraints (FICO 300-850, credit limits max 9999999999.99)
 * - Character type validations (numeric, alpha, alphanumeric)
 * 
 * All validation messages provide clear, user-friendly error descriptions.
 */
const validationSchema = yup.object({
  // ===== Account Information Validation =====
  
  acctId: yup
    .number()
    .required('Account ID is required')
    .positive('Account ID must be a positive number')
    .integer('Account ID must be an integer')
    .test('length', 'Account ID must be at most 11 digits', (value) => {
      if (value === undefined) return false;
      return value.toString().length <= 11;
    }),
  
  acctActiveStatus: yup
    .string()
    .required('Account Status is required')
    .oneOf(['Y', 'N'], 'Status must be Y (Active) or N (Closed)'),
  
  acctCreditLimit: yup
    .number()
    .required('Credit Limit is required')
    .min(0, 'Credit Limit must be non-negative')
    .max(9999999999.99, 'Credit Limit must be at most 9999999999.99')
    .test('decimal', 'Credit Limit must have at most 2 decimal places', (value) => {
      if (value === undefined) return false;
      return /^\d+(\.\d{1,2})?$/.test(value.toString());
    }),
  
  acctCashCreditLimit: yup
    .number()
    .required('Cash Credit Limit is required')
    .min(0, 'Cash Credit Limit must be non-negative')
    .max(9999999999.99, 'Cash Credit Limit must be at most 9999999999.99')
    .test('decimal', 'Cash Credit Limit must have at most 2 decimal places', (value) => {
      if (value === undefined) return false;
      return /^\d+(\.\d{1,2})?$/.test(value.toString());
    })
    .test('less-than-credit', 'Cash Credit Limit must be less than or equal to Credit Limit', 
      function(value) {
        const { acctCreditLimit } = this.parent;
        if (value === undefined || acctCreditLimit === undefined) return true;
        return value <= acctCreditLimit;
      }
    ),
  
  acctOpenDate: yup
    .string()
    .required('Open Date is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', (value) => {
      if (!value) return false;
      const date = new Date(value);
      return date instanceof Date && !isNaN(date.getTime());
    }),
  
  acctExpirationDate: yup
    .string()
    .required('Expiration Date is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', (value) => {
      if (!value) return false;
      const date = new Date(value);
      return date instanceof Date && !isNaN(date.getTime());
    })
    .test('future-date', 'Expiration Date must be in the future', (value) => {
      if (!value) return false;
      return new Date(value) > new Date();
    })
    .test('after-open', 'Expiration Date must be after Open Date', function(value) {
      const { acctOpenDate } = this.parent;
      if (!value || !acctOpenDate) return true;
      return new Date(value) > new Date(acctOpenDate);
    }),
  
  acctReissueDate: yup
    .string()
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', (value) => {
      if (!value) return true; // Optional field
      const date = new Date(value);
      return date instanceof Date && !isNaN(date.getTime());
    })
    .test('after-open', 'Reissue Date must be after Open Date', function(value) {
      const { acctOpenDate } = this.parent;
      if (!value || !acctOpenDate) return true;
      return new Date(value) > new Date(acctOpenDate);
    })
    .notRequired(),
  
  acctCurrBal: yup
    .number()
    .test('decimal', 'Balance must have at most 2 decimal places', (value) => {
      if (value === undefined) return true;
      return /^-?\d+(\.\d{1,2})?$/.test(value.toString());
    }),
  
  acctCurrCycCredit: yup
    .number()
    .test('decimal', 'Cycle Credit must have at most 2 decimal places', (value) => {
      if (value === undefined) return true;
      return /^\d+(\.\d{1,2})?$/.test(value.toString());
    }),
  
  acctCurrCycDebit: yup
    .number()
    .test('decimal', 'Cycle Debit must have at most 2 decimal places', (value) => {
      if (value === undefined) return true;
      return /^\d+(\.\d{1,2})?$/.test(value.toString());
    }),
  
  acctGroupId: yup
    .string()
    .max(10, 'Account Group must be at most 10 characters')
    .notRequired(),
  
  // ===== Customer Information Validation =====
  
  custId: yup
    .number()
    .required('Customer ID is required')
    .positive('Customer ID must be a positive number')
    .integer('Customer ID must be an integer')
    .test('length', 'Customer ID must be at most 9 digits', (value) => {
      if (value === undefined) return false;
      return value.toString().length <= 9;
    }),
  
  custSsn: yup
    .string()
    .required('SSN is required')
    .matches(/^\d{3}-\d{2}-\d{4}$/, 'SSN must be in XXX-XX-XXXX format'),
  
  custDobYyyyMmDd: yup
    .string()
    .required('Date of Birth is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', (value) => {
      if (!value) return false;
      const date = new Date(value);
      return date instanceof Date && !isNaN(date.getTime());
    })
    .test('past-date', 'Date of Birth must be in the past', (value) => {
      if (!value) return false;
      return new Date(value) < new Date();
    })
    .test('reasonable-age', 'Customer must be at least 18 years old', (value) => {
      if (!value) return false;
      const birthDate = new Date(value);
      const today = new Date();
      const age = today.getFullYear() - birthDate.getFullYear();
      const monthDiff = today.getMonth() - birthDate.getMonth();
      if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birthDate.getDate())) {
        return age - 1 >= 18;
      }
      return age >= 18;
    }),
  
  custFicoScore: yup
    .number()
    .min(300, 'FICO Score must be between 300 and 850')
    .max(850, 'FICO Score must be between 300 and 850')
    .integer('FICO Score must be an integer')
    .notRequired(),
  
  custFirstName: yup
    .string()
    .required('First Name is required')
    .max(25, 'First Name must be at most 25 characters')
    .matches(/^[A-Za-z\s'-]+$/, 'First Name must contain only letters, spaces, hyphens, and apostrophes'),
  
  custMiddleName: yup
    .string()
    .max(25, 'Middle Name must be at most 25 characters')
    .matches(/^[A-Za-z\s'-]*$/, 'Middle Name must contain only letters, spaces, hyphens, and apostrophes')
    .notRequired(),
  
  custLastName: yup
    .string()
    .required('Last Name is required')
    .max(25, 'Last Name must be at most 25 characters')
    .matches(/^[A-Za-z\s'-]+$/, 'Last Name must contain only letters, spaces, hyphens, and apostrophes'),
  
  custAddrLine1: yup
    .string()
    .required('Address Line 1 is required')
    .max(50, 'Address Line 1 must be at most 50 characters'),
  
  custAddrLine2: yup
    .string()
    .max(50, 'Address Line 2 must be at most 50 characters')
    .notRequired(),
  
  custAddrLine3: yup
    .string()
    .max(50, 'Address Line 3 must be at most 50 characters')
    .notRequired(),
  
  custAddrCity: yup
    .string()
    .required('City is required')
    .max(50, 'City must be at most 50 characters')
    .matches(/^[A-Za-z\s'-]+$/, 'City must contain only letters, spaces, hyphens, and apostrophes'),
  
  custAddrStateCd: yup
    .string()
    .required('State is required')
    .matches(/^[A-Z]{2}$/, 'State must be 2 uppercase letters'),
  
  custAddrZip: yup
    .string()
    .required('ZIP is required')
    .matches(/^\d{5}$/, 'ZIP must be 5 digits'),
  
  custAddrCountryCd: yup
    .string()
    .required('Country is required')
    .matches(/^[A-Z]{3}$/, 'Country must be 3 uppercase letters'),
  
  custPhoneNum1: yup
    .string()
    .matches(/^(\d{3}-\d{3}-\d{4})?$/, 'Phone must be in XXX-XXX-XXXX format')
    .notRequired(),
  
  custPhoneNum2: yup
    .string()
    .matches(/^(\d{3}-\d{3}-\d{4})?$/, 'Phone must be in XXX-XXX-XXXX format')
    .notRequired(),
  
  custGovtIssuedId: yup
    .string()
    .max(20, 'Government Issued ID must be at most 20 characters')
    .notRequired(),
  
  custEftAccountId: yup
    .string()
    .max(10, 'EFT Account ID must be at most 10 characters')
    .notRequired(),
  
  custPrimaryCardholderFlag: yup
    .string()
    .required('Primary Cardholder Flag is required')
    .oneOf(['Y', 'N'], 'Must be Y or N'),
});

/**
 * AccountForm Component
 * 
 * Reusable form component for viewing and updating account and customer information.
 * Implements comprehensive validation, real-time error display, and Material-UI styling.
 * 
 * @param props - Component props
 * @returns AccountForm React component
 */
const AccountForm: React.FC<AccountFormProps> = ({
  initialValues = {},
  onSubmit,
  onCancel,
  mode = 'update'
}) => {
  const isViewMode = mode === 'view';
  
  // Merge provided initial values with defaults
  const formInitialValues: AccountFormData = {
    ...defaultInitialValues,
    ...initialValues
  };
  
  /**
   * Handle form submission
   * Validates all fields and calls onSubmit callback with validated data
   * 
   * @param values - Form field values
   * @param formikHelpers - Formik helper methods
   */
  const handleSubmit = async (
    values: AccountFormData,
    { setSubmitting }: FormikHelpers<AccountFormData>
  ) => {
    try {
      await onSubmit(values);
    } catch (error) {
      console.error('Form submission error:', error);
    } finally {
      setSubmitting(false);
    }
  };
  
  /**
   * Format currency values for display
   * Adds thousand separators and ensures 2 decimal places
   * 
   * @param value - Numeric value to format
   * @returns Formatted currency string
   */
  const formatCurrency = (value: number | undefined): string => {
    if (value === undefined || value === null) return '0.00';
    return value.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  };
  
  return (
    <Formik
      initialValues={formInitialValues}
      validationSchema={validationSchema}
      onSubmit={handleSubmit}
      enableReinitialize
    >
      {({ values, errors, touched, handleChange, handleBlur, isSubmitting }) => (
        <Form>
          <Box sx={{ width: '100%', padding: 3 }}>
            {/* ===== Account Information Section ===== */}
            <Typography variant="h5" gutterBottom color="primary">
              Account Information
            </Typography>
            
            <Grid container spacing={2} sx={{ mb: 3 }}>
              {/* Account ID - Initial cursor focus (IC attribute from BMS) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="acctId"
                  label="Account Number *"
                  type="number"
                  value={values.acctId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctId && Boolean(errors.acctId)}
                  helperText={touched.acctId && errors.acctId}
                  autoFocus={!isViewMode}
                  inputProps={{ maxLength: 11 }}
                />
              </Grid>
              
              {/* Account Status */}
              <Grid item xs={12} md={6}>
                <FormControl 
                  fullWidth 
                  error={touched.acctActiveStatus && Boolean(errors.acctActiveStatus)}
                  disabled={isViewMode}
                >
                  <InputLabel id="acctActiveStatus-label">Active Status *</InputLabel>
                  <Select
                    labelId="acctActiveStatus-label"
                    name="acctActiveStatus"
                    value={values.acctActiveStatus || ''}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="Active Status *"
                  >
                    <MenuItem value={AccountStatus.ACTIVE}>Y - Active</MenuItem>
                    <MenuItem value={AccountStatus.CLOSED}>N - Closed</MenuItem>
                  </Select>
                  {touched.acctActiveStatus && errors.acctActiveStatus && (
                    <FormHelperText>{errors.acctActiveStatus}</FormHelperText>
                  )}
                </FormControl>
              </Grid>
              
              {/* Credit Limit */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="acctCreditLimit"
                  label="Credit Limit *"
                  type="number"
                  value={values.acctCreditLimit || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctCreditLimit && Boolean(errors.acctCreditLimit)}
                  helperText={touched.acctCreditLimit && errors.acctCreditLimit}
                  inputProps={{ step: '0.01', min: 0, max: 9999999999.99 }}
                  InputProps={{ startAdornment: '$' }}
                />
              </Grid>
              
              {/* Cash Credit Limit */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="acctCashCreditLimit"
                  label="Cash Credit Limit *"
                  type="number"
                  value={values.acctCashCreditLimit || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctCashCreditLimit && Boolean(errors.acctCashCreditLimit)}
                  helperText={touched.acctCashCreditLimit && errors.acctCashCreditLimit}
                  inputProps={{ step: '0.01', min: 0, max: 9999999999.99 }}
                  InputProps={{ startAdornment: '$' }}
                />
              </Grid>
              
              {/* Open Date */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctOpenDate"
                  label="Open Date *"
                  type="date"
                  value={values.acctOpenDate || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctOpenDate && Boolean(errors.acctOpenDate)}
                  helperText={touched.acctOpenDate && errors.acctOpenDate}
                  InputLabelProps={{ shrink: true }}
                />
              </Grid>
              
              {/* Expiration Date */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctExpirationDate"
                  label="Expiration Date *"
                  type="date"
                  value={values.acctExpirationDate || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctExpirationDate && Boolean(errors.acctExpirationDate)}
                  helperText={touched.acctExpirationDate && errors.acctExpirationDate}
                  InputLabelProps={{ shrink: true }}
                />
              </Grid>
              
              {/* Reissue Date */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctReissueDate"
                  label="Reissue Date"
                  type="date"
                  value={values.acctReissueDate || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctReissueDate && Boolean(errors.acctReissueDate)}
                  helperText={touched.acctReissueDate && errors.acctReissueDate}
                  InputLabelProps={{ shrink: true }}
                />
              </Grid>
              
              {/* Current Balance (Read-only) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctCurrBal"
                  label="Current Balance"
                  value={formatCurrency(values.acctCurrBal)}
                  disabled
                  InputProps={{ startAdornment: '$' }}
                  helperText="Read-only calculated field"
                />
              </Grid>
              
              {/* Current Cycle Credit (Read-only) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctCurrCycCredit"
                  label="Current Cycle Credit"
                  value={formatCurrency(values.acctCurrCycCredit)}
                  disabled
                  InputProps={{ startAdornment: '$' }}
                  helperText="Read-only calculated field"
                />
              </Grid>
              
              {/* Current Cycle Debit (Read-only) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="acctCurrCycDebit"
                  label="Current Cycle Debit"
                  value={formatCurrency(values.acctCurrCycDebit)}
                  disabled
                  InputProps={{ startAdornment: '$' }}
                  helperText="Read-only calculated field"
                />
              </Grid>
              
              {/* Account Group */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="acctGroupId"
                  label="Account Group"
                  value={values.acctGroupId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.acctGroupId && Boolean(errors.acctGroupId)}
                  helperText={touched.acctGroupId && errors.acctGroupId}
                  inputProps={{ maxLength: 10 }}
                />
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 3 }} />
            
            {/* ===== Customer Details Section ===== */}
            <Typography variant="h5" gutterBottom color="primary">
              Customer Details
            </Typography>
            
            <Grid container spacing={2} sx={{ mb: 3 }}>
              {/* Customer ID */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custId"
                  label="Customer ID *"
                  type="number"
                  value={values.custId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custId && Boolean(errors.custId)}
                  helperText={touched.custId && errors.custId}
                  inputProps={{ maxLength: 9 }}
                />
              </Grid>
              
              {/* SSN - Combined field with XXX-XX-XXXX format */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custSsn"
                  label="SSN *"
                  value={values.custSsn || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custSsn && Boolean(errors.custSsn)}
                  helperText={touched.custSsn && errors.custSsn || 'Format: XXX-XX-XXXX'}
                  placeholder="999-99-9999"
                  inputProps={{ maxLength: 11 }}
                />
              </Grid>
              
              {/* Date of Birth */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custDobYyyyMmDd"
                  label="Date of Birth *"
                  type="date"
                  value={values.custDobYyyyMmDd || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custDobYyyyMmDd && Boolean(errors.custDobYyyyMmDd)}
                  helperText={touched.custDobYyyyMmDd && errors.custDobYyyyMmDd}
                  InputLabelProps={{ shrink: true }}
                />
              </Grid>
              
              {/* FICO Score */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custFicoScore"
                  label="FICO Score"
                  type="number"
                  value={values.custFicoScore || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custFicoScore && Boolean(errors.custFicoScore)}
                  helperText={touched.custFicoScore && errors.custFicoScore || 'Range: 300-850'}
                  inputProps={{ min: 300, max: 850 }}
                />
              </Grid>
              
              {/* First Name */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custFirstName"
                  label="First Name *"
                  value={values.custFirstName || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custFirstName && Boolean(errors.custFirstName)}
                  helperText={touched.custFirstName && errors.custFirstName}
                  inputProps={{ maxLength: 25 }}
                />
              </Grid>
              
              {/* Middle Name */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custMiddleName"
                  label="Middle Name"
                  value={values.custMiddleName || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custMiddleName && Boolean(errors.custMiddleName)}
                  helperText={touched.custMiddleName && errors.custMiddleName}
                  inputProps={{ maxLength: 25 }}
                />
              </Grid>
              
              {/* Last Name */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custLastName"
                  label="Last Name *"
                  value={values.custLastName || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custLastName && Boolean(errors.custLastName)}
                  helperText={touched.custLastName && errors.custLastName}
                  inputProps={{ maxLength: 25 }}
                />
              </Grid>
              
              {/* Address Line 1 */}
              <Grid item xs={12} md={8}>
                <TextField
                  fullWidth
                  name="custAddrLine1"
                  label="Address Line 1 *"
                  value={values.custAddrLine1 || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrLine1 && Boolean(errors.custAddrLine1)}
                  helperText={touched.custAddrLine1 && errors.custAddrLine1}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>
              
              {/* State */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custAddrStateCd"
                  label="State *"
                  value={values.custAddrStateCd || ''}
                  onChange={(e) => {
                    // Auto-uppercase state code
                    const upperValue = e.target.value.toUpperCase();
                    handleChange({ target: { name: 'custAddrStateCd', value: upperValue } });
                  }}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrStateCd && Boolean(errors.custAddrStateCd)}
                  helperText={touched.custAddrStateCd && errors.custAddrStateCd || '2 letters'}
                  inputProps={{ maxLength: 2 }}
                />
              </Grid>
              
              {/* Address Line 2 */}
              <Grid item xs={12} md={8}>
                <TextField
                  fullWidth
                  name="custAddrLine2"
                  label="Address Line 2"
                  value={values.custAddrLine2 || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrLine2 && Boolean(errors.custAddrLine2)}
                  helperText={touched.custAddrLine2 && errors.custAddrLine2}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>
              
              {/* ZIP Code */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custAddrZip"
                  label="ZIP *"
                  value={values.custAddrZip || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrZip && Boolean(errors.custAddrZip)}
                  helperText={touched.custAddrZip && errors.custAddrZip || '5 digits'}
                  inputProps={{ maxLength: 5 }}
                />
              </Grid>
              
              {/* City */}
              <Grid item xs={12} md={8}>
                <TextField
                  fullWidth
                  name="custAddrCity"
                  label="City *"
                  value={values.custAddrCity || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrCity && Boolean(errors.custAddrCity)}
                  helperText={touched.custAddrCity && errors.custAddrCity}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>
              
              {/* Country */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custAddrCountryCd"
                  label="Country *"
                  value={values.custAddrCountryCd || ''}
                  onChange={(e) => {
                    // Auto-uppercase country code
                    const upperValue = e.target.value.toUpperCase();
                    handleChange({ target: { name: 'custAddrCountryCd', value: upperValue } });
                  }}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrCountryCd && Boolean(errors.custAddrCountryCd)}
                  helperText={touched.custAddrCountryCd && errors.custAddrCountryCd || '3 letters (e.g., USA)'}
                  inputProps={{ maxLength: 3 }}
                />
              </Grid>
              
              {/* Address Line 3 */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  name="custAddrLine3"
                  label="Address Line 3"
                  value={values.custAddrLine3 || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custAddrLine3 && Boolean(errors.custAddrLine3)}
                  helperText={touched.custAddrLine3 && errors.custAddrLine3}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>
              
              {/* Phone Number 1 */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custPhoneNum1"
                  label="Phone 1"
                  value={values.custPhoneNum1 || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custPhoneNum1 && Boolean(errors.custPhoneNum1)}
                  helperText={touched.custPhoneNum1 && errors.custPhoneNum1 || 'Format: XXX-XXX-XXXX'}
                  placeholder="999-999-9999"
                  inputProps={{ maxLength: 12 }}
                />
              </Grid>
              
              {/* Phone Number 2 */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  name="custPhoneNum2"
                  label="Phone 2"
                  value={values.custPhoneNum2 || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custPhoneNum2 && Boolean(errors.custPhoneNum2)}
                  helperText={touched.custPhoneNum2 && errors.custPhoneNum2 || 'Format: XXX-XXX-XXXX'}
                  placeholder="999-999-9999"
                  inputProps={{ maxLength: 12 }}
                />
              </Grid>
              
              {/* Government Issued ID */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custGovtIssuedId"
                  label="Government Issued ID"
                  value={values.custGovtIssuedId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custGovtIssuedId && Boolean(errors.custGovtIssuedId)}
                  helperText={touched.custGovtIssuedId && errors.custGovtIssuedId}
                  inputProps={{ maxLength: 20 }}
                />
              </Grid>
              
              {/* EFT Account ID */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  name="custEftAccountId"
                  label="EFT Account ID"
                  value={values.custEftAccountId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  disabled={isViewMode}
                  error={touched.custEftAccountId && Boolean(errors.custEftAccountId)}
                  helperText={touched.custEftAccountId && errors.custEftAccountId}
                  inputProps={{ maxLength: 10 }}
                />
              </Grid>
              
              {/* Primary Cardholder Flag */}
              <Grid item xs={12} md={4}>
                <FormControl 
                  fullWidth 
                  error={touched.custPrimaryCardholderFlag && Boolean(errors.custPrimaryCardholderFlag)}
                  disabled={isViewMode}
                >
                  <InputLabel id="custPrimaryCardholderFlag-label">Primary Cardholder *</InputLabel>
                  <Select
                    labelId="custPrimaryCardholderFlag-label"
                    name="custPrimaryCardholderFlag"
                    value={values.custPrimaryCardholderFlag || ''}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="Primary Cardholder *"
                  >
                    <MenuItem value="Y">Y - Yes</MenuItem>
                    <MenuItem value="N">N - No</MenuItem>
                  </Select>
                  {touched.custPrimaryCardholderFlag && errors.custPrimaryCardholderFlag && (
                    <FormHelperText>{errors.custPrimaryCardholderFlag}</FormHelperText>
                  )}
                </FormControl>
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 3 }} />
            
            {/* ===== Form Action Buttons ===== */}
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 2 }}>
              <Button
                variant="outlined"
                color="secondary"
                onClick={onCancel}
                disabled={isSubmitting}
              >
                {isViewMode ? 'Close' : 'Cancel'}
              </Button>
              
              {!isViewMode && (
                <Button
                  type="submit"
                  variant="contained"
                  color="primary"
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Saving...' : 'Save Account'}
                </Button>
              )}
            </Box>
          </Box>
        </Form>
      )}
    </Formik>
  );
};

export default AccountForm;
