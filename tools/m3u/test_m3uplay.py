#!/usr/bin/env python3
"""Test the virtual-folder M3U browser + mengine track-stepper patch.

usage: test_m3uplay.py STOCK_BROWSER.ap [PATCHED_BROWSER.ap [STOCK_MENGINE.ap PATCHED_MENGINE.ap]]
env:   CROSS, QEMU, HOSTCC, and from build.sh --test:
       M30_PLAY_BIN, M30_PLAY_ELF, M30_BROW_BIN, M30_BROW_ELF,
       M30_BTRAMP_BIN, M30_BTRAMP_ELF, M30_MTRAMP_BIN, M30_MTRAMP_ELF,
       M30_MSTEP_BIN, M30_MSTEP_ELF

Verifies:
  1. patch_m3uplay.py leaves ALL 0x40xx4C00 banks (file 0x2300..0x2B00) 100%
     untouched stock so browser.ap's live row_names[0..6] buffers at
     0x40CD4C00..0x40CD4E00 can never be overwritten.
  2. Production blobs fit in window 0x71 (0x71252500), window 0x48 (0x485946C0),
     and window 0x78 (0x780DB7E0) with zero writable data.
  3. Host -m32 scenario suite (boolean OP_READ + OP_SIZE, 0xAA trailing fill,
     multi-sector 7-row paging, Back, return from music.ap, VS isolation).
  4. QEMU MIPS16e execution of ALL THREE real assembly trampolines across
     their exact production addresses (0x40D15108 -> 0x485946C0 -> 0x71252500,
     0x780DB7E0, and 0x7A053338 -> 0x7B268000).
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CROSS = os.environ.get("CROSS", "mips64-linux-gnu-")
QEMU = os.environ.get("QEMU", "qemu-mipsel-static")
CFLAGS = ["-EL", "-mabi=32", "-march=mips32", "-mips16", "-Os", "-G0",
          "-mno-abicalls", "-fno-pic", "-ffreestanding", "-fno-builtin",
          "-nostdlib", "-fno-toplevel-reorder", "-fno-jump-tables",
          "-fno-asynchronous-unwind-tables", "-falign-functions=4"]
HFLAGS = ["-EL", "-mabi=32", "-march=mips32", "-mno-mips16", "-O1", "-G0",
          "-mno-abicalls", "-fno-pic", "-ffreestanding", "-fno-builtin", "-nostdlib"]

CLICK_FO, CLICK_NEW = 0x12808, bytes.fromhex("d01ab051")    # jal 0x485946C0
OLD_CLASSIFY_FO, OLD_CLASSIFY_STOCK = 0x12894, bytes.fromhex("811a8053")
POOL_FO, POOL_STOCK = 0x4594, 0xBFC16A2D
PLAY_FO, PLAY_VA, PLAY_MAX = 0x9B00, 0x71252500, 0x800
BROW_FO, BROW_VA, BROW_MAX = 0x163C0, 0x485946C0, 0x540
BTRAMP_FO, BTRAMP_VA, BTRAMP_MAX = 0x48E0, 0x780DB7E0, 0x220

M_TRAMP_FO, M_TRAMP_VA, M_TRAMP_MAX = 0x5C38, 0x7A053338, 0xC8
M_STEP_FO, M_STEP_VA, M_STEP_MAX = 0xCB00, 0x7B268000, 0x600


def main():
    stock_brow = sys.argv[1]
    patched_brow = sys.argv[2] if len(sys.argv) > 2 else None
    stock_meng = sys.argv[3] if len(sys.argv) > 3 else None
    patched_meng = sys.argv[4] if len(sys.argv) > 4 else None
    work = tempfile.mkdtemp(prefix="m30play.")
    rc = 0

    def check(ok, msg):
        nonlocal rc
        print(("PASS  " if ok else "FAIL  ") + msg)
        if not ok:
            rc = 1

    def run(cmd, what, cwd=work):
        r = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
        if r.returncode:
            print("FAIL  %s\n%s" % (what, r.stderr[-800:]))
            shutil.rmtree(work, ignore_errors=True)
            sys.exit(2)
        return r

    def nm_syms(elf):
        syms = {}
        for line in subprocess.run([CROSS + "nm", elf], capture_output=True, text=True,
                                   cwd=work).stdout.splitlines():
            p = line.split()
            if len(p) == 3:
                syms[p[2]] = int(p[0], 16)
        return syms

    def no_writable(elf):
        sz = subprocess.run([CROSS + "size", "-A", elf], capture_output=True, text=True,
                            cwd=work).stdout
        return not any(
            l.split()[0].startswith((".data", ".sdata", ".bss", ".sbss")) and int(l.split()[1]) > 0
            for l in sz.splitlines() if len(l.split()) >= 2 and l.split()[1].isdigit()
        )

    # ---- part 1: patch_m3uplay.py on temp copies of stock binaries --------
    dummy_p = os.path.join(work, "dummy_p.bin")
    dummy_bc = os.path.join(work, "dummy_bc.bin")
    dummy_bt = os.path.join(work, "dummy_bt.bin")
    dummy_mt = os.path.join(work, "dummy_mt.bin")
    dummy_ms = os.path.join(work, "dummy_ms.bin")
    open(dummy_p, "wb").write(bytes(range(64)))
    open(dummy_bc, "wb").write(bytes(range(48)))
    open(dummy_bt, "wb").write(bytes(range(32)))
    open(dummy_mt, "wb").write(bytes(range(32)))
    open(dummy_ms, "wb").write(bytes(range(64)))
    out_b = os.path.join(work, "patched_b.ap")
    out_m = os.path.join(work, "patched_m.ap")

    args = [sys.executable, "-I", os.path.join(HERE, "patch_m3uplay.py"),
            stock_brow, out_b, dummy_p, hex(PLAY_VA),
            dummy_bc, hex(BROW_VA), dummy_bt,
            hex(BTRAMP_VA), hex(BTRAMP_VA + 8), hex(BTRAMP_VA + 12), hex(BTRAMP_VA + 16)]
    if stock_meng:
        args += [stock_meng, out_m, dummy_mt, dummy_ms,
                 hex(M_TRAMP_VA), hex(M_TRAMP_VA + 8), hex(M_TRAMP_VA + 12), hex(M_TRAMP_VA + 16)]
    r = subprocess.run(args, capture_output=True, text=True)
    check(r.returncode == 0, "patch_m3uplay.py runs on stock binaries (%s)" % r.stderr.strip()[-200:])
    if r.returncode == 0:
        a = open(stock_brow, "rb").read()
        b = open(out_b, "rb").read()
        check(len(a) == len(b), "patched browser.ap keeps stock size")
        check(b[CLICK_FO:CLICK_FO + 4] == CLICK_NEW, "click site 0x12808 = jal 0x485946C0 (window 0x48)")
        check(b[0x2300:0x2B00] == a[0x2300:0x2B00], "bank 0x400D4C00 stays 100% untouched stock")
        check(b[OLD_CLASSIFY_FO:OLD_CLASSIFY_FO + 4] == OLD_CLASSIFY_STOCK, "classify site 0x12894 stays stock")
        check(struct.unpack_from("<I", b, POOL_FO)[0] == POOL_STOCK, "browser fetch pool stays stock")
        check(b[PLAY_FO:PLAY_FO + 64] == bytes(range(64)), "play blob written at 0x71252500 (file 0x9B00)")
        check(b[BROW_FO:BROW_FO + 48] == bytes(range(48)), "brow blob written at 0x485946C0 (file 0x163C0)")
        check(b[BTRAMP_FO:BTRAMP_FO + 32] == bytes(range(32)), "brow tramp written at 0x780DB7E0 (file 0x48E0)")
        if stock_meng:
            am = open(stock_meng, "rb").read()
            bm = open(out_m, "rb").read()
            check(len(am) == len(bm), "patched mengine.ap keeps stock size")
            check(bm[M_TRAMP_FO:M_TRAMP_FO + 32] == bytes(range(32)), "mengine tramp at 0x5C38")
            check(bm[M_STEP_FO:M_STEP_FO + 64] == bytes(range(64)), "mengine step at 0xCB00")

    r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "patch_m3uplay.py"),
                        stock_brow, out_b, dummy_p, hex(0x400D4CD0),
                        dummy_bc, hex(BROW_VA), dummy_bt,
                        hex(BTRAMP_VA), hex(BTRAMP_VA + 8), hex(BTRAMP_VA + 12), hex(BTRAMP_VA + 16)],
                       capture_output=True, text=True)
    check(r.returncode != 0, "patch tool rejects window-0x40 play_va")

    # ---- part 2: production blobs & patched outputs -----------------------
    pbin, pelf = os.environ.get("M30_PLAY_BIN"), os.environ.get("M30_PLAY_ELF")
    bbin, belf = os.environ.get("M30_BROW_BIN"), os.environ.get("M30_BROW_ELF")
    btbin, btelf = os.environ.get("M30_BTRAMP_BIN"), os.environ.get("M30_BTRAMP_ELF")
    mtbin, mtelf = os.environ.get("M30_MTRAMP_BIN"), os.environ.get("M30_MTRAMP_ELF")
    msbin, mself = os.environ.get("M30_MSTEP_BIN"), os.environ.get("M30_MSTEP_ELF")
    if pbin and pelf and bbin and belf and btbin and btelf:
        play = open(pbin, "rb").read()
        brow = open(bbin, "rb").read()
        btramp = open(btbin, "rb").read()
        psyms = nm_syms(pelf)
        bsyms = nm_syms(belf)
        btsyms = nm_syms(btelf)
        check(len(play) <= PLAY_MAX, "browser play blob fits (%d <= %d)" % (len(play), PLAY_MAX))
        check(len(brow) <= BROW_MAX, "browser vdir blob fits (%d <= %d)" % (len(brow), BROW_MAX))
        check(len(btramp) <= BTRAMP_MAX, "browser tramp fits (%d <= %d)" % (len(btramp), BTRAMP_MAX))
        check(psyms.get("m3u_file_click") == PLAY_VA, "m3u_file_click at 0x71252500")
        check(bsyms.get("m3uplay_tramp") == BROW_VA, "m3uplay_tramp at 0x485946C0")
        check(no_writable(pelf) and no_writable(belf) and no_writable(btelf),
              "browser blobs have no writable data")
        if patched_brow:
            sb = open(stock_brow, "rb").read()
            d = open(patched_brow, "rb").read()
            check(d[CLICK_FO:CLICK_FO + 4] == CLICK_NEW, "built browser.ap: click site 0x12808 patched")
            check(d[0x2300:0x2B00] == sb[0x2300:0x2B00], "built browser.ap: 0x400D4C00 completely untouched")
            check(d[PLAY_FO:PLAY_FO + len(play)] == play, "built browser.ap: play blob in 0x71252500")
            check(d[BROW_FO:BROW_FO + len(brow)] == brow, "built browser.ap: vdir blob in 0x485946C0")
            check(d[BTRAMP_FO:BTRAMP_FO + len(btramp)] == btramp, "built browser.ap: tramp blob in 0x780DB7E0")
            check(struct.unpack_from("<I", d, 0x46A4)[0] == (btsyms["m3u_b_setloc"] | 1)
                  and struct.unpack_from("<I", d, 0x46D4)[0] == (btsyms["m3u_b_dirop"] | 1),
                  "built browser.ap: fsel mode-0 table redirected")
    if mtbin and mtelf and msbin and mself:
        mt = open(mtbin, "rb").read()
        ms = open(msbin, "rb").read()
        mtsyms = nm_syms(mtelf)
        mssyms = nm_syms(mself)
        check(len(mt) <= M_TRAMP_MAX, "mengine tramp fits (%d <= %d)" % (len(mt), M_TRAMP_MAX))
        check(len(ms) <= M_STEP_MAX, "mengine step fits (%d <= %d)" % (len(ms), M_STEP_MAX))
        check(mssyms.get("m3u_meng_step") == M_STEP_VA, "m3u_meng_step at 0x7B268000")
        check(no_writable(mtelf) and no_writable(mself), "mengine blobs have no writable data")
        if patched_meng:
            dm = open(patched_meng, "rb").read()
            check(dm[M_TRAMP_FO:M_TRAMP_FO + len(mt)] == mt, "built mengine.ap: tramp in place")
            check(dm[M_STEP_FO:M_STEP_FO + len(ms)] == ms, "built mengine.ap: step in place")
            check(struct.unpack_from("<I", dm, 0x59B4)[0] == (mtsyms["m3u_setloc_0"] | 1)
                  and struct.unpack_from("<I", dm, 0x59D8)[0] == (mtsyms["m3u_next_0"] | 1),
                  "built mengine.ap: fsel mode-0 table redirected")

    # ---- part 3: host -m32 scenario test ----------------------------------
    hostcc = os.environ.get("HOSTCC", "gcc")
    probe = subprocess.run([hostcc, "-m32", "-x", "c", "-o", "probe32", "-"],
                           input="int main(void){return 0;}\n",
                           capture_output=True, text=True, cwd=work)
    check(probe.returncode == 0, "host toolchain builds 32-bit (-m32)")
    flow_def = ["-DM30_COVERFLOW"] if os.environ.get("M30_COVERFLOW_TEST") == "1" else []
    run([hostcc, "-m32", *flow_def, "-DM3UPLAY_TEST", "-I" + HERE, "-O1", "-Wall", "-Wextra",
         "-o", "hosttest",
         os.path.join(HERE, "m3uplaytest.c"),
         os.path.join(HERE, "m3uplay.c"),
         os.path.join(HERE, "m3u_brow.c"),
         os.path.join(HERE, "m3u_meng.c"),
         os.path.join(HERE, "../coverflow/m3u_queue.c")], "host test compile")
    r = subprocess.run([os.path.join(work, "hosttest")], capture_output=True, timeout=60)
    out = r.stdout.decode("utf-8", "replace").splitlines()
    check(r.returncode == 0 and any("ALL PASS" in l for l in out), "all host VFS + virtual-folder + mengine scenarios passed")
    for l in out:
        if l.startswith(("PASS", "FAIL", "ALL")):
            print("      " + l)

    # ---- part 4: QEMU MIPS16e execution of ALL THREE assembly trampolines -
    run([CROSS + "gcc"] + CFLAGS +
        ["-DM3UPLAY_TEST", "-DM3UPLAY_TEST_DONE=0x40D15201",
         "-DM3UPLAY_TEST_EMPTY=0x40D150BD", "-DM3UPLAY_TEST_BADFMT=0x40D151CF",
         "-I" + HERE, "-x", "assembler-with-cpp", "-c", "-o", "ptramp.raw.o",
         os.path.join(HERE, "m3uplay_tramp.S")], "QEMU ptramp asm")
    run([CROSS + "objcopy", "--set-section-alignment", ".text=4", "ptramp.raw.o", "ptramp.o"], "ptramp align")

    run([CROSS + "gcc"] + CFLAGS +
        ["-DM3UPLAY_TEST", "-DM3UPLAY_TEST_VD=0x10001800",
         "-I" + HERE, "-x", "assembler-with-cpp", "-c", "-o", "btramp.raw.o",
         os.path.join(HERE, "m3u_brow_tramp.S")], "QEMU btramp asm")
    run([CROSS + "objcopy", "--set-section-alignment", ".text=4", "btramp.raw.o", "btramp.o"], "btramp align")

    run([CROSS + "gcc"] + CFLAGS +
        ["-DM3UPLAY_TEST", "-DM3UPLAY_TEST_VS=0x10002000", "-DM3UPLAY_TEST_ENG=0x10002220",
         "-I" + HERE, "-x", "assembler-with-cpp", "-c", "-o", "mtramp.raw.o",
         os.path.join(HERE, "m3u_meng_tramp.S")], "QEMU mtramp asm")
    run([CROSS + "objcopy", "--set-section-alignment", ".text=4", "mtramp.raw.o", "mtramp.o"], "mtramp align")

    disp_asm = os.path.join(work, "disp.S")
    with open(disp_asm, "w") as f:
        f.write("""\t.set noreorder
\t.set mips16
\t.section .testram,"aw",@nobits
\t.space\t0x4000
\t.section .disp_sec,"ax",@progbits
\t.globl fake_disp
fake_disp:
\tsave\t0x38, $ra, $s0, $s1
\taddiu\t$s1, $sp, 0x10
\tsw\t$a0, 0x28($s1)
\tsw\t$a1, 0x2C($s1)
\tli\t$v0, 0
\tsh\t$v0, 4($s1)
\tsb\t$v0, 6($s1)
\tb\t.Lclick_site
\t.org\t0x1c
\t.insn
\t/* 0x40D150BC: Empty folder target */
\tli\t$v0, 0x18
\tsh\t$v0, 4($s1)
\tb\t.Ldone_site
\t.org\t0x66
.Lclick_site:
\tnop
\t/* 0x40D15108: exact patched file-click jal 0x485946C0 */
\t.byte\t0xd0, 0x1a, 0xb0, 0x51
\tnop
\t/* 0x40D1510E: sh $v0, 4($s1); lhu $v0, 4($s1); beqz $v0, 0x40D15200 */
\tsh\t$v0, 4($s1)
\tlhu\t$v0, 4($s1)
\tbeqz\t$v0, .Ldone_site
\taddiu\t$v0, 0x10
\tsh\t$v0, 4($s1)
\tb\t.Ldone_site
\t.org\t0x12e
\t.insn
\t/* 0x40D151CE: Unsupported format target */
\tli\t$v0, 0x19
\tsh\t$v0, 4($s1)
\t.org\t0x160
\t.insn
.Ldone_site:
\t/* 0x40D15200 */
\tlhu\t$v0, 4($s1)
\tmove\t$sp, $s1
\trestore\t0x28, $ra, $s0, $s1
\tjrc\t$ra
""")
    run([CROSS + "gcc"] + CFLAGS + ["-c", "-o", "disp.o", disp_asm], "QEMU disp asm")

    stubs_c = os.path.join(work, "qstubs.c")
    with open(stubs_c, "w") as f:
        f.write("""#include "m3uplay.h"
#define ST ((volatile u32 *)0x10003000u)
__attribute__((section(".r71_stubs")))
u32 m3u_file_click(u8 *arg0, u8 *state) {
    ST[1] = (u32)arg0; ST[2] = (u32)state; return ST[0];
}
__attribute__((section(".r71_stubs")))
u32 m3u_vdir_get_items(const u8 *req, u8 *out) {
    (void)req; (void)out; ST[5] = 777; return 1;
}
__attribute__((section(".r48_stubs")))
u32 m3u_test_stock_prompt(u8 *arg0, u8 *state) {
    ST[4] = (u32)arg0 ^ (u32)state; return 0x801Eu;
}
__attribute__((section(".r48_stubs")))
u32 m3u_vdir_setloc(const u8 *loc, u32 f) {
    (void)loc; (void)f; return ST[6];
}
__attribute__((section(".r48_stubs")))
u32 m3u_vdir_dir_op(u32 cmd, u32 idx, u8 *out) {
    ST[7] = (cmd << 8) | idx; ST[8] = (u32)out; return ST[6];
}
__attribute__((section(".r7_stubs")))
u32 m3u_test_b_stock_setloc(const u8 *l, u32 f) { (void)l; ST[5] = 200 + f; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_b_stock_del(u32 a, u32 b, u8 *c) { (void)a; (void)b; (void)c; ST[5] = 210; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_b_stock_items(const u8 *r, u8 *o) { (void)r; (void)o; ST[5] = 220; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_b_stock_dirop(u32 c, u32 i, u8 *o) { ST[5] = 300 + c * 10 + i; ST[9] = (u32)o; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_stock_setloc(u8 *d, u32 b) {
    (void)d; (void)b;
    *(volatile u16 *)0x10002226u = 2;
    *(volatile u16 *)0x10002224u = 2;
    return !b;
}
__attribute__((section(".r7_stubs")))
u32 m3u_test_stock_next(u32 *o) { (void)o; ST[3] = 10; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_stock_prev(u32 *o) { (void)o; ST[3] = 20; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_test_stock_byno(u32 *o, u32 n) { (void)o; ST[3] = 30 + n; return 1; }
__attribute__((section(".r7_stubs")))
u32 m3u_meng_step(u32 *o, u32 a, u32 op) { (void)o; ST[3] = 100 + op * 10 + (op == 0 ? a : 0); return 1; }
""")
    run([CROSS + "gcc"] + CFLAGS + ["-I" + HERE, "-c", "-o", "qstubs.raw.o", stubs_c], "QEMU stubs compile")
    run([CROSS + "objcopy", "--set-section-alignment", ".r48_stubs=4",
         "--set-section-alignment", ".r71_stubs=4",
         "--set-section-alignment", ".r7_stubs=4", "qstubs.raw.o", "qstubs.o"], "QEMU stubs align")

    qharness = os.path.join(work, "qtest.c")
    with open(qharness, "w") as f:
        f.write("""#include "m3uplay.h"
#define ST ((volatile u32 *)0x10003000u)
static long sys3(long n, long a, long b, long c) {
    register long v0 asm("v0") = n, a0 asm("a0") = a, a1 asm("a1") = b, a2 asm("a2") = c, a3 asm("a3");
    asm volatile("syscall" : "+r"(v0), "=r"(a3) : "r"(a0), "r"(a1), "r"(a2) : "memory",
        "t0","t1","t2","t3","t4","t5","t6","t7","t8","t9","hi","lo");
    return v0;
}
extern u32 fake_disp(u8 *arg0, u8 *state);
extern u32 m3u_b_setloc(const u8 *l, u32 f);
extern u32 m3u_b_del(u32 a, u32 b, u8 *c);
extern u32 m3u_b_items(const u8 *r, u8 *o);
extern u32 m3u_b_dirop(u32 c, u32 i, u8 *o);
extern u32 m3u_setloc_0(u8 *desc, u32 b);
extern u32 m3u_next_0(u32 *o);
extern u32 m3u_prev_0(u32 *o);
extern u32 m3u_byno_0(u32 *o, u32 n);

void _start(void) {
    static u8 state[32], arg0[0xA8] __attribute__((aligned(4)));
    volatile u32 *vd_magic = (volatile u32 *)0x10001800u;
    volatile struct vstate *vs = (volatile struct vstate *)0x10002000u;
    u32 (*disp)(u8 *, u8 *) = (u32 (*)(u8 *, u8 *))((u32)fake_disp | 1u);
    u32 (*bset)(const u8 *, u32) = (u32 (*)(const u8 *, u32))((u32)m3u_b_setloc | 1u);
    u32 (*bdel)(u32, u32, u8 *) = (u32 (*)(u32, u32, u8 *))((u32)m3u_b_del | 1u);
    u32 (*bitm)(const u8 *, u8 *) = (u32 (*)(const u8 *, u8 *))((u32)m3u_b_items | 1u);
    u32 (*bdir)(u32, u32, u8 *) = (u32 (*)(u32, u32, u8 *))((u32)m3u_b_dirop | 1u);
    u32 (*setloc)(u8 *, u32) = (u32 (*)(u8 *, u32))((u32)m3u_setloc_0 | 1u);
    u32 (*nextf)(u32 *) = (u32 (*)(u32 *))((u32)m3u_next_0 | 1u);
    u32 (*prevf)(u32 *) = (u32 (*)(u32 *))((u32)m3u_prev_0 | 1u);
    u32 (*bynof)(u32 *, u32) = (u32 (*)(u32 *, u32))((u32)m3u_byno_0 | 1u);
    u32 r;

    /* 1. m3uplay_tramp (0x485946C0 -> 0x71252500): 0x8001, 0, 0x8018, 0x8019, 0xFFFE */
    ST[0] = 0x8001u;
    r = disp(arg0, state);
    if (r != 0x8001u || ST[1] != (u32)arg0 || ST[2] != (u32)state) sys3(4001, 11, 0, 0);

    ST[0] = 0u;
    r = disp(arg0, state);
    if (r != 0u) sys3(4001, 12, 0, 0);

    ST[0] = 0x8018u;
    r = disp(arg0, state);
    if (r != 0x18u) sys3(4001, 13, 0, 0);

    ST[0] = 0x8019u;
    r = disp(arg0, state);
    if (r != 0x19u) sys3(4001, 14, 0, 0);

    ST[0] = 0xFFFEu; ST[4] = 0;
    r = disp(arg0, state);
    if (r != 0x802Eu || ST[4] != ((u32)arg0 ^ (u32)state)) sys3(4001, 15, 0, 0);

    /* 2. m3u_brow_tramp: inactive VD -> stock; active VD -> virtual handlers */
    *vd_magic = 0;
    ST[6] = 0xFFFEu; bset(arg0, 3); if (ST[5] != 203) sys3(4001, 21, 0, 0);
    if (bdel(1, 2, arg0) != 1 || ST[5] != 210) sys3(4001, 22, 0, 0);
    bitm(arg0, state); if (ST[5] != 220) sys3(4001, 23, 0, 0);
    bdir(1, 4, arg0); if (ST[5] != 314) sys3(4001, 24, 0, 0);

    *vd_magic = M3UVD_MAGIC;
    ST[6] = 1u; if (bset(arg0, 0) != 1) sys3(4001, 25, 0, 0);
    if (bdel(1, 2, arg0) != 0) sys3(4001, 26, 0, 0);
    bitm(arg0, state); if (ST[5] != 777) sys3(4001, 27, 0, 0);
    ST[6] = 1u; if (bdir(0, 0, state) != 1 || ST[7] != 0) sys3(4001, 28, 0, 0);
    ST[6] = 0xFFFEu; bdir(2, 0, state); if (ST[5] != 300 || ST[9] != (u32)state) sys3(4001, 29, 0, 0);
    ST[6] = 0xFFFDu; bdir(3, 5, state); if (ST[5] != 335 || ST[9] != (u32)state) sys3(4001, 30, 0, 0);

    /* 3. m3u_meng_tramp: setloc arm/disarm + next/prev/byno */
    vs->magic = M3UPLAY_MAGIC;
    vs->count = 78;
    vs->pos = 1;
    vs->cur_off[0] = 0x11223344u;
    vs->cur_off[1] = 0x55667788u;
    *(u32 *)(arg0 + 4 + 8) = 0x11223344u;
    *(u32 *)(arg0 + 4 + 12) = 0x55667788u;
    if (setloc(arg0+4,1) || vs->magic!=M3UPLAY_WAITING) sys3(4001, 37, 0, 0);
    nextf(0); if (ST[3]!=10) sys3(4001, 38, 0, 0);
    setloc(arg0 + 4, 0);
    if (vs->magic!=M3UPLAY_MAGIC) sys3(4001, 39, 0, 0);
    if (*(volatile u16 *)0x10002226u != 78 || *(volatile u16 *)0x10002224u != 1) sys3(4001, 31, 0, 0);
    nextf(0); if (ST[3] != 110) sys3(4001, 32, 0, 0);
    prevf(0); if (ST[3] != 120) sys3(4001, 33, 0, 0);
    bynof(0, 7); if (ST[3] != 107) sys3(4001, 34, 0, 0);

    *(u32 *)(arg0 + 4 + 8) = 0x99999999u;
    setloc(arg0 + 4, 0);
    if (vs->magic != 0 || vs->handoff!=M3U_HANDOFF_MISMATCH || *(volatile u16 *)0x10002226u != 2) sys3(4001, 35, 0, 0);
    nextf(0); if (ST[3] != 10) sys3(4001, 36, 0, 0);

    sys3(4004, 1, (long)"QEMU OK\\n", 8);
    sys3(4001, 0, 0, 0);
}
""")
    run([CROSS + "gcc"] + HFLAGS + ["-I" + HERE, "-c", "-o", "qtest.o", qharness], "QEMU harness compile")
    qld = os.path.join(work, "qtest.ld")
    with open(qld, "w") as f:
        f.write("""ENTRY(_start)
SECTIONS {
    . = 0x00400000;
    .text : { qtest.o(.text .text.* .rodata .rodata.*) }
    .data : { qtest.o(.data .data.*) }
    .bss  : { qtest.o(.bss .bss.* COMMON) }
    . = 0x10001000;
    .testram : { disp.o(.testram) }
    . = 0x40D150A0;
    .disp : { disp.o(.disp_sec) }
    . = 0x485946C0;
    .ptramp : { ptramp.o(.text) qstubs.o(.r48_stubs) }
    . = 0x71252500;
    .r71 : { qstubs.o(.r71_stubs) }
    . = 0x780DB7E0;
    .btramp : { btramp.o(.text) }
    . = 0x7A053338;
    .mtramp : { mtramp.o(.text) }
    . = 0x7B268000;
    .r7 : { qstubs.o(.r7_stubs) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
""")
    run([CROSS + "ld", "-EL", "-m", "elf32ltsmip", "-T", qld, "-o", "qtest.elf",
         "qtest.o", "ptramp.o", "btramp.o", "disp.o", "mtramp.o", "qstubs.o"], "QEMU link")
    r = subprocess.run([QEMU, "-cpu", "24Kc", "./qtest.elf"], cwd=work,
                       capture_output=True, timeout=60)
    check(r.returncode == 0 and r.stdout.startswith(b"QEMU OK"),
          "QEMU MIPS16e trampolines (w48 click -> w71 + w78 fsel + mengine) passed (rc=%d, out=%r)" % (r.returncode, r.stdout))

    shutil.rmtree(work, ignore_errors=True)
    print("test_m3uplay: %s" % ("OK" if rc == 0 else "FAILED"))
    return rc


if __name__ == "__main__":
    sys.exit(main())
