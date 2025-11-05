/**
 * ChartWidget.tsx
 *
 * Recharts-based chart wrapper component providing reusable visualization foundation
 * for analytics dashboard. Supports multiple chart types with responsive design,
 * interactive features, and comprehensive customization options.
 *
 * Features:
 * - Multiple chart types: line, bar, pie, area
 * - Responsive design with ResponsiveContainer
 * - Interactive tooltips with custom formatting
 * - Configurable legends and axes
 * - Loading, error, and empty states
 * - Accessibility compliance (WCAG 2.1 AA)
 * - Performance optimized with React.memo
 * - TailwindCSS styling
 *
 * Usage Examples:
 *
 * Line Chart:
 * ```tsx
 * <ChartWidget
 *   type="line"
 *   data={[
 *     { date: '2025-01', value: 120 },
 *     { date: '2025-02', value: 150 }
 *   ]}
 *   dataKeys={[{ x: 'date', y: 'value', name: 'Documents Processed' }]}
 *   title="Processing Volume"
 *   height={400}
 * />
 * ```
 *
 * Bar Chart:
 * ```tsx
 * <ChartWidget
 *   type="bar"
 *   data={monthlyData}
 *   dataKeys={[
 *     { y: 'processed', name: 'Processed' },
 *     { y: 'failed', name: 'Failed' }
 *   ]}
 *   colors={['#3b82f6', '#ef4444']}
 * />
 * ```
 *
 * Pie Chart:
 * ```tsx
 * <ChartWidget
 *   type="pie"
 *   data={documentTypes}
 *   dataKeys={[{ y: 'count', name: 'name' }]}
 *   title="Document Types"
 * />
 * ```
 *
 * @module components/analytics/ChartWidget
 */

import React, { memo } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import clsx from 'clsx';
import { FileQuestion } from 'lucide-react';

/**
 * Supported chart types
 */
export type ChartType = 'line' | 'bar' | 'pie' | 'area';

/**
 * Generic chart data interface with flexible structure
 * Allows any data shape while maintaining type safety
 */
export interface ChartData {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [key: string]: any;
}

/**
 * Data key configuration for chart rendering
 */
interface DataKeyConfig {
  /** Key for X-axis data (optional, not used for pie charts) */
  x?: string;
  /** Key(s) for Y-axis data - single string or array for multiple series */
  y: string | string[];
  /** Display name for the data series */
  name?: string;
}

/**
 * ChartWidget component props
 *
 * @template T - Type of data objects in the data array
 */
export interface ChartWidgetProps<T extends ChartData = ChartData> {
  /** Type of chart to render */
  type: ChartType;

  /** Array of data objects to visualize */
  data: T[];

  /** Configuration for data keys and series */
  dataKeys: DataKeyConfig[];

  /** Optional chart title */
  title?: string;

  /** Optional chart description */
  description?: string;

  /** Loading state flag */
  loading?: boolean;

  /** Error message to display */
  error?: string;

  /** Chart height in pixels (default: 300) */
  height?: number;

  /** Chart width (default: '100%') */
  width?: string;

  /** Show legend (default: true) */
  showLegend?: boolean;

  /** Show grid lines (default: true, not applicable for pie charts) */
  showGrid?: boolean;

  /** Show tooltip on hover (default: true) */
  showTooltip?: boolean;

  /** Custom color palette for chart series */
  colors?: string[];

  /** Custom tooltip component */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  customTooltip?: any;

  /** Callback when data point is clicked */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  onDataPointClick?: (data: any) => void;

  /** Message to display when data is empty */
  emptyMessage?: string;

  /** Label for X-axis */
  xAxisLabel?: string;

  /** Label for Y-axis */
  yAxisLabel?: string;

  /** Format function for Y-axis values */
  formatYAxis?: (value: number) => string;

  /** Format function for tooltip values */
  formatTooltip?: (value: number, name: string) => string;

  /** Additional CSS classes for container */
  className?: string;
}

/**
 * Default color palette based on TailwindCSS colors
 */
const DEFAULT_COLORS = [
  '#3b82f6', // blue-600
  '#10b981', // green-600
  '#f59e0b', // yellow-600
  '#ef4444', // red-600
  '#8b5cf6', // purple-600
  '#ec4899', // pink-600
  '#14b8a6', // teal-600
  '#f97316', // orange-600
];

/**
 * ChartWidget Component
 *
 * A flexible, reusable chart component built on Recharts that supports
 * multiple visualization types with consistent styling and behavior.
 *
 * @component
 */
const ChartWidget = <T extends ChartData = ChartData>({
  type,
  data,
  dataKeys,
  title,
  description,
  loading = false,
  error,
  height = 300,
  width = '100%',
  showLegend = true,
  showGrid = true,
  showTooltip = true,
  colors = DEFAULT_COLORS,
  customTooltip,
  onDataPointClick,
  emptyMessage = 'No data available',
  xAxisLabel,
  yAxisLabel,
  formatYAxis,
  formatTooltip,
  className,
}: ChartWidgetProps<T>): React.ReactElement => {
  /**
   * Render loading skeleton with animated pulse effect
   */
  if (loading) {
    return (
      <div
        className={clsx('border border-gray-200 rounded-lg shadow-sm p-6 bg-white', className)}
        role="status"
        aria-live="polite"
        aria-label="Loading chart data"
      >
        {title && (
          <div className="mb-4">
            <div className="h-6 bg-gray-200 rounded animate-pulse w-1/3 mb-2" />
            {description && <div className="h-4 bg-gray-200 rounded animate-pulse w-1/2" />}
          </div>
        )}
        <div
          className="bg-gray-200 rounded animate-pulse"
          style={{ height: `${height}px` }}
          aria-hidden="true"
        />
      </div>
    );
  }

  /**
   * Render error state with icon and message
   */
  if (error) {
    return (
      <div
        className={clsx('border border-red-200 rounded-lg shadow-sm p-6 bg-red-50', className)}
        role="alert"
        aria-live="assertive"
      >
        {title && <h3 className="text-lg font-semibold text-gray-900 mb-2">{title}</h3>}
        <div className="flex items-center justify-center" style={{ height: `${height}px` }}>
          <div className="text-center">
            <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-red-100 mb-4">
              <svg
                className="w-6 h-6 text-red-600"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
                aria-hidden="true"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            </div>
            <p className="text-red-800 font-medium">Error loading chart</p>
            <p className="text-red-600 text-sm mt-1">{error}</p>
          </div>
        </div>
      </div>
    );
  }

  /**
   * Render empty state when no data is available
   */
  if (!data || data.length === 0) {
    return (
      <div
        className={clsx('border border-gray-200 rounded-lg shadow-sm p-6 bg-white', className)}
        role="status"
        aria-live="polite"
      >
        {title && (
          <div className="mb-4">
            <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
            {description && <p className="text-sm text-gray-600 mt-1">{description}</p>}
          </div>
        )}
        <div className="flex items-center justify-center" style={{ height: `${height}px` }}>
          <div className="text-center">
            <FileQuestion className="w-12 h-12 text-gray-400 mx-auto mb-4" aria-hidden="true" />
            <p className="text-gray-600 font-medium">{emptyMessage}</p>
            <p className="text-gray-500 text-sm mt-1">Try adjusting your filters or date range</p>
          </div>
        </div>
      </div>
    );
  }

  /**
   * Default tooltip formatter
   */
  const defaultTooltipFormatter = (value: number, name: string): string => {
    if (formatTooltip) {
      return formatTooltip(value, name);
    }
    return typeof value === 'number' ? value.toLocaleString() : String(value);
  };

  /**
   * Default Y-axis formatter
   */
  const defaultYAxisFormatter = (value: number): string => {
    if (formatYAxis) {
      return formatYAxis(value);
    }
    return typeof value === 'number' ? value.toLocaleString() : String(value);
  };

  /**
   * Render line chart
   */
  const renderLineChart = (): React.ReactElement => {
    const xKey = dataKeys[0]?.x ?? 'x';

    return (
      <LineChart
        data={data}
        margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
        onClick={onDataPointClick}
      >
        {showGrid && <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" opacity={0.5} />}
        <XAxis
          dataKey={xKey}
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          label={
            xAxisLabel ? { value: xAxisLabel, position: 'insideBottom', offset: -5 } : undefined
          }
        />
        <YAxis
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          tickFormatter={defaultYAxisFormatter}
          label={yAxisLabel ? { value: yAxisLabel, angle: -90, position: 'insideLeft' } : undefined}
        />
        {showTooltip && (
          <Tooltip
            formatter={defaultTooltipFormatter}
            contentStyle={{
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              border: '1px solid #e5e7eb',
              borderRadius: '0.5rem',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            }}
            cursor={{ stroke: '#9ca3af', strokeWidth: 1 }}
            // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
            content={customTooltip}
          />
        )}
        {showLegend && <Legend wrapperStyle={{ paddingTop: '20px' }} />}
        {dataKeys.map((keyConfig, index) => {
          const keys = Array.isArray(keyConfig.y) ? keyConfig.y : [keyConfig.y];
          return keys.map((key, keyIndex) => (
            <Line
              key={`${key}-${keyIndex}`}
              type="monotone"
              dataKey={key}
              name={keyConfig.name ?? key}
              stroke={colors[index * keys.length + keyIndex] ?? colors[index] ?? '#3b82f6'}
              strokeWidth={2}
              dot={{ r: 4, strokeWidth: 2 }}
              activeDot={{ r: 6 }}
            />
          ));
        })}
      </LineChart>
    );
  };

  /**
   * Render bar chart
   */
  const renderBarChart = (): React.ReactElement => {
    const xKey = dataKeys[0]?.x ?? 'x';

    return (
      <BarChart
        data={data}
        margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
        onClick={onDataPointClick}
      >
        {showGrid && <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" opacity={0.5} />}
        <XAxis
          dataKey={xKey}
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          label={
            xAxisLabel ? { value: xAxisLabel, position: 'insideBottom', offset: -5 } : undefined
          }
        />
        <YAxis
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          tickFormatter={defaultYAxisFormatter}
          label={yAxisLabel ? { value: yAxisLabel, angle: -90, position: 'insideLeft' } : undefined}
        />
        {showTooltip && (
          <Tooltip
            formatter={defaultTooltipFormatter}
            contentStyle={{
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              border: '1px solid #e5e7eb',
              borderRadius: '0.5rem',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            }}
            cursor={{ fill: 'rgba(156, 163, 175, 0.2)' }}
            // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
            content={customTooltip}
          />
        )}
        {showLegend && <Legend wrapperStyle={{ paddingTop: '20px' }} />}
        {dataKeys.map((keyConfig, index) => {
          const keys = Array.isArray(keyConfig.y) ? keyConfig.y : [keyConfig.y];
          const BORDER_RADIUS_TOP = 4;
          return keys.map((key, keyIndex) => (
            <Bar
              key={`${key}-${keyIndex}`}
              dataKey={key}
              name={keyConfig.name ?? key}
              fill={colors[index * keys.length + keyIndex] ?? colors[index] ?? '#3b82f6'}
              radius={[BORDER_RADIUS_TOP, BORDER_RADIUS_TOP, 0, 0]}
              maxBarSize={60}
            />
          ));
        })}
      </BarChart>
    );
  };

  /**
   * Render pie chart
   */
  const renderPieChart = (): React.ReactElement => {
    const yKey = (
      Array.isArray(dataKeys[0]?.y) ? dataKeys[0].y[0] : (dataKeys[0]?.y ?? 'value')
    ) as string;
    const nameKey = dataKeys[0]?.name ?? dataKeys[0]?.x ?? 'name';
    const PIE_OUTER_RADIUS_DIVISOR = 3;

    return (
      <PieChart>
        <Pie
          data={data}
          dataKey={yKey}
          nameKey={nameKey}
          cx="50%"
          cy="50%"
          outerRadius={height / PIE_OUTER_RADIUS_DIVISOR}
          label={(entry: { percent?: number }) => {
            // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
            const percent = entry.percent ?? 0;
            return `${(percent * 100).toFixed(0)}%`;
          }}
          labelLine={{ stroke: '#6b7280' }}
          onClick={onDataPointClick}
        >
          {data.map((_entry, index) => (
            <Cell key={`cell-${index}`} fill={colors[index % colors.length]} />
          ))}
        </Pie>
        {showTooltip && (
          <Tooltip
            formatter={defaultTooltipFormatter}
            contentStyle={{
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              border: '1px solid #e5e7eb',
              borderRadius: '0.5rem',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            }}
            // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
            content={customTooltip}
          />
        )}
        {showLegend && <Legend verticalAlign="bottom" height={36} />}
      </PieChart>
    );
  };

  /**
   * Render area chart
   */
  const renderAreaChart = (): React.ReactElement => {
    const xKey = dataKeys[0]?.x ?? 'x';

    return (
      <AreaChart
        data={data}
        margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
        onClick={onDataPointClick}
      >
        <defs>
          {dataKeys.map((keyConfig, index) => {
            const keys = Array.isArray(keyConfig.y) ? keyConfig.y : [keyConfig.y];
            return keys.map((key, keyIndex) => {
              const colorIndex = index * keys.length + keyIndex;
              const color = colors[colorIndex] ?? colors[index] ?? '#3b82f6';
              return (
                <linearGradient
                  key={`gradient-${key}-${keyIndex}`}
                  id={`gradient-${key}`}
                  x1="0"
                  y1="0"
                  x2="0"
                  y2="1"
                >
                  <stop offset="5%" stopColor={color} stopOpacity={0.8} />
                  <stop offset="95%" stopColor={color} stopOpacity={0.1} />
                </linearGradient>
              );
            });
          })}
        </defs>
        {showGrid && <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" opacity={0.5} />}
        <XAxis
          dataKey={xKey}
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          label={
            xAxisLabel ? { value: xAxisLabel, position: 'insideBottom', offset: -5 } : undefined
          }
        />
        <YAxis
          stroke="#6b7280"
          tick={{ fill: '#6b7280', fontSize: 12 }}
          tickFormatter={defaultYAxisFormatter}
          label={yAxisLabel ? { value: yAxisLabel, angle: -90, position: 'insideLeft' } : undefined}
        />
        {showTooltip && (
          <Tooltip
            formatter={defaultTooltipFormatter}
            contentStyle={{
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              border: '1px solid #e5e7eb',
              borderRadius: '0.5rem',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            }}
            cursor={{ stroke: '#9ca3af', strokeWidth: 1 }}
            {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              ...{ content: customTooltip }
            }
          />
        )}
        {showLegend && <Legend wrapperStyle={{ paddingTop: '20px' }} />}
        {dataKeys.map((keyConfig, index) => {
          const keys = Array.isArray(keyConfig.y) ? keyConfig.y : [keyConfig.y];
          return keys.map((key, keyIndex) => {
            const colorIndex = index * keys.length + keyIndex;
            const color = colors[colorIndex] ?? colors[index] ?? '#3b82f6';
            return (
              <Area
                key={`${key}-${keyIndex}`}
                type="monotone"
                dataKey={key}
                name={keyConfig.name ?? key}
                stroke={color}
                fill={`url(#gradient-${key})`}
                strokeWidth={2}
                aria-label={`Area chart for ${keyConfig.name ?? key}`}
              />
            );
          });
        })}
      </AreaChart>
    );
  };

  /**
   * Select chart renderer based on type
   */
  const renderChart = (): React.ReactElement => {
    switch (type) {
      case 'line':
        return renderLineChart();
      case 'bar':
        return renderBarChart();
      case 'pie':
        return renderPieChart();
      case 'area':
        return renderAreaChart();
      default:
        // Fallback to line chart if invalid type provided
        return renderLineChart();
    }
  };

  /**
   * Generate ARIA label describing the chart
   */
  const getAriaLabel = (): string => {
    const chartTypeLabel = type.charAt(0).toUpperCase() + type.slice(1);
    const dataPointCount = data.length;
    const titleLabel = title !== undefined ? `${title}: ` : '';
    return `${titleLabel}${chartTypeLabel} chart with ${dataPointCount} data ${dataPointCount === 1 ? 'point' : 'points'}`;
  };

  return (
    <div
      className={clsx('border border-gray-200 rounded-lg shadow-sm p-6 bg-white', className)}
      role="img"
      aria-label={getAriaLabel()}
    >
      {/* Chart Header */}
      {(title ?? description) && (
        <div className="mb-4">
          {title && <h3 className="text-lg font-semibold text-gray-900">{title}</h3>}
          {description && <p className="text-sm text-gray-600 mt-1">{description}</p>}
        </div>
      )}

      {/* Chart Container */}
      <ResponsiveContainer width={width} height={height}>
        {renderChart()}
      </ResponsiveContainer>
    </div>
  );
};

/**
 * Export memoized component for performance optimization
 * Prevents unnecessary re-renders when props haven't changed
 */
export default memo(ChartWidget) as typeof ChartWidget;
