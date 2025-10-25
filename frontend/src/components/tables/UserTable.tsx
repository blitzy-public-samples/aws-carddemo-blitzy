/**
 * UserTable Component
 * 
 * Converted from BMS map: COUSR00.bms
 * Original function: List Users screen with 10 repeating rows
 * 
 * This reusable table component displays user data with the following features:
 * - Material-UI DataGrid for displaying user list
 * - Search by User ID functionality (USRIDIN field from BMS)
 * - Pagination with F7=Backward/F8=Forward navigation (PAGENUM field)
 * - Row selection for update ('U') and delete ('D') operations (SEL fields)
 * - Columns: Selection checkbox, User ID (8 chars), First Name (20 chars), 
 *   Last Name (20 chars), User Type (1 char)
 * - Error message display (ERRMSG field - RED color, BRT attribute)
 * - Sorting support for all columns
 * - Responsive design maintaining BMS color scheme (NEUTRAL headers, BLUE data)
 * 
 * BMS Field Mapping:
 * - USRIDIN (8 chars, GREEN, UNDERLINE) → Search TextField
 * - SEL0001-SEL0010 (1 char, GREEN, UNDERLINE) → Selection checkboxes
 * - USRID01-USRID10 (8 chars, BLUE) → userId column
 * - FNAME01-FNAME10 (20 chars, BLUE) → firstName column
 * - LNAME01-LNAME10 (20 chars, BLUE) → lastName column
 * - UTYPE01-UTYPE10 (1 char, BLUE) → userType column
 * - PAGENUM (8 chars, BLUE) → Page number display
 * - ERRMSG (78 chars, RED, BRT) → Error message display
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  Box,
  TextField,
  Typography,
  Alert,
  Paper,
  IconButton,
  Tooltip,
} from '@mui/material';
import {
  DataGrid,
  GridColDef,
  GridRowSelectionModel,
  GridSortModel,
} from '@mui/x-data-grid';
import SearchIcon from '@mui/icons-material/Search';
import NavigateBeforeIcon from '@mui/icons-material/NavigateBefore';
import NavigateNextIcon from '@mui/icons-material/NavigateNext';

/**
 * User data interface based on CSUSR01Y.cpy copybook structure
 * Represents a user security record
 */
export interface UserData {
  userId: string;          // 8 characters - USRID field
  firstName: string;       // 20 characters - FNAME field
  lastName: string;        // 20 characters - LNAME field
  userType: string;        // 1 character - UTYPE field (Admin/User/Operator)
  // Additional fields for internal use
  id?: string;             // Unique identifier for DataGrid
}

/**
 * Props for the UserTable component
 */
export interface UserTableProps {
  /** Array of user data to display */
  users: UserData[];
  
  /** Callback when a row is selected for update or delete */
  onRowSelect?: (selectedUsers: UserData[], action: 'update' | 'delete') => void;
  
  /** Callback when search is triggered */
  onSearch?: (searchTerm: string) => void;
  
  /** Callback when page changes (F7=Backward, F8=Forward) */
  onPageChange?: (page: number) => void;
  
  /** Loading state for async operations */
  loading?: boolean;
  
  /** Error message to display (ERRMSG field) */
  error?: string;
  
  /** Current page number (PAGENUM field) */
  pageNumber?: number;
  
  /** Total number of pages */
  totalPages?: number;
  
  /** Page size (default: 10 rows to match BMS map) */
  pageSize?: number;
  
  /** Enable/disable sorting */
  sortable?: boolean;
  
  /** Enable/disable row selection */
  selectable?: boolean;
}

/**
 * UserTable Component
 * 
 * Reusable data grid component for displaying user list with search,
 * pagination, and row selection capabilities.
 */
const UserTable: React.FC<UserTableProps> = ({
  users,
  onRowSelect,
  onSearch,
  onPageChange,
  loading = false,
  error = '',
  pageNumber = 1,
  totalPages = 1,
  pageSize = 10, // Match BMS map 10 repeating rows
  sortable = true,
  selectable = true,
}) => {
  // Local state for search input (USRIDIN field - 8 characters max)
  const [searchTerm, setSearchTerm] = useState<string>('');
  
  // Local state for row selection
  const [rowSelectionModel, setRowSelectionModel] = useState<GridRowSelectionModel>([]);
  
  // Local state for sort model
  const [sortModel, setSortModel] = useState<GridSortModel>([]);

  /**
   * Handle search input change
   * Enforces 8-character limit matching USRIDIN BMS field
   */
  const handleSearchChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value.slice(0, 8); // Enforce 8-char max
    setSearchTerm(value.toUpperCase()); // Convert to uppercase for consistency
  }, []);

  /**
   * Handle search button click
   * Triggers onSearch callback with current search term
   */
  const handleSearchClick = useCallback(() => {
    if (onSearch) {
      onSearch(searchTerm);
    }
  }, [searchTerm, onSearch]);

  /**
   * Handle Enter key press in search field
   */
  const handleSearchKeyPress = useCallback((event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      handleSearchClick();
    }
  }, [handleSearchClick]);

  /**
   * Handle row selection change
   * Updates selection model and notifies parent component
   */
  const handleRowSelectionChange = useCallback((newSelectionModel: GridRowSelectionModel) => {
    setRowSelectionModel(newSelectionModel);
    
    if (onRowSelect && selectable) {
      const selectedUsers = users.filter(user => 
        newSelectionModel.includes(user.id || user.userId)
      );
      // Default action is 'update' - parent component determines actual action
      onRowSelect(selectedUsers, 'update');
    }
  }, [users, onRowSelect, selectable]);

  /**
   * Handle page backward navigation (F7 key)
   */
  const handlePageBackward = useCallback(() => {
    if (pageNumber > 1 && onPageChange) {
      onPageChange(pageNumber - 1);
    }
  }, [pageNumber, onPageChange]);

  /**
   * Handle page forward navigation (F8 key)
   */
  const handlePageForward = useCallback(() => {
    if (pageNumber < totalPages && onPageChange) {
      onPageChange(pageNumber + 1);
    }
  }, [pageNumber, totalPages, onPageChange]);

  /**
   * Column definitions for the DataGrid
   * Maps BMS field definitions to Material-UI column configuration
   * 
   * BMS Field Colors:
   * - Headers: NEUTRAL
   * - Data: BLUE
   * - Selection: GREEN with UNDERLINE
   */
  const columns: GridColDef[] = useMemo(() => [
    {
      field: 'userId',
      headerName: 'User ID',
      width: 120,
      sortable,
      headerAlign: 'left',
      align: 'left',
      // USRID field - 8 characters, BLUE color in BMS
      renderCell: (params) => (
        <Typography variant="body2" sx={{ color: '#1976d2', fontFamily: 'monospace' }}>
          {params.value || ''}
        </Typography>
      ),
    },
    {
      field: 'firstName',
      headerName: 'First Name',
      width: 200,
      sortable,
      headerAlign: 'left',
      align: 'left',
      // FNAME field - 20 characters, BLUE color in BMS
      renderCell: (params) => (
        <Typography variant="body2" sx={{ color: '#1976d2' }}>
          {params.value || ''}
        </Typography>
      ),
    },
    {
      field: 'lastName',
      headerName: 'Last Name',
      width: 200,
      sortable,
      headerAlign: 'left',
      align: 'left',
      // LNAME field - 20 characters, BLUE color in BMS
      renderCell: (params) => (
        <Typography variant="body2" sx={{ color: '#1976d2' }}>
          {params.value || ''}
        </Typography>
      ),
    },
    {
      field: 'userType',
      headerName: 'Type',
      width: 100,
      sortable,
      headerAlign: 'center',
      align: 'center',
      // UTYPE field - 1 character, BLUE color in BMS
      renderCell: (params) => (
        <Typography variant="body2" sx={{ color: '#1976d2', fontFamily: 'monospace' }}>
          {params.value || ''}
        </Typography>
      ),
    },
  ], [sortable]);

  /**
   * Prepare rows for DataGrid
   * Ensures each row has a unique id field
   */
  const rows = useMemo(() => {
    return users.map((user) => ({
      id: user.id || user.userId, // Use id if available, otherwise userId
      userId: user.userId,
      firstName: user.firstName,
      lastName: user.lastName,
      userType: user.userType,
    }));
  }, [users]);

  return (
    <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 2 }}>
      {/* Search User ID Section - USRIDIN field from BMS (GREEN, UNDERLINE) */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="body1" sx={{ color: '#00BCD4', minWidth: 'auto' }}>
          Search User ID:
        </Typography>
        <TextField
          value={searchTerm}
          onChange={handleSearchChange}
          onKeyPress={handleSearchKeyPress}
          placeholder="User ID"
          size="small"
          inputProps={{
            maxLength: 8, // USRIDIN field length
            style: {
              textTransform: 'uppercase',
              fontFamily: 'monospace',
              color: '#4CAF50', // GREEN color from BMS
              borderBottom: '2px solid #4CAF50', // UNDERLINE attribute
            },
          }}
          sx={{
            width: 150,
            '& .MuiOutlinedInput-root': {
              '& fieldset': {
                borderColor: '#4CAF50',
              },
              '&:hover fieldset': {
                borderColor: '#66BB6A',
              },
              '&.Mui-focused fieldset': {
                borderColor: '#4CAF50',
              },
            },
          }}
        />
        <Tooltip title="Search">
          <IconButton
            onClick={handleSearchClick}
            size="small"
            sx={{ color: '#4CAF50' }}
            aria-label="search users"
          >
            <SearchIcon />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Page Number Display - PAGENUM field from BMS */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
        <Typography variant="h6" sx={{ color: '#757575' }}>
          List Users
        </Typography>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography variant="body2" sx={{ color: '#00BCD4' }}>
            Page:
          </Typography>
          <Typography variant="body2" sx={{ color: '#1976d2', fontFamily: 'monospace', minWidth: '60px' }}>
            {pageNumber} of {totalPages}
          </Typography>
        </Box>
      </Box>

      {/* Helper Text - matching BMS line 21 */}
      <Typography variant="body2" sx={{ color: '#757575', mb: 1 }}>
        Type 'U' to Update or 'D' to Delete a User from the list
      </Typography>

      {/* Error Message Display - ERRMSG field (RED, BRT) */}
      {error && (
        <Alert 
          severity="error" 
          sx={{ 
            mb: 2,
            backgroundColor: '#ffebee',
            color: '#c62828',
            fontWeight: 'bold', // BRT attribute
            '& .MuiAlert-icon': {
              color: '#c62828',
            },
          }}
        >
          {error}
        </Alert>
      )}

      {/* DataGrid Component - displays 10 rows matching BMS repeating fields */}
      <Paper elevation={2} sx={{ width: '100%' }}>
        <DataGrid
          rows={rows}
          columns={columns}
          loading={loading}
          checkboxSelection={selectable}
          disableRowSelectionOnClick={false}
          rowSelectionModel={rowSelectionModel}
          onRowSelectionModelChange={handleRowSelectionChange}
          sortModel={sortModel}
          onSortModelChange={setSortModel}
          pageSizeOptions={[pageSize]}
          hideFooterPagination={true} // Use custom pagination controls
          hideFooter={true} // Hide default footer, use custom controls
          autoHeight
          density="comfortable"
          sx={{
            border: 'none',
            '& .MuiDataGrid-columnHeaders': {
              backgroundColor: '#f5f5f5',
              color: '#757575', // NEUTRAL color for headers
              fontWeight: 'bold',
              fontSize: '0.875rem',
            },
            '& .MuiDataGrid-cell': {
              borderBottom: '1px solid #e0e0e0',
            },
            '& .MuiDataGrid-row:hover': {
              backgroundColor: '#f5f5f5',
            },
            '& .MuiDataGrid-row.Mui-selected': {
              backgroundColor: '#e3f2fd',
              '&:hover': {
                backgroundColor: '#bbdefb',
              },
            },
            '& .MuiCheckbox-root': {
              color: '#4CAF50', // GREEN color for checkboxes (SEL fields)
            },
            '& .MuiCheckbox-root.Mui-checked': {
              color: '#4CAF50',
            },
          }}
          initialState={{
            pagination: {
              paginationModel: { pageSize, page: 0 },
            },
          }}
        />
      </Paper>

      {/* Custom Pagination Controls - F7=Backward, F8=Forward from BMS */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 2 }}>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Tooltip title="F7=Backward">
            <span>
              <IconButton
                onClick={handlePageBackward}
                disabled={pageNumber <= 1}
                size="small"
                sx={{
                  color: '#FFC107', // YELLOW color for function keys
                  '&:disabled': {
                    color: '#e0e0e0',
                  },
                }}
                aria-label="previous page"
              >
                <NavigateBeforeIcon />
              </IconButton>
            </span>
          </Tooltip>
          <Typography variant="body2" sx={{ color: '#FFC107', alignSelf: 'center' }}>
            F7=Backward
          </Typography>
        </Box>
        
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Typography variant="body2" sx={{ color: '#FFC107', alignSelf: 'center' }}>
            F8=Forward
          </Typography>
          <Tooltip title="F8=Forward">
            <span>
              <IconButton
                onClick={handlePageForward}
                disabled={pageNumber >= totalPages}
                size="small"
                sx={{
                  color: '#FFC107', // YELLOW color for function keys
                  '&:disabled': {
                    color: '#e0e0e0',
                  },
                }}
                aria-label="next page"
              >
                <NavigateNextIcon />
              </IconButton>
            </span>
          </Tooltip>
        </Box>
      </Box>

      {/* Footer with function key help - matching BMS line 24 */}
      <Box sx={{ mt: 1 }}>
        <Typography variant="caption" sx={{ color: '#FFC107' }}>
          ENTER=Continue  F3=Back  F7=Backward  F8=Forward
        </Typography>
      </Box>
    </Box>
  );
};

export default UserTable;
