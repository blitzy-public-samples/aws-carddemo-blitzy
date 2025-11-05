/**
 * ConfidenceIndicator Component
 * 
 * Visual confidence score indicator component displaying OCR extraction confidence
 * as color-coded badge. Shows confidence percentage with green for high confidence
 * (>90%), yellow for medium (70-90%), and red for low (<70%). Includes icon and
 * tooltip with interpretation. Used throughout document review interfaces to help
 * users identify fields requiring verification.
 * 
 * @module components/documents/ConfidenceIndicator
 * 
 * @example
 * // High confidence field
 * <ConfidenceIndicator confidence={95} />
 * 
 * @example
 * // Medium confidence without label
 * <ConfidenceIndicator confidence={75} showLabel={false} />
 * 
 * @example
 * // Low confidence with custom size
 * <ConfidenceIndicator confidence={45} size="lg" />
 * 
 * @example
 * // Compact display (percentage only)
 * <ConfidenceIndicator 
 *   confidence={88} 
 *   showLabel={false} 
 *   size="sm" 
 * />
 */

import React, { memo, FC } from 'react';
import { CheckCircle2, AlertCircle, XCircle } from 'lucide-react';
import clsx from 'clsx';

/**
 * Props for the ConfidenceIndicator component
 * 
 * @interface ConfidenceIndicatorProps
 */
export interface ConfidenceIndicatorProps {
  /**
   * Confidence score as a percentage (0-100)
   * Values >= 90 are considered high confidence (green)
   * Values >= 70 and < 90 are medium confidence (yellow)
   * Values < 70 are low confidence (red)
   */
  confidence: number;

  /**
   * Whether to display the confidence level label text
   * @default true
   */
  showLabel?: boolean;

  /**
   * Whether to display the percentage value
   * @default true
   */
  showPercentage?: boolean;

  /**
   * Size variant for the indicator badge
   * - sm: Compact size for inline display
   * - md: Standard size for most use cases
   * - lg: Large size for prominent display
   * @default 'md'
   */
  size?: 'sm' | 'md' | 'lg';

  /**
   * Additional CSS classes to apply to the badge container
   */
  className?: string;
}

/**
 * Confidence level enum for internal logic
 */
type ConfidenceLevel = 'high' | 'medium' | 'low';

/**
 * ConfidenceIndicator Component
 * 
 * Displays a visual indicator of OCR extraction confidence with color coding,
 * icons, and optional labels. Implements WCAG 2.1 AA accessibility standards
 * with proper ARIA attributes and color contrast ratios.
 * 
 * Performance optimized with React.memo to prevent unnecessary re-renders
 * when props haven't changed.
 * 
 * @param props - Component props
 * @returns Rendered confidence indicator badge
 */
const ConfidenceIndicator: FC<ConfidenceIndicatorProps> = memo(({
  confidence,
  showLabel = true,
  showPercentage = true,
  size = 'md',
  className
}) => {
  // Compute confidence level based on thresholds from Section 0.7.3
  const getConfidenceLevel = (score: number): ConfidenceLevel => {
    if (score >= 90) return 'high';
    if (score >= 70) return 'medium';
    return 'low';
  };

  const confidenceLevel = getConfidenceLevel(confidence);

  // Define color schemes for each confidence level
  // Uses TailwindCSS classes with 4.5:1 contrast ratio for WCAG 2.1 AA compliance
  const colorSchemes = {
    high: {
      textColor: 'text-green-600',
      bgColor: 'bg-green-50',
      borderColor: 'border-green-200',
      icon: CheckCircle2,
      label: 'High Confidence',
      description: 'High confidence - OCR extraction is highly reliable',
    },
    medium: {
      textColor: 'text-yellow-600',
      bgColor: 'bg-yellow-50',
      borderColor: 'border-yellow-200',
      icon: AlertCircle,
      label: 'Medium Confidence',
      description: 'Medium confidence - OCR extraction may require verification',
    },
    low: {
      textColor: 'text-red-600',
      bgColor: 'bg-red-50',
      borderColor: 'border-red-200',
      icon: XCircle,
      label: 'Low Confidence',
      description: 'Low confidence - OCR extraction requires manual verification',
    },
  };

  // Define size styles for responsive display
  const sizeStyles = {
    sm: {
      container: 'text-xs px-2 py-1 gap-1',
      icon: 'w-3 h-3',
    },
    md: {
      container: 'text-sm px-3 py-1.5 gap-1.5',
      icon: 'w-4 h-4',
    },
    lg: {
      container: 'text-base px-4 py-2 gap-2',
      icon: 'w-5 h-5',
    },
  };

  const scheme = colorSchemes[confidenceLevel];
  const sizeStyle = sizeStyles[size];
  const IconComponent = scheme.icon;

  // Construct ARIA label for screen readers
  const ariaLabel = `${scheme.label}: ${confidence}% confidence score. ${scheme.description}`;

  // Construct display text
  const displayText = [];
  if (showPercentage) {
    displayText.push(`${confidence}%`);
  }
  if (showLabel) {
    displayText.push(scheme.label);
  }

  return (
    <span
      className={clsx(
        // Base styles
        'inline-flex items-center justify-center',
        'rounded-md border font-medium',
        'transition-all duration-200 ease-in-out',
        // Color scheme
        scheme.textColor,
        scheme.bgColor,
        scheme.borderColor,
        // Size-specific styles
        sizeStyle.container,
        // Custom className
        className
      )}
      role="status"
      aria-label={ariaLabel}
      title={scheme.description}
    >
      {/* Icon indicator */}
      <IconComponent 
        className={clsx(sizeStyle.icon, 'flex-shrink-0')}
        aria-hidden="true"
      />
      
      {/* Text content */}
      {displayText.length > 0 && (
        <span className="whitespace-nowrap">
          {displayText.join(' - ')}
        </span>
      )}
    </span>
  );
});

// Set display name for debugging
ConfidenceIndicator.displayName = 'ConfidenceIndicator';

export default ConfidenceIndicator;
