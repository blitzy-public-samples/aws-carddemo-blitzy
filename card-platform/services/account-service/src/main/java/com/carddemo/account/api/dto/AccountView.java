package com.carddemo.account.api.dto;

import java.math.BigDecimal;

/**
 * Account projection returned by {@code GET /accounts/{accountId}}.
 *
 * <p>Eleven components carry the eleven values that {@code 1200-SETUP-SCREEN-VARS.} at
 * {@code app/cbl/COACTVWC.cbl:L460} moves for a located account. The gate
 * {@code IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER} at
 * {@code app/cbl/COACTVWC.cbl:L471-L472} guards ten of those moves, and
 * {@code app/cbl/COACTVWC.cbl:L468} supplies the eleventh.
 *
 * <p>The components appear in the order the source moves them: L468 first, then L473 through L490.
 * That order differs from the declaration order of {@code 01 ACCOUNT-RECORD.} at
 * {@code app/cpy/CVACT01Y.cpy:L4}. The copybook declares the three dates at L10 through L12 ahead
 * of the two billing-cycle accumulators at L13 and L14. The source moves the two accumulators
 * first, and {@code src/main/resources/openapi.yaml} describes the order this record declares.
 *
 * <p>Three copybook fields reach this record under a changed shape or not at all.
 * {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy:L15} is absent, and the paragraph at
 * {@code app/cbl/COACTVWC.cbl:L473-L490} never moves it. {@code ACCT-EXPIRAION-DATE} at
 * {@code app/cpy/CVACT01Y.cpy:L11} arrives as {@link #expirationDate()}, the field name
 * {@code AccountEntity} carries. The trailing {@code FILLER PIC X(178)} at
 * {@code app/cpy/CVACT01Y.cpy:L17} maps to no component.
 * {@code card-platform/docs/traceability-matrix.md} records all three.
 *
 * <p>The record holds no customer identifier under any name. {@code 01 ACCOUNT-RECORD.} declares
 * none, and an account reaches a customer through the card cross-reference alone, which another
 * module owns.
 *
 * <p>Every component name matches the field of the same value on {@code AccountEntity}. A mapper
 * between the two renames nothing. The record carries values and holds no arithmetic, no format
 * check and no status check. {@code card-platform/docs/decision-log.md} records the component type
 * and naming decisions, and {@code card-platform/docs/business-rule-flags.md} carries the flagged
 * account rules.
 *
 * @param accountId          eleven-character account identifier, left-padded with zeros.
 *                           {@code app/cbl/COACTVWC.cbl:L468} moves {@code CC-ACCT-ID} into the
 *                           screen field {@code ACCTSIDO}. The declared source field is
 *                           {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 00000000001} at
 *                           columns 1 through 11, and text keeps those eight leading zeros through
 *                           serialization.
 * @param activeStatus       one-character status flag. Source
 *                           {@code ACCT-ACTIVE-STATUS PIC X(01)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L6}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L473}. The component carries the flag and
 *                           applies no rule. No source program reads the flag before it posts a
 *                           transaction. All 50 records of {@code app/data/ASCII/acctdata.txt} hold
 *                           {@code Y}.
 * @param currentBalance     posted balance at scale 2, twelve total digits. Source
 *                           {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L7}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L475}.
 * @param creditLimit        credit limit at scale 2, twelve total digits. Source
 *                           {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L8}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L477}. {@code app/cbl/CBTRN02C.cbl:L407}
 *                           compares the field with the working balance the two billing-cycle
 *                           accumulators produce.
 * @param cashCreditLimit    cash credit limit at scale 2, twelve total digits. Source
 *                           {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L9}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L479-L480}.
 * @param currentCycleCredit billing-cycle credit accumulator at scale 2, twelve total digits.
 *                           Source {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L13}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L482-L483}.
 *                           {@code app/cbl/CBTRN02C.cbl:L403} reads the field as the first term of
 *                           the credit-limit comparison.
 * @param currentCycleDebit  billing-cycle debit accumulator at scale 2, twelve total digits. Source
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L14}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L485}.
 *                           {@code app/cbl/CBTRN02C.cbl:L404} subtracts the field in the
 *                           credit-limit comparison.
 * @param openDate           open date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-OPEN-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L10}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L487}.
 *                           {@code app/cbl/COACTUPC.cbl:L4127-L4129} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, which places a
 *                           separator at position 5 and at position 8. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2014-11-20} at
 *                           columns 49 through 58.
 * @param expirationDate     expiration date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L11}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L488}.
 *                           {@code app/cbl/CBTRN02C.cbl:L414} compares the field as text with the
 *                           first ten characters of a 26-character transaction origin timestamp.
 *                           {@code app/cbl/COACTUPC.cbl:L4131-L4133} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2025-05-20} at
 *                           columns 59 through 68.
 * @param reissueDate        reissue date as ten characters in {@code YYYY-MM-DD} form. Source
 *                           {@code ACCT-REISSUE-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L12}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L489}.
 *                           {@code app/cbl/COACTUPC.cbl:L4135-L4137} slices the field as
 *                           {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Row 1 of
 *                           {@code app/data/ASCII/acctdata.txt} carries {@code 2025-05-20} at
 *                           columns 69 through 78.
 * @param groupId            account group identifier, ten characters. Source
 *                           {@code ACCT-GROUP-ID PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L16}, moved at
 *                           {@code app/cbl/COACTVWC.cbl:L490}.
 *                           {@code app/cbl/COACTUPC.cbl:L4139-L4140} applies
 *                           {@code FUNCTION LOWER-CASE} to both sides when it compares the field.
 *                           All 50 records of {@code app/data/ASCII/acctdata.txt} hold ten spaces.
 */
public record AccountView(
        String accountId,
        String activeStatus,
        BigDecimal currentBalance,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        BigDecimal currentCycleCredit,
        BigDecimal currentCycleDebit,
        String openDate,
        String expirationDate,
        String reissueDate,
        String groupId) {
}
