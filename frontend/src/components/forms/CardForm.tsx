/**
 * CardForm Component
 * 
 * Reusable React TypeScript form component for credit card data entry and validation.
 * Converted from BMS map: COCRDUP.bms (Card Update Screen)
 * 
 * Features:
 * - Formik-based form state management
 * - Yup schema validation matching BMS field attributes
 * - Material-UI component library
 * - PCI DSS compliant card number handling (masked display)
 * - Luhn algorithm validation for card numbers
 * - Future date validation for expiration dates
 * 
 * BMS Field Mappings:
 * - ACCTSID (line 84-88): Account number (11 digits, PROT) → cardAcctId (read-only)
 * - CARDSID (line 96-100): Card number (16 digits, UNPROT) → cardNum (with Luhn validation)
 * - CRDNAME (line 107-110): Cardholder name (50 chars, UNPROT) → cardEmbossedName
 * - CRDSTCD (line 117-120): Status code (1 char, UNPROT) → cardStatus (Y/N/B/E dropdown)
 * - EXPMON/EXPYEAR (line 127-139): Expiration (2/4 chars, UNPROT) → cardExpirationDate (YYYY-MM)
 * 
 * PCI Compliance:
 * - Card numbers are masked (****1234) in update mode
 * - Full card numbers only displayed during creation
 * - CVV fields intentionally excluded per PCI DSS 3.2 requirements
 * - Card numbers never logged to browser console
 * 
 * Conversion Notes:
 * - COBOL COMP-3 not applicable (no currency fields in card form)
 * - BMS ASKIP/PROT attributes → disabled TextField
 * - BMS UNPROT attributes → editable TextField
 * - BMS HILIGHT=UNDERLINE → Material-UI default underline style
 * - BMS IC (Initial Cursor) → autoFocus on first editable field
 * 
 * @author Blitzy Platform - COBOL to React Migration
 * @version 1.0.0
 */

import React from 'react';
import { Formik, Form } from 'formik';
import * as yup from 'yup';
import {
  Box,
  TextField,
  Select,
  MenuItem,
  Button,
  FormControl,
  InputLabel,
  Grid,
  Typography,
  FormHelperText
} from '@mui/material';
import { CardStatus } from '../../types/card';

/**
 * Card form data structure
 * 
 * Represents the editable fields in the card update form.
 * Matches BMS COCRDUP map field structure.
 */
export interface CardFormData {
  /**
   * 16-digit card number
   * From BMS: CARDSID (line 96-100)
   * 
   * Validation:
   * - Required field
   * - Exactly 16 numeric digits
   * - Must pass Luhn algorithm checksum validation
   * - Masked display (****1234) in update mode per PCI DSS
   */
  cardNum: string;

  /**
   * 11-digit account identifier
   * From BMS: ACCTSID (line 84-88, PROT attribute = read-only)
   * 
   * Validation:
   * - Required field
   * - Positive integer
   * - Read-only in update mode (associated account)
   */
  cardAcctId: number;

  /**
   * Cardholder name embossed on card
   * From BMS: CRDNAME (line 107-110)
   * 
   * Validation:
   * - Required field
   * - Maximum 50 characters
   * - Only letters and spaces allowed
   */
  cardEmbossedName: string;

  /**
   * Card status code
   * From BMS: CRDSTCD (line 117-120)
   * 
   * Validation:
   * - Required field
   * - Must be one of: Y (Active), N (Inactive), B (Blocked), E (Expired)
   * - Maps to CardStatus enum values
   */
  cardStatus: string;

  /**
   * Card expiration date in YYYY-MM format
   * From BMS: EXPMON/EXPYEAR (lines 127-139)
   * 
   * Validation:
   * - Required field
   * - YYYY-MM format (year-month only, day defaults to last day of month)
   * - Must be future date (expiration cannot be in the past)
   * 
   * Note: BMS has separate EXPMON (2 chars) and EXPYEAR (4 chars) fields
   * Combined into single field for modern UI/UX
   */
  cardExpirationDate: string;
}

/**
 * CardForm component props
 * 
 * Defines the interface for parent components (CardUpdatePage)
 * to interact with the form component.
 */
export interface CardFormProps {
  /**
   * Initial form values
   * Partial allows pre-filling some fields while leaving others empty
   * 
   * Usage:
   * - Create mode: Empty or minimal default values
   * - Update mode: Pre-filled with existing card data (cardNum masked)
   */
  initialValues?: Partial<CardFormData>;

  /**
   * Form submission callback
   * 
   * Called when form is submitted and validation passes.
   * Can be async to support API calls.
   * 
   * @param values Validated form data
   */
  onSubmit: (values: CardFormData) => void | Promise<void>;

  /**
   * Form cancellation callback
   * 
   * Called when user clicks Cancel button.
   * Should navigate back to previous screen or reset form.
   */
  onCancel: () => void;

  /**
   * Form mode
   * 
   * - 'create': Creating new card (show full card number input)
   * - 'update': Updating existing card (show masked card number)
   * 
   * Default: 'update'
   */
  mode?: 'create' | 'update';
}

/**
 * Validates credit card number using Luhn algorithm (mod 10 checksum)
 * 
 * The Luhn algorithm is used by credit card companies to verify card numbers
 * and catch simple errors in card number entry.
 * 
 * Algorithm:
 * 1. Starting from the rightmost digit, double every second digit
 * 2. If doubling results in > 9, subtract 9
 * 3. Sum all digits
 * 4. If sum is divisible by 10, card number is valid
 * 
 * Example: 4532015112830366
 * - Valid card number passes Luhn check
 * - Invalid card number fails Luhn check
 * 
 * @param cardNumber 16-digit card number string
 * @returns true if valid, false otherwise
 * 
 * Reference: ISO/IEC 7812-1 standard for credit card numbering
 */
const validateLuhnAlgorithm = (cardNumber: string): boolean => {
  // Remove any non-digit characters (spaces, dashes)
  const digits = cardNumber.replace(/\D/g, '');

  // Must be 16 digits
  if (digits.length !== 16) {
    return false;
  }

  let sum = 0;
  let isEven = false;

  // Iterate from right to left
  for (let i = digits.length - 1; i >= 0; i--) {
    let digit = parseInt(digits[i], 10);

    if (isEven) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }

    sum += digit;
    isEven = !isEven;
  }

  // Valid if sum is divisible by 10
  return sum % 10 === 0;
};

/**
 * Formats card number with spaces for readability
 * 
 * Converts: 1234567890123456
 * To: 1234 5678 9012 3456
 * 
 * Improves user experience by making card numbers easier to read.
 * Standard credit card display format.
 * 
 * @param value Raw card number string
 * @returns Formatted card number with spaces
 */
const formatCardNumber = (value: string): string => {
  // Remove existing spaces
  const cleaned = value.replace(/\s/g, '');
  
  // Add space every 4 digits
  return cleaned.replace(/(.{4})/g, '$1 ').trim();
};

/**
 * Masks card number for PCI compliance
 * 
 * Converts: 1234567890123456
 * To: ************3456
 * 
 * Shows only last 4 digits, masks first 12 with asterisks.
 * PCI DSS 3.2 requirement for displaying card numbers.
 * 
 * @param cardNumber Full 16-digit card number
 * @returns Masked card number
 */
const maskCardNumber = (cardNumber: string): string => {
  if (!cardNumber || cardNumber.length < 4) {
    return cardNumber;
  }

  const lastFour = cardNumber.slice(-4);
  return '*'.repeat(12) + lastFour;
};

/**
 * Yup validation schema matching BMS COCRDUP field attributes
 * 
 * Implements all field-level validations from BMS map definition:
 * - Required fields (UNPROT fields without default values)
 * - Length constraints (LENGTH attribute)
 * - Format validations (numeric, alphanumeric, date)
 * - Business rule validations (Luhn algorithm, future dates)
 * 
 * Validation rules preserve COBOL field validation logic while using
 * modern JavaScript validation patterns.
 */
const validationSchema = yup.object({
  cardNum: yup
    .string()
    .required('Card Number is required')
    .matches(/^[0-9]{16}$/, 'Card Number must be exactly 16 digits')
    .test(
      'luhn',
      'Invalid card number (failed Luhn checksum)',
      (value) => {
        if (!value) return false;
        return validateLuhnAlgorithm(value);
      }
    ),

  cardAcctId: yup
    .number()
    .required('Account ID is required')
    .positive('Account ID must be a positive number')
    .integer('Account ID must be an integer')
    .test(
      'length',
      'Account ID must be at most 11 digits',
      (value) => {
        if (!value) return false;
        return value.toString().length <= 11;
      }
    ),

  cardEmbossedName: yup
    .string()
    .required('Cardholder Name is required')
    .max(50, 'Cardholder Name must be at most 50 characters')
    .matches(
      /^[A-Za-z\s]+$/,
      'Cardholder Name must contain only letters and spaces'
    )
    .test(
      'no-multiple-spaces',
      'Cardholder Name cannot have consecutive spaces',
      (value) => {
        if (!value) return false;
        return !/\s{2,}/.test(value);
      }
    ),

  cardStatus: yup
    .string()
    .required('Card Status is required')
    .oneOf(
      [CardStatus.ACTIVE, CardStatus.INACTIVE, CardStatus.BLOCKED, CardStatus.EXPIRED],
      'Invalid card status'
    ),

  cardExpirationDate: yup
    .string()
    .required('Expiration Date is required')
    .matches(/^\d{4}-\d{2}$/, 'Expiration Date must be in YYYY-MM format')
    .test(
      'future-date',
      'Expiration Date must be in the future',
      (value) => {
        if (!value) return false;

        const [year, month] = value.split('-').map(Number);
        
        // Create date for last day of expiration month
        // Cards are valid through the end of the expiration month
        const expDate = new Date(year, month, 0); // Day 0 = last day of previous month (i.e., month-1)
        const today = new Date();
        
        // Set time to start of day for fair comparison
        today.setHours(0, 0, 0, 0);

        return expDate >= today;
      }
    )
});

/**
 * CardForm Component
 * 
 * Reusable form component for creating and updating credit card records.
 * Uses Formik for form state management and Yup for validation.
 * 
 * Component Features:
 * - Real-time validation feedback
 * - Responsive Material-UI layout
 * - PCI-compliant card number handling
 * - Accessibility-friendly form controls
 * - Proper error messaging
 * 
 * Usage Example:
 * ```tsx
 * <CardForm
 *   initialValues={{ cardNum: '****1234', cardAcctId: 12345678901 }}
 *   onSubmit={handleSubmit}
 *   onCancel={handleCancel}
 *   mode="update"
 * />
 * ```
 * 
 * @param props CardFormProps
 * @returns JSX.Element
 */
const CardForm: React.FC<CardFormProps> = ({
  initialValues = {},
  onSubmit,
  onCancel,
  mode = 'update'
}) => {
  // Default form values matching CardFormData structure
  const defaultValues: CardFormData = {
    cardNum: '',
    cardAcctId: 0,
    cardEmbossedName: '',
    cardStatus: CardStatus.ACTIVE,
    cardExpirationDate: ''
  };

  // Merge initial values with defaults
  const formInitialValues: CardFormData = {
    ...defaultValues,
    ...initialValues
  };

  return (
    <Formik
      initialValues={formInitialValues}
      validationSchema={validationSchema}
      onSubmit={onSubmit}
      enableReinitialize={true}
    >
      {({ values, errors, touched, handleChange, handleBlur, isSubmitting, setFieldValue }) => (
        <Form>
          <Box sx={{ p: 3, maxWidth: 800, margin: '0 auto' }}>
            {/* Form Title */}
            <Typography variant="h5" component="h2" gutterBottom sx={{ mb: 3, color: 'primary.main' }}>
              {mode === 'create' ? 'Create New Card' : 'Update Card Details'}
            </Typography>

            <Grid container spacing={3}>
              {/* Account Number - Read-only in update mode */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="cardAcctId"
                  name="cardAcctId"
                  label="Account Number"
                  value={values.cardAcctId || ''}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.cardAcctId && Boolean(errors.cardAcctId)}
                  helperText={touched.cardAcctId && errors.cardAcctId}
                  disabled={mode === 'update'}
                  required
                  InputProps={{
                    readOnly: mode === 'update'
                  }}
                />
              </Grid>

              {/* Card Number - Masked in update mode */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="cardNum"
                  name="cardNum"
                  label="Card Number"
                  value={mode === 'update' && values.cardNum ? maskCardNumber(values.cardNum) : values.cardNum}
                  onChange={(e) => {
                    // Remove spaces and limit to 16 digits
                    const cleaned = e.target.value.replace(/\D/g, '').slice(0, 16);
                    setFieldValue('cardNum', cleaned);
                  }}
                  onBlur={handleBlur}
                  error={touched.cardNum && Boolean(errors.cardNum)}
                  helperText={touched.cardNum && errors.cardNum}
                  disabled={mode === 'update'}
                  required
                  autoFocus={mode === 'create'}
                  placeholder="1234 5678 9012 3456"
                  inputProps={{
                    maxLength: 19, // 16 digits + 3 spaces
                    inputMode: 'numeric'
                  }}
                  InputProps={{
                    readOnly: mode === 'update'
                  }}
                />
              </Grid>

              {/* Cardholder Name */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  id="cardEmbossedName"
                  name="cardEmbossedName"
                  label="Name on Card"
                  value={values.cardEmbossedName}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.cardEmbossedName && Boolean(errors.cardEmbossedName)}
                  helperText={touched.cardEmbossedName && errors.cardEmbossedName}
                  required
                  placeholder="JOHN DOE"
                  inputProps={{
                    maxLength: 50,
                    style: { textTransform: 'uppercase' }
                  }}
                />
              </Grid>

              {/* Card Status */}
              <Grid item xs={12} sm={6}>
                <FormControl 
                  fullWidth 
                  error={touched.cardStatus && Boolean(errors.cardStatus)}
                  required
                >
                  <InputLabel id="cardStatus-label">Card Status</InputLabel>
                  <Select
                    labelId="cardStatus-label"
                    id="cardStatus"
                    name="cardStatus"
                    value={values.cardStatus}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="Card Status"
                  >
                    <MenuItem value={CardStatus.ACTIVE}>Active (Y)</MenuItem>
                    <MenuItem value={CardStatus.INACTIVE}>Inactive (N)</MenuItem>
                    <MenuItem value={CardStatus.BLOCKED}>Blocked (B)</MenuItem>
                    <MenuItem value={CardStatus.EXPIRED}>Expired (E)</MenuItem>
                  </Select>
                  {touched.cardStatus && errors.cardStatus && (
                    <FormHelperText>{errors.cardStatus}</FormHelperText>
                  )}
                </FormControl>
              </Grid>

              {/* Expiration Date */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="cardExpirationDate"
                  name="cardExpirationDate"
                  label="Expiration Date (MM/YYYY)"
                  value={values.cardExpirationDate}
                  onChange={(e) => {
                    let value = e.target.value.replace(/\D/g, '');
                    
                    // Format as YYYY-MM
                    if (value.length >= 4) {
                      // If user types MMYYYY, convert to YYYYMM
                      if (value.length === 6) {
                        const mm = value.slice(0, 2);
                        const yyyy = value.slice(2, 6);
                        value = `${yyyy}${mm}`;
                      }
                      
                      const year = value.slice(0, 4);
                      const month = value.slice(4, 6);
                      value = `${year}-${month}`;
                    }
                    
                    setFieldValue('cardExpirationDate', value);
                  }}
                  onBlur={handleBlur}
                  error={touched.cardExpirationDate && Boolean(errors.cardExpirationDate)}
                  helperText={touched.cardExpirationDate && errors.cardExpirationDate || 'Format: YYYY-MM (e.g., 2025-12)'}
                  required
                  placeholder="YYYY-MM"
                  inputProps={{
                    maxLength: 7, // YYYY-MM
                    inputMode: 'numeric'
                  }}
                />
              </Grid>

              {/* Action Buttons */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end', mt: 2 }}>
                  <Button
                    variant="outlined"
                    color="secondary"
                    onClick={onCancel}
                    disabled={isSubmitting}
                  >
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    variant="contained"
                    color="primary"
                    disabled={isSubmitting}
                  >
                    {isSubmitting ? 'Processing...' : mode === 'create' ? 'Create Card' : 'Update Card'}
                  </Button>
                </Box>
              </Grid>
            </Grid>
          </Box>
        </Form>
      )}
    </Formik>
  );
};

export default CardForm;
