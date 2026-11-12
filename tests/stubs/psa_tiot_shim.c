#define _GNU_SOURCE
/*
 * psa_tiot_shim.c - z/OS PSA -> TCB -> TIOT stand-in for CBSTM03A, and the
 * page-zero probe that decides whether it can run.
 *
 * Purpose
 *   CBSTM03A starts by walking z/OS control blocks from address 0
 *   (app/cbl/CBSTM03A.CBL:235-291): PSAPTR is a POINTER that is never set,
 *   so SET ADDRESS OF PSA-BLOCK TO PSAPTR addresses 0; PSA+536 holds the TCB
 *   address, TCB+12 the TIOT address, and the program DISPLAYs the job name,
 *   the step name and every DD name it finds in the TIOT. On Linux that walk
 *   dies with SIGSEGV. Preloaded into the CBSTM03A process, this library maps
 *   page 0 and builds just enough of a PSA, TCB and TIOT there for the walk to
 *   print the scenario's job, step and DD names and carry on to the statement
 *   logic.
 *
 *   It is a test seam of the gnucobol-batch adapter only. It lives in the
 *   test tree and is never compiled into or copied under app/, which is the
 *   unmodified system under test.
 *
 * Builds (both by tests/adapters/gnucobol_batch/build.py, into the build cache)
 *   cc -Wall -Werror -shared -fPIC -o psa_tiot_shim.so psa_tiot_shim.c
 *   cc -Wall -Werror -DPAGE0_PROBE -o page0_probe psa_tiot_shim.c
 *
 * Environment read by the shared library (an unset variable counts as empty)
 *   CARDDEMO_JOB   job name for TIOTNJOB, truncated or space-padded to 8
 *   CARDDEMO_STEP  step name for TIOTJSTP, truncated or space-padded to 8
 *   CARDDEMO_DDS   comma-separated DD names, one TIOT entry per non-empty
 *                  token, in order, each truncated or space-padded to 8.
 *                  The adapter must pass at least one: with none, CBSTM03A's
 *                  post-loop DISPLAY prints an entry of 8 low-values marked
 *                  "-- null  UCB".
 *   LD_PRELOAD applies to every process started with it, so the adapter sets
 *   it, and these variables, only in the environment of the CBSTM03A process.
 *
 * Requirement
 *   Mapping address 0 needs vm.mmap_min_addr = 0, or a process holding
 *   CAP_SYS_RAWIO (root). Neither build changes a kernel setting. If the
 *   mapping fails the library writes
 *     CARDDEMO-SHIM page0 mmap failed errno=<n>
 *   to stderr and returns; CBSTM03A then faults as it would without the shim.
 *
 * Probe contract (GAP-MIS-002)
 *   page0_probe maps page 0, writes and reads back a pattern, unmaps it and
 *   prints one line on stdout:
 *     page0 mappable; vm.mmap_min_addr=<v>                       exit 0
 *     errno=<n> <strerror(n)>; vm.mmap_min_addr=<v>              exit 1
 *     errno=0 mapped at <addr>; vm.mmap_min_addr=<v>             exit 1
 *     errno=0 read-back mismatch; vm.mmap_min_addr=<v>           exit 1
 *   where <v> is the integer in /proc/sys/vm/mmap_min_addr, or "unknown".
 *   adapter.prepare turns exit 1 into
 *     AdapterUnavailable(reason="page zero not mappable: <n>;
 *                        vm.mmap_min_addr=<v>", gap_id="GAP-MIS-002")
 *   taking <n> and <v> from that line. The result is recorded in
 *   coverage-report.md and decides the job-chain proof. The sysctl value is
 *   never taken as proof on its own: root maps page 0 whatever it says.
 *
 * Limitation (GAP-STB-003)
 *   - Only the fields CBSTM03A reads are supplied: the PSA's TCB pointer, the
 *     TCB's TIOT pointer, the TIOT header and the DD entries' length byte,
 *     DD name and UCB address.
 *   - Every UCB is reported valid: each entry carries the same non-zero UCB
 *     address, so no "null UCB" line can be produced for a supplied DD.
 *   - TIOTPSTP (the procedure step name) is blank.
 *   - While the page is mapped, a NULL dereference anywhere in the process
 *     reads or writes this page silently instead of faulting.
 *
 * Address arithmetic constraint
 *   CBSTM03A advances through the TIOT with COMPUTE BUMP-TIOT = BUMP-TIOT + n,
 *   where BUMP-TIOT is a 4-byte PIC S9(08) BINARY field that TIOT-INDEX
 *   (an 8-byte POINTER) redefines. Under -fbinary-byteorder=native on a
 *   little-endian host only the low 32 bits of the pointer change, and
 *   -fbinary-truncate caps the value at 99,999,999. The TIOT therefore has to
 *   sit at a small address with no carry out of the low 32 bits, which is why
 *   the PSA, the TCB and the TIOT all live inside page 0.
 *
 * Pointer handling
 *   The mapping is held in a volatile pointer variable and every byte is
 *   written or read through it in a loop. Its value is 0, so a plain pointer,
 *   memset or memcpy onto it would be a NULL dereference the compiler may
 *   assume never happens; a volatile load hides the value from the optimiser.
 *   The pointee is volatile too, so the probe's read-back reaches memory.
 */
#include <sys/mman.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

/*
 * Page-zero layout. PSAPTR is NULL, so every offset in the page is also the
 * absolute address CBSTM03A computes.
 */
#define PSA_ADDR             0u     /* PSA-BLOCK */
#define PSA_TCB_FIELD        536u   /* TCB-POINT, after FILLER X(536) */
#define TCB_ADDR             1024u  /* TCB-BLOCK */
#define TCB_TIOT_FIELD       12u    /* TIOT-POINT, after FILLER X(12) */
#define TIOT_ADDR            2048u  /* TIOT-BLOCK */
#define TIOT_NAME_LEN        8u     /* TIOTNJOB, TIOTJSTP, TIOTPSTP, TIOCDDNM */
#define TIOT_JOB_FIELD       0u     /* TIOTNJOB */
#define TIOT_STEP_FIELD      8u     /* TIOTJSTP */
#define TIOT_PROCSTEP_FIELD  16u    /* TIOTPSTP */
#define TIOT_HEADER_LEN      24u    /* LENGTH OF TIOT-BLOCK */
#define TIOT_ENTRIES_ADDR    (TIOT_ADDR + TIOT_HEADER_LEN)
#define TIOT_ENTRY_LEN       20u    /* LENGTH OF TIOT-SEG */
#define TIOT_ENTRY_LEN_BYTE  0x14u  /* TIO-LEN: the segment length */
#define TIOT_DDNAME_FIELD    4u     /* TIOCDDNM, after TIO-LEN, FILLER X(3) */
#define TIOT_UCB_FIELD       17u    /* UCB-ADDR, after FILLER X(5) */
#define TIOT_UCB_LEN         3u
/*
 * END-OF-TIOT tests the 4 bytes after an entry; a whole empty entry plus that
 * field is left zero after the last DD so the walk always ends inside the page.
 */
#define TIOT_TERMINATOR_LEN  24u
#define PAGE0_MIN_SIZE \
    (TIOT_ENTRIES_ADDR + TIOT_ENTRY_LEN + TIOT_TERMINATOR_LEN)

/* The mapping's address, which is 0 on success; see "Pointer handling". */
typedef volatile unsigned char *volatile page0_ptr;

/*
 * Returns the page size, or 0 with *err set when it is unknown or too small
 * for the layout above.
 */
static size_t page0_size(int *err)
{
    long size;

    errno = 0;
    size = sysconf(_SC_PAGESIZE);
    if (size < (long)PAGE0_MIN_SIZE) {
        *err = (size < 0 && errno != 0) ? errno : EINVAL;
        return 0;
    }
    *err = 0;
    return (size_t)size;
}

static void *page0_mmap(size_t page)
{
    return mmap((void *)0, page, PROT_READ | PROT_WRITE,
                MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
}

#ifndef PAGE0_PROBE

static const char *env_or_empty(const char *name)
{
    const char *value = getenv(name);

    return value != NULL ? value : "";
}

/* Writes width bytes at off: the first len bytes of text, then spaces. */
static void put_padded(page0_ptr base, size_t off, const char *text,
                       size_t len, size_t width)
{
    size_t i;

    for (i = 0; i < width; i++)
        base[off + i] = (unsigned char)(i < len ? text[i] : ' ');
}

/* Writes value at off as a native-order pointer, the form of a POINTER item. */
static void put_pointer(page0_ptr base, size_t off, uintptr_t value)
{
    void *pointer = (void *)value;
    unsigned char bytes[sizeof pointer];
    size_t i;

    memcpy(bytes, &pointer, sizeof pointer);
    for (i = 0; i < sizeof pointer; i++)
        base[off + i] = bytes[i];
}

/*
 * Writes one TIOT-SEG at off. The fillers stay zero; the UCB address is a
 * fixed non-zero value, so NULL-UCB is false for every supplied DD.
 */
static void put_tiot_entry(page0_ptr base, size_t off, const char *ddname,
                           size_t len)
{
    static const unsigned char ucb[TIOT_UCB_LEN] = {0x01, 0x02, 0x03};
    size_t i;

    base[off] = TIOT_ENTRY_LEN_BYTE;
    put_padded(base, off + TIOT_DDNAME_FIELD, ddname, len, TIOT_NAME_LEN);
    for (i = 0; i < TIOT_UCB_LEN; i++)
        base[off + TIOT_UCB_FIELD + i] = ucb[i];
}

/*
 * Writes one entry per non-empty comma-separated token of dds, in order,
 * keeping at most capacity of them. Returns the number of non-empty tokens
 * and sets *kept to the number written.
 */
static size_t put_tiot_entries(page0_ptr base, const char *dds,
                               size_t capacity, size_t *kept)
{
    const char *token = dds;
    size_t seen = 0;

    *kept = 0;
    for (;;) {
        size_t len = strcspn(token, ",");

        if (len > 0) {
            if (*kept < capacity) {
                put_tiot_entry(base,
                               TIOT_ENTRIES_ADDR + *kept * TIOT_ENTRY_LEN,
                               token, len);
                (*kept)++;
            }
            seen++;
        }
        if (token[len] == '\0')
            return seen;
        token += len + 1;
    }
}

static void carddemo_page0_init(void) __attribute__((constructor));

/*
 * Runs when the library is preloaded, before CBSTM03A starts. It never exits
 * or aborts the process, and writes only to stderr.
 */
static void carddemo_page0_init(void)
{
    const char *job;
    const char *step;
    page0_ptr base;
    void *mapped;
    size_t page, capacity, kept, seen, i;
    int err;

    page = page0_size(&err);
    if (page == 0) {
        fprintf(stderr, "CARDDEMO-SHIM page0 mmap failed errno=%d\n", err);
        return;
    }
    mapped = page0_mmap(page);
    if (mapped == MAP_FAILED) {
        err = errno;
        fprintf(stderr, "CARDDEMO-SHIM page0 mmap failed errno=%d\n", err);
        return;
    }
    if ((uintptr_t)mapped != 0) {
        (void)munmap(mapped, page);
        fprintf(stderr, "CARDDEMO-SHIM page0 mmap failed errno=0\n");
        return;
    }
    base = mapped;

    for (i = 0; i < page; i++)
        base[i] = 0;

    put_pointer(base, PSA_ADDR + PSA_TCB_FIELD, TCB_ADDR);
    put_pointer(base, TCB_ADDR + TCB_TIOT_FIELD, TIOT_ADDR);

    job = env_or_empty("CARDDEMO_JOB");
    step = env_or_empty("CARDDEMO_STEP");
    put_padded(base, TIOT_ADDR + TIOT_JOB_FIELD, job, strlen(job),
               TIOT_NAME_LEN);
    put_padded(base, TIOT_ADDR + TIOT_STEP_FIELD, step, strlen(step),
               TIOT_NAME_LEN);
    put_padded(base, TIOT_ADDR + TIOT_PROCSTEP_FIELD, "", 0, TIOT_NAME_LEN);

    capacity = (page - TIOT_ENTRIES_ADDR - TIOT_TERMINATOR_LEN)
               / TIOT_ENTRY_LEN;
    seen = put_tiot_entries(base, env_or_empty("CARDDEMO_DDS"), capacity,
                            &kept);
    if (seen > kept)
        fprintf(stderr, "CARDDEMO-SHIM too many ddnames, kept %zu\n", kept);
}

#else /* PAGE0_PROBE */

#define PROBE_PATTERN_LEN 64u

/* The test byte for a page offset; the two probed ranges differ. */
static unsigned char probe_pattern(size_t off)
{
    return (unsigned char)(0xA5u ^ (off & 0xFFu));
}

/* Copies the integer in /proc/sys/vm/mmap_min_addr into buf, or "unknown". */
static void read_mmap_min_addr(char *buf, size_t size)
{
    FILE *file = fopen("/proc/sys/vm/mmap_min_addr", "r");
    unsigned long long value;

    if (file != NULL && fscanf(file, "%llu", &value) == 1)
        snprintf(buf, size, "%llu", value);
    else
        snprintf(buf, size, "unknown");
    if (file != NULL)
        fclose(file);
}

int main(void)
{
    char min_addr[32];
    page0_ptr base;
    void *mapped;
    size_t page, i;
    size_t tail;
    int err;
    int match = 1;

    read_mmap_min_addr(min_addr, sizeof min_addr);

    page = page0_size(&err);
    if (page == 0) {
        printf("errno=%d %s; vm.mmap_min_addr=%s\n", err, strerror(err),
               min_addr);
        return 1;
    }
    mapped = page0_mmap(page);
    if (mapped == MAP_FAILED) {
        err = errno;
        printf("errno=%d %s; vm.mmap_min_addr=%s\n", err, strerror(err),
               min_addr);
        return 1;
    }
    if ((uintptr_t)mapped != 0) {
        (void)munmap(mapped, page);
        printf("errno=0 mapped at %p; vm.mmap_min_addr=%s\n", mapped,
               min_addr);
        return 1;
    }
    base = mapped;

    tail = page - PROBE_PATTERN_LEN;
    for (i = 0; i < PROBE_PATTERN_LEN; i++) {
        base[i] = probe_pattern(i);
        base[tail + i] = probe_pattern(tail + i);
    }
    for (i = 0; i < PROBE_PATTERN_LEN; i++) {
        if (base[i] != probe_pattern(i)
            || base[tail + i] != probe_pattern(tail + i))
            match = 0;
    }
    (void)munmap(mapped, page);

    if (!match) {
        printf("errno=0 read-back mismatch; vm.mmap_min_addr=%s\n", min_addr);
        return 1;
    }
    printf("page0 mappable; vm.mmap_min_addr=%s\n", min_addr);
    return 0;
}

#endif /* PAGE0_PROBE */
