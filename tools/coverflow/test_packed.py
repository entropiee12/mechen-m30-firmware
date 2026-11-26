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


def run(work, pack, output=None):
    firmware = (work / "verify/fw/music.ap").read_bytes()
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp/"pack").write_bytes(pack)
        (tmp/"hook").write_bytes(firmware[0x39f4:0x3a04])
        (tmp/"gate").write_bytes(firmware[0x38fa:0x38fe])
        (tmp/"skip").write_bytes(firmware[0x3b70:0x3b7a])
        sections = []
        assembly = []
        for name, offset, addr in [
            ("flow_native",0x2ad00,0x48594700), ("flow_device",0xb7a0,0x40594fa0), ("flow_queue",0x8710,0x403d4f10),
            ("flow_cache",0x29f20,0x48514720), ("flow_draw",0x138f0,0x7829b5f0),
        ]:
            original=(work/(name+".bin")).read_bytes()
            data=firmware[offset:offset+len(original)]
            assert data==original
            # Change kernel-space globals and service pointers only. All
            # internal branches, linked addresses and the LCD renderer survive.
            for old,new in {
                0xbfc10880:0x11000000,0xbfc16bb1:0x500000,
                0xbfc16bb5:0x501000,0x4805447d:0x502000,
                **{0x9fc00000+x:0x10000000+x for x in
                   (0xbf50,0xbf54,0xbf84,0xbf98,0x15404,0x15418,0x15b20,0x15f23,0x15f34,0x15f39)},
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
 .hardware 0x10000000 (NOLOAD) : { *(.hardware) }
 .pack 0x12000000 : { *(.pack) }
 .shared 0x11000000 (NOLOAD) : { *(.shared) }
 .hook 0x401550f4 : { *(.hook) }
"""+"\n".join(sections)+"\n}")
        flags=["-EL","-mabi=32","-march=mips32r2","-mno-abicalls","-fno-pic","-G0"]
        subprocess.run([CROSS+"gcc",*flags,"-mno-mips16","-mlong-calls","-Os","-ffreestanding","-fno-builtin",
                        "-c",HERE/"harness.c","-o","test.o"],cwd=tmp,check=True)
        subprocess.run([CROSS+"gcc",*flags,"-c","banks.S","-o","banks.o"],cwd=tmp,check=True)
        subprocess.run([CROSS+"ld","-EL","-m","elf32ltsmip","-T","test.ld","-o","run","test.o","banks.o"],
                       cwd=tmp,check=True)
        raw=subprocess.check_output([QEMU,"-cpu","24Kc","./run"],cwd=tmp,timeout=10)
        assert len(raw)==320*240*2
        if output:
            from PIL import Image
            rgb=bytearray()
            for (pixel,) in struct.iter_unpack("<H",raw):
                rgb.extend(((pixel>>11)*255//31,((pixel>>5)&63)*255//63,(pixel&31)*255//31))
            Image.frombytes("RGB",(320,240),bytes(rgb)).save(output)
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
