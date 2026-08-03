package com.carddemo.account.api.dto;

/**
 * Customer projection the account service returns for one customer record.
 *
 * <p>Sixteen components carry sixteen of the eighteen fields that {@code 01 CUSTOMER-RECORD}
 * declares at {@code app/cpy/CVCUS01Y.cpy:L5-L22}. Component order follows the order the account
 * view program moves each field at {@code app/cbl/COACTVWC.cbl:L494-L522}, under the gate
 * {@code IF FOUND-CUST-IN-MASTER} at {@code app/cbl/COACTVWC.cbl:L493}. The state code, the postal
 * code and the country code arrive in the order L514, L515 and L516 move them. The copybook
 * declares those three at L12, L13 and L14.
 *
 * <p>The two fields at {@code app/cpy/CVCUS01Y.cpy:L17-L18} map to no component here. The account
 * view program moves both to the screen, one through the hyphenating {@code STRING} at
 * {@code app/cbl/COACTVWC.cbl:L496-L504} and one plain at {@code app/cbl/COACTVWC.cbl:L519}.
 * card-platform/docs/business-rule-flags.md carries that divergence, and
 * card-platform/docs/decision-log.md records it alongside the text form of {@code customerId}.
 *
 * <p>The trailing {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23} maps to no
 * component. card-platform/docs/traceability-matrix.md records those dropped bytes and the
 * {@code CUST-ADDR-LINE-3} to {@code addressCity} name change.
 *
 * <p>Fifteen components hold text as the customer record stores it, with the stored width as the
 * maximum and no trimming applied. The sixteenth, {@code ficoCreditScore}, holds a number. Any
 * component is null when the caller supplies none, and no component here carries a constraint.
 *
 * @param customerId nine-character customer identifier, padded on the left with zeros. Source
 *        {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L494}. The account update program holds the same nine
 *        characters in {@code ACUP-NEW-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTUPC.cbl:L798}.
 * @param ficoCreditScore FICO credit score, a number of up to three digits. Source
 *        {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22}, moved as a
 *        number at {@code app/cbl/COACTVWC.cbl:L505-L506}. Columns 330 through 332 of
 *        {@code app/data/ASCII/custdata.txt} hold three digits on all 50 rows.
 * @param dateOfBirth date of birth, ten characters in year, month and day order with two hyphens,
 *        for example {@code 1961-06-08}. Source {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L19}, moved at {@code app/cbl/COACTVWC.cbl:L507}. Columns 309
 *        through 318 of {@code app/data/ASCII/custdata.txt} hold that form on all 50 rows.
 * @param firstName first name, up to 25 characters. Source {@code CUST-FIRST-NAME PIC X(25)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L6}, moved at {@code app/cbl/COACTVWC.cbl:L508}.
 * @param middleName middle name, up to 25 characters. Source {@code CUST-MIDDLE-NAME PIC X(25)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L7}, moved at {@code app/cbl/COACTVWC.cbl:L509}.
 * @param lastName last name, up to 25 characters. Source {@code CUST-LAST-NAME PIC X(25)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L8}, moved at {@code app/cbl/COACTVWC.cbl:L510}.
 * @param addressLine1 first line of the postal address, up to 50 characters. Source
 *        {@code CUST-ADDR-LINE-1 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L9}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L511}.
 * @param addressLine2 second line of the postal address, up to 50 characters. Source
 *        {@code CUST-ADDR-LINE-2 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L10}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L512}. {@code app/cbl/COACTUPC.cbl:L1613} marks the line
 *        optional.
 * @param addressCity city of the postal address, up to 50 characters. Source
 *        {@code CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L11}, moved to the city
 *        field {@code ACSCITYO} at {@code app/cbl/COACTVWC.cbl:L513}. The account update program
 *        labels the field {@code 'City'} at {@code app/cbl/COACTUPC.cbl:L1615} and reads the third
 *        address line at {@code app/cbl/COACTUPC.cbl:L1616}.
 * @param addressStateCode two-character state code of the postal address. Source
 *        {@code CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cpy/CVCUS01Y.cpy:L12}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L514}.
 * @param addressZip postal code, ten characters. Source {@code CUST-ADDR-ZIP PIC X(10)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L14}, moved at {@code app/cbl/COACTVWC.cbl:L515}.
 * @param addressCountryCode three-character country code of the postal address. Source
 *        {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at {@code app/cpy/CVCUS01Y.cpy:L13}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L516}.
 * @param phoneNumber1 primary telephone number, fifteen characters holding the
 *        {@code (999)999-9999} mask and two trailing spaces. Source
 *        {@code CUST-PHONE-NUM-1 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy:L15}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L517}. Columns 250 through 264 of
 *        {@code app/data/ASCII/custdata.txt} match that mask on all 50 rows.
 *        {@code app/cbl/COACTUPC.cbl:L82-L100} redefines a {@code PIC X(15)} host into the same
 *        three digit groups and three separators, named at L86, L91 and L96.
 * @param phoneNumber2 secondary telephone number, fifteen characters in the same masked form.
 *        Source {@code CUST-PHONE-NUM-2 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy:L16}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L518}. Columns 265 through 279 of
 *        {@code app/data/ASCII/custdata.txt} match the mask on all 50 rows.
 * @param eftAccountId electronic funds transfer account identifier, ten characters. Source
 *        {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L20}, moved at
 *        {@code app/cbl/COACTVWC.cbl:L520}.
 * @param primaryCardHolderIndicator single character marking the customer as the primary holder of
 *        the card. Source {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at
 *        {@code app/cpy/CVCUS01Y.cpy:L21}, moved at {@code app/cbl/COACTVWC.cbl:L521-L522}.
 */
public record CustomerView(
        String customerId,
        Integer ficoCreditScore,
        String dateOfBirth,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String addressLine2,
        String addressCity,
        String addressStateCode,
        String addressZip,
        String addressCountryCode,
        String phoneNumber1,
        String phoneNumber2,
        String eftAccountId,
        String primaryCardHolderIndicator) {
}
