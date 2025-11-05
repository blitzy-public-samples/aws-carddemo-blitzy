/**
 * ValidationErrors Component
 *
 * Displays field validation errors with visual indicators, messages, and correction suggestions.
 * Provides immediate feedback for data quality issues requiring user attention during document review.
 *
 * Features:
 * - Severity-based visual indicators (error, warning, info)
 * - Clickable field names to navigate to the field in the document
 * - Suggestion buttons to auto-apply corrections
 * - Grouped by severity for prioritization
 * - Full keyboard accessibility and screen reader support (WCAG 2.1 AA)
 *
 * @example
 * ```tsx
 * const errors: ValidationError[] = [
 *   {
 *     fieldName: 'invoice_number',
 *     fieldLabel: 'Invoice Number',
 *     message: 'Invoice number format is invalid',
 *     severity: 'error',
 *     suggestions: ['INV-2024-001', 'INV-2024-002']
 *   }
 * ];
 *
 * <ValidationErrors
 *   errors={errors}
 *   onFieldClick={(fieldName) => scrollToField(fieldName)}
 *   onSuggestionClick={(fieldName, suggestion) => applyCorrection(fieldName, suggestion)}
 * />
 * ```
 */

import React, { type FC, memo } from 'react';
import { AlertTriangle, Info } from 'lucide-react';
import clsx from 'clsx';

/**
 * Represents a single validation error for a document field
 */
export interface ValidationError {
  /** Unique field identifier used for programmatic reference */
  fieldName: string;

  /** Human-readable field label displayed to users */
  fieldLabel: string;

  /** Detailed error message explaining the validation failure */
  message: string;

  /** Severity level determining visual styling and priority */
  severity: 'error' | 'warning' | 'info';

  /** Optional array of suggested correction values the user can apply */
  suggestions?: string[];
}

/**
 * Props for the ValidationErrors component
 */
export interface ValidationErrorsProps {
  /** Array of validation errors to display */
  errors: ValidationError[];

  /** Callback invoked when a field name is clicked to navigate/focus to that field */
  onFieldClick?: (fieldName: string) => void;

  /** Callback invoked when a suggestion is clicked to apply the correction */
  onSuggestionClick?: (fieldName: string, suggestion: string) => void;

  /** Additional CSS classes to apply to the container */
  className?: string;
}

/**
 * ValidationErrors Component
 *
 * Displays a list of field validation errors grouped by severity with interactive
 * correction suggestions and field navigation capabilities.
 *
 * Implements WCAG 2.1 AA accessibility requirements with proper ARIA attributes,
 * keyboard navigation, and screen reader announcements.
 */
const ValidationErrors: FC<ValidationErrorsProps> = memo(
  ({ errors, onFieldClick, onSuggestionClick, className }) => {
    // Return null if no errors to display
    if (!errors || errors.length === 0) {
      return null;
    }

    /**
     * Groups errors by severity level for prioritized display
     * Ordering: errors first, then warnings, then info
     */
    const groupedErrors = {
      error: errors.filter((err) => err.severity === 'error'),
      warning: errors.filter((err) => err.severity === 'warning'),
      info: errors.filter((err) => err.severity === 'info'),
    };

    // Flatten grouped errors in priority order
    const sortedErrors = [...groupedErrors.error, ...groupedErrors.warning, ...groupedErrors.info];

    /**
     * Returns icon component based on severity level
     */
    const getIcon = (severity: ValidationError['severity']): JSX.Element => {
      switch (severity) {
        case 'error':
        case 'warning':
          return <AlertTriangle className="w-5 h-5 flex-shrink-0" aria-hidden="true" />;
        case 'info':
          return <Info className="w-5 h-5 flex-shrink-0" aria-hidden="true" />;
        default:
          return <AlertTriangle className="w-5 h-5 flex-shrink-0" aria-hidden="true" />;
      }
    };

    /**
     * Returns Tailwind CSS classes for severity-based styling
     */
    const getSeverityClasses = (
      severity: ValidationError['severity']
    ): {
      container: string;
      icon: string;
      label: string;
      message: string;
    } => {
      switch (severity) {
        case 'error':
          return {
            container: 'border-l-red-500 bg-red-50',
            icon: 'text-red-600',
            label: 'text-red-900',
            message: 'text-red-800',
          };
        case 'warning':
          return {
            container: 'border-l-yellow-500 bg-yellow-50',
            icon: 'text-yellow-600',
            label: 'text-yellow-900',
            message: 'text-yellow-800',
          };
        case 'info':
          return {
            container: 'border-l-blue-500 bg-blue-50',
            icon: 'text-blue-600',
            label: 'text-blue-900',
            message: 'text-blue-800',
          };
        default:
          return {
            container: 'border-l-gray-500 bg-gray-50',
            icon: 'text-gray-600',
            label: 'text-gray-900',
            message: 'text-gray-800',
          };
      }
    };

    /**
     * Handles field name click to navigate to the field
     */
    const handleFieldClick = (fieldName: string): void => {
      if (onFieldClick) {
        onFieldClick(fieldName);
      }
    };

    /**
     * Handles suggestion click to apply correction
     */
    const handleSuggestionClick = (fieldName: string, suggestion: string): void => {
      if (onSuggestionClick) {
        onSuggestionClick(fieldName, suggestion);
      }
    };

    // Calculate error count summary
    const errorCount = groupedErrors.error.length;
    const warningCount = groupedErrors.warning.length;
    const infoCount = groupedErrors.info.length;
    const totalCount = errors.length;

    return (
      <div
        className={clsx('space-y-3', className)}
        role="alert"
        aria-live="polite"
        aria-label={`${totalCount} validation ${totalCount === 1 ? 'issue' : 'issues'} found`}
      >
        {/* Error Summary Header */}
        <div className="flex items-center justify-between px-4 py-3 bg-gray-100 rounded-lg shadow-sm">
          <h3 className="text-sm font-semibold text-gray-900">
            {totalCount} validation {totalCount === 1 ? 'issue' : 'issues'} found
          </h3>
          <div className="flex items-center gap-4 text-xs font-medium">
            {errorCount > 0 && (
              <span className="text-red-600">
                {errorCount} {errorCount === 1 ? 'error' : 'errors'}
              </span>
            )}
            {warningCount > 0 && (
              <span className="text-yellow-600">
                {warningCount} {warningCount === 1 ? 'warning' : 'warnings'}
              </span>
            )}
            {infoCount > 0 && <span className="text-blue-600">{infoCount} info</span>}
          </div>
        </div>

        {/* Error List */}
        <div className="space-y-2">
          {sortedErrors.map((error, index) => {
            const severityClasses = getSeverityClasses(error.severity);

            return (
              <div
                key={`${error.fieldName}-${index}`}
                className={clsx('border-l-4 rounded-lg shadow-sm p-4', severityClasses.container)}
              >
                <div className="flex gap-3">
                  {/* Severity Icon */}
                  <div className={clsx('mt-0.5', severityClasses.icon)}>
                    {getIcon(error.severity)}
                  </div>

                  {/* Error Content */}
                  <div className="flex-1 min-w-0">
                    {/* Field Label (Clickable) */}
                    {onFieldClick ? (
                      <button
                        type="button"
                        onClick={() => handleFieldClick(error.fieldName)}
                        className={clsx(
                          'font-bold text-sm hover:underline focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 rounded',
                          severityClasses.label
                        )}
                        aria-label={`Navigate to ${error.fieldLabel} field`}
                      >
                        {error.fieldLabel}
                      </button>
                    ) : (
                      <div className={clsx('font-bold text-sm', severityClasses.label)}>
                        {error.fieldLabel}
                      </div>
                    )}

                    {/* Error Message */}
                    <p className={clsx('mt-1 text-sm', severityClasses.message)}>{error.message}</p>

                    {/* Suggestions */}
                    {error.suggestions && error.suggestions.length > 0 && (
                      <div className="mt-3">
                        <p className="text-xs font-medium text-gray-700 mb-2">
                          Suggested corrections:
                        </p>
                        <div className="flex flex-wrap gap-2">
                          {error.suggestions.map((suggestion, suggestionIndex) => (
                            <button
                              key={suggestionIndex}
                              type="button"
                              onClick={() => handleSuggestionClick(error.fieldName, suggestion)}
                              className={clsx(
                                'px-3 py-1.5 text-xs font-medium rounded-md',
                                'bg-white border border-gray-300 text-gray-700',
                                'hover:bg-gray-50 hover:border-gray-400',
                                'focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500',
                                'transition-colors duration-150'
                              )}
                              aria-label={`Apply suggestion: ${suggestion}`}
                            >
                              {suggestion}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }
);

// Display name for debugging
ValidationErrors.displayName = 'ValidationErrors';

export default ValidationErrors;
