/**
 * TransactionTable Component
 * 
 * Converted from BMS map: COTRN00.bms
 * Original function: Transaction list display with 10 repeating rows
 * 
 * This component provides a reusable transaction list table based on the COTRN00.bms map structure.
 * Features include:
 * - 10 rows per page display (matching BMS repeating fields SEL0001-SEL0010)
 * - Column structure: Selection, Transaction ID (16 chars), Date (8 chars), Description (26 chars), Amount (12 chars)
 * - Transaction ID search functionality (TRNIDIN field)
 * - Date range filtering for transaction dates
 * - Pagination with F7=Backward, F8=Forward navigation
 * - Row selection via checkboxes
 * - Column sorting capabilities
 * - Currency formatting for amount display
 * 
 * Conversion notes:
 * - BMS repeating fields (TRNID01-TRNID10, etc.) converted to Material-UI DataGrid rows
 * - BMS field attributes (FSET,NORM,UNPROT) converted to DataGrid column configurations
 * - BMS ERRMSG field (RED color, BRT attribute) converted to error display prop
 * - PAGENUM field converted to DataGrid pagination controls
 * - TRNIDIN search field converted to search filter state
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  DataGrid,
  GridColDef,
  GridRowSelectionModel,
  GridSortModel,
  GridPaginationModel,
  GridValueFormatter,
} from '@mui/x-data-grid';
import {
  Box,
  TextField,
  Alert,
  Paper,
  Typography,
  InputAdornment,
  IconButton,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import ClearIcon from '@mui/icons-material/Clear';

/**
 * Transaction interface based on COTRN00.bms field structure
 * Maps to CVTRA05Y.cpy copybook structure
 * 
 * Field mappings:
 * - id: Unique identifier for DataGrid (required by MUI)
 * - transId: TRNID fields (16 chars) - Transaction identifier
 * - transDate: TDATE fields (8 chars YYYYMMDD format) - Transaction date
 * - description: TDESC fields (26 chars) - Transaction description
 * - amount: TAMT fields (12 chars with decimal) - Transaction amount
 */
export interface Transaction {
  id: string;
  transId: string;
  transDate: string;
  description: string;
  amount: number;
}

/**
 * Props interface for TransactionTable component
 */
export interface TransactionTableProps {
  /** Array of transaction data to display */
  transactions: Transaction[];
  /** Callback fired when rows are selected */
  onRowSelectionChange?: (selectedIds: string[]) => void;
  /** Currently selected row IDs */
  selectedRows?: string[];
  /** Loading state indicator */
  loading?: boolean;
  /** Error message to display (maps to ERRMSG field) */
  errorMessage?: string;
  /** Callback fired when pagination changes */
  onPaginationChange?: (page: number, pageSize: number) => void;
  /** Current page number (0-indexed, maps to PAGENUM field) */
  currentPage?: number;
  /** Page size - defaults to 10 (matching BMS 10 repeating rows) */
  pageSize?: number;
  /** Total number of records (for server-side pagination) */
  totalRecords?: number;
  /** Enable/disable sorting */
  sortable?: boolean;
  /** Enable/disable filtering */
  filterable?: boolean;
  /** Callback fired when search filter changes */
  onSearchChange?: (searchValue: string) => void;
  /** Initial search value (maps to TRNIDIN field) */
  initialSearchValue?: string;
  /** Callback fired when date range filter changes */
  onDateRangeChange?: (startDate: string, endDate: string) => void;
  /** Custom height for the table */
  height?: number | string;
  /** Show/hide search field */
  showSearch?: boolean;
  /** Show/hide date range filters */
  showDateFilters?: boolean;
}

/**
 * TransactionTable Component
 * 
 * Displays transactions in a paginated, sortable, filterable data grid
 * based on the COTRN00.bms map structure with 10 repeating row layout
 * 
 * @param props - Component properties
 * @returns React component
 */
const TransactionTable: React.FC<TransactionTableProps> = ({
  transactions,
  onRowSelectionChange,
  selectedRows = [],
  loading = false,
  errorMessage,
  onPaginationChange,
  currentPage = 0,
  pageSize = 10, // Default to 10 rows matching BMS structure
  totalRecords,
  sortable = true,
  filterable = true,
  onSearchChange,
  initialSearchValue = '',
  onDateRangeChange,
  height = 650,
  showSearch = true,
  showDateFilters = true,
}) => {
  // Search state (maps to TRNIDIN field - 16 char transaction ID search)
  const [searchValue, setSearchValue] = useState<string>(initialSearchValue);
  
  // Date range filter state
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');

  // Pagination model
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: currentPage,
    pageSize: pageSize,
  });

  // Sort model state
  const [sortModel, setSortModel] = useState<GridSortModel>([
    { field: 'transDate', sort: 'desc' }, // Default sort by date descending
  ]);

  /**
   * Format currency values for display
   * Preserves COBOL COMP-3 precision using 2 decimal places
   * Maps to TAMT fields (12 chars with decimal formatting)
   */
  const formatCurrency = useCallback((value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  }, []);

  /**
   * Format date for display
   * Converts YYYYMMDD (8 char) to MM/DD/YYYY format
   * Maps to TDATE fields
   */
  const formatDate = useCallback((dateString: string): string => {
    if (!dateString || dateString.length !== 8) {
      return dateString;
    }
    
    const year = dateString.substring(0, 4);
    const month = dateString.substring(4, 6);
    const day = dateString.substring(6, 8);
    
    return `${month}/${day}/${year}`;
  }, []);

  /**
   * Column definitions for the DataGrid
   * Maps to COTRN00.bms field structure:
   * - SEL0001-SEL0010: Selection checkboxes (FSET,NORM,UNPROT attributes)
   * - TRNID01-TRNID10: Transaction ID (16 chars, ASKIP,FSET,NORM, BLUE color)
   * - TDATE01-TDATE10: Transaction Date (8 chars, ASKIP,FSET,NORM, BLUE color)
   * - TDESC01-TDESC10: Description (26 chars, ASKIP,FSET,NORM, BLUE color)
   * - TAMT001-TAMT010: Amount (12 chars, ASKIP,FSET,NORM, BLUE color)
   */
  const columns: GridColDef[] = useMemo(() => [
    {
      field: 'transId',
      headerName: 'Transaction ID',
      width: 180,
      sortable: sortable,
      filterable: filterable,
      headerAlign: 'left',
      align: 'left',
      description: 'Transaction identifier (16 characters)',
    },
    {
      field: 'transDate',
      headerName: 'Date',
      width: 120,
      sortable: sortable,
      filterable: filterable,
      headerAlign: 'center',
      align: 'center',
      description: 'Transaction date (8 characters YYYYMMDD)',
      valueFormatter: ((value: string) => {
        return formatDate(value);
      }) as GridValueFormatter,
    },
    {
      field: 'description',
      headerName: 'Description',
      width: 300,
      sortable: sortable,
      filterable: filterable,
      flex: 1,
      headerAlign: 'left',
      align: 'left',
      description: 'Transaction description (26 characters)',
    },
    {
      field: 'amount',
      headerName: 'Amount',
      width: 150,
      sortable: sortable,
      filterable: filterable,
      headerAlign: 'right',
      align: 'right',
      type: 'number',
      description: 'Transaction amount (12 characters with decimal)',
      valueFormatter: ((value: number) => {
        return formatCurrency(value);
      }) as GridValueFormatter,
    },
  ], [sortable, filterable, formatCurrency, formatDate]);

  /**
   * Handle search input change
   * Maps to TRNIDIN field (16 char transaction ID search)
   */
  const handleSearchChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setSearchValue(value);
    
    if (onSearchChange) {
      onSearchChange(value);
    }
  }, [onSearchChange]);

  /**
   * Clear search filter
   */
  const handleClearSearch = useCallback(() => {
    setSearchValue('');
    if (onSearchChange) {
      onSearchChange('');
    }
  }, [onSearchChange]);

  /**
   * Handle start date change
   */
  const handleStartDateChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setStartDate(value);
    
    if (onDateRangeChange) {
      onDateRangeChange(value, endDate);
    }
  }, [endDate, onDateRangeChange]);

  /**
   * Handle end date change
   */
  const handleEndDateChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setEndDate(value);
    
    if (onDateRangeChange) {
      onDateRangeChange(startDate, value);
    }
  }, [startDate, onDateRangeChange]);

  /**
   * Handle row selection change
   * Maps to SEL0001-SEL0010 fields (selection checkboxes)
   */
  const handleSelectionChange = useCallback((selectionModel: GridRowSelectionModel) => {
    if (onRowSelectionChange) {
      onRowSelectionChange(selectionModel as string[]);
    }
  }, [onRowSelectionChange]);

  /**
   * Handle pagination change
   * Maps to PAGENUM field and F7=Backward, F8=Forward navigation
   */
  const handlePaginationChange = useCallback((model: GridPaginationModel) => {
    setPaginationModel(model);
    
    if (onPaginationChange) {
      onPaginationChange(model.page, model.pageSize);
    }
  }, [onPaginationChange]);

  /**
   * Handle sort model change
   */
  const handleSortModelChange = useCallback((model: GridSortModel) => {
    setSortModel(model);
  }, []);

  /**
   * Filter transactions based on search value and date range
   * Client-side filtering when transactions array is provided
   */
  const filteredTransactions = useMemo(() => {
    let filtered = [...transactions];

    // Apply transaction ID search filter (TRNIDIN field)
    if (searchValue && searchValue.trim() !== '') {
      const searchLower = searchValue.toLowerCase().trim();
      filtered = filtered.filter(transaction =>
        transaction.transId.toLowerCase().includes(searchLower)
      );
    }

    // Apply date range filter
    if (startDate || endDate) {
      filtered = filtered.filter(transaction => {
        const transDate = transaction.transDate;
        
        if (startDate && transDate < startDate.replace(/-/g, '')) {
          return false;
        }
        
        if (endDate && transDate > endDate.replace(/-/g, '')) {
          return false;
        }
        
        return true;
      });
    }

    return filtered;
  }, [transactions, searchValue, startDate, endDate]);

  return (
    <Box sx={{ width: '100%', height: '100%' }}>
      {/* Search and Filter Controls */}
      {(showSearch || showDateFilters) && (
        <Paper
          elevation={1}
          sx={{
            p: 2,
            mb: 2,
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
          }}
        >
          {/* Transaction ID Search (TRNIDIN field) */}
          {showSearch && (
            <Box>
              <Typography
                variant="subtitle2"
                color="primary"
                sx={{ mb: 1 }}
              >
                Search Transaction ID
              </Typography>
              <TextField
                fullWidth
                size="small"
                placeholder="Enter transaction ID (16 characters)"
                value={searchValue}
                onChange={handleSearchChange}
                inputProps={{
                  maxLength: 16, // Match TRNIDIN field length
                }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon color="action" />
                    </InputAdornment>
                  ),
                  endAdornment: searchValue && (
                    <InputAdornment position="end">
                      <IconButton
                        size="small"
                        onClick={handleClearSearch}
                        edge="end"
                        aria-label="clear search"
                      >
                        <ClearIcon />
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
              />
            </Box>
          )}

          {/* Date Range Filters */}
          {showDateFilters && (
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              <Box sx={{ flex: 1, minWidth: 200 }}>
                <Typography
                  variant="subtitle2"
                  color="primary"
                  sx={{ mb: 1 }}
                >
                  Start Date
                </Typography>
                <TextField
                  fullWidth
                  size="small"
                  type="date"
                  value={startDate}
                  onChange={handleStartDateChange}
                  InputLabelProps={{
                    shrink: true,
                  }}
                />
              </Box>
              <Box sx={{ flex: 1, minWidth: 200 }}>
                <Typography
                  variant="subtitle2"
                  color="primary"
                  sx={{ mb: 1 }}
                >
                  End Date
                </Typography>
                <TextField
                  fullWidth
                  size="small"
                  type="date"
                  value={endDate}
                  onChange={handleEndDateChange}
                  InputLabelProps={{
                    shrink: true,
                  }}
                />
              </Box>
            </Box>
          )}
        </Paper>
      )}

      {/* Error Message Display (ERRMSG field) */}
      {errorMessage && (
        <Alert
          severity="error"
          sx={{
            mb: 2,
            '& .MuiAlert-message': {
              width: '100%',
            },
          }}
        >
          {errorMessage}
        </Alert>
      )}

      {/* Data Grid (10 repeating rows from COTRN00.bms) */}
      <Paper elevation={2} sx={{ height: height, width: '100%' }}>
        <DataGrid
          rows={filteredTransactions}
          columns={columns}
          paginationModel={paginationModel}
          onPaginationModelChange={handlePaginationChange}
          pageSizeOptions={[10, 25, 50, 100]} // Default to 10 matching BMS structure
          rowCount={totalRecords || filteredTransactions.length}
          paginationMode={totalRecords ? 'server' : 'client'}
          loading={loading}
          checkboxSelection
          disableRowSelectionOnClick
          rowSelectionModel={selectedRows}
          onRowSelectionModelChange={handleSelectionChange}
          sortModel={sortModel}
          onSortModelChange={handleSortModelChange}
          sortingMode="client"
          filterMode="client"
          density="standard"
          sx={{
            border: 0,
            '& .MuiDataGrid-cell': {
              borderBottom: '1px solid rgba(224, 224, 224, 1)',
            },
            '& .MuiDataGrid-columnHeaders': {
              backgroundColor: '#f5f5f5',
              borderBottom: '2px solid rgba(224, 224, 224, 1)',
              fontWeight: 600,
            },
            '& .MuiDataGrid-row:hover': {
              backgroundColor: 'rgba(25, 118, 210, 0.08)',
            },
            '& .MuiDataGrid-row.Mui-selected': {
              backgroundColor: 'rgba(25, 118, 210, 0.12)',
            },
            '& .MuiDataGrid-row.Mui-selected:hover': {
              backgroundColor: 'rgba(25, 118, 210, 0.16)',
            },
          }}
          localeText={{
            noRowsLabel: 'No transactions found',
            noResultsOverlayLabel: 'No transactions match the filter criteria',
          }}
          aria-label="Transaction list table"
          getRowId={(row) => row.id}
        />
      </Paper>

      {/* Instructions (maps to BMS instruction line) */}
      <Box sx={{ mt: 2 }}>
        <Typography variant="caption" color="text.secondary">
          Type 'S' to View Transaction details from the list. Use checkboxes to select multiple transactions.
        </Typography>
      </Box>
    </Box>
  );
};

export default TransactionTable;
