/*
 * ceedays.c - test-tree stand-in for the Language Environment callable
 * service CEEDAYS, called by app/cbl/CSUTLDTC.cbl:116-120.
 * Gap register: GAP-STB-002 (stub limitation), GAP-INF-001 (inferred, not
 * observed).
 *
 * Scope
 *   Only the gnucobol-batch adapter uses this stub, so that CSUTLDTC runs
 *   unchanged on GnuCOBOL. It lives in the test tree alone: it is never
 *   placed under, compiled into, or copied over anything in app/.
 *
 * Build contract
 *   The shared object must be named CEEDAYS.so and export the symbol
 *   CEEDAYS, because libcob resolves the dynamic CALL "CEEDAYS" by that
 *   file name (searched through COB_LIBRARY_PATH) and that symbol:
 *     cc -std=c11 -Wall -Wextra -Werror -shared -fPIC -o CEEDAYS.so ceedays.c
 *     cobc -m -o CEEDAYS.so ceedays.c
 *
 * Interface (CSUTLDTC is compiled -std=ibm, so every binary is big-endian)
 *   date_vstr, pic_vstr  Vstrings: S9(4) BINARY length, then up to 256
 *                        bytes of text. CSUTLDTC always passes length 10,
 *                        so trailing spaces are part of both strings.
 *                        Neither is ever written.
 *   lilian               S9(9) BINARY, 4 bytes: the Lilian day number,
 *                        where day 1 is 1582-10-15.
 *   fc                   12-byte feedback token: SEVERITY (2), MSG-NO (2),
 *                        CASE-SEV-CTL (1), FACILITY-ID (3, 'CEE' in
 *                        EBCDIC), I-S-INFO (4).
 *   All 4 Lilian bytes and all 12 feedback bytes are written on every call:
 *   CSUTLDTC ends with EXIT PROGRAM, so its FEEDBACK-CODE keeps the
 *   previous call's token unless overwritten. The return value is always
 *   0; libcob stores it in RETURN-CODE, which CSUTLDTC then overwrites
 *   with SEVERITY.
 *
 * Rules, applied in this order to the text with trailing spaces trimmed
 *   picture other than exactly YYYY-MM-DD or YYYYMMDD    09D6 (CEE2518)
 *   date empty, or shorter than the picture              09CB (CEE2507)
 *   date longer than the picture                         09CC (CEE2508)
 *   non-digit at a Y, M or D position, or a picture
 *   literal ('-') not matched                            09D8 (CEE2520)
 *   month outside 1-12                                   09D5 (CEE2517)
 *   day outside the month (proleptic Gregorian)          09CC (CEE2508)
 *   date before 1582-10-15                               09D1 (CEE2513)
 *   A valid date returns its Lilian day and a feedback token of 12 zero
 *   bytes. An error returns Lilian 0 and the severity-3 token
 *   00 03 <msg> 59 C3 C5 C5 00 00 00 00, the form CSUTLDTC's 88-levels
 *   test. Treating excess input as 09CC is an inferred choice.
 *
 * Injection
 *   When CARDDEMO_CEEDAYS_FEEDBACK holds exactly 4 hex digits (either
 *   case), the computation is skipped and that value is returned as the
 *   message number of the error token, whatever the date. The adapter sets
 *   it per subprocess from the scenario parameter ceedays_feedback, so
 *   that every branch of CSUTLDTC's token mapping runs. Any other value,
 *   the empty string included, is a harness misconfiguration: the stub
 *   writes one diagnostic line to stderr and exits 70 (EX_SOFTWARE). The
 *   line shows the rejected value with each backslash written as \\ and
 *   each byte outside 0x20..0x7E as \xHH (upper-case hex), so it is always
 *   a single line and the value can be recovered from it.
 *
 * Limitations
 *   - GAP-STB-002: only the two pictures the callers pass are supported.
 *     There are no era pictures, so 09CD (invalid era) and 09D9 (year in
 *     era zero) arise only by injection; there is no 2-digit-year
 *     windowing and there are no Mmm or ZZ tokens.
 *   - GAP-INF-001: real CEEDAYS may differ in separator leniency, in how
 *     it treats excess input and in which token wins when several apply.
 *     Every computed token is inferred, not observed, until app/'s
 *     CSUTLDTC is observed against a real CEEDAYS.
 *   - GAP-MIS-010: on unikix the installation's CEEDAYS, if it supplies
 *     one, is used instead of this stub.
 *   - COACTUPC calls CSUTLDTC through CSUTLDPY (EDIT-DATE-LE) with the
 *     8-byte WS-EDIT-DATE-CCYYMMDD, so its 10-byte date ends with the
 *     2 high-order bytes of WS-EDIT-DATE-BINARY, and its mask 'YYYYMMDD'
 *     is followed by 2 spaces of WS-SEVERITY. That online path runs only
 *     on unikix, which never loads this stub; here those 2 binary bytes
 *     would make the date longer than the picture (09CC).
 *
 * The stub writes nothing to stdout, which carries the program's DISPLAY
 * lines, and uses no clock, locale, file or network access. Digits are
 * tested as explicit '0'..'9' comparisons so that no locale applies.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VSTRING_MAX_TEXT 256
#define FEEDBACK_BYTES 12
#define LILIAN_BYTES 4
#define CONDITION_SEVERITY 3
#define CASE_SEV_CTL 0x59
#define EXIT_BAD_INJECTION 70
#define FEEDBACK_ENV "CARDDEMO_CEEDAYS_FEEDBACK"

/* CEEDAYS message numbers; CSUTLDTC displays MSG-NO in decimal. */
#define MSG_INSUFFICIENT_DATA 0x09CBu /* 2507 'Insufficient'    */
#define MSG_BAD_DATE_VALUE    0x09CCu /* 2508 'Datevalue error' */
#define MSG_UNSUPP_RANGE      0x09D1u /* 2513 'Unsupp. Range'   */
#define MSG_INVALID_MONTH     0x09D5u /* 2517 'Invalid month'   */
#define MSG_BAD_PIC_STRING    0x09D6u /* 2518 'Bad Pic String'  */
#define MSG_NON_NUMERIC_DATA  0x09D8u /* 2520 'Nonnumeric data' */

/* FACILITY-ID 'CEE' in EBCDIC (cp037). */
static const unsigned char FACILITY_CEE[3] = {0xC3, 0xC5, 0xC5};

/* The picture strings the callers pass: CORPT00C and COTRN02C pass
 * YYYY-MM-DD, COACTUPC (through CSUTLDPY) passes YYYYMMDD. */
static const char *const SUPPORTED_PICTURES[] = {"YYYY-MM-DD", "YYYYMMDD"};

int CEEDAYS(unsigned char *date_vstr, unsigned char *pic_vstr,
            unsigned char *lilian, unsigned char *fc);

/* Signed big-endian halfword. */
static int be16(const unsigned char *p)
{
    int value = ((int)p[0] << 8) | (int)p[1];
    return value >= 0x8000 ? value - 0x10000 : value;
}

/* Points *text at the Vstring's text and returns its length, clamped to
 * 0..256, with trailing spaces (0x20 only) removed. */
static size_t vstring_trimmed(const unsigned char *vstr,
                              const unsigned char **text)
{
    int declared = be16(vstr);
    size_t length;

    if (declared < 0) {
        declared = 0;
    } else if (declared > VSTRING_MAX_TEXT) {
        declared = VSTRING_MAX_TEXT;
    }
    length = (size_t)declared;
    *text = vstr + 2;
    while (length > 0 && (*text)[length - 1] == 0x20) {
        length--;
    }
    return length;
}

static int is_digit(unsigned char c)
{
    return c >= '0' && c <= '9';
}

static int hex_value(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    return -1;
}

static int is_leap_year(long long year)
{
    return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
}

static int days_in_month(long long year, int month)
{
    static const int DAYS[12] = {31, 28, 31, 30, 31, 30,
                                 31, 31, 30, 31, 30, 31};

    if (month == 2 && is_leap_year(year)) {
        return 29;
    }
    return DAYS[month - 1];
}

/* Days since 1970-01-01 in the proleptic Gregorian calendar
 * (H. Hinnant, "chrono-Compatible Low-Level Date Algorithms"). */
static long long days_from_civil(long long year, int month, int day)
{
    long long era;
    long long year_of_era;
    long long day_of_year;
    long long day_of_era;

    year -= month <= 2;
    era = (year >= 0 ? year : year - 399) / 400;
    year_of_era = year - era * 400;
    day_of_year = (153LL * (month > 2 ? month - 3 : month + 9) + 2) / 5
                  + day - 1;
    day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100
                 + day_of_year;
    return era * 146097 + day_of_era - 719468;
}

/* Lilian day 1 is 1582-10-15, so day 0 is 1582-10-14. */
static long long lilian_day(long long year, int month, int day)
{
    return days_from_civil(year, month, day) - days_from_civil(1582, 10, 14);
}

/* Returns the matching supported picture, or NULL. */
static const char *supported_picture(const unsigned char *text,
                                     size_t length)
{
    size_t i;

    for (i = 0; i < sizeof SUPPORTED_PICTURES / sizeof SUPPORTED_PICTURES[0];
         i++) {
        const char *picture = SUPPORTED_PICTURES[i];
        if (strlen(picture) == length && memcmp(picture, text, length) == 0) {
            return picture;
        }
    }
    return NULL;
}

/* Applies the rules of the header comment. Returns 0 and sets *lilian_out
 * for a valid date, otherwise the CEEDAYS message number. */
static unsigned evaluate(const unsigned char *date_vstr,
                         const unsigned char *pic_vstr,
                         long long *lilian_out)
{
    const unsigned char *pic_text;
    const unsigned char *date_text;
    size_t pic_length = vstring_trimmed(pic_vstr, &pic_text);
    size_t date_length = vstring_trimmed(date_vstr, &date_text);
    const char *picture = supported_picture(pic_text, pic_length);
    long long year = 0;
    int month = 0;
    int day = 0;
    size_t i;

    if (picture == NULL) {
        return MSG_BAD_PIC_STRING;
    }
    if (date_length == 0 || date_length < pic_length) {
        return MSG_INSUFFICIENT_DATA;
    }
    if (date_length > pic_length) {
        return MSG_BAD_DATE_VALUE;
    }

    for (i = 0; i < pic_length; i++) {
        unsigned char c = date_text[i];
        switch (picture[i]) {
        case 'Y':
            if (!is_digit(c)) {
                return MSG_NON_NUMERIC_DATA;
            }
            year = year * 10 + (c - '0');
            break;
        case 'M':
            if (!is_digit(c)) {
                return MSG_NON_NUMERIC_DATA;
            }
            month = month * 10 + (c - '0');
            break;
        case 'D':
            if (!is_digit(c)) {
                return MSG_NON_NUMERIC_DATA;
            }
            day = day * 10 + (c - '0');
            break;
        default:
            if (c != (unsigned char)picture[i]) {
                return MSG_NON_NUMERIC_DATA;
            }
            break;
        }
    }

    if (month < 1 || month > 12) {
        return MSG_INVALID_MONTH;
    }
    if (day < 1 || day > days_in_month(year, month)) {
        return MSG_BAD_DATE_VALUE;
    }
    /* A 4-digit year bounds the date above at 9999-12-31 (Lilian 3074324),
     * so only the lower bound of the supported range needs a test. */
    *lilian_out = lilian_day(year, month, day);
    if (*lilian_out < 1) {
        return MSG_UNSUPP_RANGE;
    }
    return 0;
}

/* Parses an injected feedback value: 1 and *msg for exactly 4 hex digits,
 * 0 for anything else. */
static int parse_injection(const char *value, unsigned *msg)
{
    unsigned parsed = 0;
    size_t i;

    if (strlen(value) != 4) {
        return 0;
    }
    for (i = 0; i < 4; i++) {
        int nibble = hex_value(value[i]);
        if (nibble < 0) {
            return 0;
        }
        parsed = (parsed << 4) | (unsigned)nibble;
    }
    *msg = parsed;
    return 1;
}

/* Writes value to stream reversibly and without line breaks: a byte in
 * 0x20..0x7E other than backslash as itself, a backslash as \\, and every
 * other byte as \x followed by two upper-case hex digits. */
static void put_escaped(FILE *stream, const char *value)
{
    const unsigned char *p;

    for (p = (const unsigned char *)value; *p != '\0'; p++) {
        if (*p == '\\') {
            fputs("\\\\", stream);
        } else if (*p >= 0x20 && *p <= 0x7E) {
            fputc(*p, stream);
        } else {
            fprintf(stream, "\\x%02X", (unsigned)*p);
        }
    }
}

static void put_valid(unsigned char *lilian, unsigned char *fc,
                      long long day_number)
{
    unsigned long long bits = (unsigned long long)day_number;

    lilian[0] = (unsigned char)((bits >> 24) & 0xFFu);
    lilian[1] = (unsigned char)((bits >> 16) & 0xFFu);
    lilian[2] = (unsigned char)((bits >> 8) & 0xFFu);
    lilian[3] = (unsigned char)(bits & 0xFFu);
    memset(fc, 0, FEEDBACK_BYTES);
}

static void put_condition(unsigned char *lilian, unsigned char *fc,
                          unsigned msg)
{
    memset(lilian, 0, LILIAN_BYTES);
    memset(fc, 0, FEEDBACK_BYTES);
    fc[1] = CONDITION_SEVERITY;
    fc[2] = (unsigned char)((msg >> 8) & 0xFFu);
    fc[3] = (unsigned char)(msg & 0xFFu);
    fc[4] = CASE_SEV_CTL;
    memcpy(fc + 5, FACILITY_CEE, sizeof FACILITY_CEE);
}

int CEEDAYS(unsigned char *date_vstr, unsigned char *pic_vstr,
            unsigned char *lilian, unsigned char *fc)
{
    const char *injected = getenv(FEEDBACK_ENV);
    long long day_number = 0;
    unsigned msg;

    if (injected != NULL) {
        if (!parse_injection(injected, &msg)) {
            fprintf(stderr, "CARDDEMO-CEEDAYS invalid %s '", FEEDBACK_ENV);
            put_escaped(stderr, injected);
            fputs("'\n", stderr);
            fflush(stderr);
            exit(EXIT_BAD_INJECTION);
        }
        put_condition(lilian, fc, msg);
        return 0;
    }

    msg = evaluate(date_vstr, pic_vstr, &day_number);
    if (msg == 0) {
        put_valid(lilian, fc, day_number);
    } else {
        put_condition(lilian, fc, msg);
    }
    return 0;
}
