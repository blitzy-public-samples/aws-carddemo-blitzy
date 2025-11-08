/**
 * Card Update Component - BMS Credit Card Update Screen Modernization
 * 
 * Purpose: React functional component for updating credit card details with comprehensive 
 * form validation and error handling. Transforms COCRDUP.bms (Card Update Screen) BMS mapset 
 * to React, providing an editable form for updating card expiration date (EXPMON/EXPYEAR) 
 * and active status (CRDSTCD).
 * 
 * Original COBOL Sources:
 * - app/bms/COCRDUP.bms: BMS mapset definition for card update screen (CCUP transaction)
 * - app/cbl/COCRDUPC.cbl: Card update COBOL program (online CICS transaction)
 * - app/cpy/CVACT02Y.cpy: Card record structure (CARD-RECORD, 150 bytes)
 * - app/cpy/COCOM01Y.cpy: COMMAREA structure for session state
 * - app/cpy/CSMSG01Y.cpy: Message constants
 * 
 * BMS Layout Mapping (from COCRDUP.bms):
 * - ACCTSID POS=(7,45) LENGTH=11 ATTRB=FSET,IC,NORM,PROT: Account Number (protected, display only)
 * - CARDSID POS=(8,45) LENGTH=16 ATTRB=FSET,NORM,UNPROT HILIGHT=UNDERLINE: Card Number (search/lookup input)
 * - CRDNAME POS=(11,25) LENGTH=50 ATTRB=UNPROT HILIGHT=UNDERLINE: Cardholder Name (editable)
 * - CRDSTCD POS=(13,25) LENGTH=1 ATTRB=UNPROT: Card Active Status Y/N (editable toggle)
 * - EXPMON POS=(15,25) LENGTH=2 ATTRB=UNPROT JUSTIFY=RIGHT: Expiry Month (editable, 01-12)
 * - EXPYEAR POS=(15,30) LENGTH=4 ATTRB=UNPROT JUSTIFY=RIGHT: Expiry Year (editable, 4-digit)
 * - EXPDAY POS=(15,36) ATTRB=DRK,FSET,PROT: Day defaulting to '01' (hidden from user)
 * - INFOMSG POS=(20,25) LENGTH=40 COLOR=NEUTRAL: Info messages
 * - ERRMSG POS=(23,1) LENGTH=80 COLOR=RED BRT: Error messages
 * - FKEYS POS=(24,1): 'ENTER=Process F3=Exit' (function key legend)
 * - FKEYSC POS=(24,23) ATTRB=ASKIP,DRK: 'F5=Save F12=Cancel' (conditional function keys)
 * 
 * COBOL Data Structures (from CVACT02Y.cpy):
 * - CARD-NUM PIC X(16): 16-digit card number
 * - CARD-EMBOSSED-NAME PIC X(50): Name on card (max 50 characters)
 * - CARD-EXPIRAION-DATE PIC X(10): Expiration date (YYYY-MM-DD format in database)
 * - CARD-ACTIVE-STATUS PIC X(01): Active status ('Y' or 'N')
 * 
 * Validation Rules:
 * - Card Name: Required, max 50 characters, letters and spaces only
 * - Active Status: Must be 'Y' or 'N' (mapped from COBOL 88-level conditions)
 * - Expiry Month: Required, 01-12 range, 2-digit format with right justification
 * - Expiry Year: Required, 4-digit format, current year or future, right justification
 * - Combined Expiration: Must not be expired (past current date)
 * 
 * Function Key Mappings (from BMS FKEYS fields):
 * - ENTER: Process/Submit form → formik.handleSubmit
 * - F3: Exit → Navigate back to card list
 * - F5: Save/Confirm changes → Alternative submit trigger
 * - F12: Cancel → Reset form and navigate back with confirmation if dirty
 * 
 * REST API Integration:
 * - GET /api/cards/{cardNumber}: Fetch card details for editing
 * - PUT /api/cards/{cardNumber}: Update card with validated data
 * 
 * React Transformation Strategy:
 * - Formik for form state management (replaces COBOL WORKING-STORAGE section)
 * - Yup for validation schema (replaces COBOL PIC clause validation)
 * - Material-UI components for modern form UI (replaces BMS DFHMDF fields)
 * - React Router useParams for card number from URL (replaces CICS COMMAREA)
 * - Keyboard event handlers for F3/F5/F12/Enter keys (replaces CICS AID handling)
 * - Error/success message state (replaces BMS ERRMSG/INFOMSG fields)
 * 
 * Migration Context:
 * This component maintains functional equivalence with COBOL program COCRDUPC.cbl
 * while modernizing to React + Material-UI architecture as specified in Agent Action 
 * Plan sections 0.1, 0.3, 0.6, and 0.10. All business logic and validation rules 
 * are preserved exactly from the original COBOL implementation.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useFormik } from 'formik';
import * as yup from 'yup';
import {
  Box,
  Container,
  TextField,
  Button,
  FormControlLabel,
  Switch,
  Alert,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Typography,
  Grid,
  Paper
} from '@mui/material';
import { updateCard, getCardByNumber } from '../../services/cardService.js';
import Header from '../common/Header.jsx';
import Footer from '../common/Footer.jsx';
import { useAuth } from '../../context/AuthContext.js';
import { 
  CARD_STATUS, 
  ERROR_REQUIRED, 
  ERROR_INVALID_FORMAT, 
  DATE_FORMAT_API, 
  MSG_SERVER_ERROR 
} from '../../utils/constants.js';

/**
 * CardUpdateComponent - Main Component Function
 * 
 * COBOL Equivalent: COCRDUPC.cbl PROCEDURE DIVISION
 * Replaces pseudo-conversational CICS transaction processing with stateless React component
 * 
 * @returns {JSX.Element} Card update form component
 */
const CardUpdateComponent = () => {
  // Extract cardNumber from URL route parameters
  // Replaces COBOL: EXEC CICS RECEIVE MAP('CCRDUPA') MAPSET('COCRDUP') INTO(CCRDUPAI)
  const { cardNumber } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();

  // Component state management
  // Replaces COBOL WORKING-STORAGE SECTION variables
  const [loading, setLoading] = useState(false);
  const [fetchingCard, setFetchingCard] = useState(true);
  const [errorMessage, setErrorMessage] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cardData, setCardData] = useState(null);

  // Ref for first input field focus management
  const firstInputRef = useRef(null);

  /**
   * Validation Schema using Yup
   * 
   * COBOL Equivalent: Field validation from CVACT02Y.cpy PIC clauses
   * - CARD-EMBOSSED-NAME PIC X(50): Max 50 characters
   * - CARD-ACTIVE-STATUS PIC X(01): 'Y' or 'N' values
   * - Expiry month: 01-12 range validation
   * - Expiry year: 4-digit current or future year validation
   * - Combined expiration date validation: Must not be expired
   */
  const validationSchema = yup.object({
    cardName: yup
      .string()
      .required('Cardholder name is required')
      .max(50, 'Name must be 50 characters or less')
      .matches(/^[A-Za-z\s]+$/, 'Name must contain only letters and spaces')
      .trim(),
    activeStatus: yup
      .string()
      .required('Active status is required')
      .oneOf(['Y', 'N'], 'Status must be Y (Active) or N (Inactive)'),
    expiryMonth: yup
      .number()
      .required('Expiry month is required')
      .min(1, 'Month must be between 01 and 12')
      .max(12, 'Month must be between 01 and 12')
      .integer('Month must be a valid number'),
    expiryYear: yup
      .number()
      .required('Expiry year is required')
      .min(new Date().getFullYear(), 'Year must be current year or future')
      .max(2099, 'Year must be a valid 4-digit year')
      .integer('Year must be a valid number')
      .test('not-expired', 'Card expiration date must be in the future', function(year) {
        const { expiryMonth } = this.parent;
        if (!year || !expiryMonth) {
          return true; // Let required validation handle empty values
        }

        const currentDate = new Date();
        const currentYear = currentDate.getFullYear();
        const currentMonth = currentDate.getMonth() + 1;

        // Card expires at end of expiration month, so equal month/year is still valid
        if (year < currentYear || (year === currentYear && expiryMonth < currentMonth)) {
          return false;
        }

        return true;
      })
  });

  /**
   * Formik form management
   * 
   * COBOL Equivalent: WORKING-STORAGE SECTION form field variables
   * Replaces COBOL form handling with Formik state management
   */
  const formik = useFormik({
    initialValues: {
      cardName: '',
      activeStatus: CARD_STATUS.ACTIVE,
      expiryMonth: '',
      expiryYear: ''
    },
    validationSchema: validationSchema,
    validateOnChange: true,
    validateOnBlur: true,
    onSubmit: async (values) => {
      // Show confirmation dialog before submitting
      setConfirmDialogOpen(true);
    }
  });

  /**
   * Fetch card details on component mount
   * 
   * COBOL Equivalent: COCRDUPC.cbl PROCEDURE DIVISION card data retrieval
   * Replaces: EXEC CICS READ DATASET(CARDDAT) INTO(CARD-RECORD) RIDFLD(WS-CARD-RID)
   */
  useEffect(() => {
    const fetchCardDetails = async () => {
      if (!cardNumber) {
        setErrorMessage('Card number is required');
        setFetchingCard(false);
        return;
      }

      try {
        setFetchingCard(true);
        setErrorMessage('');

        const card = await getCardByNumber(cardNumber);
        
        setCardData(card);

        // Parse expiration date (format: MM/YYYY or YYYY-MM-DD)
        let month = '';
        let year = '';

        if (card.expirationDate) {
          if (card.expirationDate.includes('/')) {
            // MM/YYYY format
            const parts = card.expirationDate.split('/');
            month = parts[0];
            year = parts[1];
          } else if (card.expirationDate.includes('-')) {
            // YYYY-MM-DD format
            const parts = card.expirationDate.split('-');
            year = parts[0];
            month = parts[1];
          }
        }

        // Set form initial values with fetched data
        formik.setValues({
          cardName: card.cardholderName || '',
          activeStatus: card.activeStatus || CARD_STATUS.ACTIVE,
          expiryMonth: month || '',
          expiryYear: year || ''
        });

      } catch (error) {
        setErrorMessage(error.message || 'Failed to retrieve card details');
      } finally {
        setFetchingCard(false);
      }
    };

    fetchCardDetails();
  }, [cardNumber]);

  /**
   * Handle confirmed form submission
   * 
   * COBOL Equivalent: COCRDUPC.cbl card update logic
   * Replaces: EXEC CICS REWRITE DATASET(CARDDAT) FROM(CARD-RECORD)
   */
  const handleConfirmedSubmit = async () => {
    setConfirmDialogOpen(false);
    setLoading(true);
    setErrorMessage('');
    setSuccessMessage('');

    try {
      const values = formik.values;

      // Build expiration date in YYYY-MM-DD format for API
      // EXPDAY is hidden field defaulting to '01' (first day of month)
      const expirationDate = `${values.expiryYear}-${String(values.expiryMonth).padStart(2, '0')}-01`;

      // Prepare update payload
      const updatePayload = {
        cardholderName: values.cardName.trim(),
        activeStatus: values.activeStatus,
        expirationDate: expirationDate
      };

      // Call API to update card
      await updateCard(cardNumber, updatePayload);

      // Display success message matching BMS INFOMSG field
      setSuccessMessage('Card updated successfully');

      // Navigate back to card list after 2-second delay
      setTimeout(() => {
        navigate('/cards');
      }, 2000);

    } catch (error) {
      // Display error message matching BMS ERRMSG field (COLOR=RED)
      setErrorMessage(error.message || MSG_SERVER_ERROR);
      
      // Focus on first field with error
      if (firstInputRef.current) {
        firstInputRef.current.focus();
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Exit action (F3 key)
   * 
   * COBOL Equivalent: EXEC CICS XCTL PROGRAM('COMEN01C') (return to main menu)
   * Replaces function key processing and navigation
   */
  const handleExit = useCallback(() => {
    navigate('/cards');
  }, [navigate]);

  /**
   * Handle Cancel action (F12 key)
   * 
   * COBOL Equivalent: EXEC CICS RETURN TRANSID (cancel without saving)
   * Checks for unsaved changes before canceling
   */
  const handleCancel = useCallback(() => {
    if (formik.dirty) {
      setCancelDialogOpen(true);
    } else {
      navigate('/cards');
    }
  }, [formik.dirty, navigate]);

  /**
   * Handle confirmed cancel action
   */
  const handleConfirmedCancel = () => {
    setCancelDialogOpen(false);
    formik.resetForm();
    navigate('/cards');
  };

  /**
   * Keyboard event handler for function keys
   * 
   * COBOL Equivalent: EXEC CICS HANDLE AID processing
   * Maps CICS AID keys to React keyboard events:
   * - ENTER key → Process/Submit form
   * - F3 key → Exit
   * - F5 key → Save (alternative submit)
   * - F12 key → Cancel
   */
  useEffect(() => {
    const handleKeyDown = (event) => {
      // F3 key - Exit
      if (event.key === 'F3') {
        event.preventDefault();
        handleExit();
      }
      // F5 key - Save (same as submit)
      else if (event.key === 'F5') {
        event.preventDefault();
        formik.handleSubmit();
      }
      // F12 key - Cancel
      else if (event.key === 'F12') {
        event.preventDefault();
        handleCancel();
      }
      // Enter key - Submit form (if not in textarea)
      else if (event.key === 'Enter' && event.target.tagName !== 'TEXTAREA') {
        event.preventDefault();
        formik.handleSubmit();
      }
    };

    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [handleExit, handleCancel, formik]);

  // Render loading state while fetching card details
  if (fetchingCard) {
    return (
      <Box>
        <Header 
          transactionCode="CCUP" 
          programName="COCRDUPC"
          title1="AWS Mainframe Modernization"
          title2="CardDemo"
        />
        <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
          <Paper elevation={3} sx={{ p: 4, textAlign: 'center' }}>
            <CircularProgress />
            <Typography variant="body1" sx={{ mt: 2 }}>
              Loading card details...
            </Typography>
          </Paper>
        </Container>
        <Footer 
          primaryKeys="ENTER=Process F3=Exit"
          secondaryKeys="F5=Save F12=Cancel"
        />
      </Box>
    );
  }

  return (
    <Box>
      {/* Header Component - BMS lines 1-3 */}
      <Header 
        transactionCode="CCUP" 
        programName="COCRDUPC"
        title1="AWS Mainframe Modernization"
        title2="CardDemo"
      />

      <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
        <Paper elevation={3} sx={{ p: 4 }}>
          {/* Screen Title - BMS POS=(4,30) */}
          <Typography variant="h5" align="center" gutterBottom sx={{ mb: 4 }}>
            Update Credit Card Details
          </Typography>

          {/* Success Message - BMS INFOMSG POS=(20,25) COLOR=NEUTRAL */}
          {successMessage && (
            <Alert severity="success" sx={{ mb: 3 }} onClose={() => setSuccessMessage('')}>
              {successMessage}
            </Alert>
          )}

          {/* Error Message - BMS ERRMSG POS=(23,1) COLOR=RED BRT */}
          {errorMessage && (
            <Alert severity="error" sx={{ mb: 3 }} onClose={() => setErrorMessage('')}>
              {errorMessage}
            </Alert>
          )}

          {/* Form Content */}
          <form onSubmit={formik.handleSubmit}>
            <Grid container spacing={3}>
              {/* Account Number - BMS ACCTSID POS=(7,45) ATTRB=PROT (Protected, display only) */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  label="Account Number"
                  value={cardData?.accountId || ''}
                  disabled
                  variant="outlined"
                  InputProps={{
                    readOnly: true,
                  }}
                  helperText="Account number is read-only"
                />
              </Grid>

              {/* Card Number - BMS CARDSID POS=(8,45) (Display only in update context) */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  label="Card Number"
                  value={cardNumber || ''}
                  disabled
                  variant="outlined"
                  InputProps={{
                    readOnly: true,
                  }}
                  helperText="Card number is read-only"
                />
              </Grid>

              {/* Cardholder Name - BMS CRDNAME POS=(11,25) LENGTH=50 ATTRB=UNPROT */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  label="Name on Card *"
                  name="cardName"
                  inputRef={firstInputRef}
                  value={formik.values.cardName}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.cardName && Boolean(formik.errors.cardName)}
                  helperText={formik.touched.cardName && formik.errors.cardName}
                  variant="outlined"
                  inputProps={{
                    maxLength: 50
                  }}
                  disabled={loading}
                />
              </Grid>

              {/* Card Active Status - BMS CRDSTCD POS=(13,25) LENGTH=1 ATTRB=UNPROT */}
              <Grid item xs={12}>
                <FormControlLabel
                  control={
                    <Switch
                      name="activeStatus"
                      checked={formik.values.activeStatus === CARD_STATUS.ACTIVE}
                      onChange={(event) => {
                        formik.setFieldValue(
                          'activeStatus',
                          event.target.checked ? CARD_STATUS.ACTIVE : CARD_STATUS.INACTIVE
                        );
                      }}
                      disabled={loading}
                      color="primary"
                    />
                  }
                  label={
                    <Typography>
                      Card Active (Y/N): <strong>{formik.values.activeStatus}</strong>
                    </Typography>
                  }
                />
                {formik.touched.activeStatus && formik.errors.activeStatus && (
                  <Typography color="error" variant="caption" display="block">
                    {formik.errors.activeStatus}
                  </Typography>
                )}
              </Grid>

              {/* Expiration Date Fields - BMS EXPMON/EXPYEAR POS=(15,25) and POS=(15,30) */}
              <Grid item xs={12}>
                <Typography variant="subtitle1" gutterBottom>
                  Expiry Date *
                </Typography>
                <Grid container spacing={2} alignItems="center">
                  {/* Expiry Month - BMS EXPMON LENGTH=2 JUSTIFY=RIGHT */}
                  <Grid item xs={5}>
                    <TextField
                      fullWidth
                      label="Month (MM)"
                      name="expiryMonth"
                      type="number"
                      value={formik.values.expiryMonth}
                      onChange={formik.handleChange}
                      onBlur={formik.handleBlur}
                      error={formik.touched.expiryMonth && Boolean(formik.errors.expiryMonth)}
                      helperText={formik.touched.expiryMonth && formik.errors.expiryMonth}
                      variant="outlined"
                      inputProps={{
                        min: 1,
                        max: 12,
                        maxLength: 2,
                        pattern: '[0-9]*',
                        style: { textAlign: 'right' } // JUSTIFY=RIGHT
                      }}
                      disabled={loading}
                      placeholder="01"
                    />
                  </Grid>

                  {/* Separator - BMS POS=(15,28) '/' */}
                  <Grid item xs={1}>
                    <Typography variant="h5" align="center">
                      /
                    </Typography>
                  </Grid>

                  {/* Expiry Year - BMS EXPYEAR LENGTH=4 JUSTIFY=RIGHT */}
                  <Grid item xs={6}>
                    <TextField
                      fullWidth
                      label="Year (YYYY)"
                      name="expiryYear"
                      type="number"
                      value={formik.values.expiryYear}
                      onChange={formik.handleChange}
                      onBlur={formik.handleBlur}
                      error={formik.touched.expiryYear && Boolean(formik.errors.expiryYear)}
                      helperText={formik.touched.expiryYear && formik.errors.expiryYear}
                      variant="outlined"
                      inputProps={{
                        min: new Date().getFullYear(),
                        max: 2099,
                        maxLength: 4,
                        pattern: '[0-9]*',
                        style: { textAlign: 'right' } // JUSTIFY=RIGHT
                      }}
                      disabled={loading}
                      placeholder={String(new Date().getFullYear())}
                    />
                  </Grid>
                </Grid>
                <Typography variant="caption" color="textSecondary" sx={{ mt: 1, display: 'block' }}>
                  Note: Day of month defaults to 01 (first day of expiry month)
                </Typography>
              </Grid>

              {/* Action Buttons */}
              <Grid item xs={12}>
                <Grid container spacing={2} justifyContent="flex-end">
                  {/* Exit Button - F3 Key */}
                  <Grid item>
                    <Button
                      variant="outlined"
                      color="secondary"
                      onClick={handleExit}
                      disabled={loading}
                    >
                      Exit (F3)
                    </Button>
                  </Grid>

                  {/* Cancel Button - F12 Key */}
                  <Grid item>
                    <Button
                      variant="outlined"
                      color="warning"
                      onClick={handleCancel}
                      disabled={loading}
                    >
                      Cancel (F12)
                    </Button>
                  </Grid>

                  {/* Submit/Process Button - ENTER Key */}
                  <Grid item>
                    <Button
                      type="submit"
                      variant="contained"
                      color="primary"
                      disabled={loading || !formik.isValid}
                      startIcon={loading && <CircularProgress size={20} />}
                    >
                      {loading ? 'Processing...' : 'Save Changes (ENTER)'}
                    </Button>
                  </Grid>
                </Grid>
              </Grid>
            </Grid>
          </form>
        </Paper>
      </Container>

      {/* Confirmation Dialog for Save */}
      <Dialog
        open={confirmDialogOpen}
        onClose={() => setConfirmDialogOpen(false)}
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
      >
        <DialogTitle id="confirm-dialog-title">
          Confirm Card Update
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-dialog-description">
            Are you sure you want to save changes to card {cardNumber}?
          </DialogContentText>
          <Box sx={{ mt: 2 }}>
            <Typography variant="body2">
              <strong>Cardholder Name:</strong> {formik.values.cardName}
            </Typography>
            <Typography variant="body2">
              <strong>Active Status:</strong> {formik.values.activeStatus === CARD_STATUS.ACTIVE ? 'Active (Y)' : 'Inactive (N)'}
            </Typography>
            <Typography variant="body2">
              <strong>Expiration Date:</strong> {String(formik.values.expiryMonth).padStart(2, '0')}/{formik.values.expiryYear}
            </Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmDialogOpen(false)} color="secondary">
            No, Go Back
          </Button>
          <Button onClick={handleConfirmedSubmit} color="primary" variant="contained" autoFocus>
            Yes, Save Changes
          </Button>
        </DialogActions>
      </Dialog>

      {/* Confirmation Dialog for Cancel */}
      <Dialog
        open={cancelDialogOpen}
        onClose={() => setCancelDialogOpen(false)}
        aria-labelledby="cancel-dialog-title"
        aria-describedby="cancel-dialog-description"
      >
        <DialogTitle id="cancel-dialog-title">
          Unsaved Changes
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="cancel-dialog-description">
            You have unsaved changes. Are you sure you want to discard them and go back?
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCancelDialogOpen(false)} color="primary">
            No, Keep Editing
          </Button>
          <Button onClick={handleConfirmedCancel} color="warning" variant="contained">
            Yes, Discard Changes
          </Button>
        </DialogActions>
      </Dialog>

      {/* Footer Component - BMS line 24 with function keys */}
      <Footer 
        primaryKeys="ENTER=Process F3=Exit"
        secondaryKeys="F5=Save F12=Cancel"
      />
    </Box>
  );
};

// PropTypes for type checking
CardUpdateComponent.propTypes = {
  // No props required - uses URL params
};

// Export as default as per schema exports requirement
export default CardUpdateComponent;
