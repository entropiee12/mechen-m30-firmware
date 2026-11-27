/* Host round-trip test for native_settings.c field mapping (import direction).
 *
 * Compiles the real import_native() and drives it with settings.cfg content,
 * checking each key lands on the right record byte and that out-of-range values
 * are rejected. The only on-device dependency is the VM write at 0xbfc16585,
 * which we neutralize by mapping a one-instruction stub at that address
 * (x86-64 Linux; run on the build VM, not the device).
 *
 *   cc -O0 -o /tmp/test_native_fields tools/theme/test_native_fields.c && /tmp/test_native_fields
 */
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

#include "native_settings.c"   /* import mode (NATIVE_EXPORT undefined) */

static int failures;
#define CHECK(cond) do { if (!(cond)) { \
    printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static void map_vm_stub(void)
{
    unsigned char *base = mmap((void *)0xbfc16000u, 0x1000,
        PROT_READ | PROT_WRITE | PROT_EXEC,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (base == MAP_FAILED) { perror("mmap"); __builtin_trap(); }
    /* xor eax,eax ; ret  -> harmless int-returning stub at the called address */
    base[0x585] = 0x31; base[0x586] = 0xc0; base[0x587] = 0xc3;
}

/* Protect bytes used by the stock Settings dialog. */
static void map_extras(void)
{
    unsigned char *base = mmap((void *)0x9fc15000u, 0x1000,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (base == MAP_FAILED) { perror("mmap extras"); __builtin_trap(); }
}
#define EQ (*(volatile byte *)0x9fc15762u)
#define FS (*(volatile byte *)0x9fc15761u)

static void import(byte *record, const char *cfg)
{
    memset(record, 0, 56);
    import_native(record, (const byte *)cfg, (unsigned)strlen(cfg));
}

int main(void)
{
    map_vm_stub();
    map_extras();
    byte record[56];

    /* New keys map to their record bytes; an existing key still works. */
    import(record, "time_format=1\ndate_format=2\nusb_mode=1\nbrightness=7\n");
    CHECK(record[12] == 1);   /* time_format  */
    CHECK(record[13] == 2);   /* date_format  */
    CHECK(record[15] == 1);   /* usb_mode     */
    CHECK(record[2] == 7);    /* brightness (regression) */

    /* Every in-range value is accepted. */
    import(record, "time_format=0\ndate_format=0\nusb_mode=0\n");
    CHECK(record[12] == 0 && record[13] == 0 && record[15] == 0);
    import(record, "date_format=1\n");
    CHECK(record[13] == 1);

    /* Out-of-range values are rejected (bytes stay at their prior value). */
    import(record, "date_format=3\ntime_format=2\nusb_mode=9\n");
    CHECK(record[13] == 0);   /* 3 > max 2 -> ignored */
    CHECK(record[12] == 0);   /* 2 > max 1 -> ignored */
    CHECK(record[15] == 0);   /* 9 > max 1 -> ignored */

    /* CRLF + BOM tolerated for the new keys. */
    import(record, "\xef\xbb\xbf" "time_format=1\r\ndate_format=1\r\nusb_mode=1\r\n");
    CHECK(record[12] == 1 && record[13] == 1 && record[15] == 1);

    /* The old audio keys must leave the Settings dialog state alone. */
    EQ = 3; FS = 1;
    import(record, "eq=5\nfullsound=0\n");
    CHECK(EQ == 3 && FS == 1);

    if (!failures) printf("PASS: native field import and dialog isolation\n");
    return failures != 0;
}
