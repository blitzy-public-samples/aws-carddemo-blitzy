import React, { memo } from 'react';
import { TrendingUp, TrendingDown, Minus, ArrowUp, ArrowDown } from 'lucide-react';
import clsx from 'clsx';

/**
 * Trend direction for metric display
 * @typedef {'up' | 'down' | 'stable'} TrendDirection
 */
export type TrendDirection = 'up' | 'down' | 'stable';

/**
 * Visual variant for the metric card
 * @typedef {'default' | 'success' | 'warning' | 'danger'} MetricVariant
 */
export type MetricVariant = 'default' | 'success' | 'warning' | 'danger';

/**
 * Props for the MetricCard component
 * @interface MetricCardProps
 */
export interface MetricCardProps {
  /** Title of the metric (e.g., "Total Documents Processed") */
  title: string;
  
  /** Current value of the metric (can be string or number) */
  value: string | number;
  
  /** Optional subtitle displayed below title */
  subtitle?: string;
  
  /** Optional description displayed at the bottom of the card */
  description?: string;
  
  /** Trend direction indicator (up, down, or stable) */
  trend?: TrendDirection;
  
  /** Percentage change from previous period (positive or negative number) */
  change?: number;
  
  /** Label for the change percentage (e.g., "vs last month", "vs last week") */
  changeLabel?: string;
  
  /** Optional icon displayed in the top-right corner */
  icon?: React.ReactNode;
  
  /** Visual variant affecting background and border colors */
  variant?: MetricVariant;
  
  /** Loading state - displays skeleton placeholder */
  loading?: boolean;
  
  /** Custom function to format the value display */
  formatValue?: (value: number) => string;
  
  /** Click handler - makes card interactive when provided */
  onClick?: () => void;
  
  /** Additional CSS classes for customization */
  className?: string;
}

/**
 * MetricCard Component
 * 
 * A comprehensive KPI metric display card that shows key performance indicators
 * with trend visualization, percentage changes, and variant-based styling.
 * 
 * @component
 * @example
 * // Basic usage with number value
 * <MetricCard
 *   title="Total Documents"
 *   value={1234}
 *   trend="up"
 *   change={12.5}
 *   changeLabel="vs last month"
 * />
 * 
 * @example
 * // Currency formatting with success variant
 * <MetricCard
 *   title="Revenue"
 *   value={45000}
 *   variant="success"
 *   trend="up"
 *   change={8.3}
 *   formatValue={(val) => `$${val.toLocaleString('en-US', { minimumFractionDigits: 2 })}`}
 *   icon={<DollarSign className="w-6 h-6" />}
 * />
 * 
 * @example
 * // Percentage display with warning variant
 * <MetricCard
 *   title="Error Rate"
 *   value="2.3%"
 *   variant="warning"
 *   trend="down"
 *   change={-0.5}
 *   description="Errors decreased this period"
 * />
 * 
 * @example
 * // Loading state
 * <MetricCard
 *   title="Processing Time"
 *   value={0}
 *   loading={true}
 * />
 * 
 * @example
 * // Clickable card
 * <MetricCard
 *   title="Active Users"
 *   value={342}
 *   onClick={() => navigate('/users')}
 *   trend="up"
 *   change={5.2}
 * />
 */
const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  subtitle,
  description,
  trend,
  change,
  changeLabel = 'vs previous period',
  icon,
  variant = 'default',
  loading = false,
  formatValue,
  onClick,
  className,
}) => {
  /**
   * Format the display value using custom formatter or default formatting
   */
  const getFormattedValue = (): string => {
    if (typeof value === 'string') {
      return value;
    }
    
    if (formatValue) {
      return formatValue(value);
    }
    
    // Default formatting with locale support and thousand separators
    return value.toLocaleString('en-US', {
      maximumFractionDigits: 2,
    });
  };

  /**
   * Get the appropriate trend icon based on trend direction
   */
  const getTrendIcon = () => {
    if (!trend) return null;
    
    const iconClasses = 'w-5 h-5';
    
    switch (trend) {
      case 'up':
        return <TrendingUp className={clsx(iconClasses, 'text-green-600 dark:text-green-400')} aria-label="Trending up" />;
      case 'down':
        return <TrendingDown className={clsx(iconClasses, 'text-red-600 dark:text-red-400')} aria-label="Trending down" />;
      case 'stable':
        return <Minus className={clsx(iconClasses, 'text-gray-500 dark:text-gray-400')} aria-label="Stable" />;
      default:
        return null;
    }
  };

  /**
   * Get the change indicator with color coding and arrow icon
   */
  const getChangeIndicator = () => {
    if (change === undefined || change === null) return null;
    
    const isPositive = change > 0;
    const isNegative = change < 0;
    const isNeutral = change === 0;
    
    const changeClasses = clsx(
      'inline-flex items-center gap-1 text-xs font-medium',
      {
        'text-green-600 dark:text-green-400': isPositive,
        'text-red-600 dark:text-red-400': isNegative,
        'text-gray-500 dark:text-gray-400': isNeutral,
      }
    );
    
    const ArrowIcon = isPositive ? ArrowUp : isNegative ? ArrowDown : null;
    
    return (
      <div className="flex items-center gap-2 mt-2">
        <span className={changeClasses}>
          {ArrowIcon && <ArrowIcon className="w-3 h-3" aria-hidden="true" />}
          <span>
            {isPositive && '+'}
            {change.toFixed(1)}%
          </span>
        </span>
        {changeLabel && (
          <span className="text-xs text-gray-500 dark:text-gray-400">
            {changeLabel}
          </span>
        )}
      </div>
    );
  };

  /**
   * Get variant-specific styling classes
   */
  const getVariantClasses = () => {
    switch (variant) {
      case 'success':
        return 'bg-green-50 border-green-200 dark:bg-green-900/20 dark:border-green-800';
      case 'warning':
        return 'bg-yellow-50 border-yellow-200 dark:bg-yellow-900/20 dark:border-yellow-800';
      case 'danger':
        return 'bg-red-50 border-red-200 dark:bg-red-900/20 dark:border-red-800';
      case 'default':
      default:
        return 'bg-white border-gray-200 dark:bg-gray-800 dark:border-gray-700';
    }
  };

  /**
   * Render loading skeleton
   */
  if (loading) {
    return (
      <div
        className={clsx(
          'border rounded-lg shadow-sm p-4 md:p-6',
          getVariantClasses(),
          className
        )}
        role="article"
        aria-busy="true"
        aria-label="Loading metric"
      >
        <div className="animate-pulse">
          {/* Title skeleton */}
          <div className="h-4 bg-gray-300 dark:bg-gray-600 rounded w-2/3 mb-3"></div>
          
          {/* Value skeleton */}
          <div className="h-8 md:h-10 bg-gray-300 dark:bg-gray-600 rounded w-1/2 mb-3"></div>
          
          {/* Change skeleton */}
          <div className="h-3 bg-gray-300 dark:bg-gray-600 rounded w-1/3"></div>
        </div>
      </div>
    );
  }

  /**
   * Main card container classes
   */
  const containerClasses = clsx(
    'border rounded-lg shadow-sm p-4 md:p-6 transition-shadow duration-200',
    getVariantClasses(),
    {
      'cursor-pointer hover:shadow-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2': onClick,
    },
    className
  );

  /**
   * Accessibility label for the card
   */
  const ariaLabel = `${title}: ${getFormattedValue()}${
    change !== undefined ? `, ${change > 0 ? 'up' : change < 0 ? 'down' : 'unchanged'} ${Math.abs(change)}%` : ''
  }`;

  return (
    <div
      className={containerClasses}
      onClick={onClick}
      onKeyDown={(e) => {
        if (onClick && (e.key === 'Enter' || e.key === ' ')) {
          e.preventDefault();
          onClick();
        }
      }}
      role="article"
      aria-label={ariaLabel}
      aria-live="polite"
      tabIndex={onClick ? 0 : undefined}
    >
      {/* Header with title and icon */}
      <div className="flex items-start justify-between mb-2">
        <div className="flex-1">
          <h3 className="text-sm font-medium text-gray-600 dark:text-gray-300">
            {title}
          </h3>
          {subtitle && (
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              {subtitle}
            </p>
          )}
        </div>
        {icon && (
          <div className="ml-3 text-gray-400 dark:text-gray-500" aria-hidden="true">
            {icon}
          </div>
        )}
      </div>

      {/* Main value */}
      <div className="flex items-baseline gap-2 mb-1">
        <p className="text-2xl md:text-3xl font-bold text-gray-900 dark:text-gray-100">
          {getFormattedValue()}
        </p>
        {trend && (
          <div className="flex-shrink-0">
            {getTrendIcon()}
          </div>
        )}
      </div>

      {/* Change indicator */}
      {getChangeIndicator()}

      {/* Description */}
      {description && (
        <p className="text-xs text-gray-500 dark:text-gray-400 mt-3">
          {description}
        </p>
      )}
    </div>
  );
};

// Wrap component with React.memo for performance optimization
// Prevents unnecessary re-renders when props haven't changed
export default memo(MetricCard);
