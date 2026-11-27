"""Execute the repacked artwork callsite and all four MIPS blobs in QEMU."""
import argparse
import os
from pathlib import Path
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
CROSS = os.environ.get("CROSS", "mips64-linux-gnu-")
QEMU = os.environ.get("QEMU", "qemu-mipsel-static")


def run(work, pack, output=None, automatic=False):
    firmware = (work / "verify/fw/music.ap").read_bytes()
    # The Music menu's UI service must never route through the Now Playing
    # filter: it needs those calls to paint every list row.
    assert struct.unpack_from("<I",firmware,0x2f1c)[0]==0xbfc16405
    assert struct.unpack_from("<I",firmware,0x3b24)[0]!=0xbfc16405
    symbols = subprocess.check_output([CROSS+"nm", str(work/"flow_auto.elf")], text=True)
    auto_entry = next(int(line.split()[0], 16) | 1
                      for line in symbols.splitlines() if line.endswith(" flow_auto_paint"))
    queue_symbols = subprocess.check_output([CROSS+"nm",str(work/"flow_queue.elf")],text=True)
    native_queue = next(int(line.split()[0],16)|1 for line in queue_symbols.splitlines()
                        if line.endswith(" flow_native_tracks"))
    m3u_symbols = subprocess.check_output([CROSS+"nm", str(work/"flow_m3u.elf")], text=True)
    m3u_entry = next(int(line.split()[0],16)|1 for line in m3u_symbols.splitlines()
                     if line.endswith(" flow_m3u_track"))
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp/"pack").write_bytes(pack)
        (tmp/"hook").write_bytes(firmware[0x39f4:0x3a04])
        (tmp/"gate").write_bytes(firmware[0x38fa:0x38fe])
        (tmp/"skip").write_bytes(firmware[0x3b70:0x3b7a])
        sections = []
        assembly = []
        blobs = [
            ("flow_native",0x2ad00,0x48594700), ("flow_device",0xb790,0x40594f90), ("flow_queue",0x8710,0x403d4f10),
            ("flow_cache",0x29f20,0x48514720), ("flow_draw",0x138f0,0x7829b5f0),
        ]
        if automatic:
            blobs += [("flow_auto",0x20410,0x71752510),
                      ("flow_library",0x6000,0x40295000),
                      ("flow_tile",0x2240c,0x7185250c)]
        for name, offset, addr in blobs:
            original=(work/(name+".bin")).read_bytes()
            data=firmware[offset:offset+len(original)]
            assert data==original
            # Change kernel-space globals and service pointers only. All
            # internal branches, linked addresses and the LCD renderer survive.
            for old,new in {
                **{0xbfc10880+x:0x11000000+x for x in range(0x180)},
                0xbfc16bb1:0x500000,0xbfc16bb5:0x501000,0x4805447d:0x502000,
                **({m3u_entry:0x503000,0x401d51b9:0x504000,0x480546e9:0x505000}
                   if automatic else {auto_entry:0x503000}),
                **{0x9fc00000+x:0x10000000+x for x in
                   (0xbf50,0xbf54,0xbf84,0xbf98,*range(0x15400,0x15fa4))},
            }.items():
                data=data.replace(struct.pack("<I",old),struct.pack("<I",new))
            (tmp/name).write_bytes(data)
            assembly.append(f'.section .{name},"ax"\n.incbin "{name}"')
            sections.append(f'.{name} {addr:#x} : {{ *(.{name}) }}')
        assembly.append('''.section .hook,"ax"
.set mips16
.globl run_refresh
.ent run_refresh
run_refresh:
nop
save 24,$ra
move $2,$4
.incbin "gate"
.short 0x107a
.org 0x100
normal_art:
.incbin "hook"
.short 0x1006
.org 0x11e
joined:
restore 24,$ra
jrc $ra
.org 0x27c
.incbin "skip"
.end run_refresh
.section .pack,"a"
.balign 4
.globl pack_start,pack_end
pack_start: .incbin "pack"
pack_end:
''')
        (tmp/"banks.S").write_text("\n".join(assembly))
        (tmp/"test.ld").write_text("""ENTRY(_start)
SECTIONS {
 . = 0x400000; .text : { *(.text*) } .rodata : { *(.rodata*) }
 .bss : { *(.bss*) *(.sbss*) }
 .sysmock 0x500000 : { *(.sysmock) } .fsmock 0x501000 : { *(.fsmock) }
 .stockmock 0x502000 : { *(.stockmock) }
 .automock 0x503000 : { *(.automock) }
 .metamock 0x504000 : { *(.metamock) }
 .decodemock 0x505000 : { *(.decodemock) }
 .hardware 0x10000000 (NOLOAD) : { *(.hardware) }
 .pack 0x12000000 : { *(.pack) }
 .shared 0x11000000 (NOLOAD) : { *(.shared) }
 .hook 0x401550f4 : { *(.hook) }
"""+"\n".join(sections)+"\n}")
        flags=["-EL","-mabi=32","-march=mips32r2","-mno-abicalls","-fno-pic","-G0"]
        subprocess.run([CROSS+"gcc",*flags,"-mno-mips16","-mlong-calls","-Os","-ffreestanding","-fno-builtin",
                        f"-DFLOW_NATIVE_TEST_ENTRY={native_queue}",f"-DFLOW_LIVE_DECODE={os.environ.get('FLOW_LIVE_DECODE','0')}",*(["-DFLOW_DIAG_TEST"] if automatic and os.environ.get("FLOW_DIAG")=="1" else []),
                        "-c",HERE/("harness_auto.c" if automatic else "harness.c"),"-o","test.o"],cwd=tmp,check=True)
        subprocess.run([CROSS+"gcc",*flags,"-c","banks.S","-o","banks.o"],cwd=tmp,check=True)
        subprocess.run([CROSS+"ld","-EL","-m","elf32ltsmip","-T","test.ld","-o","run","test.o","banks.o"],
                       cwd=tmp,check=True)
        try:
            trace=["-d","in_asm,cpu","-D",os.environ["FLOW_QEMU_TRACE"]] if os.environ.get("FLOW_QEMU_TRACE") else []
            raw=subprocess.check_output([QEMU,*trace,"-cpu","24Kc","./run"],cwd=tmp,timeout=10,stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError as error:
            print(error.output.decode("latin1", errors="replace"))
            raise
        assert len(raw)==(0 if automatic else 320*240*2)
        if output:
            from PIL import Image
            rgb=bytearray()
            for (pixel,) in struct.iter_unpack("<H",raw):
                rgb.extend(((pixel>>11)*255//31,((pixel>>5)&63)*255//63,(pixel&31)*255//31))
            Image.frombytes("RGB",(320,240),bytes(rgb)).save(output)
        if automatic:
            if os.environ.get("FLOW_LIVE_DECODE","0")=="0":
                print("PASS: ordinary album and playlist opens draw current art without neighbor decoding")
                return
            print("PASS: packed automatic loader, native/M3U queues, quantized neighbors, preload, failures and restoration")
            return
        print("PASS: packed Now Playing hook, three visible covers, five-cover cache, next/back, boundaries and fallback")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work",type=Path)
    parser.add_argument("--pack",type=Path)
    parser.add_argument("--preview",type=Path)
    args=parser.parse_args()
    if args.pack:
        pack=args.pack.read_bytes()
        assert len(pack)>=8*1024
    else:
        parts=[]
        for i in range(1,10):
            h=struct.pack("<IIHH",0x3146434d,0xabcd123,i,9)
            h+=struct.pack("<64H",*(i*0x820+j*32 for j in range(64)))
            h+=bytes((j%256 for j in range(800)))+bytes(80)
            checksum=2166136261
            for b in h: checksum=(checksum^b)*16777619&0xffffffff
            parts.append(h+struct.pack("<I",checksum))
        pack=b"".join(parts)
    run(args.work,pack,args.preview)
    run(args.work,pack,automatic=True)
