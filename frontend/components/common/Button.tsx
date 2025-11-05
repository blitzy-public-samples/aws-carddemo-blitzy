import React, {
  type ButtonHTMLAttributes,
  forwardRef,
  memo,
  type MouseEvent,
  type ReactNode,
} from 'react';
import { Loader2 } from 'lucide-react';
import clsx from 'clsx';

/**
 * Button variant types defining the visual style of the button
 * - primary: Brand-colored button for primary actions (blue background)
 * - secondary: Outlined button for secondary actions (gray border)
 * - danger: Red button for destructive actions (red background)
 * - ghost: Minimal button with no background, only hover effect
 */
export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

/**
 * Button size types defining the dimensions of the button
 * - sm: Small button (height: 32px, minimum touch target: 32px)
 * - md: Medium button (height: 40px, minimum touch target: 40px)
 * - lg: Large button (height: 48px, exceeds 44px minimum touch target per WCAG 2.1 AA)
 */
export type ButtonSize = 'sm' | 'md' | 'lg';

/**
 * Props interface for the Button component
 * Extends native HTML button attributes while adding custom functionality
 *
 * @interface ButtonProps
 * @extends {ButtonHTMLAttributes<HTMLButtonElement>}
 */
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /**
   * Visual style variant of the button
   * @default 'primary'
   */
  variant?: ButtonVariant;

  /**
   * Size of the button (affects padding and font size)
   * @default 'md'
   */
  size?: ButtonSize;

  /**
   * Whether the button is in a loading state
   * When true, displays a spinner and disables the button
   * @default false
   */
  loading?: boolean;

  /**
   * Whether the button is disabled
   * When true, reduces opacity and prevents interactions
   * @default false
   */
  disabled?: boolean;

  /**
   * Icon to display before the button text
   * Accepts any ReactNode (typically an icon component)
   */
  leftIcon?: ReactNode;

  /**
   * Icon to display after the button text
   * Accepts any ReactNode (typically an icon component)
   */
  rightIcon?: ReactNode;

  /**
   * Whether the button should take up the full width of its container
   * @default false
   */
  fullWidth?: boolean;

  /**
   * Additional CSS classes to apply to the button
   * Merged with internal styles using clsx
   */
  className?: string;

  /**
   * Button content (text, elements, etc.)
   */
  children?: ReactNode;

  /**
   * Click event handler
   * Not triggered when button is disabled or loading
   */
  onClick?: (event: MouseEvent<HTMLButtonElement>) => void;

  /**
   * HTML button type attribute
   * @default 'button'
   */
  type?: 'button' | 'submit' | 'reset';

  /**
   * ARIA label for accessibility
   * Provides context for screen readers
   */
  ariaLabel?: string;
}

/**
 * Button component - Versatile, accessible button for user interactions
 *
 * A production-ready button component that serves as the foundational interactive
 * element throughout the OCR Processing Application. Implements comprehensive
 * accessibility features per WCAG 2.1 AA standards, multiple visual variants,
 * loading states, and icon support.
 *
 * @component
 * @example
 * // Primary button with default styling
 * <Button onClick={handleClick}>Click Me</Button>
 *
 * @example
 * // Secondary button with left icon
 * <Button variant="secondary" leftIcon={<Download />}>
 *   Download
 * </Button>
 *
 * @example
 * // Large danger button in loading state
 * <Button variant="danger" size="lg" loading>
 *   Deleting...
 * </Button>
 *
 * @example
 * // Full-width ghost button with custom styles
 * <Button variant="ghost" fullWidth className="mt-4">
 *   Cancel
 * </Button>
 *
 * Features:
 * - Multiple variants: primary, secondary, danger, ghost
 * - Three sizes: sm (32px), md (40px), lg (48px)
 * - Loading state with animated spinner
 * - Disabled state with reduced opacity
 * - Icon support (left and right positioning)
 * - Full keyboard accessibility (Enter/Space)
 * - ARIA attributes for screen readers
 * - Focus visible styles per WCAG guidelines
 * - Minimum 44x44px touch targets on mobile (lg size)
 * - Smooth transitions and hover effects
 *
 * @param {ButtonProps} props - Component props
 * @param {React.Ref<HTMLButtonElement>} ref - Forwarded ref to button element
 * @returns {React.ReactElement} Rendered button component
 */
const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'primary',
      size = 'md',
      loading = false,
      disabled = false,
      leftIcon,
      rightIcon,
      fullWidth = false,
      className,
      children,
      onClick,
      type = 'button',
      ariaLabel,
      ...restProps
    },
    ref
  ) => {
    /**
     * Base styles applied to all button variants
     * Includes transitions, focus styles, and accessibility features
     */
    const baseStyles = clsx(
      // Layout and typography
      'inline-flex items-center justify-center gap-2',
      'font-medium rounded-lg',
      'transition-all duration-200 ease-in-out',

      // Focus styles for keyboard navigation (WCAG 2.1 AA)
      'focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
      'focus-visible:ring-blue-500',

      // Disabled cursor
      (disabled || loading) && 'cursor-not-allowed',
      !disabled && !loading && 'cursor-pointer',

      // Full width option
      fullWidth && 'w-full'
    );

    /**
     * Variant-specific styles for different button types
     * Each variant has distinct colors and hover effects
     */
    const variantStyles = {
      primary: clsx(
        'bg-blue-600 text-white',
        'hover:bg-blue-700 active:bg-blue-800',
        'shadow-sm hover:shadow-md',
        (disabled || loading) && 'bg-blue-400 hover:bg-blue-400'
      ),
      secondary: clsx(
        'bg-white border border-gray-300 text-gray-700',
        'hover:bg-gray-50 active:bg-gray-100',
        'shadow-sm hover:shadow-md',
        (disabled || loading) && 'bg-gray-50 text-gray-400 border-gray-200 hover:bg-gray-50'
      ),
      danger: clsx(
        'bg-red-600 text-white',
        'hover:bg-red-700 active:bg-red-800',
        'shadow-sm hover:shadow-md',
        (disabled || loading) && 'bg-red-400 hover:bg-red-400'
      ),
      ghost: clsx(
        'bg-transparent text-gray-700',
        'hover:bg-gray-100 active:bg-gray-200',
        (disabled || loading) && 'text-gray-400 hover:bg-transparent'
      ),
    };

    /**
     * Size-specific styles for button dimensions
     * Ensures minimum touch target of 44x44px per WCAG 2.1 AA (Section 0.7.10)
     * - sm: 32px height (acceptable for desktop)
     * - md: 40px height (close to 44px minimum)
     * - lg: 48px height (exceeds 44px minimum, ideal for mobile)
     */
    const sizeStyles = {
      sm: 'px-3 py-1.5 text-sm h-8 min-h-[32px]',
      md: 'px-4 py-2 text-base h-10 min-h-[40px]',
      lg: 'px-6 py-3 text-lg h-12 min-h-[48px]',
    };

    /**
     * Opacity reduction for disabled state
     * Makes the button appear inactive while maintaining visual hierarchy
     */
    const disabledStyles = (disabled || loading) && !loading ? 'opacity-60' : '';

    /**
     * Combine all styles using clsx for conditional application
     */
    const buttonClasses = clsx(
      baseStyles,
      variantStyles[variant],
      sizeStyles[size],
      disabledStyles,
      className
    );

    /**
     * Handle click events with loading and disabled state checks
     * Prevents event propagation when button should not be interactive
     *
     * @param {MouseEvent<HTMLButtonElement>} event - Click event
     */
    const handleClick = (event: MouseEvent<HTMLButtonElement>): void => {
      if (disabled || loading) {
        event.preventDefault();
        return;
      }

      if (onClick) {
        onClick(event);
      }
    };

    /**
     * Render loading spinner or left icon
     * When loading, spinner replaces left icon if present
     */
    const renderLeftContent = (): ReactNode => {
      if (loading) {
        return <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />;
      }
      if (leftIcon) {
        return (
          <span className="inline-flex" aria-hidden="true">
            {leftIcon}
          </span>
        );
      }
      return null;
    };

    /**
     * Render right icon
     * Not affected by loading state
     */
    const renderRightContent = (): ReactNode => {
      if (rightIcon && !loading) {
        return (
          <span className="inline-flex" aria-hidden="true">
            {rightIcon}
          </span>
        );
      }
      return null;
    };

    return (
      <button
        ref={ref}
        type={type}
        className={buttonClasses}
        disabled={disabled || loading}
        onClick={handleClick}
        aria-disabled={disabled || loading}
        aria-busy={loading}
        aria-label={ariaLabel}
        {...restProps}
      >
        {renderLeftContent()}
        {children && <span>{children}</span>}
        {renderRightContent()}
      </button>
    );
  }
);

/**
 * Display name for React DevTools
 * Helps with debugging and component identification
 */
Button.displayName = 'Button';

/**
 * Create memoized version of Button component for performance optimization
 * Prevents unnecessary re-renders when props haven't changed
 * Critical for performance in lists and frequently updating UIs
 */
const MemoizedButton = memo(Button);

/**
 * Set displayName on memoized component as well
 * Ensures proper display in React DevTools
 */
MemoizedButton.displayName = 'Button';

/**
 * Export memoized Button component as default
 */
export default MemoizedButton;

/**
 * Named export for direct import
 * Allows: import { Button } from './Button'
 */
export { MemoizedButton as Button };
