/**
 * AccountTable.tsx
 * 
 * Converted from BMS map: COACTVW.bms
 * Original function: Account detail view structure
 * 
 * Reusable account list table component using Material-UI DataGrid displaying 
 * account records with columns for account number, status, credit limit, 
 * current balance, open date, and expiration date.
 * 
 * Features:
 * - Client-side sorting by account number and status
 * - Pagination controls matching BMS PAGENO pattern
 * - Row selection via checkbox with callbacks
 * - Filtering capabilities
 * - Responsive design
 * 
 * Conversion notes:
 * - BMS field ACCTSID (11-digit account number) → accountNumber column
 * - BMS field ACSTTUS (Y/N active status) → status column
 * - BMS field ACRDLIM (credit limit with PICOUT format) → creditLimit column with currency formatting
 * - BMS field ACURBAL (current balance) → currentBalance column with currency formatting
 * - BMS field ADTOPEN (open date) → openDate column with date formatting
 * - BMS field AEXPDT (expiration date) → expirationDate column with date formatting
 * - BMS ASKIP/PROT attributes → read-only table display
 * - BMS pagination → Material-UI DataGrid pagination
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  DataGrid,
  GridColDef,
  GridRowSelectionModel,
  GridSortModel,
  GridFilterModel,
  GridPaginationModel,
  GridRowParams,
} from '@mui/x-data-grid';
import { Box, Typography } from '@mui/material';

/**
 * Account interface based on CVACT01Y.cpy copybook structure
 * Maps COBOL PIC fields to TypeScript types:
 * - PIC 9(11) → number (accountId)
 * - PIC X(1) → string (activeStatus)
 * - PIC S9(10)V99 COMP-3 → number (currency fields)
 * - PIC X(10) YYYY-MM-DD → string (date fields)
 */
export interface Account {
  /** Account ID - Primary key (ACCT-ID from CVACT01Y.cpy) */
  accountId: number;
  
  /** Account number displayed to users (11 digits, maps to ACCTSID from BMS) */
  accountNumber: string;
  
  /** Active status: 'Y' or 'N' (ACCT-ACTIVE-STATUS from CVACT01Y.cpy, ACSTTUS from BMS) */
  activeStatus: string;
  
  /** Credit limit in dollars (ACCT-CREDIT-LIMIT from CVACT01Y.cpy, ACRDLIM from BMS) */
  creditLimit: number;
  
  /** Cash credit limit in dollars (ACCT-CASH-CREDIT-LIMIT from CVACT01Y.cpy, ACSHLIM from BMS) */
  cashCreditLimit: number;
  
  /** Current balance in dollars (ACCT-CURR-BAL from CVACT01Y.cpy, ACURBAL from BMS) */
  currentBalance: number;
  
  /** Account open date YYYY-MM-DD (ACCT-OPEN-DATE from CVACT01Y.cpy, ADTOPEN from BMS) */
  openDate: string;
  
  /** Account expiration date YYYY-MM-DD (ACCT-EXPIRATION-DATE from CVACT01Y.cpy, AEXPDT from BMS) */
  expirationDate: string;
  
  /** Account reissue date YYYY-MM-DD (ACCT-REISSUE-DATE from CVACT01Y.cpy, AREISDT from BMS) */
  reissueDate?: string;
  
  /** Current cycle credit amount (ACCT-CURR-CYC-CREDIT from CVACT01Y.cpy, ACRCYCR from BMS) */
  currentCycleCredit?: number;
  
  /** Current cycle debit amount (ACCT-CURR-CYC-DEBIT from CVACT01Y.cpy, ACRCYDB from BMS) */
  currentCycleDebit?: number;
  
  /** Account group identifier (ACCT-GROUP-ID from CVACT01Y.cpy, AADDGRP from BMS) */
  accountGroupId?: string;
}

/**
 * Props for AccountTable component
 */
export interface AccountTableProps {
  /** Array of account records to display */
  accounts: Account[];
  
  /** Loading state indicator */
  loading?: boolean;
  
  /** Error message to display */
  error?: string;
  
  /** Callback fired when row selection changes */
  onSelectionChange?: (selectedIds: number[]) => void;
  
  /** Callback fired when a row is clicked */
  onRowClick?: (account: Account) => void;
  
  /** Enable/disable row selection checkboxes (default: true) */
  checkboxSelection?: boolean;
  
  /** Initial page size (default: 10) */
  pageSize?: number;
  
  /** Available page size options (default: [10, 25, 50, 100]) */
  pageSizeOptions?: number[];
  
  /** Height of the table in pixels (default: 600) */
  height?: number;
  
  /** Enable/disable filtering (default: true) */
  enableFiltering?: boolean;
  
  /** Enable/disable sorting (default: true) */
  enableSorting?: boolean;
}

/**
 * AccountTable Component
 * 
 * Displays account data in a sortable, filterable, and paginated data grid.
 * Replaces BMS COACTVW.bms account list display with modern React table.
 */
const AccountTable: React.FC<AccountTableProps> = ({
  accounts,
  loading = false,
  error,
  onSelectionChange,
  onRowClick,
  checkboxSelection = true,
  pageSize = 10,
  pageSizeOptions = [10, 25, 50, 100],
  height = 600,
  enableFiltering = true,
  enableSorting = true,
}) => {
  // State for row selection
  const [rowSelectionModel, setRowSelectionModel] = useState<GridRowSelectionModel>([]);
  
  // State for pagination
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: 0,
    pageSize: pageSize,
  });
  
  // State for sorting
  const [sortModel, setSortModel] = useState<GridSortModel>([
    { field: 'accountNumber', sort: 'asc' },
  ]);
  
  // State for filtering
  const [filterModel, setFilterModel] = useState<GridFilterModel>({
    items: [],
  });

  /**
   * Format currency values matching BMS PICOUT format '+ZZZ,ZZZ,ZZZ.99'
   * Handles positive and negative values with proper formatting
   */
  const formatCurrency = useCallback((value: number | null | undefined): string => {
    if (value === null || value === undefined) {
      return '$0.00';
    }
    
    const formatter = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    
    return formatter.format(value);
  }, []);

  /**
   * Format date values from YYYY-MM-DD to MM/DD/YYYY matching BMS display format
   */
  const formatDate = useCallback((dateString: string | null | undefined): string => {
    if (!dateString) {
      return '';
    }
    
    try {
      // Parse YYYY-MM-DD format
      const [year, month, day] = dateString.split('-');
      if (!year || !month || !day) {
        return dateString;
      }
      
      // Return MM/DD/YYYY format
      return `${month}/${day}/${year}`;
    } catch (error) {
      return dateString;
    }
  }, []);

  /**
   * Format active status for display
   * Maps 'Y'/'N' to user-friendly labels with color coding
   */
  const formatStatus = useCallback((status: string): string => {
    return status === 'Y' ? 'Active' : 'Inactive';
  }, []);

  /**
   * Column definitions matching BMS COACTVW.bms field structure
   * 
   * Conversion mapping:
   * - ACCTSID (POS=(5,38), LENGTH=11) → accountNumber column
   * - ACSTTUS (POS=(5,70), LENGTH=1) → activeStatus column
   * - ACRDLIM (POS=(6,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99') → creditLimit column
   * - ACURBAL (POS=(8,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99') → currentBalance column
   * - ADTOPEN (POS=(6,17), LENGTH=10) → openDate column
   * - AEXPDT (POS=(7,17), LENGTH=10) → expirationDate column
   */
  const columns: GridColDef[] = useMemo(() => [
    {
      field: 'accountNumber',
      headerName: 'Account Number',
      width: 150,
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Account number (11 digits) - ACCTSID field from BMS',
    },
    {
      field: 'activeStatus',
      headerName: 'Status',
      width: 120,
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Account active status (Y/N) - ACSTTUS field from BMS',
      valueFormatter: (value: string) => formatStatus(value),
      cellClassName: (params) => {
        return params.value === 'Y' ? 'status-active' : 'status-inactive';
      },
    },
    {
      field: 'creditLimit',
      headerName: 'Credit Limit',
      width: 150,
      type: 'number',
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Credit limit - ACRDLIM field from BMS with PICOUT format',
      valueFormatter: (value: number) => formatCurrency(value),
      align: 'right',
      headerAlign: 'right',
    },
    {
      field: 'currentBalance',
      headerName: 'Current Balance',
      width: 160,
      type: 'number',
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Current account balance - ACURBAL field from BMS',
      valueFormatter: (value: number) => formatCurrency(value),
      align: 'right',
      headerAlign: 'right',
      cellClassName: (params) => {
        // Apply styling for negative balances
        return params.value < 0 ? 'balance-negative' : 'balance-positive';
      },
    },
    {
      field: 'openDate',
      headerName: 'Open Date',
      width: 130,
      type: 'date',
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Account open date - ADTOPEN field from BMS',
      valueFormatter: (value: string) => formatDate(value),
    },
    {
      field: 'expirationDate',
      headerName: 'Expiration Date',
      width: 140,
      type: 'date',
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Account expiration date - AEXPDT field from BMS',
      valueFormatter: (value: string) => formatDate(value),
    },
    {
      field: 'cashCreditLimit',
      headerName: 'Cash Credit Limit',
      width: 160,
      type: 'number',
      sortable: enableSorting,
      filterable: enableFiltering,
      description: 'Cash credit limit - ACSHLIM field from BMS',
      valueFormatter: (value: number) => formatCurrency(value),
      align: 'right',
      headerAlign: 'right',
    },
  ], [enableSorting, enableFiltering, formatCurrency, formatDate, formatStatus]);

  /**
   * Handle row selection change
   * Extracts accountId from selected rows and fires callback
   */
  const handleSelectionChange = useCallback((newSelection: GridRowSelectionModel) => {
    setRowSelectionModel(newSelection);
    
    if (onSelectionChange) {
      // Convert selection model to array of account IDs
      const selectedIds = newSelection.map(id => Number(id));
      onSelectionChange(selectedIds);
    }
  }, [onSelectionChange]);

  /**
   * Handle row click event
   * Fires callback with full account object
   */
  const handleRowClick = useCallback((params: GridRowParams) => {
    if (onRowClick) {
      onRowClick(params.row as Account);
    }
  }, [onRowClick]);

  /**
   * Handle pagination change
   * Maintains page state for navigation
   */
  const handlePaginationChange = useCallback((model: GridPaginationModel) => {
    setPaginationModel(model);
  }, []);

  /**
   * Handle sort model change
   * Maintains sort state for columns
   */
  const handleSortChange = useCallback((model: GridSortModel) => {
    setSortModel(model);
  }, []);

  /**
   * Handle filter model change
   * Maintains filter state for columns
   */
  const handleFilterChange = useCallback((model: GridFilterModel) => {
    setFilterModel(model);
  }, []);

  /**
   * Render error state
   */
  if (error) {
    return (
      <Box
        sx={{
          height: height,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          border: '1px solid #e0e0e0',
          borderRadius: 1,
          backgroundColor: '#fff3e0',
        }}
      >
        <Typography color="error" variant="body1">
          Error loading accounts: {error}
        </Typography>
      </Box>
    );
  }

  /**
   * Render empty state
   */
  if (!loading && accounts.length === 0) {
    return (
      <Box
        sx={{
          height: height,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          border: '1px solid #e0e0e0',
          borderRadius: 1,
        }}
      >
        <Typography variant="body1" color="textSecondary">
          No accounts found
        </Typography>
      </Box>
    );
  }

  /**
   * Main DataGrid render
   * Implements Material-UI DataGrid with all features enabled
   */
  return (
    <Box
      sx={{
        height: height,
        width: '100%',
        '& .status-active': {
          color: '#2e7d32',
          fontWeight: 500,
        },
        '& .status-inactive': {
          color: '#d32f2f',
          fontWeight: 500,
        },
        '& .balance-negative': {
          color: '#d32f2f',
        },
        '& .balance-positive': {
          color: '#2e7d32',
        },
        '& .MuiDataGrid-root': {
          border: '1px solid #e0e0e0',
        },
        '& .MuiDataGrid-columnHeaders': {
          backgroundColor: '#f5f5f5',
          borderBottom: '2px solid #1976d2',
        },
        '& .MuiDataGrid-cell': {
          borderBottom: '1px solid #f0f0f0',
        },
        '& .MuiDataGrid-row:hover': {
          backgroundColor: '#f5f5f5',
          cursor: onRowClick ? 'pointer' : 'default',
        },
      }}
    >
      <DataGrid
        rows={accounts}
        columns={columns}
        getRowId={(row) => row.accountId}
        loading={loading}
        checkboxSelection={checkboxSelection}
        disableRowSelectionOnClick={!onRowClick}
        rowSelectionModel={rowSelectionModel}
        onRowSelectionModelChange={handleSelectionChange}
        onRowClick={handleRowClick}
        paginationModel={paginationModel}
        onPaginationModelChange={handlePaginationChange}
        pageSizeOptions={pageSizeOptions}
        sortModel={sortModel}
        onSortModelChange={handleSortChange}
        filterModel={filterModel}
        onFilterModelChange={handleFilterChange}
        disableColumnFilter={!enableFiltering}
        disableColumnSelector={false}
        disableDensitySelector={false}
        sx={{
          '& .MuiDataGrid-columnHeaderTitle': {
            fontWeight: 600,
            color: '#1976d2',
          },
        }}
        initialState={{
          pagination: {
            paginationModel: { page: 0, pageSize: pageSize },
          },
          sorting: {
            sortModel: [{ field: 'accountNumber', sort: 'asc' }],
          },
        }}
        autoHeight={false}
      />
    </Box>
  );
};

export default AccountTable;
