/**
 * Client-side Form Validation Utilities
 * 
 * Replicates COBOL PIC clause validation patterns and 88-level condition checks from:
 * - app/cpy/CVCUS01Y.cpy (Customer data structure)
 * - app/cpy/CVACT01Y.cpy (Account data structure)
 * - app/cpy/CVACT02Y.cpy (Card data structure)
 * - app/cpy/CVTRA05Y.cpy (Transaction data structure)
 * - app/cpy/CSLKPCDY.cpy (Lookup codes - state codes, area codes, zip codes)
 * 
 * All validation functions return { isValid: boolean, errorMessage: string }
 * preserving COBOL validation rules per Agent Action Plan section 0.10
 */

/**
 * Valid North American phone area codes from NANPA
 * Replaces COBOL 88-level VALID-PHONE-AREA-CODE from CSLKPCDY.cpy lines 30-1010
 */
const VALID_AREA_CODES = new Set([
  '201', '202', '203', '204', '205', '206', '207', '208', '209', '210',
  '212', '213', '214', '215', '216', '217', '218', '219', '220', '223',
  '224', '225', '226', '228', '229', '231', '234', '236', '239', '240',
  '242', '246', '248', '249', '250', '251', '252', '253', '254', '256',
  '260', '262', '264', '267', '268', '269', '270', '272', '276', '279',
  '281', '284', '289', '301', '302', '303', '304', '305', '306', '307',
  '308', '309', '310', '312', '313', '314', '315', '316', '317', '318',
  '319', '320', '321', '323', '325', '326', '330', '331', '332', '334',
  '336', '337', '339', '341', '343', '345', '346', '347', '351', '352',
  '360', '361', '364', '365', '367', '380', '385', '386', '401', '402',
  '403', '404', '405', '406', '407', '408', '409', '410', '412', '413',
  '414', '415', '416', '417', '418', '419', '423', '424', '425', '428',
  '430', '431', '432', '434', '435', '437', '438', '440', '441', '442',
  '443', '445', '447', '448', '450', '456', '458', '463', '464', '468',
  '469', '470', '472', '473', '474', '475', '478', '479', '480', '484',
  '500', '501', '502', '503', '504', '505', '506', '507', '508', '509',
  '510', '512', '513', '514', '515', '516', '517', '518', '519', '520',
  '530', '531', '534', '539', '540', '541', '548', '551', '557', '559',
  '561', '562', '563', '564', '567', '570', '571', '573', '574', '575',
  '579', '580', '581', '582', '585', '586', '587', '588', '600', '601',
  '602', '603', '604', '605', '606', '607', '608', '609', '610', '612',
  '613', '614', '615', '616', '617', '618', '619', '620', '623', '626',
  '628', '629', '630', '631', '636', '639', '640', '641', '646', '647',
  '649', '650', '651', '657', '658', '659', '660', '661', '662', '667',
  '669', '670', '671', '672', '678', '680', '681', '682', '684', '700',
  '701', '702', '703', '704', '705', '706', '707', '708', '709', '710',
  '712', '713', '714', '715', '716', '717', '718', '719', '720', '721',
  '724', '725', '726', '727', '731', '732', '734', '737', '740', '743',
  '747', '754', '757', '760', '762', '763', '765', '767', '769', '770',
  '772', '773', '774', '775', '778', '779', '780', '781', '782', '784',
  '785', '786', '787', '800', '801', '802', '803', '804', '805', '806',
  '807', '808', '809', '810', '812', '813', '814', '815', '816', '817',
  '818', '819', '820', '825', '828', '830', '831', '832', '833', '835',
  '838', '839', '840', '843', '844', '845', '847', '848', '849', '850',
  '854', '855', '856', '857', '858', '859', '860', '862', '863', '864',
  '865', '866', '867', '868', '869', '870', '872', '873', '876', '877',
  '878', '879', '880', '881', '882', '888', '898', '899', '900', '911',
  '922', '933', '944', '955', '966', '977', '988', '999'
]);

/**
 * Valid US state codes
 * Replaces COBOL 88-level VALID-US-STATE-CODE from CSLKPCDY.cpy lines 1013-1069
 */
const VALID_STATE_CODES = new Set([
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'FL', 'GA',
  'HI', 'ID', 'IL', 'IN', 'IA', 'KS', 'KY', 'LA', 'ME', 'MD',
  'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ',
  'NM', 'NY', 'NC', 'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC',
  'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY',
  'DC', 'AS', 'GU', 'MP', 'PR', 'VI'
]);

/**
 * Valid state and zip code prefix combinations
 * Replaces COBOL 88-level VALID-US-STATE-ZIP-CD2-COMBO from CSLKPCDY.cpy lines 1073-1313
 * Format: StateCode + First2DigitsOfZip (e.g., 'CA90', 'NY10')
 */
const VALID_STATE_ZIP_COMBOS = new Set([
  'AA34', 'AE90', 'AE91', 'AE92', 'AE93', 'AE94', 'AE95', 'AE96', 'AE97', 'AE98',
  'AK99', 'AL35', 'AL36', 'AP96', 'AR71', 'AR72', 'AS96', 'AZ85', 'AZ86',
  'CA90', 'CA91', 'CA92', 'CA93', 'CA94', 'CA95', 'CA96', 'CO80', 'CO81',
  'CT06', 'DC20', 'DE19', 'FL32', 'FL33', 'FL34',
  'GA30', 'GA31', 'GU96', 'HI96', 'IA50', 'IA51', 'IA52',
  'ID83', 'IL60', 'IL61', 'IL62', 'IN46', 'IN47',
  'KS66', 'KS67', 'KY40', 'KY41', 'KY42',
  'LA70', 'LA71', 'MA01', 'MA02', 'MD20', 'MD21',
  'ME03', 'ME04', 'MI48', 'MI49', 'MN55', 'MN56',
  'MO63', 'MO64', 'MO65', 'MP96', 'MS38', 'MS39',
  'MT59', 'NC27', 'NC28', 'ND58', 'NE68', 'NE69',
  'NH03', 'NJ07', 'NJ08', 'NM87', 'NM88',
  'NV89', 'NY10', 'NY11', 'NY12', 'NY13', 'NY14',
  'OH43', 'OH44', 'OH45', 'OK73', 'OK74',
  'OR97', 'PA15', 'PA16', 'PA17', 'PA18', 'PA19',
  'PR00', 'PR006', 'PR007', 'PR009', 'RI02',
  'SC29', 'SD57', 'TN37', 'TN38',
  'TX75', 'TX76', 'TX77', 'TX78', 'TX79',
  'UT84', 'VA20', 'VA22', 'VA23', 'VA24',
  'VI00', 'VI008', 'VT05', 'WA98', 'WA99',
  'WI53', 'WI54', 'WV24', 'WV25', 'WV26', 'WY82', 'WY83'
]);

/**
 * Validates required field is not empty
 * Ensures field has a non-empty, non-whitespace value
 * 
 * @param {any} value - The value to validate
 * @param {string} fieldName - Name of the field for error messages
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateRequired(value, fieldName = 'Field') {
  if (value === null || value === undefined || value === '') {
    return {
      isValid: false,
      errorMessage: `${fieldName} is required`
    };
  }
  
  if (typeof value === 'string' && value.trim() === '') {
    return {
      isValid: false,
      errorMessage: `${fieldName} cannot be empty`
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates customer ID field
 * Replicates COBOL: CUST-ID PIC 9(09) from CVCUS01Y.cpy line 5
 * Must be exactly 9 numeric digits
 * 
 * @param {string} customerId - The customer ID to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateCustomerId(customerId) {
  if (!customerId || typeof customerId !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Customer ID is required'
    };
  }
  
  // Must be exactly 9 digits (PIC 9(09))
  const customerIdPattern = /^\d{9}$/;
  
  if (!customerIdPattern.test(customerId)) {
    return {
      isValid: false,
      errorMessage: 'Customer ID must be exactly 9 digits'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates account ID field
 * Replicates COBOL: ACCT-ID PIC 9(11) from CVACT01Y.cpy line 5
 * Must be exactly 11 numeric digits
 * 
 * @param {string} accountId - The account ID to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateAccountId(accountId) {
  if (!accountId || typeof accountId !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Account ID is required'
    };
  }
  
  // Must be exactly 11 digits (PIC 9(11))
  const accountIdPattern = /^\d{11}$/;
  
  if (!accountIdPattern.test(accountId)) {
    return {
      isValid: false,
      errorMessage: 'Account ID must be exactly 11 digits'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates card number field
 * Replicates COBOL: CARD-NUM PIC X(16) from CVACT02Y.cpy line 5
 * Must be exactly 16 characters (alphanumeric)
 * Performs basic Luhn algorithm check for card number validity
 * 
 * @param {string} cardNumber - The card number to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateCardNumber(cardNumber) {
  if (!cardNumber || typeof cardNumber !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Card number is required'
    };
  }
  
  // Remove any spaces or dashes
  const cleanedNumber = cardNumber.replace(/[\s-]/g, '');
  
  // Must be exactly 16 characters (PIC X(16))
  if (cleanedNumber.length !== 16) {
    return {
      isValid: false,
      errorMessage: 'Card number must be exactly 16 characters'
    };
  }
  
  // Must be numeric
  if (!/^\d{16}$/.test(cleanedNumber)) {
    return {
      isValid: false,
      errorMessage: 'Card number must contain only digits'
    };
  }
  
  // Luhn algorithm validation
  let sum = 0;
  let isEven = false;
  
  for (let i = cleanedNumber.length - 1; i >= 0; i--) {
    let digit = parseInt(cleanedNumber.charAt(i), 10);
    
    if (isEven) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }
    
    sum += digit;
    isEven = !isEven;
  }
  
  if (sum % 10 !== 0) {
    return {
      isValid: false,
      errorMessage: 'Card number is not valid'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates CVV code field
 * Replicates COBOL: CARD-CVV-CD PIC 9(03) from CVACT02Y.cpy line 7
 * Must be exactly 3 numeric digits
 * 
 * @param {string} cvv - The CVV code to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateCVV(cvv) {
  if (!cvv || typeof cvv !== 'string') {
    return {
      isValid: false,
      errorMessage: 'CVV is required'
    };
  }
  
  // Must be exactly 3 digits (PIC 9(03))
  const cvvPattern = /^\d{3}$/;
  
  if (!cvvPattern.test(cvv)) {
    return {
      isValid: false,
      errorMessage: 'CVV must be exactly 3 digits'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates Social Security Number field
 * Replicates COBOL: CUST-SSN PIC 9(09) from CVCUS01Y.cpy line 17
 * Must be exactly 9 numeric digits
 * 
 * @param {string} ssn - The SSN to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateSSN(ssn) {
  if (!ssn || typeof ssn !== 'string') {
    return {
      isValid: false,
      errorMessage: 'SSN is required'
    };
  }
  
  // Remove any dashes
  const cleanedSSN = ssn.replace(/-/g, '');
  
  // Must be exactly 9 digits (PIC 9(09))
  const ssnPattern = /^\d{9}$/;
  
  if (!ssnPattern.test(cleanedSSN)) {
    return {
      isValid: false,
      errorMessage: 'SSN must be exactly 9 digits'
    };
  }
  
  // SSN cannot be all zeros
  if (cleanedSSN === '000000000') {
    return {
      isValid: false,
      errorMessage: 'SSN cannot be all zeros'
    };
  }
  
  // First three digits cannot be 000 or 666
  const firstThree = cleanedSSN.substring(0, 3);
  if (firstThree === '000' || firstThree === '666') {
    return {
      isValid: false,
      errorMessage: 'Invalid SSN format'
    };
  }
  
  // First three digits cannot be 900-999 (reserved)
  if (parseInt(firstThree, 10) >= 900) {
    return {
      isValid: false,
      errorMessage: 'Invalid SSN format'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates phone number field with area code check
 * Replicates COBOL: CUST-PHONE-NUM-1 PIC X(15) from CVCUS01Y.cpy line 15
 * Validates area code against NANPA list (CSLKPCDY.cpy lines 30-1010)
 * 
 * @param {string} phoneNumber - The phone number to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validatePhoneNumber(phoneNumber) {
  if (!phoneNumber || typeof phoneNumber !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Phone number is required'
    };
  }
  
  // Remove all non-numeric characters
  const cleanedPhone = phoneNumber.replace(/\D/g, '');
  
  // Must be 10 or 11 digits (with optional country code)
  if (cleanedPhone.length < 10 || cleanedPhone.length > 11) {
    return {
      isValid: false,
      errorMessage: 'Phone number must be 10 or 11 digits'
    };
  }
  
  // Extract area code (first 3 digits after optional country code)
  let areaCode;
  if (cleanedPhone.length === 11) {
    // Assume first digit is country code (1 for US/Canada)
    if (cleanedPhone.charAt(0) !== '1') {
      return {
        isValid: false,
        errorMessage: 'Invalid country code for North American phone number'
      };
    }
    areaCode = cleanedPhone.substring(1, 4);
  } else {
    areaCode = cleanedPhone.substring(0, 3);
  }
  
  // Validate area code against NANPA list (88-level VALID-PHONE-AREA-CODE)
  if (!VALID_AREA_CODES.has(areaCode)) {
    return {
      isValid: false,
      errorMessage: `Area code ${areaCode} is not valid`
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates state code field
 * Replicates COBOL: CUST-ADDR-STATE-CD PIC X(02) from CVCUS01Y.cpy line 12
 * Validates against US state code list (CSLKPCDY.cpy lines 1013-1069)
 * 
 * @param {string} stateCode - The state code to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateStateCode(stateCode) {
  if (!stateCode || typeof stateCode !== 'string') {
    return {
      isValid: false,
      errorMessage: 'State code is required'
    };
  }
  
  // Must be exactly 2 characters (PIC X(02))
  if (stateCode.length !== 2) {
    return {
      isValid: false,
      errorMessage: 'State code must be exactly 2 characters'
    };
  }
  
  // Convert to uppercase for validation
  const upperStateCode = stateCode.toUpperCase();
  
  // Validate against US state code list (88-level VALID-US-STATE-CODE)
  if (!VALID_STATE_CODES.has(upperStateCode)) {
    return {
      isValid: false,
      errorMessage: `State code ${stateCode} is not valid`
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates zip code field with state combination check
 * Replicates COBOL: CUST-ADDR-ZIP PIC X(10) from CVCUS01Y.cpy line 14
 * Validates state+zip combination (CSLKPCDY.cpy lines 1071-1313)
 * 
 * @param {string} zipCode - The zip code to validate
 * @param {string} stateCode - Optional state code for combination validation
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateZipCode(zipCode, stateCode = null) {
  if (!zipCode || typeof zipCode !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Zip code is required'
    };
  }
  
  // Remove any spaces or dashes
  const cleanedZip = zipCode.replace(/[\s-]/g, '');
  
  // Must be 5 or 9 digits (ZIP or ZIP+4 format)
  const zipPattern = /^\d{5}(\d{4})?$/;
  
  if (!zipPattern.test(cleanedZip)) {
    return {
      isValid: false,
      errorMessage: 'Zip code must be 5 or 9 digits'
    };
  }
  
  // If state code provided, validate state+zip combination
  if (stateCode && typeof stateCode === 'string') {
    const upperStateCode = stateCode.toUpperCase();
    const zipPrefix = cleanedZip.substring(0, 2);
    const combo = upperStateCode + zipPrefix;
    
    // Check against valid state-zip combinations (88-level VALID-US-STATE-ZIP-CD2-COMBO)
    if (!VALID_STATE_ZIP_COMBOS.has(combo)) {
      return {
        isValid: false,
        errorMessage: `Zip code ${cleanedZip} is not valid for state ${stateCode}`
      };
    }
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates credit limit field
 * Replicates COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99 from CVACT01Y.cpy line 8
 * Must be a valid monetary amount with up to 10 integer digits and exactly 2 decimal places
 * 
 * @param {string|number} creditLimit - The credit limit to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateCreditLimit(creditLimit) {
  if (creditLimit === null || creditLimit === undefined || creditLimit === '') {
    return {
      isValid: false,
      errorMessage: 'Credit limit is required'
    };
  }
  
  // Convert to string for validation
  const limitStr = creditLimit.toString().trim();
  
  // Must be a valid decimal number with up to 2 decimal places
  const limitPattern = /^-?\d{1,10}(\.\d{1,2})?$/;
  
  if (!limitPattern.test(limitStr)) {
    return {
      isValid: false,
      errorMessage: 'Credit limit must be a valid amount with up to 10 digits and 2 decimal places'
    };
  }
  
  const limitValue = parseFloat(limitStr);
  
  // Must be a valid number
  if (isNaN(limitValue)) {
    return {
      isValid: false,
      errorMessage: 'Credit limit must be a valid number'
    };
  }
  
  // Must be positive (88-level LIMIT-VALID VALUE 1000 THRU 999999999)
  if (limitValue < 1000) {
    return {
      isValid: false,
      errorMessage: 'Credit limit must be at least $1,000.00'
    };
  }
  
  // Maximum value for PIC S9(10)V99
  if (limitValue > 9999999999.99) {
    return {
      isValid: false,
      errorMessage: 'Credit limit cannot exceed $9,999,999,999.99'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates transaction amount field
 * Replicates COBOL: TRAN-AMT PIC S9(09)V99 from CVTRA05Y.cpy line 10
 * Must be a valid monetary amount with up to 9 integer digits and exactly 2 decimal places
 * 
 * @param {string|number} amount - The transaction amount to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateAmount(amount) {
  if (amount === null || amount === undefined || amount === '') {
    return {
      isValid: false,
      errorMessage: 'Amount is required'
    };
  }
  
  // Convert to string for validation
  const amountStr = amount.toString().trim();
  
  // Must be a valid decimal number with up to 2 decimal places
  const amountPattern = /^-?\d{1,9}(\.\d{1,2})?$/;
  
  if (!amountPattern.test(amountStr)) {
    return {
      isValid: false,
      errorMessage: 'Amount must be a valid number with up to 9 digits and 2 decimal places'
    };
  }
  
  const amountValue = parseFloat(amountStr);
  
  // Must be a valid number
  if (isNaN(amountValue)) {
    return {
      isValid: false,
      errorMessage: 'Amount must be a valid number'
    };
  }
  
  // Must be greater than zero for transactions
  if (amountValue <= 0) {
    return {
      isValid: false,
      errorMessage: 'Amount must be greater than zero'
    };
  }
  
  // Maximum value for PIC S9(09)V99
  if (amountValue > 999999999.99) {
    return {
      isValid: false,
      errorMessage: 'Amount cannot exceed $999,999,999.99'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates date field in YYYY-MM-DD format
 * Replicates COBOL: CUST-DOB-YYYY-MM-DD PIC X(10) from CVCUS01Y.cpy line 19
 * Must be a valid date in ISO format
 * 
 * @param {string} date - The date to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateDate(date) {
  if (!date || typeof date !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Date is required'
    };
  }
  
  // Must match YYYY-MM-DD format (PIC X(10))
  const datePattern = /^\d{4}-\d{2}-\d{2}$/;
  
  if (!datePattern.test(date)) {
    return {
      isValid: false,
      errorMessage: 'Date must be in YYYY-MM-DD format'
    };
  }
  
  // Validate as a real date
  const [year, month, day] = date.split('-').map(num => parseInt(num, 10));
  const dateObj = new Date(year, month - 1, day);
  
  if (dateObj.getFullYear() !== year || 
      dateObj.getMonth() !== month - 1 || 
      dateObj.getDate() !== day) {
    return {
      isValid: false,
      errorMessage: 'Invalid date'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates card expiration date field in MM/YY format
 * Must be a future date relative to current date
 * 
 * @param {string} expirationDate - The expiration date to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateExpirationDate(expirationDate) {
  if (!expirationDate || typeof expirationDate !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Expiration date is required'
    };
  }
  
  // Must match MM/YY format
  const expirationPattern = /^(0[1-9]|1[0-2])\/\d{2}$/;
  
  if (!expirationPattern.test(expirationDate)) {
    return {
      isValid: false,
      errorMessage: 'Expiration date must be in MM/YY format'
    };
  }
  
  const [month, year] = expirationDate.split('/').map(num => parseInt(num, 10));
  
  // Convert 2-digit year to 4-digit year (assuming 20xx)
  const fullYear = 2000 + year;
  
  // Create date object for last day of expiration month
  const expDate = new Date(fullYear, month, 0);
  const today = new Date();
  
  // Card must not be expired
  if (expDate < today) {
    return {
      isValid: false,
      errorMessage: 'Card has expired'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates name field (alphanumeric with length constraint)
 * Replicates COBOL: CUST-FIRST-NAME, CUST-LAST-NAME PIC X(25) from CVCUS01Y.cpy lines 6, 8
 * Must be 1-25 characters, alphanumeric with spaces, hyphens, and apostrophes allowed
 * 
 * @param {string} name - The name to validate
 * @param {number} maxLength - Maximum length (default 25 per COBOL PIC X(25))
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateName(name, maxLength = 25) {
  if (!name || typeof name !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Name is required'
    };
  }
  
  const trimmedName = name.trim();
  
  // Must not be empty after trimming
  if (trimmedName.length === 0) {
    return {
      isValid: false,
      errorMessage: 'Name cannot be empty'
    };
  }
  
  // Must not exceed maximum length (PIC X(25) default)
  if (trimmedName.length > maxLength) {
    return {
      isValid: false,
      errorMessage: `Name cannot exceed ${maxLength} characters`
    };
  }
  
  // Must contain only letters, spaces, hyphens, and apostrophes
  const namePattern = /^[a-zA-Z\s\-']+$/;
  
  if (!namePattern.test(trimmedName)) {
    return {
      isValid: false,
      errorMessage: 'Name can only contain letters, spaces, hyphens, and apostrophes'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}

/**
 * Validates email address format
 * Standard email validation for modern web applications
 * 
 * @param {string} email - The email address to validate
 * @returns {{ isValid: boolean, errorMessage: string }}
 */
export function validateEmail(email) {
  if (!email || typeof email !== 'string') {
    return {
      isValid: false,
      errorMessage: 'Email is required'
    };
  }
  
  const trimmedEmail = email.trim();
  
  // Basic email pattern validation
  const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  
  if (!emailPattern.test(trimmedEmail)) {
    return {
      isValid: false,
      errorMessage: 'Invalid email format'
    };
  }
  
  // Email length check (reasonable maximum)
  if (trimmedEmail.length > 100) {
    return {
      isValid: false,
      errorMessage: 'Email cannot exceed 100 characters'
    };
  }
  
  return { isValid: true, errorMessage: '' };
}
