/**
 * TransactionReportComponent.jsx
 * 
 * React functional component for generating and displaying transaction reports.
 * Transforms CORPT00M.bms 3270 report screen to modern Material-UI interface.
 * 
 * Features:
 * - Report type selection (Monthly, Yearly, Custom date range)
 * - Advanced filtering (card number, transaction type, category)
 * - Date validation matching COBOL CORPT00C business logic
 * - Report display with aggregated transaction data
 * - Export functionality (PDF/CSV)
 * - Responsive design with Material-UI components
 * 
 * Integration: GET /api/reports/transactions
 * Source: app/cbl/CORPT00C.cbl, app/bms/CORPT00.bms
 */

import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Container,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Select,
  Snackbar,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  TextField,
  Typography,
  Divider
} from '@mui/material';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import {
  PictureAsPdf as PdfIcon,
  TableChart as CsvIcon,
  Assessment as ReportIcon
} from '@mui/icons-material';
import axios from 'axios';

/**
 * TransactionReportComponent - Main functional component
 * Preserves COBOL CORPT00C.cbl business logic and BMS screen layout
 */
const TransactionReportComponent = () => {
  const navigate = useNavigate();

  // State management
  const [loading, setLoading] = useState(false);
  const [reportData, setReportData] = useState(null);
  const [transactionTypes, setTransactionTypes] = useState([]);
  const [categories, setCategories] = useState([]);
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
  const [snackbar, setSnackbar] = useState({
    open: false,
    message: '',
    severity: 'info'
  });
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  // Formik form validation schema matching COBOL validation logic (lines 259-426)
  const validationSchema = Yup.object({
    reportType: Yup.string()
      .required('Select a report type to print report...'),
    startDate: Yup.date()
      .nullable()
      .when('reportType', {
        is: 'Custom',
        then: (schema) => schema
          .required('Start Date - Month can NOT be empty...')
          .typeError('Start Date - Not a valid date...')
      }),
    endDate: Yup.date()
      .nullable()
      .when('reportType', {
        is: 'Custom',
        then: (schema) => schema
          .required('End Date - Month can NOT be empty...')
          .typeError('End Date - Not a valid date...')
          .min(Yup.ref('startDate'), 'End Date must be after Start Date')
      }),
    cardNumber: Yup.string()
      .matches(/^[0-9]*$/, 'Card Number must be numeric')
      .max(16, 'Card Number must be 16 digits or less'),
    transactionType: Yup.string(),
    category: Yup.string()
  });

  // Formik form initialization
  const formik = useFormik({
    initialValues: {
      reportType: '',
      startDate: null,
      endDate: null,
      cardNumber: '',
      transactionType: '',
      category: ''
    },
    validationSchema: validationSchema,
    onSubmit: () => {
      handleConfirmOpen();
    }
  });

  /**
   * Fetch reference data on component mount
   * Loads transaction types and categories for filter dropdowns
   */
  useEffect(() => {
    const fetchReferenceData = async () => {
      try {
        const token = localStorage.getItem('jwtToken');
        const config = {
          headers: { Authorization: `Bearer ${token}` }
        };

        // Fetch transaction types from GET /api/reference/transaction-types
        const typesResponse = await axios.get(
          '/api/reference/transaction-types',
          config
        );
        setTransactionTypes(typesResponse.data || []);

        // Fetch categories from GET /api/reference/categories
        const categoriesResponse = await axios.get(
          '/api/reference/categories',
          config
        );
        setCategories(categoriesResponse.data || []);
      } catch (error) {
        console.error('Error fetching reference data:', error);
        setSnackbar({
          open: true,
          message: 'Failed to load filter options',
          severity: 'error'
        });
      }
    };

    fetchReferenceData();
  }, []);

  /**
   * Handle report type change
   * Implements COBOL date calculation logic (lines 213-256)
   */
  const handleReportTypeChange = (event) => {
    const reportType = event.target.value;
    formik.setFieldValue('reportType', reportType);

    const now = new Date();
    
    if (reportType === 'Monthly') {
      // Monthly report: First day to last day of current month (lines 215-236)
      const startDate = new Date(now.getFullYear(), now.getMonth(), 1);
      const endDate = new Date(now.getFullYear(), now.getMonth() + 1, 0);
      
      formik.setFieldValue('startDate', startDate);
      formik.setFieldValue('endDate', endDate);
    } else if (reportType === 'Yearly') {
      // Yearly report: January 1 to December 31 of current year (lines 239-253)
      const startDate = new Date(now.getFullYear(), 0, 1);
      const endDate = new Date(now.getFullYear(), 11, 31);
      
      formik.setFieldValue('startDate', startDate);
      formik.setFieldValue('endDate', endDate);
    } else if (reportType === 'Custom') {
      // Custom report: User enters dates
      formik.setFieldValue('startDate', null);
      formik.setFieldValue('endDate', null);
    }
  };

  /**
   * Open confirmation dialog before report generation
   * Implements COBOL CONFIRM field logic (lines 464-474)
   */
  const handleConfirmOpen = () => {
    setConfirmDialogOpen(true);
  };

  /**
   * Handle confirmation dialog close with 'Cancel'
   * Matches COBOL 'N' confirmation (lines 480-483)
   */
  const handleConfirmCancel = () => {
    setConfirmDialogOpen(false);
  };

  /**
   * Generate report after confirmation
   * Implements COBOL SUBMIT-JOB-TO-INTRDR logic (lines 462-510)
   * Replaces JCL job submission with REST API call
   */
  const handleConfirmYes = async () => {
    setConfirmDialogOpen(false);
    setLoading(true);

    try {
      const token = localStorage.getItem('jwtToken');
      const userId = localStorage.getItem('userId');

      // Format dates to ISO 8601 for API (matching COBOL date structures lines 60-72)
      const formatDate = (date) => {
        if (!date) return null;
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      };

      const params = {
        reportType: formik.values.reportType,
        startDate: formatDate(formik.values.startDate),
        endDate: formatDate(formik.values.endDate),
        cardNumber: formik.values.cardNumber || null,
        transactionType: formik.values.transactionType || null,
        category: formik.values.category || null,
        userId: userId
      };

      // Call GET /api/reports/transactions with filters
      const response = await axios.get('/api/reports/transactions', {
        params: params,
        headers: { Authorization: `Bearer ${token}` }
      });

      setReportData(response.data);
      setPage(0);

      // Success message matching COBOL (lines 448-454)
      setSnackbar({
        open: true,
        message: `${formik.values.reportType} report generated successfully`,
        severity: 'success'
      });
    } catch (error) {
      console.error('Error generating report:', error);
      
      const errorMessage = error.response?.data?.message || 
        'Failed to generate report. Please try again.';
      
      setSnackbar({
        open: true,
        message: errorMessage,
        severity: 'error'
      });
    } finally {
      setLoading(false);
    }
  };

  /**
   * Export report to PDF format
   * Calls GET /api/reports/transactions/export?format=pdf
   */
  const handleExportPDF = async () => {
    try {
      setLoading(true);
      const token = localStorage.getItem('jwtToken');

      const formatDate = (date) => {
        if (!date) return null;
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      };

      const params = {
        format: 'pdf',
        reportType: formik.values.reportType,
        startDate: formatDate(formik.values.startDate),
        endDate: formatDate(formik.values.endDate),
        cardNumber: formik.values.cardNumber || null,
        transactionType: formik.values.transactionType || null,
        category: formik.values.category || null
      };

      const response = await axios.get('/api/reports/transactions/export', {
        params: params,
        headers: { Authorization: `Bearer ${token}` },
        responseType: 'blob'
      });

      // Create download link
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `transaction_report_${Date.now()}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();

      setSnackbar({
        open: true,
        message: 'Report exported to PDF successfully',
        severity: 'success'
      });
    } catch (error) {
      console.error('Error exporting PDF:', error);
      setSnackbar({
        open: true,
        message: 'Failed to export PDF',
        severity: 'error'
      });
    } finally {
      setLoading(false);
    }
  };

  /**
   * Export report to CSV format
   * Calls GET /api/reports/transactions/export?format=csv
   */
  const handleExportCSV = async () => {
    try {
      setLoading(true);
      const token = localStorage.getItem('jwtToken');

      const formatDate = (date) => {
        if (!date) return null;
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      };

      const params = {
        format: 'csv',
        reportType: formik.values.reportType,
        startDate: formatDate(formik.values.startDate),
        endDate: formatDate(formik.values.endDate),
        cardNumber: formik.values.cardNumber || null,
        transactionType: formik.values.transactionType || null,
        category: formik.values.category || null
      };

      const response = await axios.get('/api/reports/transactions/export', {
        params: params,
        headers: { Authorization: `Bearer ${token}` },
        responseType: 'blob'
      });

      // Create download link
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `transaction_report_${Date.now()}.csv`);
      document.body.appendChild(link);
      link.click();
      link.remove();

      setSnackbar({
        open: true,
        message: 'Report exported to CSV successfully',
        severity: 'success'
      });
    } catch (error) {
      console.error('Error exporting CSV:', error);
      setSnackbar({
        open: true,
        message: 'Failed to export CSV',
        severity: 'error'
      });
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle back navigation to menu
   * Implements PF3=Back functionality (DFHPF3 returns to COMEN01C line 188)
   */
  const handleBack = () => {
    navigate('/menu');
  };

  /**
   * Close snackbar notification
   */
  const handleSnackbarClose = () => {
    setSnackbar({ ...snackbar, open: false });
  };

  /**
   * Handle pagination change
   */
  const handleChangePage = (event, newPage) => {
    setPage(newPage);
  };

  const handleChangeRowsPerPage = (event) => {
    setRowsPerPage(parseInt(event.target.value, 10));
    setPage(0);
  };

  /**
   * Format currency for display with BigDecimal precision
   * Preserves COBOL COMP-3 decimal precision (2 decimal places)
   */
  const formatCurrency = (amount) => {
    if (amount === null || amount === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(amount);
  };

  /**
   * Format date for display
   */
  const formatDisplayDate = (dateString) => {
    if (!dateString) return '';
    const date = new Date(dateString);
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const year = date.getFullYear();
    return `${month}/${day}/${year}`;
  };

  return (
    <LocalizationProvider dateAdapter={AdapterDateFns}>
      <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
        {/* Header matching BMS screen title (lines 75-79) */}
        <Box sx={{ mb: 3 }}>
          <Typography variant="h4" component="h1" gutterBottom color="primary">
            <ReportIcon sx={{ mr: 1, verticalAlign: 'middle' }} />
            Transaction Reports
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Transaction: CR00 | Program: CORPT00C
          </Typography>
        </Box>

        {/* Filter Section */}
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Report Criteria
            </Typography>
            <Divider sx={{ mb: 2 }} />

            <form onSubmit={formik.handleSubmit}>
              <Grid container spacing={3}>
                {/* Report Type Selection - RadioGroup (lines 80-121) */}
                <Grid item xs={12}>
                  <FormControl 
                    component="fieldset" 
                    error={formik.touched.reportType && Boolean(formik.errors.reportType)}
                  >
                    <FormLabel component="legend">Report Type *</FormLabel>
                    <RadioGroup
                      name="reportType"
                      value={formik.values.reportType}
                      onChange={handleReportTypeChange}
                      onBlur={formik.handleBlur}
                    >
                      <FormControlLabel
                        value="Monthly"
                        control={<Radio />}
                        label="Monthly (Current Month)"
                      />
                      <FormControlLabel
                        value="Yearly"
                        control={<Radio />}
                        label="Yearly (Current Year)"
                      />
                      <FormControlLabel
                        value="Custom"
                        control={<Radio />}
                        label="Custom (Date Range)"
                      />
                    </RadioGroup>
                    {formik.touched.reportType && formik.errors.reportType && (
                      <FormHelperText>{formik.errors.reportType}</FormHelperText>
                    )}
                  </FormControl>
                </Grid>

                {/* Custom Date Range Fields (lines 127-199) */}
                {formik.values.reportType === 'Custom' && (
                  <>
                    <Grid item xs={12} sm={6}>
                      <DatePicker
                        label="Start Date *"
                        value={formik.values.startDate}
                        onChange={(value) => formik.setFieldValue('startDate', value)}
                        format="MM/dd/yyyy"
                        slotProps={{
                          textField: {
                            fullWidth: true,
                            error: formik.touched.startDate && Boolean(formik.errors.startDate),
                            helperText: formik.touched.startDate && formik.errors.startDate,
                            onBlur: formik.handleBlur,
                            name: 'startDate'
                          }
                        }}
                      />
                    </Grid>
                    <Grid item xs={12} sm={6}>
                      <DatePicker
                        label="End Date *"
                        value={formik.values.endDate}
                        onChange={(value) => formik.setFieldValue('endDate', value)}
                        format="MM/dd/yyyy"
                        minDate={formik.values.startDate}
                        slotProps={{
                          textField: {
                            fullWidth: true,
                            error: formik.touched.endDate && Boolean(formik.errors.endDate),
                            helperText: formik.touched.endDate && formik.errors.endDate,
                            onBlur: formik.handleBlur,
                            name: 'endDate'
                          }
                        }}
                      />
                    </Grid>
                  </>
                )}

                {/* Card Number Filter (new requirement) */}
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    id="cardNumber"
                    name="cardNumber"
                    label="Card Number (Optional)"
                    value={formik.values.cardNumber}
                    onChange={formik.handleChange}
                    onBlur={formik.handleBlur}
                    error={formik.touched.cardNumber && Boolean(formik.errors.cardNumber)}
                    helperText={formik.touched.cardNumber && formik.errors.cardNumber}
                    inputProps={{ maxLength: 16 }}
                  />
                </Grid>

                {/* Transaction Type Filter */}
                <Grid item xs={12} sm={6}>
                  <FormControl fullWidth>
                    <Select
                      id="transactionType"
                      name="transactionType"
                      value={formik.values.transactionType}
                      onChange={formik.handleChange}
                      onBlur={formik.handleBlur}
                      displayEmpty
                      error={formik.touched.transactionType && Boolean(formik.errors.transactionType)}
                    >
                      <MenuItem key="empty-transaction-type" value="">
                        <em>All Transaction Types</em>
                      </MenuItem>
                      {transactionTypes.map((type) => (
                        <MenuItem key={type.typeCode} value={type.typeCode}>
                          {type.typeName}
                        </MenuItem>
                      ))}
                    </Select>
                    {formik.touched.transactionType && formik.errors.transactionType && (
                      <FormHelperText error>{formik.errors.transactionType}</FormHelperText>
                    )}
                  </FormControl>
                </Grid>

                {/* Category Filter */}
                <Grid item xs={12} sm={6}>
                  <FormControl fullWidth>
                    <Select
                      id="category"
                      name="category"
                      value={formik.values.category}
                      onChange={formik.handleChange}
                      onBlur={formik.handleBlur}
                      displayEmpty
                      error={formik.touched.category && Boolean(formik.errors.category)}
                    >
                      <MenuItem key="empty-category" value="">
                        <em>All Categories</em>
                      </MenuItem>
                      {categories.map((cat) => (
                        <MenuItem key={cat.categoryCode} value={cat.categoryCode}>
                          {cat.categoryName}
                        </MenuItem>
                      ))}
                    </Select>
                    {formik.touched.category && formik.errors.category && (
                      <FormHelperText error>{formik.errors.category}</FormHelperText>
                    )}
                  </FormControl>
                </Grid>

                {/* Action Buttons */}
                <Grid item xs={12}>
                  <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
                    <Button
                      variant="outlined"
                      onClick={handleBack}
                      disabled={loading}
                    >
                      Back to Menu (F3)
                    </Button>
                    <Button
                      type="submit"
                      variant="contained"
                      color="primary"
                      disabled={loading}
                    >
                      {loading ? 'Generating...' : 'Generate Report (ENTER)'}
                    </Button>
                  </Box>
                </Grid>
              </Grid>
            </form>
          </CardContent>
        </Card>

        {/* Report Display Area */}
        {reportData && (
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                <Typography variant="h6">
                  Report Results
                </Typography>
                <Box sx={{ display: 'flex', gap: 1 }}>
                  <Button
                    variant="outlined"
                    startIcon={<PdfIcon />}
                    onClick={handleExportPDF}
                    disabled={loading}
                    size="small"
                  >
                    Export PDF
                  </Button>
                  <Button
                    variant="outlined"
                    startIcon={<CsvIcon />}
                    onClick={handleExportCSV}
                    disabled={loading}
                    size="small"
                  >
                    Export CSV
                  </Button>
                </Box>
              </Box>
              <Divider sx={{ mb: 2 }} />

              {/* Summary Section */}
              <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={12} sm={4}>
                  <Paper elevation={2} sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="body2" color="text.secondary">
                      Total Transactions
                    </Typography>
                    <Typography variant="h4" color="primary">
                      {reportData.totalCount || 0}
                    </Typography>
                  </Paper>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Paper elevation={2} sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="body2" color="text.secondary">
                      Total Amount
                    </Typography>
                    <Typography variant="h4" color="success.main">
                      {formatCurrency(reportData.totalAmount)}
                    </Typography>
                  </Paper>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Paper elevation={2} sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="body2" color="text.secondary">
                      Date Range
                    </Typography>
                    <Typography variant="h6">
                      {formatDisplayDate(reportData.startDate)} - {formatDisplayDate(reportData.endDate)}
                    </Typography>
                  </Paper>
                </Grid>
              </Grid>

              {/* Category Breakdown Table */}
              {reportData.categoryBreakdown && reportData.categoryBreakdown.length > 0 && (
                <Box sx={{ mb: 3 }}>
                  <Typography variant="h6" gutterBottom>
                    Category Breakdown
                  </Typography>
                  <TableContainer component={Paper} variant="outlined">
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell><strong>Category</strong></TableCell>
                          <TableCell align="right"><strong>Count</strong></TableCell>
                          <TableCell align="right"><strong>Total Amount</strong></TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {reportData.categoryBreakdown.map((category, index) => (
                          <TableRow key={index}>
                            <TableCell>{category.categoryName}</TableCell>
                            <TableCell align="right">{category.count}</TableCell>
                            <TableCell align="right">{formatCurrency(category.totalAmount)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Box>
              )}

              {/* Merchant Summary Table with Pagination */}
              {reportData.merchantSummary && reportData.merchantSummary.length > 0 && (
                <Box>
                  <Typography variant="h6" gutterBottom>
                    Top Merchants
                  </Typography>
                  <TableContainer component={Paper} variant="outlined">
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell><strong>Merchant Name</strong></TableCell>
                          <TableCell align="right"><strong>Transaction Count</strong></TableCell>
                          <TableCell align="right"><strong>Total Amount</strong></TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {reportData.merchantSummary
                          .slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)
                          .map((merchant, index) => (
                            <TableRow key={index}>
                              <TableCell>{merchant.merchantName}</TableCell>
                              <TableCell align="right">{merchant.count}</TableCell>
                              <TableCell align="right">{formatCurrency(merchant.totalAmount)}</TableCell>
                            </TableRow>
                          ))}
                      </TableBody>
                    </Table>
                    <TablePagination
                      component="div"
                      count={reportData.merchantSummary.length}
                      page={page}
                      onPageChange={handleChangePage}
                      rowsPerPage={rowsPerPage}
                      onRowsPerPageChange={handleChangeRowsPerPage}
                      rowsPerPageOptions={[5, 10, 25]}
                    />
                  </TableContainer>
                </Box>
              )}

              {/* No Data Message */}
              {(!reportData.categoryBreakdown || reportData.categoryBreakdown.length === 0) &&
               (!reportData.merchantSummary || reportData.merchantSummary.length === 0) && (
                <Typography variant="body1" color="text.secondary" align="center" sx={{ py: 4 }}>
                  No transaction data available for the selected criteria.
                </Typography>
              )}
            </CardContent>
          </Card>
        )}

        {/* Loading Overlay */}
        {loading && (
          <Box
            sx={{
              position: 'fixed',
              top: 0,
              left: 0,
              right: 0,
              bottom: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: 'rgba(0, 0, 0, 0.5)',
              zIndex: 9999
            }}
          >
            <CircularProgress size={60} />
          </Box>
        )}

        {/* Confirmation Dialog (lines 206-217) */}
        <Dialog
          open={confirmDialogOpen}
          onClose={handleConfirmCancel}
          aria-labelledby="confirm-dialog-title"
          aria-describedby="confirm-dialog-description"
        >
          <DialogTitle id="confirm-dialog-title">
            Confirm Report Generation
          </DialogTitle>
          <DialogContent>
            <DialogContentText id="confirm-dialog-description">
              The {formik.values.reportType} report will be generated with the selected filters.
              Please confirm to continue.
            </DialogContentText>
          </DialogContent>
          <DialogActions>
            <Button onClick={handleConfirmCancel} color="secondary">
              Cancel (N)
            </Button>
            <Button onClick={handleConfirmYes} color="primary" variant="contained" autoFocus>
              Confirm (Y)
            </Button>
          </DialogActions>
        </Dialog>

        {/* Error/Success Message Snackbar (lines 218-221) */}
        <Snackbar
          open={snackbar.open}
          autoHideDuration={6000}
          onClose={handleSnackbarClose}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
        >
          <Alert 
            onClose={handleSnackbarClose} 
            severity={snackbar.severity}
            sx={{ width: '100%' }}
          >
            {snackbar.message}
          </Alert>
        </Snackbar>
      </Container>
    </LocalizationProvider>
  );
};

export default TransactionReportComponent;
