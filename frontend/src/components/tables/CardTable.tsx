/**
 * CardTable Component
 * 
 * Converted from COBOL BMS map: COCRDLI.bms
 * Original function: Credit card list display with 7 repeating rows
 * 
 * Displays a list of credit cards in a Material-UI DataGrid with:
 * - Selection checkboxes (CRDSEL1-7 fields)
 * - Account Number column (ACCTNO1-7 - 11 characters)
 * - Card Number column (CRDNUM1-7 - 16 characters)
 * - Card Status column (CRDSTS1-7 - 'Y'/'N')
 * - Pagination controls (F7=Backward, F8=Forward from BMS)
 * - Error and info message display (ERRMSG, INFOMSG fields)
 * 
 * Conversion notes:
 * - BMS 7-row display structure converted to paginated DataGrid
 * - FSET/NORM/PROT attributes converted to selectable rows
 * - POS coordinates converted to column layout
 * - F7/F8 navigation converted to pagination controls
 */

import React, { useState, useCallback } from 'react';
import {
  DataGrid,
  GridColDef,
  GridRowSelectionModel,
  GridPaginationModel,
  GridCallbackDetails,
} from '@mui/x-data-grid';
import {
  Box,
  Alert,
  Typography,
  Paper,
  useTheme,
  useMediaQuery,
} from '@mui/material';

/**
 * Card data interface matching CVACT02Y.cpy copybook structure
 * and COCRDLI.bms field definitions
 */
export interface CardData {
  /**
   * Unique identifier for the card (used as DataGrid row id)
   */
  id: string;
  
  /**
   * Credit card number (16 characters) - CRDNUM field
   * Maps to CRDNUM1-7 in BMS map (LENGTH=16, POS varying)
   */
  cardNumber: string;
  
  /**
   * Account number (11 characters) - ACCTNO field
   * Maps to ACCTNO1-7 in BMS map (LENGTH=11, POS varying)
   */
  accountNumber: string;
  
  /**
   * Card active status - CRDSTS field
   * Maps to CRDSTS1-7 in BMS map (LENGTH=1, POS varying)
   * Valid values: 'Y' (active), 'N' (inactive)
   */
  status: 'Y' | 'N';
  
  /**
   * Card account ID for foreign key reference
   */
  cardAcctId: number;
  
  /**
   * Cardholder/cardmember ID
   */
  cardMemberId: number;
  
  /**
   * Embossed name on card
   */
  embossedName?: string;
  
  /**
   * Card expiration date
   */
  expirationDate?: string;
  
  /**
   * Card activation date
   */
  activeDate?: string;
}

/**
 * Props for CardTable component
 */
export interface CardTableProps {
  /**
   * Array of card data to display
   */
  cards: CardData[];
  
  /**
   * Callback fired when row selection changes
   * Maps to CRDSEL1-7 selection checkboxes in BMS map
   */
  onSelectionChange?: (selectedCardIds: string[]) => void;
  
  /**
   * Callback fired when a row is clicked
   */
  onRowClick?: (card: CardData) => void;
  
  /**
   * Currently selected card IDs
   */
  selectedCardIds?: string[];
  
  /**
   * Loading state
   */
  loading?: boolean;
  
  /**
   * Error message to display (maps to ERRMSG field in BMS)
   * ERRMSG: LENGTH=78, POS=(23,1), COLOR=RED, BRT attribute
   */
  errorMessage?: string;
  
  /**
   * Info message to display (maps to INFOMSG field in BMS)
   * INFOMSG: LENGTH=45, POS=(20,19), COLOR=NEUTRAL
   */
  infoMessage?: string;
  
  /**
   * Page size (default 7 to match BMS 7-row display)
   */
  pageSize?: number;
  
  /**
   * Initial page number (maps to PAGENO field in BMS)
   */
  initialPage?: number;
  
  /**
   * Disable pagination
   */
  disablePagination?: boolean;
  
  /**
   * Height of the table (default 'auto')
   */
  height?: number | string;
}

/**
 * CardTable Component
 * 
 * Reusable credit card list table component based on COCRDLI.bms map structure.
 * Displays credit cards in a Material-UI DataGrid with selection, pagination,
 * and responsive layout.
 * 
 * @param props - CardTableProps
 * @returns React component
 */
const CardTable: React.FC<CardTableProps> = ({
  cards,
  onSelectionChange,
  onRowClick,
  selectedCardIds = [],
  loading = false,
  errorMessage,
  infoMessage,
  pageSize = 7, // Default to 7 rows matching BMS display
  initialPage = 0,
  disablePagination = false,
  height = 'auto',
}) => {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));
  
  // Pagination state (maps to PAGENO field in BMS)
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: initialPage,
    pageSize: pageSize,
  });
  
  // Row selection state (maps to CRDSEL1-7 checkboxes in BMS)
  const [rowSelectionModel, setRowSelectionModel] = useState<GridRowSelectionModel>(
    selectedCardIds
  );
  
  /**
   * Handle row selection change
   * Maps to CRDSEL1-7 selection checkbox interaction in BMS map
   */
  const handleSelectionChange = useCallback(
    (newSelection: GridRowSelectionModel, details: GridCallbackDetails) => {
      setRowSelectionModel(newSelection);
      
      if (onSelectionChange) {
        onSelectionChange(newSelection as string[]);
      }
    },
    [onSelectionChange]
  );
  
  /**
   * Handle pagination change
   * Maps to F7=Backward and F8=Forward navigation in BMS map
   */
  const handlePaginationChange = useCallback(
    (model: GridPaginationModel, details: GridCallbackDetails) => {
      setPaginationModel(model);
    },
    []
  );
  
  /**
   * Handle row click
   */
  const handleRowClick = useCallback(
    (params: any) => {
      if (onRowClick && params.row) {
        onRowClick(params.row as CardData);
      }
    },
    [onRowClick]
  );
  
  /**
   * Format card number for display (mask middle digits for security)
   * Example: 4111111111111111 -> 4111 **** **** 1111
   */
  const formatCardNumber = (cardNumber: string): string => {
    if (!cardNumber || cardNumber.length !== 16) {
      return cardNumber;
    }
    
    const first4 = cardNumber.substring(0, 4);
    const last4 = cardNumber.substring(12, 16);
    return `${first4} **** **** ${last4}`;
  };
  
  /**
   * Format status for display
   * 'Y' -> 'Active', 'N' -> 'Inactive'
   */
  const formatStatus = (status: 'Y' | 'N'): string => {
    return status === 'Y' ? 'Active' : 'Inactive';
  };
  
  /**
   * Column definitions matching BMS map structure:
   * - Select column (CRDSEL1-7)
   * - Account Number (ACCTNO1-7, LENGTH=11)
   * - Card Number (CRDNUM1-7, LENGTH=16)
   * - Active Status (CRDSTS1-7, LENGTH=1)
   */
  const columns: GridColDef<CardData>[] = [
    {
      field: 'accountNumber',
      headerName: 'Account Number',
      width: 150,
      minWidth: 120,
      flex: isMobile ? 0 : 1,
      sortable: true,
      // Maps to ACCTNO1-7 fields in BMS (LENGTH=11, POS varying by row)
      description: 'Account number (11 characters)',
    },
    {
      field: 'cardNumber',
      headerName: 'Card Number',
      width: 200,
      minWidth: 180,
      flex: isMobile ? 0 : 1.5,
      sortable: true,
      // Maps to CRDNUM1-7 fields in BMS (LENGTH=16, POS varying by row)
      description: 'Credit card number (16 characters, masked for security)',
      renderCell: (params) => (
        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
          {formatCardNumber(params.value)}
        </Typography>
      ),
    },
    {
      field: 'status',
      headerName: 'Active',
      width: 100,
      minWidth: 90,
      flex: isMobile ? 0 : 0.5,
      sortable: true,
      // Maps to CRDSTS1-7 fields in BMS (LENGTH=1, POS varying by row)
      description: 'Card active status (Y/N)',
      renderCell: (params) => (
        <Typography
          variant="body2"
          sx={{
            color: params.value === 'Y' ? theme.palette.success.main : theme.palette.error.main,
            fontWeight: 'medium',
          }}
        >
          {formatStatus(params.value as 'Y' | 'N')}
        </Typography>
      ),
    },
  ];
  
  /**
   * Calculate table height based on page size and whether it's auto
   */
  const calculateHeight = (): string | number => {
    if (height !== 'auto') {
      return height;
    }
    
    // Base height + (rows * row height) + header + pagination
    const baseHeight = 56; // Header height
    const rowHeight = 52; // Default row height
    const paginationHeight = disablePagination ? 0 : 56;
    const messageHeight = (errorMessage || infoMessage) ? 60 : 0;
    
    const visibleRows = Math.min(cards.length, paginationModel.pageSize);
    
    return baseHeight + (visibleRows * rowHeight) + paginationHeight + messageHeight + 20;
  };
  
  return (
    <Box sx={{ width: '100%' }}>
      {/* Error Message Display - Maps to ERRMSG field (POS=(23,1), COLOR=RED) */}
      {errorMessage && (
        <Alert 
          severity="error" 
          sx={{ mb: 2 }}
          role="alert"
          aria-live="assertive"
        >
          {errorMessage}
        </Alert>
      )}
      
      {/* Info Message Display - Maps to INFOMSG field (POS=(20,19), COLOR=NEUTRAL) */}
      {infoMessage && (
        <Alert 
          severity="info" 
          sx={{ mb: 2 }}
          role="status"
          aria-live="polite"
        >
          {infoMessage}
        </Alert>
      )}
      
      {/* DataGrid Component */}
      <Paper elevation={2}>
        <DataGrid
          rows={cards}
          columns={columns}
          loading={loading}
          checkboxSelection
          disableRowSelectionOnClick
          rowSelectionModel={rowSelectionModel}
          onRowSelectionModelChange={handleSelectionChange}
          onRowClick={handleRowClick}
          paginationModel={paginationModel}
          onPaginationModelChange={handlePaginationChange}
          pageSizeOptions={[7, 14, 21, 50, 100]}
          pagination={!disablePagination}
          autoHeight={height === 'auto'}
          sx={{
            height: height === 'auto' ? undefined : calculateHeight(),
            '& .MuiDataGrid-cell': {
              padding: '8px',
            },
            '& .MuiDataGrid-columnHeader': {
              backgroundColor: theme.palette.mode === 'dark' 
                ? theme.palette.grey[800] 
                : theme.palette.grey[100],
              fontWeight: 'bold',
            },
            '& .MuiDataGrid-row': {
              '&:hover': {
                backgroundColor: theme.palette.mode === 'dark'
                  ? theme.palette.grey[800]
                  : theme.palette.grey[50],
                cursor: onRowClick ? 'pointer' : 'default',
              },
              '&.Mui-selected': {
                backgroundColor: theme.palette.mode === 'dark'
                  ? theme.palette.primary.dark
                  : theme.palette.primary.light,
                '&:hover': {
                  backgroundColor: theme.palette.mode === 'dark'
                    ? theme.palette.primary.dark
                    : theme.palette.primary.light,
                },
              },
            },
            // Responsive adjustments
            ...(isMobile && {
              '& .MuiDataGrid-columnHeader, & .MuiDataGrid-cell': {
                fontSize: '0.875rem',
                padding: '4px',
              },
            }),
          }}
          // Accessibility attributes
          aria-label="Credit card list table"
          getRowId={(row) => row.id}
          // Localization for pagination (matching F7/F8 navigation)
          localeText={{
            MuiTablePagination: {
              labelDisplayedRows: ({ from, to, count }) =>
                `Page ${Math.ceil(from / paginationModel.pageSize) + 1} - Showing ${from}–${to} of ${count}`,
            },
          }}
        />
      </Paper>
      
      {/* Navigation Help Text - Maps to BMS line 24 function keys */}
      {!disablePagination && cards.length > pageSize && (
        <Box sx={{ mt: 1, display: 'flex', justifyContent: 'center' }}>
          <Typography variant="caption" color="text.secondary">
            Use pagination controls for navigation (F7=Backward, F8=Forward in legacy system)
          </Typography>
        </Box>
      )}
    </Box>
  );
};

export default CardTable;
