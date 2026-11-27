/* Host test for the native_settings EXPORT path (record -> settings.cfg).
 *
 * save_native() is the Settings-exit hook: it writes the VM record, then mounts
 * the card and calls export_native() to mirror fields into settings.cfg. That
 * path has no other test, so drive it here with a mocked card VFS and the real
 * rewrite() from preferences.c, and capture what SAVE writes.
 *
 * The SYS dispatcher (0xbfc16585) is neutralized with a stub that returns 0 for
 * every call (VM write, card mount, ctx alloc, release, unmount). The
 * FOLDER/READ/REWRITE/SAVE entry points are macro-cast from names, so on x86-64
 * they bind straight to our mock functions (no 32-bit truncation).
 *
 *   cc -O0 -DNATIVE_EXPORT -DFOLDER_ENTRY=mock_folder -DREAD_ENTRY=mock_read \
 *      -DREWRITE_ENTRY=host_rewrite -DSAVE_ENTRY=mock_save \
 *      -I tools/theme tools/theme/test_native_export.c -o /tmp/tne && /tmp/tne
 */
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

typedef unsigned char byte;
typedef unsigned int word;
#include "settings_line.h"

/* ---- captured SAVE output ---- */
static byte saved[512];
static word saved_context;
static int saved_size = -1, saved_exists = -1, save_calls;

/* ---- mocks ---- */
static int mock_folder(word ctx, const char *name) { return strcmp(name, "m30") == 0; }
static int mock_read(word ctx, const char *name, byte *buf) { return -1; /* no existing file */ }
static int mock_save(word ctx, byte *buf, unsigned size, int exists)
{
    saved_context = ctx;
    memcpy(saved, buf, size < 512 ? size : 512);
    saved_size = size; saved_exists = exists; save_calls++;
    return 1;
}

/* real rewrite() from preferences.c (self-contained: uses setting_line only) */
static int host_rewrite(byte *buf, unsigned size, const char *key, const char *value)
{
    while (size && (buf[size-1]==' ' || buf[size-1]=='\r' || buf[size-1]=='\n')) size--;
    unsigned start=0,end=0,n=0;
    int found=setting_line(buf,size,key,&start,&end);
    if (found<0) return -1;
    while (value[n]) n++;
    if (!found) {
        unsigned k=0;
        while (key[k]) k++;
        if (size+1+k+1+n>=512) return -1;
        if (size) buf[size++]='\n';
        for (unsigned i=0;i<k;i++) buf[size++]=key[i];
        buf[size++]='=';
        start=end=size;
    }
    unsigned next_size=size-(end-start)+n;
    if (next_size>=512) return -1;
    if (start+n>end) {
        for (unsigned i=size;i>end;i--) buf[start+n+i-end-1]=buf[i-1];
    } else {
        for (unsigned i=end;i<size;i++) buf[start+n+i-end]=buf[i];
    }
    for (unsigned i=0;i<n;i++) buf[start+i]=value[i];
    buf[next_size++]='\n';
    return next_size;
}

#include "native_settings.c"   /* NATIVE_EXPORT defined via -D below */

static void map_sys_stub(void)
{
    unsigned char *base = mmap((void *)0xbfc16000u, 0x1000,
        PROT_READ | PROT_WRITE | PROT_EXEC,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (base == MAP_FAILED) { perror("mmap"); __builtin_trap(); }
    base[0x58d] = 0x31; base[0x58e] = 0xc0; base[0x58f] = 0xc3;
    base[0x585] = 0x31; base[0x586] = 0xc0; base[0x587] = 0xc3; /* xor eax,eax; ret */
}

static void map_extras(void)
{
    unsigned char *base = mmap((void *)0x9fc15000u, 0x1000,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (base == MAP_FAILED) { perror("mmap extras"); __builtin_trap(); }
}

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %d: %s\n", __LINE__, #cond); failures++; } } while (0)
static int has(const char *key)
{
    char needle[32]; snprintf(needle, sizeof needle, "%s=", key);
    return strstr((const char *)saved, needle) != NULL;
}

int main(void)
{
    map_sys_stub();
    map_extras();
    byte record[56];
    memset(record, 0, 56);
    record[2] = 7;    /* brightness */
    record[12] = 1;   /* time_format */
    record[13] = 2;   /* date_format */
    record[15] = 1;   /* usb_mode */

    volatile word *card_context = (volatile word *)0x9fc1550cu;
    *card_context = 7;
    save_native(record, 0, 56, 19);
    CHECK(saved_context == 7);

    printf("save_calls=%d size=%d exists=%d\n", save_calls, saved_size, saved_exists);
    if (save_calls > 0) printf("--- settings.cfg ---\n%.*s--------------------\n", saved_size, saved);

    CHECK(save_calls == 1);            /* export ran and saved */
    CHECK(saved_size > 0);
    CHECK(has("brightness"));
    CHECK(has("time_format"));
    CHECK(has("date_format"));
    CHECK(has("usb_mode"));
    CHECK(has("language"));
    CHECK(has("repeat"));

    *card_context = ~0u;
    save_native(record, 0, 56, 19);
    CHECK(save_calls == 2 && saved_context == 0);

    if (!failures) printf("PASS: native export writes settings.cfg\n");
    return failures != 0;
}
