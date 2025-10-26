/**
 * TransactionForm Component
 * 
 * Reusable React TypeScript form component for transaction entry and validation.
 * Converted from BMS map: COTRN02.bms (Transaction Add screen)
 * 
 * Features:
 * - Formik-based form state management
 * - Yup validation matching BMS field attributes and constraints
 * - Material-UI components for consistent UI
 * - Mutually exclusive account ID or card number selection
 * - Comprehensive field validation with real-time error display
 * - BigDecimal precision handling for transaction amounts
 * - Confirmation requirement before submission
 * 
 * Integration:
 * - Uses Transaction, TransactionType, TransactionCategory from types/transaction.ts
 * - Parent: TransactionEntryPage.tsx
 * 
 * @module components/forms/TransactionForm
 */

import React from 'react';
import { Formik, Form, Field } from 'formik';
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
  FormControlLabel,
  Checkbox,
  Typography,
  Divider,
  FormHelperText
} from '@mui/material';
import { TransactionCategory } from '../../types/transaction';

/**
 * TransactionFormData interface
 * 
 * Defines the structure of form data for transaction entry.
 * Maps BMS COTRN02 fields to TypeScript properties.
 * 
 * Field Mappings from BMS:
 * - ACTIDIN (11 digits) → acctId
 * - CARDNIN (16 digits) → cardNum
 * - TTYPCD (2 chars) → transTypeCd
 * - TCATCD (4 digits) → transCatCd
 * - TRNSRC (10 chars) → transSource
 * - TDESC (100 chars max) → transDesc
 * - TRNAMT (12 chars) → transAmt
 * - MID (9 digits) → transMerchantId
 * - MNAME (50 chars max) → transMerchantName
 * - MCITY (50 chars max) → transMerchantCity
 * - MZIP (10 chars) → transMerchantZip
 * - TORIGDT (YYYY-MM-DD) → transOrigTs
 * - TPROCDT (YYYY-MM-DD) → transProcTs
 * - CONFIRM (Y/N) → confirm
 */
export interface TransactionFormData {
  /**
   * Account ID (11 digits)
   * BMS: ACTIDIN, POS=(6,21), LENGTH=11
   * Mutually exclusive with cardNum - one required
   */
  acctId?: string;

  /**
   * Card number (16 digits)
   * BMS: CARDNIN, POS=(6,55), LENGTH=16
   * Mutually exclusive with acctId - one required
   */
  cardNum?: string;

  /**
   * Transaction type code (2 characters)
   * BMS: TTYPCD, POS=(10,15), LENGTH=2
   * Valid values: 01-07 (Purchase, Payment, Cash Advance, etc.)
   * Required field
   */
  transTypeCd: string;

  /**
   * Transaction category code (4 digits)
   * BMS: TCATCD, POS=(10,36), LENGTH=4
   * Valid values: 5411, 5541, 5812, etc. (Merchant Category Codes)
   * Required field
   */
  transCatCd: number;

  /**
   * Transaction source (max 10 characters)
   * BMS: TRNSRC, POS=(10,54), LENGTH=10
   * Examples: 'POS', 'ATM', 'ONLINE', 'MOBILE'
   * Required field
   */
  transSource: string;

  /**
   * Transaction description (max 100 characters)
   * BMS: TDESC, POS=(12,19), LENGTH=60 (spec extends to 100)
   * Free-text description of transaction
   * Required field
   */
  transDesc: string;

  /**
   * Transaction amount with 2 decimal precision
   * BMS: TRNAMT, POS=(14,14), LENGTH=12
   * Range: -99999999.99 to 99999999.99
   * Positive for debits, negative for credits
   * Maintains BigDecimal precision from backend
   * Required field
   */
  transAmt: number;

  /**
   * Merchant ID (9 digits, optional)
   * BMS: MID, POS=(16,19), LENGTH=9
   */
  transMerchantId?: string;

  /**
   * Merchant name (max 50 characters, optional)
   * BMS: MNAME, POS=(16,48), LENGTH=30 (spec extends to 50)
   */
  transMerchantName?: string;

  /**
   * Merchant city (max 50 characters, optional)
   * BMS: MCITY, POS=(18,21), LENGTH=25 (spec extends to 50)
   */
  transMerchantCity?: string;

  /**
   * Merchant ZIP code (max 10 characters, optional)
   * BMS: MZIP, POS=(18,67), LENGTH=10
   */
  transMerchantZip?: string;

  /**
   * Original transaction date (YYYY-MM-DD format)
   * BMS: TORIGDT, POS=(14,42), LENGTH=10
   * Date when transaction originally occurred
   * Required field
   */
  transOrigTs: string;

  /**
   * Processing date (YYYY-MM-DD format)
   * BMS: TPROCDT, POS=(14,68), LENGTH=10
   * Date when transaction is processed by system
   * Required field
   */
  transProcTs: string;

  /**
   * Confirmation flag (Y/N)
   * BMS: CONFIRM, POS=(21,63), LENGTH=1
   * Must be 'Y' to submit form
   * Required field
   */
  confirm: string;
}

/**
 * TransactionFormProps interface
 * 
 * Props for TransactionForm component.
 */
export interface TransactionFormProps {
  /**
   * Initial values for form fields (optional)
   * Used for pre-populating form or copying last transaction
   */
  initialValues?: Partial<TransactionFormData>;

  /**
   * Form submission callback
   * Called when form is validated and submitted
   * 
   * @param values - Validated form data
   */
  onSubmit: (values: TransactionFormData) => void | Promise<void>;

  /**
   * Cancel callback
   * Called when user clicks Cancel button (F3 from BMS)
   */
  onCancel: () => void;
}

/**
 * Yup validation schema for transaction form
 * 
 * Validates all fields according to BMS COTRN02 constraints:
 * - Account ID OR Card Number required (mutually exclusive)
 * - Transaction type, category, source, description, amount, dates required
 * - Amount with 2 decimal precision, range validation
 * - Date format validation (YYYY-MM-DD)
 * - Merchant fields optional
 * - Confirmation required (Y/N)
 * 
 * Preserves COBOL field constraints and data precision requirements.
 */
const validationSchema = yup.object({
  acctId: yup.string()
    .when('cardNum', {
      is: (val: string | undefined) => !val || val.length === 0,
      then: (schema) => schema
        .required('Either Account ID or Card Number is required')
        .matches(/^\d{11}$/, 'Account ID must be exactly 11 digits'),
      otherwise: (schema) => schema.notRequired()
    }),
  
  cardNum: yup.string()
    .when('acctId', {
      is: (val: string | undefined) => !val || val.length === 0,
      then: (schema) => schema
        .required('Either Account ID or Card Number is required')
        .matches(/^\d{16}$/, 'Card Number must be exactly 16 digits'),
      otherwise: (schema) => schema.notRequired()
    }),
  
  transTypeCd: yup.string()
    .required('Transaction Type is required')
    .matches(/^\d{2}$/, 'Transaction Type must be 2 digits')
    .oneOf(['01', '02', '03', '04', '05', '06', '07'], 'Invalid Transaction Type code'),
  
  transCatCd: yup.number()
    .required('Transaction Category is required')
    .positive('Transaction Category must be positive')
    .integer('Transaction Category must be an integer')
    .oneOf(
      [5411, 5541, 5812, 4511, 5999, 5912, 4900, 5311, 9999],
      'Invalid Transaction Category code'
    ),
  
  transSource: yup.string()
    .required('Transaction Source is required')
    .max(10, 'Transaction Source must be at most 10 characters')
    .trim(),
  
  transDesc: yup.string()
    .required('Description is required')
    .max(100, 'Description must be at most 100 characters')
    .trim(),
  
  transAmt: yup.number()
    .required('Amount is required')
    .test(
      'decimal',
      'Amount must have at most 2 decimal places',
      value => {
        if (value === undefined || value === null) return false;
        const strValue = value.toString();
        const decimalIndex = strValue.indexOf('.');
        if (decimalIndex === -1) return true;
        return strValue.length - decimalIndex - 1 <= 2;
      }
    )
    .min(-99999999.99, 'Amount must be greater than or equal to -99999999.99')
    .max(99999999.99, 'Amount must be less than or equal to 99999999.99'),
  
  transMerchantId: yup.string()
    .matches(/^\d{0,9}$/, 'Merchant ID must be up to 9 digits')
    .notRequired(),
  
  transMerchantName: yup.string()
    .max(50, 'Merchant Name must be at most 50 characters')
    .notRequired(),
  
  transMerchantCity: yup.string()
    .max(50, 'Merchant City must be at most 50 characters')
    .notRequired(),
  
  transMerchantZip: yup.string()
    .max(10, 'Merchant ZIP must be at most 10 characters')
    .notRequired(),
  
  transOrigTs: yup.string()
    .required('Original Date is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Original Date must be in YYYY-MM-DD format')
    .test(
      'valid-date',
      'Original Date must be a valid date',
      value => {
        if (!value) return false;
        const date = new Date(value);
        return date instanceof Date && !isNaN(date.getTime());
      }
    ),
  
  transProcTs: yup.string()
    .required('Processing Date is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Processing Date must be in YYYY-MM-DD format')
    .test(
      'valid-date',
      'Processing Date must be a valid date',
      value => {
        if (!value) return false;
        const date = new Date(value);
        return date instanceof Date && !isNaN(date.getTime());
      }
    ),
  
  confirm: yup.string()
    .required('Confirmation is required')
    .oneOf(['Y', 'N'], 'Confirmation must be Y or N')
    .test(
      'must-confirm',
      'You must confirm to submit the transaction',
      value => value === 'Y'
    )
}, [['acctId', 'cardNum']]); // Cyclic dependency resolution for mutually exclusive fields

/**
 * TransactionForm Component
 * 
 * Renders a comprehensive transaction entry form with validation.
 * 
 * Form Structure (matching BMS COTRN02 layout):
 * - Section 1: Account/Card Selection (mutually exclusive)
 * - Section 2: Transaction Details (type, category, source, description, amount, dates)
 * - Section 3: Merchant Information (optional fields)
 * - Section 4: Confirmation and Actions
 * 
 * @param props - Component props
 * @returns React functional component
 */
export const TransactionForm: React.FC<TransactionFormProps> = ({
  initialValues,
  onSubmit,
  onCancel
}) => {
  /**
   * Default initial values for form fields
   * Matches BMS COTRN02 INITIAL attributes
   */
  const defaultInitialValues: TransactionFormData = {
    acctId: '',
    cardNum: '',
    transTypeCd: '',
    transCatCd: 0,
    transSource: '',
    transDesc: '',
    transAmt: 0,
    transMerchantId: '',
    transMerchantName: '',
    transMerchantCity: '',
    transMerchantZip: '',
    transOrigTs: new Date().toISOString().split('T')[0], // Default to today
    transProcTs: new Date().toISOString().split('T')[0], // Default to today
    confirm: 'N'
  };

  /**
   * Merge provided initial values with defaults
   */
  const formInitialValues = {
    ...defaultInitialValues,
    ...initialValues
  };

  return (
    <Formik
      initialValues={formInitialValues}
      validationSchema={validationSchema}
      onSubmit={onSubmit}
      validateOnChange={true}
      validateOnBlur={true}
    >
      {({ values, errors, touched, handleChange, handleBlur, isSubmitting, setFieldValue, resetForm }) => (
        <Form>
          <Box sx={{ p: 3 }}>
            {/* Title Section */}
            <Typography variant="h5" gutterBottom sx={{ color: 'primary.main', mb: 3 }}>
              Add Transaction
            </Typography>

            {/* Section 1: Account/Card Selection */}
            <Grid container spacing={3}>
              <Grid item xs={12}>
                <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
                  Account Selection (one required)
                </Typography>
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="acctId"
                  name="acctId"
                  label="Enter Account #"
                  value={values.acctId}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.acctId && Boolean(errors.acctId)}
                  helperText={touched.acctId && errors.acctId}
                  autoFocus
                  inputProps={{ maxLength: 11 }}
                  disabled={values.cardNum !== '' && values.cardNum !== undefined}
                />
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="cardNum"
                  name="cardNum"
                  label="Card #"
                  value={values.cardNum}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.cardNum && Boolean(errors.cardNum)}
                  helperText={touched.cardNum && errors.cardNum}
                  inputProps={{ maxLength: 16 }}
                  disabled={values.acctId !== '' && values.acctId !== undefined}
                />
              </Grid>
            </Grid>

            <Divider sx={{ my: 3 }} />

            {/* Section 2: Transaction Details */}
            <Grid container spacing={3}>
              <Grid item xs={12}>
                <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
                  Transaction Details
                </Typography>
              </Grid>

              <Grid item xs={12} md={4}>
                <FormControl 
                  fullWidth 
                  error={touched.transTypeCd && Boolean(errors.transTypeCd)}
                >
                  <InputLabel id="transTypeCd-label">Type CD *</InputLabel>
                  <Select
                    labelId="transTypeCd-label"
                    id="transTypeCd"
                    name="transTypeCd"
                    value={values.transTypeCd}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="Type CD *"
                  >
                    <MenuItem value="">
                      <em>Select Type</em>
                    </MenuItem>
                    <MenuItem value="01">Purchase (01)</MenuItem>
                    <MenuItem value="02">Payment (02)</MenuItem>
                    <MenuItem value="03">Cash Advance (03)</MenuItem>
                    <MenuItem value="04">Refund (04)</MenuItem>
                    <MenuItem value="05">Balance Transfer (05)</MenuItem>
                    <MenuItem value="06">Fee (06)</MenuItem>
                    <MenuItem value="07">Interest (07)</MenuItem>
                  </Select>
                  {touched.transTypeCd && errors.transTypeCd && (
                    <FormHelperText>{errors.transTypeCd}</FormHelperText>
                  )}
                </FormControl>
              </Grid>

              <Grid item xs={12} md={4}>
                <FormControl 
                  fullWidth 
                  error={touched.transCatCd && Boolean(errors.transCatCd)}
                >
                  <InputLabel id="transCatCd-label">Category CD *</InputLabel>
                  <Select
                    labelId="transCatCd-label"
                    id="transCatCd"
                    name="transCatCd"
                    value={values.transCatCd}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="Category CD *"
                  >
                    <MenuItem value={0}>
                      <em>Select Category</em>
                    </MenuItem>
                    <MenuItem value={TransactionCategory.GROCERIES}>
                      Groceries ({TransactionCategory.GROCERIES})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.GAS}>
                      Gas ({TransactionCategory.GAS})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.DINING}>
                      Dining ({TransactionCategory.DINING})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.TRAVEL}>
                      Travel ({TransactionCategory.TRAVEL})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.ONLINE}>
                      Online ({TransactionCategory.ONLINE})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.HEALTHCARE}>
                      Healthcare ({TransactionCategory.HEALTHCARE})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.UTILITIES}>
                      Utilities ({TransactionCategory.UTILITIES})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.MERCHANDISE}>
                      Merchandise ({TransactionCategory.MERCHANDISE})
                    </MenuItem>
                    <MenuItem value={TransactionCategory.OTHER}>
                      Other ({TransactionCategory.OTHER})
                    </MenuItem>
                  </Select>
                  {touched.transCatCd && errors.transCatCd && (
                    <FormHelperText>{errors.transCatCd}</FormHelperText>
                  )}
                </FormControl>
              </Grid>

              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  id="transSource"
                  name="transSource"
                  label="Source *"
                  value={values.transSource}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transSource && Boolean(errors.transSource)}
                  helperText={touched.transSource && errors.transSource}
                  inputProps={{ maxLength: 10 }}
                  placeholder="POS, ATM, ONLINE, MOBILE"
                />
              </Grid>

              <Grid item xs={12}>
                <TextField
                  fullWidth
                  id="transDesc"
                  name="transDesc"
                  label="Description *"
                  value={values.transDesc}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transDesc && Boolean(errors.transDesc)}
                  helperText={touched.transDesc && errors.transDesc}
                  multiline
                  rows={2}
                  inputProps={{ maxLength: 100 }}
                />
              </Grid>

              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  id="transAmt"
                  name="transAmt"
                  label="Amount *"
                  type="number"
                  value={values.transAmt}
                  onChange={handleChange}
                  onBlur={(e) => {
                    handleBlur(e);
                    // Format to 2 decimal places on blur
                    const formattedValue = parseFloat(e.target.value).toFixed(2);
                    setFieldValue('transAmt', parseFloat(formattedValue));
                  }}
                  error={touched.transAmt && Boolean(errors.transAmt)}
                  helperText={
                    touched.transAmt && errors.transAmt 
                      ? errors.transAmt 
                      : '(-99999999.99 to 99999999.99)'
                  }
                  inputProps={{
                    step: '0.01',
                    min: '-99999999.99',
                    max: '99999999.99'
                  }}
                />
              </Grid>

              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  id="transOrigTs"
                  name="transOrigTs"
                  label="Orig Date *"
                  type="date"
                  value={values.transOrigTs}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transOrigTs && Boolean(errors.transOrigTs)}
                  helperText={
                    touched.transOrigTs && errors.transOrigTs 
                      ? errors.transOrigTs 
                      : '(YYYY-MM-DD)'
                  }
                  InputLabelProps={{
                    shrink: true,
                  }}
                />
              </Grid>

              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  id="transProcTs"
                  name="transProcTs"
                  label="Proc Date *"
                  type="date"
                  value={values.transProcTs}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transProcTs && Boolean(errors.transProcTs)}
                  helperText={
                    touched.transProcTs && errors.transProcTs 
                      ? errors.transProcTs 
                      : '(YYYY-MM-DD)'
                  }
                  InputLabelProps={{
                    shrink: true,
                  }}
                />
              </Grid>
            </Grid>

            <Divider sx={{ my: 3 }} />

            {/* Section 3: Merchant Information (Optional) */}
            <Grid container spacing={3}>
              <Grid item xs={12}>
                <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
                  Merchant Information (optional)
                </Typography>
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="transMerchantId"
                  name="transMerchantId"
                  label="Merchant ID"
                  value={values.transMerchantId}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transMerchantId && Boolean(errors.transMerchantId)}
                  helperText={touched.transMerchantId && errors.transMerchantId}
                  inputProps={{ maxLength: 9 }}
                />
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="transMerchantName"
                  name="transMerchantName"
                  label="Merchant Name"
                  value={values.transMerchantName}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transMerchantName && Boolean(errors.transMerchantName)}
                  helperText={touched.transMerchantName && errors.transMerchantName}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="transMerchantCity"
                  name="transMerchantCity"
                  label="Merchant City"
                  value={values.transMerchantCity}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transMerchantCity && Boolean(errors.transMerchantCity)}
                  helperText={touched.transMerchantCity && errors.transMerchantCity}
                  inputProps={{ maxLength: 50 }}
                />
              </Grid>

              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  id="transMerchantZip"
                  name="transMerchantZip"
                  label="Merchant Zip"
                  value={values.transMerchantZip}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.transMerchantZip && Boolean(errors.transMerchantZip)}
                  helperText={touched.transMerchantZip && errors.transMerchantZip}
                  inputProps={{ maxLength: 10 }}
                />
              </Grid>
            </Grid>

            <Divider sx={{ my: 3 }} />

            {/* Section 4: Confirmation and Actions */}
            <Grid container spacing={3}>
              <Grid item xs={12}>
                <Box sx={{ p: 2, bgcolor: 'grey.100', borderRadius: 1 }}>
                  <Typography variant="body1" gutterBottom sx={{ color: 'text.primary', mb: 2 }}>
                    You are about to add this transaction. Please confirm:
                  </Typography>
                  
                  <FormControl error={touched.confirm && Boolean(errors.confirm)}>
                    <FormControlLabel
                      control={
                        <Checkbox
                          id="confirm"
                          name="confirm"
                          checked={values.confirm === 'Y'}
                          onChange={(e) => {
                            setFieldValue('confirm', e.target.checked ? 'Y' : 'N');
                          }}
                          color="primary"
                        />
                      }
                      label="Yes, I confirm (Y)"
                    />
                    {touched.confirm && errors.confirm && (
                      <FormHelperText error>{errors.confirm}</FormHelperText>
                    )}
                  </FormControl>
                </Box>
              </Grid>

              {/* Action Buttons */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
                  <Button
                    variant="outlined"
                    onClick={onCancel}
                    disabled={isSubmitting}
                  >
                    Cancel (F3)
                  </Button>

                  <Button
                    variant="outlined"
                    onClick={() => resetForm()}
                    disabled={isSubmitting}
                  >
                    Clear (F4)
                  </Button>

                  <Button
                    type="submit"
                    variant="contained"
                    color="primary"
                    disabled={isSubmitting || values.confirm !== 'Y'}
                  >
                    {isSubmitting ? 'Adding...' : 'Add Transaction'}
                  </Button>
                </Box>
              </Grid>

              <Grid item xs={12}>
                <Typography variant="caption" color="text.secondary">
                  ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.
                </Typography>
              </Grid>
            </Grid>
          </Box>
        </Form>
      )}
    </Formik>
  );
};
