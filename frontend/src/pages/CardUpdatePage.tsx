/**
 * CardUpdatePage Component
 * 
 * Converted from BMS map: COCRDUP.bms (Card Update Screen)
 * Converted from COBOL program: COCRDUPC.cbl (Card Update Program)
 * 
 * Purpose:
 * Provides a page for updating existing credit card information, including
 * cardholder name, expiration date, card status, and activation date.
 * 
 * COBOL Program Flow (COCRDUPC.cbl):
 * 1. PROCESS-ENTER-KEY: Validate user input and determine operation
 * 2. VALIDATE-INPUTS: Validate all input fields per business rules
 * 3. READ-CARD-RECORD: Retrieve card from CARDFILE VSAM
 * 4. UPDATE-CARD-RECORD: EXEC CICS REWRITE FILE('CARDDAT') operation
 * 5. SEND-MAP-DATAONLY: Redisplay screen with confirmation message
 * 
 * BMS Screen Layout (COCRDUP.bms 24x80):
 * - Line 1: Transaction name, title, date
 * - Line 2: Program name, subtitle, time
 * - Line 4: "Update Credit Card Details" heading
 * - Line 7: Account Number (ACCTSID - 11 chars, PROT/read-only)
 * - Line 8: Card Number (CARDSID - 16 chars, UNPROT/editable)
 * - Line 11: Name on card (CRDNAME - 50 chars, UNPROT)
 * - Line 13: Card Active Y/N (CRDSTCD - 1 char, UNPROT)
 * - Line 15: Expiry Date (EXPMON/EXPYEAR - MM/YYYY format)
 * - Line 20: Info message (INFOMSG - 40 chars)
 * - Line 23: Error message (ERRMSG - 80 chars, RED)
 * - Line 24: Function keys (ENTER=Process F3=Exit F5=Save F12=Cancel)
 * 
 * Modern React Implementation:
 * - Material-UI components replace BMS 3270 terminal screen
 * - React Router for navigation (replaces CICS XCTL/RETURN)
 * - Formik form state management (replaces BMS map I/O areas)
 * - REST API calls (replaces VSAM file I/O)
 * - Responsive layout (adapts to mobile/tablet/desktop)
 * - PCI-compliant card number masking (shows only last 4 digits)
 * 
 * Key Conversions:
 * - COBOL EXEC CICS READ FILE('CARDDAT') → cardService.getCardById()
 * - COBOL EXEC CICS REWRITE FILE('CARDDAT') → cardService.updateCard()
 * - COBOL SEND MAP(CCRDUPA) DATAONLY → React state updates
 * - COBOL RECEIVE MAP(CCRDUPA) → Formik form submission
 * - COBOL CARD-RECORD (CVACT02Y.cpy) → Card TypeScript interface
 * - BMS field validation → Yup validation schema
 * 
 * Security:
 * - Requires authentication via useAuth hook (replaces RACF checks)
 * - Card number masked for PCI DSS compliance
 * - JWT token sent with API requests for authorization
 * 
 * @author Blitzy Platform - COBOL to React Migration
 * @version 1.0.0
 * @license Apache-2.0
 */

import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box,
  Container,
  Typography,
  Paper,
  Alert
} from '@mui/material';
import CardForm, { CardFormData } from '../components/forms/CardForm';
import { Card } from '../types/card';
import * as cardService from '../services/cardService';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/common/LoadingSpinner';
import ErrorMessage from '../components/common/ErrorMessage';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import { formatDate } from '../utils/dateFormatter';

/**
 * CardUpdatePage Component
 * 
 * Page component for updating credit card information.
 * Fetches card data by ID from URL parameter, displays form with current values,
 * and handles update submission.
 * 
 * URL Route: /cards/:cardNum/update
 * URL Parameter: cardNum - 16-character card number (can be masked or full)
 * 
 * Component States:
 * - loading: true while fetching card data (shows LoadingSpinner)
 * - error: Error message if fetch or update fails (shows ErrorMessage)
 * - card: Fetched card data used to populate form initialValues
 * - successMessage: Confirmation message after successful update
 * 
 * User Flow:
 * 1. Page loads with loading spinner
 * 2. Card data fetched from backend API
 * 3. Form populated with current card data (card number masked)
 * 4. User edits fields (embossed name, expiration date, status)
 * 5. User clicks Save button
 * 6. Validation runs (Yup schema)
 * 7. If valid, API call to update card
 * 8. Success: Navigate to card list with success message
 * 9. Error: Display error message, keep form open
 * 
 * COBOL Equivalent Flow (COCRDUPC.cbl):
 * - MAIN-PARA → CardUpdatePage component mount
 * - PROCESS-ENTER-KEY → Form onSubmit handler
 * - VALIDATE-INPUTS → Yup validation schema
 * - READ-CARD-RECORD → useEffect fetch with cardService.getCardById()
 * - UPDATE-CARD-RECORD → handleSubmit with cardService.updateCard()
 * - SEND-MAP-DATAONLY → Navigate with success message
 */
const CardUpdatePage: React.FC = () => {
  // React Router hooks
  const navigate = useNavigate();
  const { cardNum } = useParams<{ cardNum: string }>();

  // Authentication hook (replaces COBOL RACF security checks)
  const { isAuthenticated, user } = useAuth();

  // Component state
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const [card, setCard] = useState<Card | null>(null);
  const [successMessage, setSuccessMessage] = useState<string>('');

  /**
   * Fetch card data on component mount
   * 
   * Converted from COBOL: READ-CARD-RECORD paragraph
   * Original: EXEC CICS READ FILE('CARDDAT') RIDFLD(CARD-NUM) INTO(CARD-RECORD)
   * 
   * Error Handling:
   * - COBOL file-status 23 (NOTFND) → 404 error "Card not found"
   * - COBOL RESP(NOTAUTH) → 403 error "Not authorized"
   * - COBOL RESP(IOERR) → 500 error "System error"
   */
  useEffect(() => {
    // Verify user is authenticated (replaces COBOL RACF authentication check)
    if (!isAuthenticated) {
      setError('You must be logged in to update cards');
      setLoading(false);
      navigate('/signin');
      return;
    }

    // Validate card number parameter exists
    if (!cardNum) {
      setError('Card number is required');
      setLoading(false);
      return;
    }

    // Fetch card data from backend
    const fetchCard = async () => {
      try {
        setLoading(true);
        setError('');

        // Call cardService.getCardById() - replaces EXEC CICS READ
        const cardData = await cardService.getCardById(cardNum);
        setCard(cardData);
      } catch (err: any) {
        // Map COBOL error codes to user-friendly messages
        // COBOL: IF FILE-STATUS = '23' → "Card not found"
        // COBOL: IF RESP = DFHRESP(NOTFND) → "Card not found"
        // COBOL: IF RESP = DFHRESP(NOTAUTH) → "Not authorized"
        
        if (err.response?.status === 404) {
          setError(`Card number ${cardNum} not found`);
        } else if (err.response?.status === 403) {
          setError('You are not authorized to view this card');
        } else {
          setError(
            err.response?.data?.message ||
            err.message ||
            'Failed to retrieve card information. Please try again.'
          );
        }
        console.error('[CardUpdatePage] Error fetching card:', err);
      } finally {
        setLoading(false);
      }
    };

    fetchCard();
  }, [cardNum, isAuthenticated, navigate]);

  /**
   * Handle form submission
   * 
   * Converted from COBOL: UPDATE-CARD-RECORD paragraph
   * Original: EXEC CICS REWRITE FILE('CARDDAT') FROM(CARD-RECORD)
   * 
   * Validation Flow:
   * 1. Yup schema validation runs automatically in CardForm
   * 2. If validation passes, this handler is called
   * 3. Call cardService.updateCard() with validated data
   * 4. On success: Navigate back to card list with success message
   * 5. On error: Display error message, stay on form
   * 
   * COBOL Validation (VALIDATE-INPUTS paragraph):
   * - WS-EDIT-CARD-FLAG: Tracks validation state
   * - FLG-CARDFILTER-ISVALID: Set to '1' when all validations pass
   * - Field-level validations for each input
   * 
   * @param values Validated form data from Formik
   */
  const handleSubmit = async (values: CardFormData): Promise<void> => {
    try {
      setError('');
      setSuccessMessage('');

      // Validate card number exists
      if (!cardNum) {
        setError('Card number is required for update');
        return;
      }

      // Prepare update payload
      // Only include fields that can be updated (exclude primary key and system fields)
      const updateData: Partial<Card> = {
        cardEmbossedName: values.cardEmbossedName,
        cardExpirationDate: values.cardExpirationDate,
        cardStatus: values.cardStatus,
      };

      // Call cardService.updateCard() - replaces EXEC CICS REWRITE
      await cardService.updateCard(cardNum, updateData);

      // Display success message (replaces COBOL INFOMSG field)
      // COBOL: MOVE 'Card updated successfully' TO INFOMSG
      setSuccessMessage('Card updated successfully');

      // Navigate back to card list after short delay
      // Replaces COBOL: EXEC CICS RETURN TRANSID('CCRD')
      setTimeout(() => {
        navigate('/cards', { 
          state: { 
            message: `Card ${cardNum} updated successfully` 
          } 
        });
      }, 1500);

    } catch (err: any) {
      // Map COBOL error conditions to error messages
      // COBOL: IF FILE-STATUS = '23' → "Card not found"
      // COBOL: Optimistic locking conflict → "Card was modified by another user"
      // COBOL: IF WS-EDIT-CARD-FLAG = '0' → Validation error message

      if (err.response?.status === 404) {
        setError('Card not found. It may have been deleted.');
      } else if (err.response?.status === 409) {
        setError('Card was modified by another user. Please refresh and try again.');
      } else if (err.response?.status === 400) {
        setError(
          err.response?.data?.message ||
          'Validation failed. Please check your input and try again.'
        );
      } else {
        setError(
          err.response?.data?.message ||
          err.message ||
          'Failed to update card. Please try again.'
        );
      }
      console.error('[CardUpdatePage] Error updating card:', err);
    }
  };

  /**
   * Handle form cancellation
   * 
   * Converted from COBOL: F12=Cancel or F3=Exit key handling
   * Original: EXEC CICS RETURN TRANSID('CCRD')
   * 
   * Navigates back to card list without saving changes.
   */
  const handleCancel = (): void => {
    // Navigate back to card list (replaces CICS RETURN)
    navigate('/cards');
  };

  /**
   * Render loading state
   * 
   * Replaces COBOL: Initial screen display before card data loaded
   * Shows spinner while EXEC CICS READ operation in progress
   */
  if (loading) {
    return (
      <Box>
        <Header />
        <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
          <LoadingSpinner message="Loading card information..." />
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Render error state
   * 
   * Replaces COBOL: ERRMSG field (line 154-157, RED color, 80 chars)
   * Displays error if card fetch failed
   */
  if (error && !card) {
    return (
      <Box>
        <Header />
        <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
          <Paper elevation={3} sx={{ p: 4 }}>
            <Typography variant="h4" component="h1" gutterBottom>
              Update Card
            </Typography>
            <ErrorMessage message={error} />
            <Box sx={{ mt: 2 }}>
              <Typography
                variant="body2"
                color="textSecondary"
                sx={{ cursor: 'pointer', textDecoration: 'underline' }}
                onClick={handleCancel}
              >
                Return to Card List
              </Typography>
            </Box>
          </Paper>
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Main render: Card update form
   * 
   * Replaces COBOL BMS screen: CCRDUPA map (24x80 terminal screen)
   * 
   * Screen Structure:
   * - Header: Transaction info, title, date/time (BMS lines 1-2)
   * - Page title: "Update Card" (BMS line 4: "Update Credit Card Details")
   * - CardForm: Input fields for card data (BMS lines 7-15)
   * - Success message: Confirmation after save (BMS INFOMSG line 20)
   * - Error message: Validation or API errors (BMS ERRMSG line 23)
   * - Footer: Application info and navigation
   */
  return (
    <Box>
      {/* Application header with navigation */}
      <Header />

      {/* Main content container */}
      <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
        <Paper elevation={3} sx={{ p: 4 }}>
          {/* Page heading - replaces BMS line 4 */}
          <Typography 
            variant="h4" 
            component="h1" 
            gutterBottom
            sx={{ mb: 3, color: 'primary.main' }}
          >
            Update Card
          </Typography>

          {/* Success message display - replaces BMS INFOMSG field (line 149-153) */}
          {successMessage && (
            <Alert severity="success" sx={{ mb: 3 }}>
              {successMessage}
            </Alert>
          )}

          {/* Error message display - replaces BMS ERRMSG field (line 154-157, RED) */}
          {error && (
            <ErrorMessage message={error} />
          )}

          {/* Card update form - replaces BMS CCRDUPA map input fields */}
          {card && (
            <CardForm
              // Pre-fill form with existing card data
              // Converts Card interface to CardFormData interface
              initialValues={{
                cardNum: card.cardNum, // Masked format: ************1234
                cardAcctId: card.cardAcctId, // Read-only field (BMS PROT attribute)
                cardEmbossedName: card.cardEmbossedName, // BMS CRDNAME field
                cardStatus: card.cardStatus, // BMS CRDSTCD field (Y/N/B/E)
                cardExpirationDate: card.cardExpirationDate, // BMS EXPMON/EXPYEAR combined
              }}
              // Form submission handler - replaces COBOL UPDATE-CARD-RECORD
              onSubmit={handleSubmit}
              // Form cancellation handler - replaces F12 or F3 key
              onCancel={handleCancel}
              // Update mode shows masked card number (PCI compliance)
              mode="update"
            />
          )}

          {/* Audit trail information */}
          {card && (
            <Box sx={{ mt: 3, pt: 2, borderTop: 1, borderColor: 'divider' }}>
              <Typography variant="body2" color="textSecondary">
                <strong>Last Updated:</strong> {formatDate(new Date(card.updatedAt))} by {user?.userId || 'Unknown'}
              </Typography>
              <Typography variant="body2" color="textSecondary">
                <strong>Created:</strong> {formatDate(new Date(card.createdAt))}
              </Typography>
            </Box>
          )}
        </Paper>
      </Container>

      {/* Application footer */}
      <Footer />
    </Box>
  );
};

// Default export as specified in exports schema
export default CardUpdatePage;
