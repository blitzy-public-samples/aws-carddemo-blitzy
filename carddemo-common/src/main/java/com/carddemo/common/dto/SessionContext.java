/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.dto;

import java.io.Serializable;

/**
 * :purpose: Externalized, server-side replacement for the CICS pseudo-conversational
 *  COMMAREA ``CARDDEMO-COMMAREA`` (copybook ``COCOM01Y``). It carries routing plus
 *  user, customer, account, and card context across the stateless REST calls that
 *  replace the legacy ``XCTL`` / ``RETURN TRANSID`` program transfers, and is stored
 *  in Spring Session (Redis) and/or carried in JWT claims.
 * :note: Flattens the five COBOL group levels ``CDEMO-GENERAL-INFO``,
 *  ``CDEMO-CUSTOMER-INFO``, ``CDEMO-ACCOUNT-INFO``, ``CDEMO-CARD-INFO``, and
 *  ``CDEMO-MORE-INFO`` into a single sixteen-field holder.
 */
public class SessionContext implements Serializable {

    /**
     * :purpose: Serialization version identifier required for stable Spring Session
     *  (Redis) (de)serialization of this holder.
     */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Enumerates COMMAREA ``CDEMO-USER-TYPE`` PIC X(01) 88-levels:
     *  ``CDEMO-USRTYP-ADMIN`` (VALUE 'A') and ``CDEMO-USRTYP-USER`` (VALUE 'U').
     */
    public enum UserType {

        /** :purpose: COBOL ``88 CDEMO-USRTYP-ADMIN VALUE 'A'`` — administrator user type. */
        CDEMO_USRTYP_ADMIN('A'),

        /** :purpose: COBOL ``88 CDEMO-USRTYP-USER VALUE 'U'`` — standard user type. */
        CDEMO_USRTYP_USER('U');

        /** :purpose: Single-character COMMAREA code carried in ``CDEMO-USER-TYPE``. */
        private final char code;

        UserType(char code) {
            this.code = code;
        }

        /**
         * :returns: the single-character COMMAREA code ('A' for admin, 'U' for user).
         */
        public char getCode() {
            return code;
        }

        /**
         * :purpose: Tolerant lookup of a {@code UserType} from a raw COMMAREA value.
         *  Session state (unlike request input) may be unset before sign-on, so this
         *  never throws: it returns {@code null} for a null/blank code and for any code
         *  that matches neither 'A' nor 'U'.
         * :param code: the raw value; leading/trailing spaces are trimmed and the first
         *  character is upper-cased before matching.
         * :returns: the matching {@code UserType}, or {@code null} when the value is
         *  null, blank, or unrecognized.
         */
        public static UserType fromCode(String code) {
            if (code == null) {
                return null;
            }
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            char first = Character.toUpperCase(trimmed.charAt(0));
            for (UserType type : values()) {
                if (type.code == first) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * :purpose: Enumerates COMMAREA ``CDEMO-PGM-CONTEXT`` PIC 9(01) 88-levels:
     *  ``CDEMO-PGM-ENTER`` (VALUE 0) and ``CDEMO-PGM-REENTER`` (VALUE 1). Models the
     *  pseudo-conversational first-entry (0) versus re-entry (1) flags used across the
     *  seventeen online programs.
     */
    public enum ProgramContext {

        /** :purpose: COBOL ``88 CDEMO-PGM-ENTER VALUE 0`` — first entry into a program. */
        CDEMO_PGM_ENTER(0),

        /** :purpose: COBOL ``88 CDEMO-PGM-REENTER VALUE 1`` — re-entry into a program. */
        CDEMO_PGM_REENTER(1);

        /** :purpose: Numeric COMMAREA code carried in ``CDEMO-PGM-CONTEXT``. */
        private final int code;

        ProgramContext(int code) {
            this.code = code;
        }

        /**
         * :returns: the numeric COMMAREA code (0 for first entry, 1 for re-entry).
         */
        public int getCode() {
            return code;
        }

        /**
         * :purpose: Look up a {@code ProgramContext} from its numeric COMMAREA code.
         * :param code: the numeric context flag (0 or 1).
         * :returns: the matching {@code ProgramContext}, or {@code null} for any value
         *  other than 0 or 1.
         */
        public static ProgramContext fromCode(int code) {
            for (ProgramContext context : values()) {
                if (context.code == code) {
                    return context;
                }
            }
            return null;
        }
    }

    /** :purpose: COBOL ``CDEMO-FROM-TRANID`` PIC X(04) — originating transaction id. */
    private String fromTranid;

    /** :purpose: COBOL ``CDEMO-FROM-PROGRAM`` PIC X(08) — originating program name. */
    private String fromProgram;

    /** :purpose: COBOL ``CDEMO-TO-TRANID`` PIC X(04) — target transaction id. */
    private String toTranid;

    /** :purpose: COBOL ``CDEMO-TO-PROGRAM`` PIC X(08) — target program name. */
    private String toProgram;

    /** :purpose: COBOL ``CDEMO-USER-ID`` PIC X(08) — signed-on user id. */
    private String userId;

    /** :purpose: COBOL ``CDEMO-USER-TYPE`` PIC X(01) + 88-levels — user role. */
    private UserType userType;

    /** :purpose: COBOL ``CDEMO-PGM-CONTEXT`` PIC 9(01) + 88-levels — entry/re-entry flag. */
    private ProgramContext programContext;

    /** :purpose: COBOL ``CDEMO-CUST-ID`` PIC 9(09) — customer identifier. */
    private Long custId;

    /** :purpose: COBOL ``CDEMO-CUST-FNAME`` PIC X(25) — customer first name. */
    private String custFname;

    /** :purpose: COBOL ``CDEMO-CUST-MNAME`` PIC X(25) — customer middle name. */
    private String custMname;

    /** :purpose: COBOL ``CDEMO-CUST-LNAME`` PIC X(25) — customer last name. */
    private String custLname;

    /** :purpose: COBOL ``CDEMO-ACCT-ID`` PIC 9(11) — account identifier. */
    private Long acctId;

    /** :purpose: COBOL ``CDEMO-ACCT-STATUS`` PIC X(01) — account status flag. */
    private String acctStatus;

    /**
     * :purpose: COBOL ``CDEMO-CARD-NUM`` PIC 9(16) — sixteen-digit card number kept as a
     *  {@code String} to preserve leading zeros (frozen-contract identifier, never used
     *  arithmetically).
     */
    private String cardNum;

    /** :purpose: COBOL ``CDEMO-LAST-MAP`` PIC X(7) — last BMS map name. */
    private String lastMap;

    /** :purpose: COBOL ``CDEMO-LAST-MAPSET`` PIC X(7) — last BMS mapset name. */
    private String lastMapset;

    /**
     * :purpose: No-argument constructor required by (de)serialization frameworks
     *  (Spring Session / Redis) and by mapper assembly.
     */
    public SessionContext() {
    }

    /**
     * :returns: the originating transaction id (``CDEMO-FROM-TRANID``).
     */
    public String getFromTranid() {
        return fromTranid;
    }

    /**
     * :param fromTranid: the originating transaction id (``CDEMO-FROM-TRANID``).
     */
    public void setFromTranid(String fromTranid) {
        this.fromTranid = fromTranid;
    }

    /**
     * :returns: the originating program name (``CDEMO-FROM-PROGRAM``).
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * :param fromProgram: the originating program name (``CDEMO-FROM-PROGRAM``).
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * :returns: the target transaction id (``CDEMO-TO-TRANID``).
     */
    public String getToTranid() {
        return toTranid;
    }

    /**
     * :param toTranid: the target transaction id (``CDEMO-TO-TRANID``).
     */
    public void setToTranid(String toTranid) {
        this.toTranid = toTranid;
    }

    /**
     * :returns: the target program name (``CDEMO-TO-PROGRAM``).
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * :param toProgram: the target program name (``CDEMO-TO-PROGRAM``).
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * :returns: the signed-on user id (``CDEMO-USER-ID``).
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :param userId: the signed-on user id (``CDEMO-USER-ID``).
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :returns: the user role (``CDEMO-USER-TYPE``), or {@code null} when unset.
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * :param userType: the user role (``CDEMO-USER-TYPE``).
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * :returns: the entry/re-entry flag (``CDEMO-PGM-CONTEXT``), or {@code null} when unset.
     */
    public ProgramContext getProgramContext() {
        return programContext;
    }

    /**
     * :param programContext: the entry/re-entry flag (``CDEMO-PGM-CONTEXT``).
     */
    public void setProgramContext(ProgramContext programContext) {
        this.programContext = programContext;
    }

    /**
     * :returns: the customer identifier (``CDEMO-CUST-ID``).
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * :param custId: the customer identifier (``CDEMO-CUST-ID``).
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * :returns: the customer first name (``CDEMO-CUST-FNAME``).
     */
    public String getCustFname() {
        return custFname;
    }

    /**
     * :param custFname: the customer first name (``CDEMO-CUST-FNAME``).
     */
    public void setCustFname(String custFname) {
        this.custFname = custFname;
    }

    /**
     * :returns: the customer middle name (``CDEMO-CUST-MNAME``).
     */
    public String getCustMname() {
        return custMname;
    }

    /**
     * :param custMname: the customer middle name (``CDEMO-CUST-MNAME``).
     */
    public void setCustMname(String custMname) {
        this.custMname = custMname;
    }

    /**
     * :returns: the customer last name (``CDEMO-CUST-LNAME``).
     */
    public String getCustLname() {
        return custLname;
    }

    /**
     * :param custLname: the customer last name (``CDEMO-CUST-LNAME``).
     */
    public void setCustLname(String custLname) {
        this.custLname = custLname;
    }

    /**
     * :returns: the account identifier (``CDEMO-ACCT-ID``).
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * :param acctId: the account identifier (``CDEMO-ACCT-ID``).
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * :returns: the account status flag (``CDEMO-ACCT-STATUS``).
     */
    public String getAcctStatus() {
        return acctStatus;
    }

    /**
     * :param acctStatus: the account status flag (``CDEMO-ACCT-STATUS``).
     */
    public void setAcctStatus(String acctStatus) {
        this.acctStatus = acctStatus;
    }

    /**
     * :returns: the sixteen-digit card number as a string (``CDEMO-CARD-NUM``).
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * :param cardNum: the sixteen-digit card number as a string (``CDEMO-CARD-NUM``).
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * :returns: the last BMS map name (``CDEMO-LAST-MAP``).
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * :param lastMap: the last BMS map name (``CDEMO-LAST-MAP``).
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * :returns: the last BMS mapset name (``CDEMO-LAST-MAPSET``).
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * :param lastMapset: the last BMS mapset name (``CDEMO-LAST-MAPSET``).
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }
}
