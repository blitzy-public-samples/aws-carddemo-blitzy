/**
 * CardDemo Card Update Component
 * 
 * React functional component implementing card update form with comprehensive
 * validation matching COBOL COCRDUPC.cbl business rules. Transforms BMS mapset
 * COCRDUP and COBOL program COCRDUPC.cbl to modern React form with Formik and
 * Yup validation framework.
 * 
 * COBOL Program Mapping:
 * Source: app/cbl/COCRDUPC.cbl - Credit card detail update processing
 * Source: app/bms/COCRDUP.bms - BMS card update screen definition
 * Target: React functional component with Material-UI and Formik
 * 
 * Business Logic Preservation:
 * - Card name validation: alphabetic characters and spaces only (COCRDUPC line 183-184)
 * - Card status validation: Y/N only (COCRDUPC line 195-196, 88-level FLG-YES-NO-VALID line 91)
 * - Expiry month validation: 1-12 range (COCRDUPC line 92-95, 88-level VALID-MONTH)
 * - Expiry year validation: 1950-2099 range (COCRDUPC line 96-99, 88-level VALID-YEAR)
 * - Change detection before update (COCRDUPC line 187-188 NO-CHANGES-DETECTED)
 * - Transaction boundaries preserved via REST API atomicity
 * 
 * BMS Screen Transformation:
 * - ACCTSID (PROT) → Read-only TextField (Account Number)
 * - CARDSID (PROT for update screen) → Read-only TextField (Card Number)
 * - CRDNAME (UNPROT) → Editable Field (Name on card, max 50 chars)
 * - CRDSTCD (UNPROT) → Select dropdown (Card Active Y/N)
 * - EXPMON (UNPROT) → TextField (Month, 2 digits)
 * - EXPYEAR (UNPROT) → TextField (Year, 4 digits)
 * - INFOMSG (PROT) → Alert component for info messages
 * - ERRMSG (PROT) → Alert component for error messages
 * - FKEYS → Navigation buttons (F3=Exit, F5=Save, F12=Cancel)
 * 
 * Key Features:
 * - Formik form state management replacing COBOL WORKING-STORAGE fields
 * - Yup schema validation matching all COBOL validation paragraphs
 * - Read-only fields for account and card numbers (BMS PROT attribute)
 * - Editable fields with real-time validation feedback
 * - Change detection comparing initial vs current values
 * - Success/failure messaging matching COBOL WS-INFO-MSG and WS-RETURN-MSG
 * - Navigation matching BMS PF key functions
 * - Loading and saving states with visual feedback
 * 
 * @module components/card/CardUpdateComponent
 */

import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Formik, Form, Field } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Paper,
  TextField,
  Button,
  Typography,
  Grid,
  MenuItem,
  Alert,
  CircularProgress,
  Divider
} from '@mui/material';
import { ArrowBack, Save, Cancel } from '@mui/icons-material';
import cardService from '../../services/cardService';

/**
 * Yup Validation Schema
 * 
 * Maps COBOL COCRDUPC validation rules (lines 65-99, 173-200) to declarative
 * Yup schema. Preserves all business validation logic from COBOL paragraphs:
 * - EDIT-CARDNAME-FIELD
 * - EDIT-CARDSTATUS-FIELD
 * - EDIT-CARDEXPMON-FIELD
 * - EDIT-CARDEXPYEAR-FIELD
 * 
 * COBOL 88-Level Conditions Mapped:
 * - FLG-YES-NO-VALID (line 91): Values 'Y', 'N'
 * - VALID-MONTH (line 95): Values 1 THRU 12
 * - VALID-YEAR (line 99): Values 1950 THRU 2099
 * 
 * Error Messages Match COBOL WS-RETURN-MSG Values:
 * - WS-PROMPT-FOR-NAME (line 181-182): 'Card name not provided'
 * - WS-NAME-MUST-BE-ALPHA (line 183-184): 'Card name can only contain alphabets and spaces'
 * - CARD-STATUS-MUST-BE-YES-NO (line 195-196): 'Card Active Status must be Y or N'
 * - CARD-EXPIRY-MONTH-NOT-VALID (line 197-198): 'Card expiry month must be between 1 and 12'
 * - CARD-EXPIRY-YEAR-NOT-VALID (line 199-200): 'Invalid card expiry year'
 */
const validationSchema = Yup.object({
  embossedName: Yup.string()
    .required('Card name not provided')
    .max(50, 'Card name must be 50 characters or less')
    .matches(/^[A-Za-z\s]+$/, 'Card name can only contain alphabets and spaces'),
  cardStatus: Yup.string()
    .required('Card Active Status must be Y or N')
    .oneOf(['Y', 'N'], 'Card Active Status must be Y or N'),
  expiryMonth: Yup.number()
    .required('Card expiry month is required')
    .min(1, 'Card expiry month must be between 1 and 12')
    .max(12, 'Card expiry month must be between 1 and 12')
    .integer('Month must be a valid number'),
  expiryYear: Yup.number()
    .required('Card expiry year is required')
    .min(1950, 'Invalid card expiry year')
    .max(2099, 'Invalid card expiry year')
    .integer('Year must be a valid number')
});

/**
 * CardUpdateComponent
 * 
 * Main functional component implementing card update form functionality.
 * Replaces COBOL COCRDUPC.cbl procedural logic with modern React declarative
 * patterns while maintaining identical business behavior.
 * 
 * Component State Management:
 * - loading: Data fetch in progress indicator (EXEC CICS READ DATASET)
 * - saving: Update in progress indicator (EXEC CICS REWRITE DATASET)
 * - error: Error message display (COBOL WS-RETURN-MSG)
 * - infoMessage: Information message display (COBOL WS-INFO-MSG)
 * - originalCardData: Initial card state for change detection
 * - initialValues: Formik initial form values from fetched data
 * 
 * COBOL Procedure Division Mapping:
 * - MAIN-PARA → Component render with conditional loading/form display
 * - PROCESS-ENTER-KEY → handleSubmit function
 * - VALIDATE-INPUT-KEY-FIELDS → Yup validation schema
 * - READ-CARDDAT-FILE → useEffect with cardService.getCard()
 * - UPDATE-CARDDAT-FILE → handleSubmit with cardService.updateCard()
 * - SEND-CARDUPD-SCREEN → JSX return statement
 * 
 * @returns {JSX.Element} Card update form component
 */
const CardUpdateComponent = () => {
  // URL parameter extraction (card number from route /cards/:cardNumber/edit)
  // Maps to COBOL COMMAREA input field CARD-NUM-IN
  const { cardNumber } = useParams();
  
  // Navigation hook for programmatic routing
  // Replaces COBOL EXEC CICS XCTL and EXEC CICS RETURN
  const navigate = useNavigate();
  
  /**
   * Component State
   * 
   * Maps COBOL WORKING-STORAGE fields to React component state:
   * - loading → WS-CICS-PROCESSING-VARS status flags
   * - saving → WS-CICS-PROCESSING-VARS update in progress
   * - error → WS-RETURN-MSG display field
   * - infoMessage → WS-INFO-MSG display field
   * - originalCardData → Original CARDDAT record for comparison
   * - initialValues → COMMAREA input fields
   */
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const [originalCardData, setOriginalCardData] = useState(null);
  const [initialValues, setInitialValues] = useState({
    embossedName: '',
    cardStatus: 'Y',
    expiryMonth: '',
    expiryYear: '',
    accountId: '',
    cardNumber: ''
  });

  /**
   * Fetch Card Data Effect
   * 
   * Maps COBOL paragraph READ-CARDDAT-FILE which executes:
   * EXEC CICS READ DATASET('CARDDAT')
   *   RIDFLD(CARD-NUM)
   *   INTO(CARD-RECORD)
   *   RESP(WS-RESP-CD)
   * END-EXEC
   * 
   * Business Logic:
   * - Retrieves card details by card number on component mount
   * - Populates form fields with existing card data
   * - Handles card not found condition (NOTFND)
   * - Displays info message FOUND-CARDS-FOR-ACCOUNT (line 160-161)
   * - Transforms card status: 'A' (Active) → 'Y', 'I' (Inactive) → 'N'
   * - Parses expiry date into separate month and year fields
   */
  useEffect(() => {
    const fetchCardData = async () => {
      setLoading(true);
      setError('');
      // Initial info message matching COBOL FOUND-CARDS-FOR-ACCOUNT (line 160-161)
      setInfoMessage('Details of selected card shown above');
      
      try {
        // Execute card retrieval
        // Maps COBOL: EXEC CICS READ DATASET('CARDDAT')
        const card = await cardService.getCard(cardNumber);
        setOriginalCardData(card);
        
        // Parse expiration date from YYYY-MM-DD format
        // Maps COBOL date fields: CARD-EXPIRY-MONTH, CARD-EXPIRY-YEAR
        const expiryDate = new Date(card.expiryDate);
        const month = expiryDate.getMonth() + 1;
        const year = expiryDate.getFullYear();
        
        // Populate form initial values
        // Maps COBOL: MOVE statements to COMMAREA fields
        setInitialValues({
          embossedName: card.embossedName || card.cardholderName || '',
          // Transform card status: 'A' (Active) → 'Y', otherwise → 'N'
          // Maps COBOL: IF CARD-STATUS = 'A' THEN 'Y' ELSE 'N'
          cardStatus: card.cardStatus === 'A' ? 'Y' : 'N',
          expiryMonth: String(month).padStart(2, '0'),
          expiryYear: String(year),
          accountId: card.accountId,
          cardNumber: card.cardNumber
        });
        
        // Display prompt for changes matching COBOL PROMPT-FOR-CHANGES (line 164-165)
        setInfoMessage('Update card details presented above.');
      } catch (err) {
        // Handle card not found or access error
        // Maps COBOL: HANDLE CONDITION NOTFND
        setError(err.message || 'Card not found');
      } finally {
        setLoading(false);
      }
    };
    
    if (cardNumber) {
      fetchCardData();
    }
  }, [cardNumber]);

  /**
   * Form Submit Handler
   * 
   * Maps COBOL paragraph UPDATE-CARDDAT-FILE which executes:
   * 1. Change detection (NO-CHANGES-DETECTED validation)
   * 2. EXEC CICS READ DATASET('CARDDAT') UPDATE
   * 3. Update field modifications
   * 4. EXEC CICS REWRITE DATASET('CARDDAT')
   * 5. EXEC CICS SYNCPOINT (transaction commit)
   * 
   * COBOL Procedure Division Flow:
   * - PROCESS-ENTER-KEY (main entry point)
   * - VALIDATE-INPUT-KEY-FIELDS (handled by Yup schema)
   * - CHECK-FOR-CHANGES (change detection logic)
   * - UPDATE-CARDDAT-FILE (API call to persist changes)
   * - SEND-SUCCESS-MESSAGE or SEND-ERROR-MESSAGE
   * 
   * @param {Object} values - Form values from Formik
   * @param {Object} formikBag - Formik helper methods
   */
  const handleSubmit = async (values, { setSubmitting, setFieldError }) => {
    setSaving(true);
    setError('');
    // Display validation confirmation matching COBOL PROMPT-FOR-CONFIRMATION (line 166-167)
    setInfoMessage('Changes validated. Press F5 to save');
    
    try {
      // Change Detection Logic
      // Maps COBOL paragraph CHECK-FOR-CHANGES (lines 187-188)
      // IF original values = current values THEN NO-CHANGES-DETECTED
      const hasChanges = (
        values.embossedName !== initialValues.embossedName ||
        values.cardStatus !== initialValues.cardStatus ||
        values.expiryMonth !== initialValues.expiryMonth ||
        values.expiryYear !== initialValues.expiryYear
      );
      
      if (!hasChanges) {
        // Display error matching COBOL NO-CHANGES-DETECTED (line 187-188)
        setError('No change detected with respect to values fetched.');
        setSaving(false);
        return;
      }
      
      // Construct update payload
      // Maps COBOL: MOVE values to CARD-RECORD fields
      const updateData = {
        embossedName: values.embossedName,
        // Transform card status back: 'Y' → 'A' (Active), 'N' → 'I' (Inactive)
        cardStatus: values.cardStatus === 'Y' ? 'A' : 'I',
        // Construct expiry date from separate month and year fields
        // Maps COBOL: STRING CARD-EXPIRY-YEAR '-' CARD-EXPIRY-MONTH '-01'
        expiryDate: `${values.expiryYear}-${values.expiryMonth}-01`
      };
      
      // Execute update operation
      // Maps COBOL: EXEC CICS REWRITE DATASET('CARDDAT')
      await cardService.updateCard(cardNumber, updateData);
      
      // Display success message matching COBOL CONFIRM-UPDATE-SUCCESS (line 168-169)
      setInfoMessage('Changes committed to database');
      
      // Navigate back to card list after successful update
      // Maps COBOL: EXEC CICS RETURN TRANSID('CCLI')
      setTimeout(() => {
        navigate('/cards');
      }, 2000);
    } catch (err) {
      // Display error message matching COBOL INFORM-FAILURE (line 170-171)
      setError('Changes unsuccessful. Please try again');
      
      // Handle field-level validation errors from backend
      if (err.errors) {
        Object.keys(err.errors).forEach(key => {
          setFieldError(key, err.errors[key]);
        });
      }
    } finally {
      setSaving(false);
      setSubmitting(false);
    }
  };

  /**
   * Handle F3 Exit Button
   * 
   * Maps COBOL: IF EIBAID = DFHPF3 THEN EXEC CICS RETURN
   * Navigation to card list screen matching PF3 key behavior
   */
  const handleExit = () => {
    navigate('/cards');
  };

  /**
   * Handle F12 Cancel Button
   * 
   * Maps COBOL: IF EIBAID = DFHPF12 THEN EXEC CICS XCTL
   * Navigation to card detail view matching PF12 key behavior
   */
  const handleCancel = () => {
    navigate(`/cards/${cardNumber}`);
  };

  /**
   * JSX Return - Card Update Form Render
   * 
   * Maps BMS mapset COCRDUP screen layout to React component structure.
   * Preserves exact field positioning, attributes, and validation behavior.
   * 
   * BMS Field Mapping:
   * - TITLE01/TITLE02 → Typography component (screen title)
   * - ACCTSID (PROT) → TextField disabled (read-only account number)
   * - CARDSID (PROT for update) → TextField disabled (read-only card number)
   * - CRDNAME (UNPROT) → Field component (editable embossed name)
   * - CRDSTCD (UNPROT) → Field component with Select (editable status Y/N)
   * - EXPMON (UNPROT) → Field component (editable month)
   * - EXPYEAR (UNPROT) → Field component (editable year)
   * - INFOMSG → Alert severity="info" (information messages)
   * - ERRMSG → Alert severity="error" (error messages)
   * - FKEYS → Button components (F3=Exit, F5=Save, F12=Cancel)
   */
  return (
    <Box sx={{ p: 3 }}>
      {/* Screen Header - Maps BMS TITLE01 and TITLE02 fields */}
      <Typography variant="h4" gutterBottom>
        Update Credit Card Details
      </Typography>

      {/* Info Message Display - Maps BMS INFOMSG field (PROT attribute) */}
      {infoMessage && !error && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {infoMessage}
        </Alert>
      )}

      {/* Error Message Display - Maps BMS ERRMSG field (ASKIP, BRT, FSET, RED) */}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Loading Indicator - Replaces COBOL "Processing..." message */}
      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 3 }}>
          <CircularProgress />
        </Box>
      ) : (
        <Formik
          initialValues={initialValues}
          validationSchema={validationSchema}
          onSubmit={handleSubmit}
          enableReinitialize
        >
          {({ errors, touched, values, isSubmitting }) => (
            <Form>
              <Paper sx={{ p: 3, mb: 3 }}>
                <Grid container spacing={3}>
                  {/* Account Number Field - Maps BMS ACCTSID (FSET, IC, NORM, PROT) */}
                  {/* Read-only field per BMS PROT attribute */}
                  <Grid item xs={12} md={6}>
                    <TextField
                      label="Account Number"
                      value={values.accountId}
                      disabled
                      fullWidth
                      variant="filled"
                      helperText="Read-only field"
                    />
                  </Grid>
                  
                  {/* Card Number Field - Maps BMS CARDSID (FSET, NORM, UNPROT in list, PROT in update) */}
                  {/* Read-only in update context per business logic */}
                  <Grid item xs={12} md={6}>
                    <TextField
                      label="Card Number"
                      value={values.cardNumber}
                      disabled
                      fullWidth
                      variant="filled"
                      helperText="Read-only field"
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <Divider />
                  </Grid>

                  {/* Card Name Field - Maps BMS CRDNAME (UNPROT, HILIGHT=UNDERLINE) */}
                  {/* Editable field with validation per COBOL EDIT-CARDNAME-FIELD */}
                  <Grid item xs={12}>
                    <Field
                      as={TextField}
                      name="embossedName"
                      label="Name on card"
                      fullWidth
                      inputProps={{ maxLength: 50 }}
                      error={touched.embossedName && Boolean(errors.embossedName)}
                      helperText={touched.embossedName && errors.embossedName}
                      required
                      disabled={isSubmitting || saving}
                    />
                  </Grid>

                  {/* Card Status Field - Maps BMS CRDSTCD (UNPROT, HILIGHT=UNDERLINE) */}
                  {/* Dropdown with Y/N options per COBOL FLG-YES-NO-VALID (line 91) */}
                  <Grid item xs={12} md={6}>
                    <Field
                      as={TextField}
                      name="cardStatus"
                      label="Card Active Y/N"
                      select
                      fullWidth
                      error={touched.cardStatus && Boolean(errors.cardStatus)}
                      helperText={touched.cardStatus && errors.cardStatus}
                      required
                      disabled={isSubmitting || saving}
                    >
                      <MenuItem value="Y">Y (Active)</MenuItem>
                      <MenuItem value="N">N (Inactive)</MenuItem>
                    </Field>
                  </Grid>

                  <Grid item xs={12}>
                    <Divider />
                  </Grid>

                  {/* Expiry Date Section Header */}
                  <Grid item xs={12}>
                    <Typography variant="subtitle2" gutterBottom>
                      Expiry Date:
                    </Typography>
                  </Grid>
                  
                  {/* Expiry Month Field - Maps BMS EXPMON (UNPROT, HILIGHT=UNDERLINE, JUSTIFY=RIGHT) */}
                  {/* Validates 1-12 range per COBOL VALID-MONTH 88-level (line 95) */}
                  <Grid item xs={12} md={6}>
                    <Field
                      as={TextField}
                      name="expiryMonth"
                      label="Month (MM)"
                      fullWidth
                      inputProps={{ maxLength: 2 }}
                      placeholder="MM"
                      error={touched.expiryMonth && Boolean(errors.expiryMonth)}
                      helperText={touched.expiryMonth && errors.expiryMonth}
                      required
                      disabled={isSubmitting || saving}
                    />
                  </Grid>
                  
                  {/* Expiry Year Field - Maps BMS EXPYEAR (UNPROT, HILIGHT=UNDERLINE, JUSTIFY=RIGHT) */}
                  {/* Validates 1950-2099 range per COBOL VALID-YEAR 88-level (line 99) */}
                  <Grid item xs={12} md={6}>
                    <Field
                      as={TextField}
                      name="expiryYear"
                      label="Year (YYYY)"
                      fullWidth
                      inputProps={{ maxLength: 4 }}
                      placeholder="YYYY"
                      error={touched.expiryYear && Boolean(errors.expiryYear)}
                      helperText={touched.expiryYear && errors.expiryYear}
                      required
                      disabled={isSubmitting || saving}
                    />
                  </Grid>
                </Grid>
              </Paper>

              {/* Navigation Buttons - Maps BMS FKEYS and FKEYSC fields */}
              {/* F3=Exit, F5=Save, F12=Cancel per COBOL PF key handling */}
              <Box sx={{ display: 'flex', gap: 2 }}>
                {/* F3 Exit Button - Maps COBOL: IF EIBAID = DFHPF3 */}
                <Button
                  startIcon={<ArrowBack />}
                  onClick={handleExit}
                  disabled={saving || isSubmitting}
                  variant="outlined"
                >
                  F3: Exit
                </Button>
                
                {/* F5 Save Button (Submit) - Maps COBOL: IF EIBAID = DFHENTER */}
                <Button
                  type="submit"
                  variant="contained"
                  startIcon={<Save />}
                  disabled={saving || isSubmitting}
                  color="primary"
                >
                  {saving ? 'Saving...' : 'F5: Save'}
                </Button>
                
                {/* F12 Cancel Button - Maps COBOL: IF EIBAID = DFHPF12 */}
                <Button
                  variant="outlined"
                  startIcon={<Cancel />}
                  onClick={handleCancel}
                  disabled={saving || isSubmitting}
                >
                  F12: Cancel
                </Button>
              </Box>
            </Form>
          )}
        </Formik>
      )}
    </Box>
  );
};

export default CardUpdateComponent;

