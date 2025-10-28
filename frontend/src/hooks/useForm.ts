/**
 * Custom React Hook for Form State Management
 * 
 * Converted from COBOL BMS map handling patterns to React form management.
 * Replaces COBOL field validation and screen I/O with modern React state management.
 * 
 * Original COBOL pattern:
 * - BMS maps with field attributes (ASKIP, PROT, NUM, BRT)
 * - Field validation in PROCEDURE DIVISION
 * - Error message handling via ERRMSG fields
 * 
 * Transformation notes:
 * - COBOL field validation → React validation function
 * - BMS field attributes → React validation rules
 * - COBOL MOVE statements → React setState
 * - Screen I/O (SEND MAP/RECEIVE MAP) → Form submission handlers
 */

import { useState, useCallback, useMemo } from 'react';

/**
 * Validation errors object type
 * Maps field names to error messages
 * 
 * Replaces COBOL error message handling:
 * MOVE 'Account ID is required' TO ERR-MSG
 */
export type ValidationErrors<T> = {
  [K in keyof T]?: string;
};

/**
 * Form validation function type
 * Accepts form values and returns validation errors
 * 
 * Replaces COBOL field validation logic:
 * IF ACCT-ID-I = SPACES
 *     MOVE 'Account ID is required' TO ERR-MSG
 * END-IF
 */
export type ValidationFunction<T> = (values: T) => ValidationErrors<T>;

/**
 * Form submission handler type
 * 
 * Replaces COBOL program CALL or EXEC CICS LINK:
 * EXEC CICS LINK PROGRAM('COACTUPC') COMMAREA(...) END-EXEC
 */
export type SubmitHandler<T> = (values: T) => void | Promise<void>;

/**
 * Form hook return type
 * Defines all form state and handlers returned by useForm hook
 */
export interface UseFormReturn<T> {
  /** Current form values */
  values: T;
  
  /** Validation errors for each field */
  errors: ValidationErrors<T>;
  
  /** Fields that have been touched (blurred) */
  touched: { [K in keyof T]?: boolean };
  
  /** Whether form has unsaved changes */
  isDirty: boolean;
  
  /** Whether form is currently submitting */
  isSubmitting: boolean;
  
  /** Handle input change events */
  handleChange: (
    event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => void;
  
  /** Handle input blur events */
  handleBlur: (
    event: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => void;
  
  /** Handle form submission */
  handleSubmit: (event?: React.FormEvent<HTMLFormElement>) => Promise<void>;
  
  /** Reset form to initial values */
  resetForm: () => void;
  
  /** Set specific field value programmatically */
  setFieldValue: (field: keyof T, value: any) => void;
  
  /** Set field as touched */
  setFieldTouched: (field: keyof T, touched?: boolean) => void;
  
  /** Set validation errors programmatically */
  setErrors: (errors: ValidationErrors<T>) => void;
  
  /** Get error message for specific field (only if touched) */
  getFieldError: (field: keyof T) => string | undefined;
  
  /** Check if specific field has error (only if touched) */
  hasFieldError: (field: keyof T) => boolean;
}

/**
 * Form hook configuration options
 */
export interface UseFormOptions<T> {
  /** Initial form values */
  initialValues: T;
  
  /** Validation function */
  validate?: ValidationFunction<T>;
  
  /** Form submission handler */
  onSubmit: SubmitHandler<T>;
  
  /** Validate on change (default: false) */
  validateOnChange?: boolean;
  
  /** Validate on blur (default: true) */
  validateOnBlur?: boolean;
}

/**
 * Custom React hook for managing form state
 * 
 * Provides form state management, validation, and submission handling
 * with a Formik-compatible API. Supports TypeScript generic typing
 * for type-safe form values.
 * 
 * Replaces COBOL BMS map handling patterns from programs like:
 * - COACTUP.cbl (Account update form - COACTUP.bms)
 * - COCRDUP.cbl (Card update form - COCRDUP.bms)
 * - COTRN02.cbl (Transaction entry form - COTRN02.bms)
 * - COUSR01.cbl (User add form - COUSR01.bms)
 * 
 * @template T - Type of form values object
 * @param options - Form configuration options
 * @returns Form state and handlers
 * 
 * @example
 * // Account form (COACTUP.bms replacement)
 * interface AccountFormValues {
 *   accountId: string;
 *   creditLimit: string;
 *   status: string;
 * }
 * 
 * const AccountForm: React.FC = () => {
 *   const form = useForm<AccountFormValues>({
 *     initialValues: {
 *       accountId: '',
 *       creditLimit: '',
 *       status: 'A'
 *     },
 *     validate: (values) => {
 *       const errors: ValidationErrors<AccountFormValues> = {};
 *       if (!values.accountId) {
 *         errors.accountId = 'Account ID is required';
 *       }
 *       if (!values.creditLimit) {
 *         errors.creditLimit = 'Credit limit is required';
 *       }
 *       return errors;
 *     },
 *     onSubmit: async (values) => {
 *       await accountService.updateAccount(values);
 *     }
 *   });
 *   
 *   return (
 *     <form onSubmit={form.handleSubmit}>
 *       <TextField
 *         name="accountId"
 *         value={form.values.accountId}
 *         onChange={form.handleChange}
 *         onBlur={form.handleBlur}
 *         error={form.hasFieldError('accountId')}
 *         helperText={form.getFieldError('accountId')}
 *       />
 *       <Button type="submit" disabled={form.isSubmitting}>
 *         Submit
 *       </Button>
 *     </form>
 *   );
 * };
 */
export function useForm<T extends Record<string, any>>({
  initialValues,
  validate,
  onSubmit,
  validateOnChange = false,
  validateOnBlur = true
}: UseFormOptions<T>): UseFormReturn<T> {
  // Form state management
  // Replaces COBOL WORKING-STORAGE SECTION variables
  const [values, setValues] = useState<T>(initialValues);
  const [errors, setErrors] = useState<ValidationErrors<T>>({});
  const [touched, setTouched] = useState<{ [K in keyof T]?: boolean }>({});
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  
  /**
   * Check if form has unsaved changes
   * 
   * Replaces COBOL dirty flag logic:
   * IF WS-ORIGINAL-DATA NOT = WS-CURRENT-DATA
   *     MOVE 'Y' TO WS-DATA-CHANGED
   * END-IF
   */
  const isDirty = useMemo(() => {
    return JSON.stringify(values) !== JSON.stringify(initialValues);
  }, [values, initialValues]);
  
  /**
   * Validate form values
   * 
   * Replaces COBOL validation paragraphs:
   * VALIDATE-FIELDS.
   *     PERFORM VALIDATE-ACCT-ID
   *     PERFORM VALIDATE-CREDIT-LIMIT
   *     IF WS-ERROR-COUNT > 0
   *         GO TO DISPLAY-ERROR
   *     END-IF.
   */
  const validateForm = useCallback((valuesToValidate: T): ValidationErrors<T> => {
    if (!validate) return {};
    return validate(valuesToValidate);
  }, [validate]);
  
  /**
   * Handle input change events
   * 
   * Replaces COBOL MOVE statements:
   * MOVE ACCT-ID-I TO WS-ACCT-ID
   * MOVE CREDIT-LIMIT-I TO WS-CREDIT-LIMIT
   */
  const handleChange = useCallback((
    event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value, type } = event.target;
    
    // Handle checkbox inputs
    // Replaces COBOL 88-level condition handling
    const inputValue = type === 'checkbox' 
      ? (event.target as HTMLInputElement).checked 
      : value;
    
    setValues(prev => ({
      ...prev,
      [name]: inputValue
    }));
    
    // Validate on change if enabled
    // Replaces COBOL real-time validation
    if (validateOnChange && validate) {
      const newValues = { ...values, [name]: inputValue };
      const validationErrors = validateForm(newValues);
      setErrors(validationErrors);
    }
  }, [values, validateOnChange, validate, validateForm]);
  
  /**
   * Handle input blur events
   * 
   * Replaces COBOL field exit validation:
   * FIELD-EXIT-VALIDATION.
   *     IF ACCT-ID-I = SPACES
   *         MOVE 'Account ID is required' TO ERR-MSG
   *         SET CURSOR ON ACCT-ID-I
   *     END-IF.
   */
  const handleBlur = useCallback((
    event: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name } = event.target;
    
    // Mark field as touched
    // Replaces COBOL field-visited flag:
    // MOVE 'Y' TO WS-FIELD-VISITED(field-index)
    setTouched(prev => ({
      ...prev,
      [name]: true
    }));
    
    // Validate on blur if enabled
    if (validateOnBlur && validate) {
      const validationErrors = validateForm(values);
      setErrors(validationErrors);
    }
  }, [values, validateOnBlur, validate, validateForm]);
  
  /**
   * Handle form submission
   * 
   * Replaces COBOL form submission logic:
   * PROCESS-ENTER-KEY.
   *     PERFORM VALIDATE-ALL-FIELDS
   *     IF WS-ERROR-COUNT = 0
   *         EXEC CICS LINK PROGRAM('COACTUPC') COMMAREA(...) END-EXEC
   *     ELSE
   *         PERFORM DISPLAY-ERRORS
   *     END-IF.
   */
  const handleSubmit = useCallback(async (
    event?: React.FormEvent<HTMLFormElement>
  ) => {
    if (event) {
      event.preventDefault();
    }
    
    // Validate form
    // Replaces COBOL VALIDATE-ALL-FIELDS paragraph
    const validationErrors = validateForm(values);
    setErrors(validationErrors);
    
    // Mark all fields as touched
    // Replaces COBOL: MOVE 'Y' TO WS-ALL-FIELDS-VISITED
    const allTouched = Object.keys(values).reduce((acc, key) => {
      acc[key as keyof T] = true;
      return acc;
    }, {} as { [K in keyof T]: boolean });
    setTouched(allTouched);
    
    // Check if form has errors
    // Replaces COBOL: IF WS-ERROR-COUNT > 0
    if (Object.keys(validationErrors).length > 0) {
      return;
    }
    
    // Submit form
    // Replaces COBOL EXEC CICS LINK or CALL statement
    try {
      setIsSubmitting(true);
      await onSubmit(values);
    } catch (error) {
      // Error handling
      // Replaces COBOL EXEC CICS ABEND or error paragraph
      console.error('Form submission error:', error);
      throw error;
    } finally {
      setIsSubmitting(false);
    }
  }, [values, validateForm, onSubmit]);
  
  /**
   * Reset form to initial values
   * 
   * Replaces COBOL form clear logic:
   * CLEAR-SCREEN.
   *     MOVE SPACES TO ACCT-ID-I
   *     MOVE SPACES TO CREDIT-LIMIT-I
   *     MOVE SPACES TO ERR-MSG
   *     EXEC CICS SEND MAP(...) ERASE END-EXEC.
   */
  const resetForm = useCallback(() => {
    setValues(initialValues);
    setErrors({});
    setTouched({});
    setIsSubmitting(false);
  }, [initialValues]);
  
  /**
   * Set specific field value programmatically
   * 
   * Replaces COBOL programmatic field update:
   * MOVE WS-NEW-VALUE TO ACCT-ID-I
   */
  const setFieldValue = useCallback((field: keyof T, value: any) => {
    setValues(prev => ({
      ...prev,
      [field]: value
    }));
    
    // Validate if enabled
    if (validateOnChange && validate) {
      const newValues = { ...values, [field]: value };
      const validationErrors = validateForm(newValues);
      setErrors(validationErrors);
    }
  }, [values, validateOnChange, validate, validateForm]);
  
  /**
   * Set field as touched
   * 
   * Replaces COBOL field-visited flag:
   * MOVE 'Y' TO WS-FIELD-VISITED(field-index)
   */
  const setFieldTouched = useCallback((field: keyof T, isTouched: boolean = true) => {
    setTouched(prev => ({
      ...prev,
      [field]: isTouched
    }));
  }, []);
  
  /**
   * Get error for specific field (only if touched)
   * 
   * Replaces COBOL error display logic:
   * IF WS-FIELD-VISITED(field-index) = 'Y'
   *     MOVE WS-ERROR-MSG(field-index) TO ERRMSG-O
   * END-IF
   */
  const getFieldError = useCallback((field: keyof T): string | undefined => {
    return touched[field] ? errors[field] : undefined;
  }, [errors, touched]);
  
  /**
   * Check if field has error (only if touched)
   * 
   * Replaces COBOL error check:
   * IF WS-FIELD-VISITED(field-index) = 'Y' AND
   *    WS-ERROR-MSG(field-index) NOT = SPACES
   *     SET FIELD-HAS-ERROR TO TRUE
   * END-IF
   */
  const hasFieldError = useCallback((field: keyof T): boolean => {
    return Boolean(touched[field] && errors[field]);
  }, [errors, touched]);
  
  // Return all form state and handlers
  // Provides Formik-compatible API for easy integration
  return {
    values,
    errors,
    touched,
    isDirty,
    isSubmitting,
    handleChange,
    handleBlur,
    handleSubmit,
    resetForm,
    setFieldValue,
    setFieldTouched,
    setErrors,
    getFieldError,
    hasFieldError
  };
}
