/**
 * ConfirmDialog Component
 * 
 * Converted from BMS confirmation screen patterns:
 * - COUSR03.bms: Delete user confirmation (F5=Delete function key)
 * - COTRN02.bms: Transaction confirmation with Y/N field (line 279-286)
 * 
 * Original COBOL pattern:
 * BMS screen displayed data with inline confirmation field requiring Y/N input
 * User types 'Y' to confirm or 'N' to cancel, then presses Enter
 * 
 * Modern React pattern:
 * Modal dialog overlay with message and clickable buttons
 * User clicks button or uses keyboard shortcuts (Enter=confirm, Escape=cancel)
 * 
 * Purpose:
 * - Display confirmation dialogs for critical user actions
 * - Replace BMS confirmation screens with modern modal dialogs
 * - Prevent accidental destructive operations (delete, cancel)
 * - Confirm important actions (submit transaction, update account)
 * 
 * Per Agent Action Plan Section 0.4.19 and Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This component preserves BMS confirmation screen functionality while modernizing
 * UX with modal dialogs. No unnecessary features added beyond standard confirmation patterns.
 */

import React from 'react';
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Button
} from '@mui/material';

/**
 * ConfirmDialog component props
 * 
 * Maps BMS confirmation patterns to React props:
 * - BMS CONFIRM field (Y/N) → onConfirm/onCancel callbacks
 * - BMS message text → title and message props
 * - Function keys → confirmText/cancelText labels
 * - Color attributes → confirmColor for severity indication
 */
interface ConfirmDialogProps {
  /** Controls dialog visibility (controlled component) */
  open: boolean;
  
  /** Dialog title text (e.g., "Delete User?", "Confirm Transaction") */
  title: string;
  
  /** Confirmation message explaining the action */
  message: string;
  
  /** Confirm button label (default: "Confirm", can be "Delete", "Yes", "Submit") */
  confirmText?: string;
  
  /** Cancel button label (default: "Cancel", can be "No", "Go Back") */
  cancelText?: string;
  
  /** 
   * Confirm button color for severity indication:
   * - 'error' (red) for destructive operations like delete
   * - 'primary' (blue) for normal confirmations
   * - 'warning' (orange) for cautionary actions
   */
  confirmColor?: 'primary' | 'secondary' | 'error' | 'warning' | 'info' | 'success';
  
  /** Callback when user confirms action */
  onConfirm: () => void;
  
  /** Callback when user cancels or closes dialog */
  onCancel: () => void;
  
  /** If true, clicking outside dialog does not close it */
  disableBackdropClick?: boolean;
  
  /** Shows loading state on confirm button during async operations */
  loading?: boolean;
}

/**
 * ConfirmDialog Component
 * 
 * Reusable confirmation dialog for user action verification using Material-UI Dialog.
 * 
 * Features:
 * - Configurable title and message
 * - Customizable button labels and colors
 * - Keyboard support (Enter to confirm, Escape to cancel)
 * - Optional backdrop click handling
 * - Loading state for async operations
 * - Accessibility compliant (ARIA labels, keyboard navigation)
 * 
 * Usage Examples:
 * 
 * 1. Delete Confirmation (COUSR03.bms pattern):
 * <ConfirmDialog
 *   open={deleteDialogOpen}
 *   title="Delete User?"
 *   message={`Are you sure you want to delete user ${userId}? This action cannot be undone.`}
 *   confirmText="Delete"
 *   confirmColor="error"
 *   onConfirm={handleDeleteUser}
 *   onCancel={() => setDeleteDialogOpen(false)}
 * />
 * 
 * 2. Transaction Confirmation (COTRN02.bms pattern):
 * <ConfirmDialog
 *   open={confirmTransactionOpen}
 *   title="Confirm Transaction"
 *   message={`Submit transaction for $${amount} to account ${accountId}?`}
 *   confirmText="Submit"
 *   confirmColor="primary"
 *   onConfirm={handleSubmitTransaction}
 *   onCancel={() => setConfirmTransactionOpen(false)}
 *   loading={isSubmitting}
 * />
 */
const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  open,
  title,
  message,
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  confirmColor = 'primary',
  onConfirm,
  onCancel,
  disableBackdropClick = false,
  loading = false
}) => {
  /**
   * Handle dialog close event
   * 
   * Prevents closing on backdrop click if disableBackdropClick is true.
   * This is useful for critical confirmations where accidental close is risky.
   * 
   * @param event - Close event
   * @param reason - Reason for closing ('backdropClick' or 'escapeKeyDown')
   */
  const handleClose = (_event: {}, reason: 'backdropClick' | 'escapeKeyDown') => {
    // Prevent closing on backdrop click if disabled
    if (disableBackdropClick && reason === 'backdropClick') {
      return;
    }
    onCancel();
  };

  /**
   * Handle keyboard shortcuts
   * 
   * Maps keyboard events to dialog actions:
   * - Enter key: Confirms action (calls onConfirm)
   * - Escape key: Cancels action (handled by Dialog's onClose)
   * 
   * Matches native dialog behavior and BMS Enter key pattern.
   * Enter disabled during loading to prevent double-submit.
   * 
   * @param event - Keyboard event
   */
  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter' && !loading) {
      event.preventDefault();
      onConfirm();
    }
  };

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      onKeyDown={handleKeyDown}
      aria-labelledby="confirm-dialog-title"
      aria-describedby="confirm-dialog-description"
      maxWidth="sm"
      fullWidth
    >
      <DialogTitle id="confirm-dialog-title">
        {title}
      </DialogTitle>
      <DialogContent>
        <DialogContentText id="confirm-dialog-description">
          {message}
        </DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button 
          onClick={onCancel} 
          color="inherit"
          disabled={loading}
        >
          {cancelText}
        </Button>
        <Button 
          onClick={onConfirm} 
          color={confirmColor}
          variant="contained"
          disabled={loading}
          autoFocus
        >
          {loading ? 'Processing...' : confirmText}
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default ConfirmDialog;
