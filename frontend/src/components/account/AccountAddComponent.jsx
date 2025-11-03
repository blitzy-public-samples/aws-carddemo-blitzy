/**
 * AccountAddComponent.jsx
 * 
 * React functional component for creating new credit card accounts in the CardDemo
 * application. This component transforms the COBOL account creation functionality
 * from mainframe BMS screens to a modern Material-UI React form interface.
 * 
 * COBOL Source Transformation:
 * - BMS Mapset: COACTUP.bms (Account Update screen adapted for account creation)
 * - Copybook: COACTUP.CPY (Field definitions and data structures)
 * - COBOL Programs: COACTADD.cbl (implicit account creation logic)
 * 
 * Key Transformations:
 * - BMS DFHMDF fields → Material-UI TextField/Select/DatePicker components
 * - COBOL PIC clauses → Yup validation schema rules
 * - CICS WRITE DATASET('ACCTDAT') → REST API POST /api/accounts
 * - BMS ERRMSG field → Toast notifications for user feedback
 * - PF keys (F3=Exit, F12=Cancel) → Button onClick handlers
 * - COMP-3 decimal precision → BigDecimal scale 2 (toFixed(2))
 * 
 * Form Fields (from COACTUP BMS mapset):
 * - Customer ID (ACSTNUM): 9-digit numeric, required
 * - Account Status (ACSTTUS): Y/N selection, required
 * - Credit Limit (ACRDLIM): 15-char decimal (max 999999999.99), required
 * - Cash Credit Limit (ACSHLIM): 15-char decimal, optional
 * - Open Date (OPNYEAR/OPNMON/OPNDAY): Date picker, required, not in future
 * - Expiry Date (EXPYEAR/EXPMON/EXPDAY): Date picker, required, after open date
 * - Reissue Date (RISYEAR/RISMON/RISDAY): Date picker, optional
 * - Account Group (AADDGRP): 10-char text, optional
 * 
 * Validation Rules (from COBOL field validation):
 * - Customer ID: Required, numeric, exactly 9 digits, must exist in CUSTDAT
 * - Account Status: Required, must be 'Y' (active) or 'N' (inactive)
 * - Credit Limit: Required, positive number, min 0, max 999999999.99
 * - Cash Credit Limit: Optional, positive number, cannot exceed credit limit
 * - Open Date: Required, valid date format, cannot be in future
 * - Expiry Date: Required, valid date format, must be after open date
 * - Reissue Date: Optional, valid date format
 * - Account Group: Optional, max 10 characters
 * 
 * Integration:
 * - Uses accountService.createAccount() for REST API interaction
 * - Displays success/error notifications via react-toastify
 * - Navigates to /accounts list on successful creation
 * - Navigates back on cancel action
 * 
 * @module components/account/AccountAddComponent
 */

import { useNavigate } from 'react-router-dom';
import { Formik, Form } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Button,
  TextField,
  Typography,
  Paper,
  Grid,
  MenuItem,
  CircularProgress,
  FormControl,
  InputLabel,
  Select,
  FormHelperText
} from '@mui/material';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import { toast } from 'react-toastify';
import accountService from '../../services/accountService';

/**
 * Yup Validation Schema
 * 
 * Defines declarative validation rules matching COBOL field validation patterns
 * from COACTUP BMS mapset and COBOL account creation business rules.
 * 
 * Validation Rule Mappings:
 * - customerId: ACSTNUM PIC X(9) - Required, numeric, 9 digits
 * - accountStatus: ACSTTUSI PIC X(1) - Required, Y or N values
 * - creditLimit: ACRDLIMI PIC X(15) COMP-3 - Required, 0 to 999999999.99
 * - cashCreditLimit: ACSHLIMI PIC X(15) - Optional, max creditLimit
 * - openDate: OPNYEAR/OPNMON/OPNDAY - Required, not in future
 * - expiryDate: EXPYEAR/EXPMON/EXPDAY - Required, after openDate
 * - reissueDate: RISYEAR/RISMON/RISDAY - Optional
 * - accountGroup: AADDGRPI PIC X(10) - Optional, max 10 chars
 */
const validationSchema = Yup.object().shape({
  customerId: Yup.string()
    .required('Customer ID is required')
    .matches(/^\d{9}$/, 'Customer ID must be exactly 9 digits')
    .test('non-zero', 'Customer ID cannot be all zeros', (value) => {
      return value !== '000000000';
    }),
  
  accountStatus: Yup.string()
    .required('Account status is required')
    .oneOf(['Y', 'N'], 'Account status must be Y (Active) or N (Inactive)'),
  
  creditLimit: Yup.number()
    .required('Credit limit is required')
    .min(0, 'Credit limit must be a positive number')
    .max(999999999.99, 'Credit limit cannot exceed 999,999,999.99')
    .test('decimal-places', 'Credit limit must have at most 2 decimal places', (value) => {
      if (value === undefined || value === null) return true;
      const decimalPlaces = (value.toString().split('.')[1] || '').length;
      return decimalPlaces <= 2;
    }),
  
  cashCreditLimit: Yup.number()
    .nullable()
    .min(0, 'Cash credit limit must be a positive number')
    .max(999999999.99, 'Cash credit limit cannot exceed 999,999,999.99')
    .test('max-credit-limit', 'Cash credit limit cannot exceed credit limit', function(value) {
      const { creditLimit } = this.parent;
      if (value === null || value === undefined || value === '') return true;
      return value <= creditLimit;
    })
    .test('decimal-places', 'Cash credit limit must have at most 2 decimal places', (value) => {
      if (value === undefined || value === null || value === '') return true;
      const decimalPlaces = (value.toString().split('.')[1] || '').length;
      return decimalPlaces <= 2;
    }),
  
  openDate: Yup.date()
    .required('Open date is required')
    .nullable()
    .max(new Date(), 'Open date cannot be in the future')
    .typeError('Open date must be a valid date'),
  
  expiryDate: Yup.date()
    .required('Expiry date is required')
    .nullable()
    .min(Yup.ref('openDate'), 'Expiry date must be after open date')
    .typeError('Expiry date must be a valid date'),
  
  reissueDate: Yup.date()
    .nullable()
    .typeError('Reissue date must be a valid date'),
  
  accountGroup: Yup.string()
    .max(10, 'Account group cannot exceed 10 characters')
    .nullable()
});

/**
 * AccountAddComponent - Functional React Component
 * 
 * Renders a Material-UI form for creating new credit card accounts, transforming
 * the COBOL COACTUP BMS screen into a modern web interface.
 * 
 * Component Behavior:
 * 1. Displays form with all required and optional fields
 * 2. Validates input using Yup schema (COBOL validation equivalence)
 * 3. Submits data to REST API via accountService.createAccount()
 * 4. Shows success notification and navigates to account list
 * 5. Shows error notifications for validation or API errors
 * 6. Provides cancel navigation back to previous page
 * 
 * State Management:
 * - Form state managed by Formik (values, errors, touched, isSubmitting)
 * - No component-level state needed beyond Formik
 * 
 * Error Handling:
 * - Client-side validation via Yup schema
 * - Server-side validation errors displayed inline with fields
 * - Network errors displayed via toast notifications
 * - Field-specific errors highlighted with error text
 * 
 * @returns {JSX.Element} Account creation form component
 */
const AccountAddComponent = () => {
  const navigate = useNavigate();

  /**
   * Initial form values
   * 
   * Maps to COBOL WORKING-STORAGE section variables with default values.
   * All fields start empty/null except accountStatus which defaults to 'Y' (active).
   */
  const initialValues = {
    customerId: '',
    accountStatus: 'Y',
    creditLimit: '',
    cashCreditLimit: '',
    openDate: null,
    expiryDate: null,
    reissueDate: null,
    accountGroup: ''
  };

  /**
   * Form submission handler
   * 
   * Maps COBOL account creation transaction processing:
   * - COBOL: EXEC CICS WRITE DATASET('ACCTDAT') FROM(new-account-record)
   * - JavaScript: POST /api/accounts with JSON request body
   * 
   * Processing Steps:
   * 1. Format currency values to 2 decimal places (COMP-3 precision)
   * 2. Format dates to YYYY-MM-DD (ISO 8601 format)
   * 3. Call accountService.createAccount() API method
   * 4. Display success notification with generated account ID
   * 5. Navigate to /accounts list page
   * 6. Handle errors with toast notifications and inline field errors
   * 
   * Transaction Semantics:
   * Backend applies @Transactional for atomic account creation including:
   * - Account record insertion
   * - Cross-reference table updates (XREF equivalent)
   * - Initial balance initialization (0.00)
   * - Audit trail logging
   * 
   * @param {Object} values - Form field values from Formik
   * @param {Object} formikBag - Formik helper methods
   * @param {Function} formikBag.setSubmitting - Set submitting state
   * @param {Function} formikBag.setErrors - Set field-specific errors
   */
  const handleSubmit = async (values, { setSubmitting, setErrors }) => {
    try {
      // Format currency values to 2 decimal places (COMP-3 precision preservation)
      // Maps COBOL: ACCT-CREDIT-LIMIT PIC S9(13)V99 COMP-3
      const accountData = {
        customerId: values.customerId,
        accountStatus: values.accountStatus,
        creditLimit: parseFloat(values.creditLimit).toFixed(2),
        cashCreditLimit: values.cashCreditLimit 
          ? parseFloat(values.cashCreditLimit).toFixed(2) 
          : '0.00',
        openDate: values.openDate 
          ? values.openDate.toISOString().split('T')[0] 
          : null,
        expiryDate: values.expiryDate 
          ? values.expiryDate.toISOString().split('T')[0] 
          : null,
        reissueDate: values.reissueDate 
          ? values.reissueDate.toISOString().split('T')[0] 
          : null,
        accountGroup: values.accountGroup || null
      };

      // Execute account creation via REST API
      // Maps COBOL: EXEC CICS WRITE DATASET('ACCTDAT')
      const newAccount = await accountService.createAccount(accountData);

      // Display success notification
      // Maps BMS INFOMSG field display
      toast.success(
        `Account ${newAccount.accountId} created successfully!`,
        {
          position: 'top-right',
          autoClose: 5000,
          hideProgressBar: false,
          closeOnClick: true,
          pauseOnHover: true,
          draggable: true
        }
      );

      // Navigate to account list
      // Maps COBOL: EXEC CICS RETURN TRANSID('COAL')
      navigate('/accounts');
      
    } catch (error) {
      // Handle validation and API errors
      // Maps COBOL error handling logic with ERRMSG field display
      
      if (error.status === 400 && error.errors) {
        // Field-specific validation errors from backend
        // Maps COBOL: FLG-*-NOT-OK validation failures
        setErrors(error.errors);
        toast.error('Please correct the validation errors in the form', {
          position: 'top-right',
          autoClose: 5000
        });
      } else if (error.status === 404) {
        // Customer not found error
        // Maps COBOL: CUSTDAT READ NOTFND condition
        setErrors({ customerId: 'Customer not found' });
        toast.error('Customer ID does not exist in the system', {
          position: 'top-right',
          autoClose: 5000
        });
      } else {
        // Generic error handling
        // Maps COBOL: General file I/O error handling
        const errorMessage = error.message || 'Failed to create account. Please try again.';
        toast.error(errorMessage, {
          position: 'top-right',
          autoClose: 5000
        });
      }
    } finally {
      // Reset submitting state regardless of success or failure
      setSubmitting(false);
    }
  };

  /**
   * Cancel handler
   * 
   * Maps PF3=Exit and F12=Cancel key functions from BMS screen.
   * Navigates back to previous page without saving changes.
   * 
   * COBOL Equivalent: EXEC CICS RETURN
   */
  const handleCancel = () => {
    navigate(-1);
  };

  return (
    <LocalizationProvider dateAdapter={AdapterDateFns}>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'flex-start',
          minHeight: '100vh',
          backgroundColor: '#f5f5f5',
          padding: 3
        }}
      >
        <Paper
          elevation={3}
          sx={{
            width: '100%',
            maxWidth: 900,
            padding: 4,
            marginTop: 2
          }}
        >
          {/* Title Section - Maps BMS TITLE field at POS=(4,33) */}
          <Typography
            variant="h4"
            component="h1"
            gutterBottom
            align="center"
            sx={{
              color: '#1976d2',
              marginBottom: 3,
              fontWeight: 500
            }}
          >
            Add New Account
          </Typography>

          <Typography
            variant="subtitle1"
            align="center"
            sx={{
              color: '#666',
              marginBottom: 4
            }}
          >
            Create a new credit card account for an existing customer
          </Typography>

          {/* Formik Form Container */}
          <Formik
            initialValues={initialValues}
            validationSchema={validationSchema}
            onSubmit={handleSubmit}
            validateOnChange={true}
            validateOnBlur={true}
          >
            {({ values, errors, touched, isSubmitting, setFieldValue, handleChange, handleBlur }) => (
              <Form noValidate>
                <Grid container spacing={3}>
                  
                  {/* Customer Information Section */}
                  <Grid item xs={12}>
                    <Typography
                      variant="h6"
                      sx={{
                        color: '#1976d2',
                        marginBottom: 2,
                        borderBottom: '2px solid #1976d2',
                        paddingBottom: 1
                      }}
                    >
                      Customer Details
                    </Typography>
                  </Grid>

                  {/* Customer ID Field - Maps ACSTNUM field (PIC X(9), UNPROT) */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      required
                      id="customerId"
                      name="customerId"
                      label="Customer ID"
                      value={values.customerId}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.customerId && Boolean(errors.customerId)}
                      helperText={touched.customerId && errors.customerId}
                      autoFocus
                      inputProps={{
                        maxLength: 9,
                        pattern: '[0-9]*',
                        inputMode: 'numeric'
                      }}
                      sx={{
                        '& .MuiInputBase-root': {
                          backgroundColor: '#fff'
                        },
                        '& .MuiInputBase-input': {
                          textDecoration: 'underline'
                        }
                      }}
                    />
                  </Grid>

                  {/* Account Status Field - Maps ACSTTUS field (PIC X(1), UNPROT) */}
                  <Grid item xs={12} sm={6}>
                    <FormControl
                      fullWidth
                      required
                      error={touched.accountStatus && Boolean(errors.accountStatus)}
                    >
                      <InputLabel id="accountStatus-label">Account Status</InputLabel>
                      <Select
                        labelId="accountStatus-label"
                        id="accountStatus"
                        name="accountStatus"
                        value={values.accountStatus}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        label="Account Status"
                        sx={{
                          backgroundColor: '#fff',
                          '& .MuiSelect-select': {
                            textDecoration: 'underline'
                          }
                        }}
                      >
                        <MenuItem value="Y">Y - Active</MenuItem>
                        <MenuItem value="N">N - Inactive</MenuItem>
                      </Select>
                      {touched.accountStatus && errors.accountStatus && (
                        <FormHelperText>{errors.accountStatus}</FormHelperText>
                      )}
                    </FormControl>
                  </Grid>

                  {/* Account Financial Information Section */}
                  <Grid item xs={12}>
                    <Typography
                      variant="h6"
                      sx={{
                        color: '#1976d2',
                        marginTop: 2,
                        marginBottom: 2,
                        borderBottom: '2px solid #1976d2',
                        paddingBottom: 1
                      }}
                    >
                      Financial Information
                    </Typography>
                  </Grid>

                  {/* Credit Limit Field - Maps ACRDLIM field (PIC X(15), COMP-3 precision) */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      required
                      id="creditLimit"
                      name="creditLimit"
                      label="Credit Limit"
                      value={values.creditLimit}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.creditLimit && Boolean(errors.creditLimit)}
                      helperText={touched.creditLimit && errors.creditLimit}
                      type="number"
                      inputProps={{
                        step: '0.01',
                        min: '0',
                        max: '999999999.99'
                      }}
                      sx={{
                        '& .MuiInputBase-root': {
                          backgroundColor: '#fff'
                        },
                        '& .MuiInputBase-input': {
                          textDecoration: 'underline'
                        }
                      }}
                      InputProps={{
                        startAdornment: <Typography sx={{ marginRight: 1 }}>$</Typography>
                      }}
                    />
                  </Grid>

                  {/* Cash Credit Limit Field - Maps ACSHLIM field (PIC X(15), COMP-3 precision) */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="cashCreditLimit"
                      name="cashCreditLimit"
                      label="Cash Credit Limit"
                      value={values.cashCreditLimit}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.cashCreditLimit && Boolean(errors.cashCreditLimit)}
                      helperText={touched.cashCreditLimit && errors.cashCreditLimit}
                      type="number"
                      inputProps={{
                        step: '0.01',
                        min: '0',
                        max: '999999999.99'
                      }}
                      sx={{
                        '& .MuiInputBase-root': {
                          backgroundColor: '#fff'
                        },
                        '& .MuiInputBase-input': {
                          textDecoration: 'underline'
                        }
                      }}
                      InputProps={{
                        startAdornment: <Typography sx={{ marginRight: 1 }}>$</Typography>
                      }}
                    />
                  </Grid>

                  {/* Account Dates Section */}
                  <Grid item xs={12}>
                    <Typography
                      variant="h6"
                      sx={{
                        color: '#1976d2',
                        marginTop: 2,
                        marginBottom: 2,
                        borderBottom: '2px solid #1976d2',
                        paddingBottom: 1
                      }}
                    >
                      Account Dates
                    </Typography>
                  </Grid>

                  {/* Open Date Field - Maps OPNYEAR/OPNMON/OPNDAY fields */}
                  <Grid item xs={12} sm={4}>
                    <DatePicker
                      label="Open Date *"
                      value={values.openDate}
                      onChange={(newValue) => setFieldValue('openDate', newValue)}
                      maxDate={new Date()}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          error: touched.openDate && Boolean(errors.openDate),
                          helperText: touched.openDate && errors.openDate,
                          onBlur: handleBlur,
                          name: 'openDate',
                          sx: {
                            '& .MuiInputBase-root': {
                              backgroundColor: '#fff'
                            },
                            '& .MuiInputBase-input': {
                              textDecoration: 'underline'
                            }
                          }
                        }
                      }}
                    />
                  </Grid>

                  {/* Expiry Date Field - Maps EXPYEAR/EXPMON/EXPDAY fields */}
                  <Grid item xs={12} sm={4}>
                    <DatePicker
                      label="Expiry Date *"
                      value={values.expiryDate}
                      onChange={(newValue) => setFieldValue('expiryDate', newValue)}
                      minDate={values.openDate || new Date()}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          error: touched.expiryDate && Boolean(errors.expiryDate),
                          helperText: touched.expiryDate && errors.expiryDate,
                          onBlur: handleBlur,
                          name: 'expiryDate',
                          sx: {
                            '& .MuiInputBase-root': {
                              backgroundColor: '#fff'
                            },
                            '& .MuiInputBase-input': {
                              textDecoration: 'underline'
                            }
                          }
                        }
                      }}
                    />
                  </Grid>

                  {/* Reissue Date Field - Maps RISYEAR/RISMON/RISDAY fields (optional) */}
                  <Grid item xs={12} sm={4}>
                    <DatePicker
                      label="Reissue Date"
                      value={values.reissueDate}
                      onChange={(newValue) => setFieldValue('reissueDate', newValue)}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          error: touched.reissueDate && Boolean(errors.reissueDate),
                          helperText: touched.reissueDate && errors.reissueDate,
                          onBlur: handleBlur,
                          name: 'reissueDate',
                          sx: {
                            '& .MuiInputBase-root': {
                              backgroundColor: '#fff'
                            },
                            '& .MuiInputBase-input': {
                              textDecoration: 'underline'
                            }
                          }
                        }
                      }}
                    />
                  </Grid>

                  {/* Account Group Field - Maps AADDGRP field (PIC X(10), UNPROT) */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="accountGroup"
                      name="accountGroup"
                      label="Account Group"
                      value={values.accountGroup}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.accountGroup && Boolean(errors.accountGroup)}
                      helperText={touched.accountGroup && errors.accountGroup}
                      inputProps={{
                        maxLength: 10
                      }}
                      sx={{
                        '& .MuiInputBase-root': {
                          backgroundColor: '#fff'
                        },
                        '& .MuiInputBase-input': {
                          textDecoration: 'underline'
                        }
                      }}
                    />
                  </Grid>

                  {/* Form Action Buttons - Maps FKEYS field (PF keys) */}
                  <Grid item xs={12}>
                    <Box
                      sx={{
                        display: 'flex',
                        justifyContent: 'flex-end',
                        gap: 2,
                        marginTop: 3,
                        paddingTop: 3,
                        borderTop: '1px solid #e0e0e0'
                      }}
                    >
                      {/* Cancel Button - Maps F12=Cancel */}
                      <Button
                        variant="outlined"
                        color="secondary"
                        onClick={handleCancel}
                        disabled={isSubmitting}
                        sx={{
                          minWidth: 120
                        }}
                      >
                        Cancel
                      </Button>

                      {/* Submit Button - Maps ENTER=Process, F5=Save */}
                      <Button
                        type="submit"
                        variant="contained"
                        color="primary"
                        disabled={isSubmitting}
                        sx={{
                          minWidth: 120
                        }}
                      >
                        {isSubmitting ? (
                          <>
                            <CircularProgress
                              size={20}
                              sx={{ marginRight: 1, color: '#fff' }}
                            />
                            Creating...
                          </>
                        ) : (
                          'Create Account'
                        )}
                      </Button>
                    </Box>
                  </Grid>

                  {/* Required Fields Note */}
                  <Grid item xs={12}>
                    <Typography
                      variant="caption"
                      sx={{
                        color: '#666',
                        fontStyle: 'italic',
                        display: 'block',
                        marginTop: 2
                      }}
                    >
                      * Required fields
                    </Typography>
                  </Grid>

                </Grid>
              </Form>
            )}
          </Formik>
        </Paper>
      </Box>
    </LocalizationProvider>
  );
};

export default AccountAddComponent;


