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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

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
     * :purpose: Canonical ``HttpSession`` attribute name under which this
     *  COMMAREA-replacement context is stored by every CardDemo service and the
     *  API gateway. It is the single coordination point for the shared,
     *  Redis-backed session: sign-on writes this key and every other component
     *  (menu role gate, account, card, transaction, bill-pay, and the
     *  session-derived authentication filter) reads the same key, so a
     *  divergent literal can never break the role gate again.
     */
    public static final String SESSION_ATTRIBUTE_NAME = SessionAttributes.SESSION_CONTEXT;

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
         * :purpose: Serialize the user type as its exact one-character COMMAREA code so
         *  the JSON wire value is ``"A"`` or ``"U"`` rather than the Java enum name.
         * :returns: the single-character code as a one-character string.
         */
        @JsonValue
        public String toJsonValue() {
            return String.valueOf(code);
        }

        /**
         * :purpose: Tolerant lookup of a {@code UserType} from a raw COMMAREA value, used
         *  both for session hydration and as the Jackson deserialization factory. The
         *  COMMAREA ``CDEMO-USER-TYPE`` field is spaces before sign-on, so a {@code null}
         *  or blank value yields {@code null} (fail-closed for authorization guards)
         *  rather than throwing; otherwise the value is trimmed, its first character is
         *  upper-cased, and matched against ``'A'`` ({@code CDEMO_USRTYP_ADMIN}) or ``'U'``
         *  ({@code CDEMO_USRTYP_USER}). An unrecognized character also yields {@code null}.
         * :param code: the raw COMMAREA value (may be null, blank, or mixed case).
         * :returns: {@code CDEMO_USRTYP_ADMIN} for ``'A'``, {@code CDEMO_USRTYP_USER} for
         *  ``'U'``, or {@code null} for null/blank/unrecognized input.
         */
        @JsonCreator
        public static UserType fromCode(String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            char first = Character.toUpperCase(code.trim().charAt(0));
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
         * :purpose: Serialize the program context as its numeric COMMAREA code so the
         *  JSON wire value is ``0`` or ``1`` rather than the Java enum name.
         * :returns: the numeric COMMAREA code (0 for first entry, 1 for re-entry).
         */
        @JsonValue
        public int getCode() {
            return code;
        }

        /**
         * :purpose: Look up a {@code ProgramContext} from its numeric COMMAREA code, used
         *  both for session hydration and as the Jackson deserialization factory.
         * :param code: the numeric context flag (0 or 1).
         * :returns: the matching {@code ProgramContext}, or {@code null} for any value
         *  other than 0 or 1.
         */
        @JsonCreator
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
    @Size(max = 4)
    private String fromTranid;

    /** :purpose: COBOL ``CDEMO-FROM-PROGRAM`` PIC X(08) — originating program name. */
    @Size(max = 8)
    private String fromProgram;

    /** :purpose: COBOL ``CDEMO-TO-TRANID`` PIC X(04) — target transaction id. */
    @Size(max = 4)
    private String toTranid;

    /** :purpose: COBOL ``CDEMO-TO-PROGRAM`` PIC X(08) — target program name. */
    @Size(max = 8)
    private String toProgram;

    /** :purpose: COBOL ``CDEMO-USER-ID`` PIC X(08) — signed-on user id. */
    @Size(max = 8)
    private String userId;

    /** :purpose: COBOL ``CDEMO-USER-TYPE`` PIC X(01) + 88-levels — user role. */
    private UserType userType;

    /** :purpose: COBOL ``CDEMO-PGM-CONTEXT`` PIC 9(01) + 88-levels — entry/re-entry flag. */
    private ProgramContext programContext;

    /**
     * :purpose: COBOL ``CDEMO-CUST-ID`` PIC 9(09) — customer identifier. Serialized as a
     *  JSON string so the frontend receives an exact-width numeric identifier without
     *  precision loss.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Digits(integer = 9, fraction = 0)
    private Long custId;

    /** :purpose: COBOL ``CDEMO-CUST-FNAME`` PIC X(25) — customer first name. */
    @Size(max = 25)
    private String custFname;

    /** :purpose: COBOL ``CDEMO-CUST-MNAME`` PIC X(25) — customer middle name. */
    @Size(max = 25)
    private String custMname;

    /** :purpose: COBOL ``CDEMO-CUST-LNAME`` PIC X(25) — customer last name. */
    @Size(max = 25)
    private String custLname;

    /**
     * :purpose: COBOL ``CDEMO-ACCT-ID`` PIC 9(11) — account identifier. Serialized as a
     *  JSON string so the frontend receives an exact-width numeric identifier without
     *  precision loss.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Digits(integer = 11, fraction = 0)
    private Long acctId;

    /** :purpose: COBOL ``CDEMO-ACCT-STATUS`` PIC X(01) — account status flag. */
    @Size(max = 1)
    private String acctStatus;

    /**
     * :purpose: COBOL ``CDEMO-CARD-NUM`` PIC 9(16) — sixteen-digit card number kept as a
     *  {@code String} to preserve leading zeros (frozen-contract identifier, never used
     *  arithmetically).
     */
    @Size(max = 16)
    private String cardNum;

    /** :purpose: COBOL ``CDEMO-LAST-MAP`` PIC X(7) — last BMS map name. */
    @Size(max = 7)
    private String lastMap;

    /** :purpose: COBOL ``CDEMO-LAST-MAPSET`` PIC X(7) — last BMS mapset name. */
    @Size(max = 7)
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
