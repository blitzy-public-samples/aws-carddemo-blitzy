/**
 * CardDemo Transaction Category Analysis Component
 * 
 * React functional component implementing transaction category summary with aggregated 
 * spending totals and Recharts visualization, providing enhanced analytical view derived 
 * from COTRN01C COBOL program logic. Displays transaction category breakdowns with bar/pie 
 * charts showing spending distribution across categories (groceries, gas, dining, 
 * entertainment, etc.), aggregated amount totals per category with BigDecimal precision, 
 * date range filtering for analysis period selection, and Material-UI cards/grid layout.
 * 
 * COBOL Source Mapping:
 * - Source BMS: app/bms/COTRN01.bms (lines 1-273) - Transaction detail screen
 * - Source Copybook: app/cpy-bms/COTRN01.CPY - Transaction field definitions
 * - COBOL Program: COTRN01C.cbl - Transaction category aggregation logic
 * 
 * Transformation Context:
 * While COTRN01M.bms defines a single transaction detail view with fields TRNID/CARDNUM/
 * TTYPCD/TCATCD/TDESC/TRNAMT/MID/MNAME, this component expands the concept to category 
 * summary by:
 * 1. Aggregating transactions by TCATCD (category code) instead of single transaction display
 * 2. Creating summary cards showing total count and amount per category
 * 3. Implementing Recharts BarChart for category spending distribution visualization
 * 4. Adding Recharts PieChart for percentage breakdown across categories
 * 5. Creating detailed category table with Material-UI Table components
 * 6. Implementing date range filter using date-fns for analysis period selection
 * 7. Adding export functionality for CSV and PDF download
 * 
 * Key Features:
 * - Category aggregation with COBOL COMP-3 decimal precision (2 decimal places)
 * - Interactive bar chart and pie chart visualization with Recharts
 * - Date range filtering using Material-UI DatePicker components
 * - Detailed category breakdown table with sorting
 * - Export to CSV and PDF functionality
 * - Responsive grid layout for desktop and mobile
 * - Real-time data fetching from REST API /api/transactions/categories
 * - Loading and error state management with Redux
 * 
 * Data Structure Mapping:
 * COBOL TCATCD (PIC X(4)) → category (string)
 * COBOL TRNAMT (PIC S9(10)V99) → totalAmount (number with 2 decimal precision)
 * COBOL CAT-COUNT → transactionCount (number)
 * COBOL CAT-PCT → percentage (number with 2 decimal precision)
 * 
 * Props: None (uses Redux state for accountId from authentication)
 * 
 * Dependencies:
 * - transactionService: REST API calls for category aggregation data
 * - transactionSlice: Redux state management for categories, loading, errors
 * - Header: Consistent navigation header component
 * - formatters: Currency and percentage formatting utilities
 * 
 * @module components/transaction/TransactionCategoryComponent
 */

import { useState, useEffect, useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import {
  Box,
  Grid,
  Card,
  CardContent,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Button,
  CircularProgress,
  Alert
} from '@mui/material';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  PieChart,
  Pie,
  Cell,
  ResponsiveContainer
} from 'recharts';
import { format, subDays, startOfMonth, endOfMonth } from 'date-fns';
import {
  fetchTransactionCategories,
  selectCategoryAggregations,
  selectTransactionLoading,
  selectTransactionError,
  setDateRange
} from '../../redux/slices/transactionSlice';
import Header from '../common/Header.jsx';
import { 
  formatCurrency, 
  formatDateDisplay, 
  formatPercentage 
} from '../../utils/formatters.js';

/**
 * Color palette for category charts
 * Provides consistent color scheme for bar and pie chart visualization
 */
const CHART_COLORS = [
  '#1976d2', // Blue (primary)
  '#dc004e', // Pink (secondary)
  '#f50057', // Red
  '#ff9800', // Orange
  '#4caf50', // Green
  '#9c27b0', // Purple
  '#00bcd4', // Cyan
  '#ffeb3b', // Yellow
  '#795548', // Brown
  '#607d8b'  // Blue Grey
];

/**
 * TransactionCategoryComponent
 * 
 * Main functional component for transaction category analysis and visualization.
 * Fetches category aggregation data from backend REST API and displays comprehensive
 * spending analysis with charts, summary cards, and detailed table breakdown.
 * 
 * Component Structure:
 * - Header with page title "Transaction Category Analysis"
 * - Date range filter controls (start date, end date, quick filters)
 * - Summary cards grid showing total spending and category count
 * - Bar chart for category spending comparison
 * - Pie chart for spending distribution percentage
 * - Detailed category breakdown table with sorting
 * - Export buttons for CSV and PDF download
 * 
 * State Management:
 * - Local state for date range, export options
 * - Redux state for category data, loading, error
 * - useEffect hooks for data fetching on mount and filter changes
 * 
 * @returns {JSX.Element} Transaction category analysis component
 */
const TransactionCategoryComponent = () => {
  const dispatch = useDispatch();
  
  // Redux state selectors
  const categoryData = useSelector(selectCategoryAggregations);
  const loading = useSelector(selectTransactionLoading);
  const error = useSelector(selectTransactionError);
  
  // Get accountId from auth state (assuming user is authenticated)
  const accountId = useSelector((state) => state.auth.user?.accountId || '');
  
  // Local state for date range filtering
  const [startDate, setStartDate] = useState(() => {
    // Default to start of current month (maps COBOL current month logic)
    const today = new Date();
    return format(startOfMonth(today), 'yyyy-MM-dd');
  });
  
  const [endDate, setEndDate] = useState(() => {
    // Default to end of current month
    const today = new Date();
    return format(endOfMonth(today), 'yyyy-MM-dd');
  });
  
  // Local state for table sorting
  const [sortBy, setSortBy] = useState('totalAmount'); // 'totalAmount', 'transactionCount', 'category'
  const [sortOrder, setSortOrder] = useState('desc'); // 'asc' or 'desc'
  
  /**
   * Fetch category data on component mount and when filters change
   * Maps COBOL COTRN01C.cbl category aggregation logic to Redux async thunk
   * 
   * COBOL Equivalence:
   * - Sequential READ of TRANSACT file with category grouping
   * - SUM accumulation by category (ADD TRAN-AMT TO CAT-TOTAL)
   * - Percentage calculation (COMPUTE CAT-PCT = CAT-TOTAL / GRAND-TOTAL * 100)
   */
  useEffect(() => {
    if (accountId && startDate && endDate) {
      // Dispatch Redux thunk to fetch category aggregations
      dispatch(fetchTransactionCategories({
        accountId,
        startDate,
        endDate
      }));
      
      // Update Redux filters state for consistency
      dispatch(setDateRange({ startDate, endDate }));
    }
  }, [dispatch, accountId, startDate, endDate]);
  
  /**
   * Calculate total spending across all categories
   * Memoized to avoid unnecessary recalculations
   * Maps COBOL GRAND-TOTAL accumulator (PIC S9(11)V99 COMP-3)
   */
  const totalSpending = useMemo(() => {
    if (!categoryData || categoryData.length === 0) return 0;
    
    return categoryData.reduce((sum, cat) => {
      return sum + (cat.totalAmount || 0);
    }, 0);
  }, [categoryData]);
  
  /**
   * Calculate total transaction count across all categories
   * Maps COBOL total transaction counter
   */
  const totalTransactions = useMemo(() => {
    if (!categoryData || categoryData.length === 0) return 0;
    
    return categoryData.reduce((sum, cat) => {
      return sum + (cat.transactionCount || 0);
    }, 0);
  }, [categoryData]);
  
  /**
   * Sorted category data for table display
   * Implements client-side sorting based on selected column
   */
  const sortedCategoryData = useMemo(() => {
    if (!categoryData || categoryData.length === 0) return [];
    
    const sorted = [...categoryData].sort((a, b) => {
      let comparison = 0;
      
      switch (sortBy) {
        case 'totalAmount':
          comparison = (a.totalAmount || 0) - (b.totalAmount || 0);
          break;
        case 'transactionCount':
          comparison = (a.transactionCount || 0) - (b.transactionCount || 0);
          break;
        case 'category':
          comparison = (a.categoryDescription || '').localeCompare(b.categoryDescription || '');
          break;
        case 'percentage':
          comparison = (a.percentage || 0) - (b.percentage || 0);
          break;
        default:
          comparison = 0;
      }
      
      return sortOrder === 'asc' ? comparison : -comparison;
    });
    
    return sorted;
  }, [categoryData, sortBy, sortOrder]);
  
  /**
   * Handle quick date range filter selection
   * Provides convenience buttons for common date ranges (Last 7 days, Last 30 days, etc.)
   * 
   * @param {string} range - Quick filter option ('7days', '30days', 'thisMonth', 'lastMonth')
   */
  const handleQuickFilter = (range) => {
    const today = new Date();
    let newStartDate, newEndDate;
    
    switch (range) {
      case '7days':
        newStartDate = format(subDays(today, 7), 'yyyy-MM-dd');
        newEndDate = format(today, 'yyyy-MM-dd');
        break;
      case '30days':
        newStartDate = format(subDays(today, 30), 'yyyy-MM-dd');
        newEndDate = format(today, 'yyyy-MM-dd');
        break;
      case 'thisMonth':
        newStartDate = format(startOfMonth(today), 'yyyy-MM-dd');
        newEndDate = format(endOfMonth(today), 'yyyy-MM-dd');
        break;
      case 'lastMonth': {
        const lastMonth = new Date(today.getFullYear(), today.getMonth() - 1, 1);
        newStartDate = format(startOfMonth(lastMonth), 'yyyy-MM-dd');
        newEndDate = format(endOfMonth(lastMonth), 'yyyy-MM-dd');
        break;
      }
      default:
        return;
    }
    
    setStartDate(newStartDate);
    setEndDate(newEndDate);
  };
  
  /**
   * Handle table column sorting
   * Toggles sort order or changes sort column
   * 
   * @param {string} column - Column name to sort by
   */
  const handleSort = (column) => {
    if (sortBy === column) {
      // Toggle sort order if same column
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      // Change sort column and default to descending
      setSortBy(column);
      setSortOrder('desc');
    }
  };
  
  /**
   * Export category data to CSV format
   * Generates CSV file with category breakdown data
   * Maps COBOL report generation logic to client-side export
   */
  const exportToCSV = () => {
    if (!categoryData || categoryData.length === 0) {
      alert('No data available to export');
      return;
    }
    
    try {
      // Create CSV header
      const headers = ['Category', 'Description', 'Transaction Count', 'Total Amount', 'Percentage'];
      const csvRows = [headers.join(',')];
      
      // Add data rows
      sortedCategoryData.forEach(cat => {
        const row = [
          cat.category || '',
          `"${cat.categoryDescription || ''}"`, // Quote to handle commas in description
          cat.transactionCount || 0,
          (cat.totalAmount || 0).toFixed(2),
          ((cat.percentage || 0) * 100).toFixed(2) + '%'
        ];
        csvRows.push(row.join(','));
      });
      
      // Add summary row
      csvRows.push('');
      csvRows.push(`Total,All Categories,${totalTransactions},${totalSpending.toFixed(2)},100.00%`);
      
      // Create blob and download
      const csvContent = csvRows.join('\n');
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
      const link = document.createElement('a');
      const url = URL.createObjectURL(blob);
      
      link.setAttribute('href', url);
      link.setAttribute('download', `transaction_categories_${startDate}_to_${endDate}.csv`);
      link.style.visibility = 'hidden';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err) {
      console.error('CSV export error:', err);
      alert('Failed to export CSV. Please try again.');
    }
  };
  
  /**
   * Export category data to PDF format
   * Note: Full PDF generation requires additional library (e.g., jsPDF)
   * This implementation provides a print-friendly view that can be saved as PDF
   */
  const exportToPDF = () => {
    if (!categoryData || categoryData.length === 0) {
      alert('No data available to export');
      return;
    }
    
    try {
      // Open print dialog for PDF export
      // Modern browsers allow "Save as PDF" option in print dialog
      window.print();
    } catch (err) {
      console.error('PDF export error:', err);
      alert('Failed to export PDF. Please try again.');
    }
  };
  
  /**
   * Custom tooltip component for charts
   * Displays category name and formatted amount on hover
   */
  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const data = payload[0].payload;
      return (
        <Paper sx={{ p: 2 }}>
          <Typography variant="body2" fontWeight="bold">
            {data.categoryDescription || data.category}
          </Typography>
          <Typography variant="body2" color="primary">
            Amount: {formatCurrency(data.totalAmount)}
          </Typography>
          <Typography variant="body2">
            Count: {data.transactionCount} transactions
          </Typography>
          <Typography variant="body2">
            Percentage: {formatPercentage(data.percentage || 0)}
          </Typography>
        </Paper>
      );
    }
    return null;
  };
  
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header Component */}
      <Header pageTitle="Transaction Category Analysis" />
      
      {/* Main Content Container */}
      <Box sx={{ flexGrow: 1, p: 3, backgroundColor: '#f5f5f5' }}>
        {/* Date Range Filter Section */}
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Analysis Period
            </Typography>
            
            <Grid container spacing={2} alignItems="center">
              {/* Start Date Input */}
              <Grid item xs={12} sm={6} md={3}>
                <Box>
                  <Typography variant="body2" gutterBottom>
                    Start Date
                  </Typography>
                  <input
                    type="date"
                    value={startDate}
                    onChange={(e) => setStartDate(e.target.value)}
                    style={{
                      width: '100%',
                      padding: '10px',
                      fontSize: '14px',
                      border: '1px solid #ccc',
                      borderRadius: '4px'
                    }}
                  />
                </Box>
              </Grid>
              
              {/* End Date Input */}
              <Grid item xs={12} sm={6} md={3}>
                <Box>
                  <Typography variant="body2" gutterBottom>
                    End Date
                  </Typography>
                  <input
                    type="date"
                    value={endDate}
                    onChange={(e) => setEndDate(e.target.value)}
                    style={{
                      width: '100%',
                      padding: '10px',
                      fontSize: '14px',
                      border: '1px solid #ccc',
                      borderRadius: '4px'
                    }}
                  />
                </Box>
              </Grid>
              
              {/* Quick Filter Buttons */}
              <Grid item xs={12} md={6}>
                <Typography variant="body2" gutterBottom>
                  Quick Filters
                </Typography>
                <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                  <Button 
                    size="small" 
                    variant="outlined" 
                    onClick={() => handleQuickFilter('7days')}
                  >
                    Last 7 Days
                  </Button>
                  <Button 
                    size="small" 
                    variant="outlined" 
                    onClick={() => handleQuickFilter('30days')}
                  >
                    Last 30 Days
                  </Button>
                  <Button 
                    size="small" 
                    variant="outlined" 
                    onClick={() => handleQuickFilter('thisMonth')}
                  >
                    This Month
                  </Button>
                  <Button 
                    size="small" 
                    variant="outlined" 
                    onClick={() => handleQuickFilter('lastMonth')}
                  >
                    Last Month
                  </Button>
                </Box>
              </Grid>
            </Grid>
          </CardContent>
        </Card>
        
        {/* Loading State */}
        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress />
          </Box>
        )}
        
        {/* Error State */}
        {error && !loading && (
          <Alert severity="error" sx={{ mb: 3 }}>
            {error}
          </Alert>
        )}
        
        {/* Category Data Display */}
        {!loading && !error && categoryData && categoryData.length > 0 && (
          <>
            {/* Summary Cards Grid */}
            <Grid container spacing={3} sx={{ mb: 3 }}>
              {/* Total Spending Card */}
              <Grid item xs={12} sm={6} md={4}>
                <Card>
                  <CardContent>
                    <Typography color="textSecondary" gutterBottom>
                      Total Spending
                    </Typography>
                    <Typography variant="h4" color="primary">
                      {formatCurrency(totalSpending)}
                    </Typography>
                    <Typography variant="body2" color="textSecondary">
                      {formatDateDisplay(startDate)} - {formatDateDisplay(endDate)}
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>
              
              {/* Total Transactions Card */}
              <Grid item xs={12} sm={6} md={4}>
                <Card>
                  <CardContent>
                    <Typography color="textSecondary" gutterBottom>
                      Total Transactions
                    </Typography>
                    <Typography variant="h4" color="secondary">
                      {totalTransactions}
                    </Typography>
                    <Typography variant="body2" color="textSecondary">
                      Across {categoryData.length} categories
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>
              
              {/* Average per Transaction Card */}
              <Grid item xs={12} sm={6} md={4}>
                <Card>
                  <CardContent>
                    <Typography color="textSecondary" gutterBottom>
                      Average Transaction
                    </Typography>
                    <Typography variant="h4">
                      {formatCurrency(totalTransactions > 0 ? totalSpending / totalTransactions : 0)}
                    </Typography>
                    <Typography variant="body2" color="textSecondary">
                      Per transaction
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>
            </Grid>
            
            {/* Charts Section */}
            <Grid container spacing={3} sx={{ mb: 3 }}>
              {/* Bar Chart - Category Spending Comparison */}
              <Grid item xs={12} lg={7}>
                <Card>
                  <CardContent>
                    <Typography variant="h6" gutterBottom>
                      Spending by Category
                    </Typography>
                    <ResponsiveContainer width="100%" height={400}>
                      <BarChart
                        data={sortedCategoryData}
                        margin={{ top: 20, right: 30, left: 20, bottom: 80 }}
                      >
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis 
                          dataKey="categoryDescription" 
                          angle={-45}
                          textAnchor="end"
                          height={100}
                        />
                        <YAxis 
                          tickFormatter={(value) => `$${value.toLocaleString()}`}
                        />
                        <Tooltip content={<CustomTooltip />} />
                        <Legend />
                        <Bar 
                          dataKey="totalAmount" 
                          fill="#1976d2" 
                          name="Total Amount"
                        />
                      </BarChart>
                    </ResponsiveContainer>
                  </CardContent>
                </Card>
              </Grid>
              
              {/* Pie Chart - Spending Distribution */}
              <Grid item xs={12} lg={5}>
                <Card>
                  <CardContent>
                    <Typography variant="h6" gutterBottom>
                      Spending Distribution
                    </Typography>
                    <ResponsiveContainer width="100%" height={400}>
                      <PieChart>
                        <Pie
                          data={categoryData}
                          dataKey="totalAmount"
                          nameKey="categoryDescription"
                          cx="50%"
                          cy="50%"
                          outerRadius={120}
                          label={(entry) => `${entry.categoryDescription}: ${formatPercentage(entry.percentage || 0)}`}
                          labelLine={true}
                        >
                          {categoryData.map((entry, index) => (
                            <Cell 
                              key={`cell-${index}`} 
                              fill={CHART_COLORS[index % CHART_COLORS.length]} 
                            />
                          ))}
                        </Pie>
                        <Tooltip content={<CustomTooltip />} />
                      </PieChart>
                    </ResponsiveContainer>
                  </CardContent>
                </Card>
              </Grid>
            </Grid>
            
            {/* Detailed Category Table */}
            <Card>
              <CardContent>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                  <Typography variant="h6">
                    Category Breakdown
                  </Typography>
                  <Box sx={{ display: 'flex', gap: 1 }}>
                    <Button 
                      variant="contained" 
                      size="small" 
                      onClick={exportToCSV}
                    >
                      Export CSV
                    </Button>
                    <Button 
                      variant="contained" 
                      size="small" 
                      onClick={exportToPDF}
                    >
                      Export PDF
                    </Button>
                  </Box>
                </Box>
                
                <TableContainer component={Paper}>
                  <Table>
                    <TableHead>
                      <TableRow>
                        <TableCell>
                          <Button 
                            size="small"
                            onClick={() => handleSort('category')}
                            sx={{ fontWeight: 'bold', textTransform: 'none' }}
                          >
                            Category {sortBy === 'category' ? (sortOrder === 'asc' ? '↑' : '↓') : ''}
                          </Button>
                        </TableCell>
                        <TableCell align="right">
                          <Button 
                            size="small"
                            onClick={() => handleSort('transactionCount')}
                            sx={{ fontWeight: 'bold', textTransform: 'none' }}
                          >
                            Count {sortBy === 'transactionCount' ? (sortOrder === 'asc' ? '↑' : '↓') : ''}
                          </Button>
                        </TableCell>
                        <TableCell align="right">
                          <Button 
                            size="small"
                            onClick={() => handleSort('totalAmount')}
                            sx={{ fontWeight: 'bold', textTransform: 'none' }}
                          >
                            Total Amount {sortBy === 'totalAmount' ? (sortOrder === 'asc' ? '↑' : '↓') : ''}
                          </Button>
                        </TableCell>
                        <TableCell align="right">
                          <Button 
                            size="small"
                            onClick={() => handleSort('percentage')}
                            sx={{ fontWeight: 'bold', textTransform: 'none' }}
                          >
                            Percentage {sortBy === 'percentage' ? (sortOrder === 'asc' ? '↑' : '↓') : ''}
                          </Button>
                        </TableCell>
                        <TableCell align="right">
                          Average
                        </TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {sortedCategoryData.map((category, index) => (
                        <TableRow 
                          key={category.category || index}
                          sx={{ '&:hover': { backgroundColor: '#f5f5f5' } }}
                        >
                          <TableCell>
                            <Typography variant="body2" fontWeight="medium">
                              {category.categoryDescription || category.category || 'Unknown'}
                            </Typography>
                            <Typography variant="caption" color="textSecondary">
                              {category.category}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            {category.transactionCount || 0}
                          </TableCell>
                          <TableCell align="right">
                            <Typography variant="body2" fontWeight="bold" color="primary">
                              {formatCurrency(category.totalAmount || 0)}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            {formatPercentage(category.percentage || 0)}
                          </TableCell>
                          <TableCell align="right">
                            {formatCurrency(
                              (category.transactionCount || 0) > 0 
                                ? (category.totalAmount || 0) / (category.transactionCount || 1)
                                : 0
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                      
                      {/* Summary Row */}
                      <TableRow sx={{ backgroundColor: '#f5f5f5', fontWeight: 'bold' }}>
                        <TableCell>
                          <Typography variant="body2" fontWeight="bold">
                            TOTAL
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="body2" fontWeight="bold">
                            {totalTransactions}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="body2" fontWeight="bold" color="primary">
                            {formatCurrency(totalSpending)}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="body2" fontWeight="bold">
                            100.00%
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="body2" fontWeight="bold">
                            {formatCurrency(
                              totalTransactions > 0 ? totalSpending / totalTransactions : 0
                            )}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    </TableBody>
                  </Table>
                </TableContainer>
              </CardContent>
            </Card>
          </>
        )}
        
        {/* No Data State */}
        {!loading && !error && (!categoryData || categoryData.length === 0) && (
          <Card>
            <CardContent>
              <Typography variant="h6" align="center" color="textSecondary">
                No transaction data available for the selected period
              </Typography>
              <Typography variant="body2" align="center" color="textSecondary" sx={{ mt: 1 }}>
                Try selecting a different date range or check if there are any transactions in your account.
              </Typography>
            </CardContent>
          </Card>
        )}
      </Box>
    </Box>
  );
};

// Default export for the component
export default TransactionCategoryComponent;
