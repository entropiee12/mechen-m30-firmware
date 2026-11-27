"""Install the Now Playing artwork-refresh callback at its verified stock branch."""
from pathlib import Path
import struct
import sys

path=Path('music.ap')
data=bytearray(path.read_bytes())
for name,bank,base,size,start in [
    ('flow_native',0x2aa00,0x48594400,0x800,0x300),
    ('flow_library',0x5c00,0x40294c00,0x800,0x400),
    ('flow_m3u',0x22c00,0x71892500,0x800,0x0c),
    ('flow_tile',0x22400,0x71852500,0x800,0x0c),
    ('flow_auto',0x20400,0x71752500,0x800,0x10),
    ('flow_device',0xb400,0x40594c00,0x800,0x390),
    ('flow_queue',0x8400,0x403d4c00,0x800,0x310),
    ('flow_cache',0x29e00,0x48514600,0x600,0x120),
    ('flow_draw',0x13500,0x7829b200,0x800,0x3f0),
]:
    blob=Path(name+'.bin').read_bytes()
    # Check the linker placement as well as the free space in stock firmware.
    # A byte-for-byte repack alone cannot detect a blob linked 1 KiB too early.
    elf=Path(name+'.elf').read_bytes()
    assert elf[:6]==b'\x7fELF\x01\x01'
    section_offset=struct.unpack_from('<I',elf,32)[0]
    section_size,section_count,names_index=struct.unpack_from('<HHH',elf,46)
    sections=[struct.unpack_from('<10I',elf,section_offset+i*section_size)
              for i in range(section_count)]
    names=elf[sections[names_index][4]:sections[names_index][4]+sections[names_index][5]]
    text=next(section for section in sections
              if names[section[0]:].split(b'\0',1)[0]==b'.text')
    assert text[3]==base+start and text[5]==len(blob), (name,hex(text[3]),hex(base+start))
    assert struct.pack('<III',bank,base,size) in data[0x100:0x1000]
    assert 0<len(blob)<=size-start and not any(data[bank+start:bank+start+len(blob)])
    data[bank+start:bank+start+len(blob)]=blob
entry=int(sys.argv[1],16)
assert 0x40594f90<=entry<0x40595400 and entry%4==0
assert data[0x39f4:0x3a04]==bytes.fromhex('50b2609a046a6cea032230181f510065')
def jal(address):
    word=address>>2
    return struct.pack('<HH',0x1800|((word>>21)&31)|(((word>>16)&31)<<5),word&65535)
assert jal(0x4805447c)==data[0x39fe:0x3a02]
data[0x39f4:0x3a04]=jal(entry)+bytes.fromhex('0065')*6
# The stock dirty-bit-8 branch skips the complete artwork block. Route its
# taken edge through a second callback, then return to the original join.
bypass=int(sys.argv[2],16)
assert 0x40594f90<=bypass<0x40595400 and bypass%4==0
assert data[0x38fa:0x38fe]==bytes.fromhex('80f00a22')
assert data[0x3a12:0x3a16]==bytes.fromhex('5099609a')
assert not any(data[0x3b70:0x3b7a])
data[0x38fa:0x38fe]=bytes.fromhex('20f11922')  # beqz v0,40155370
data[0x3b70:0x3b7a]=jal(bypass)+bytes.fromhex('00654d170065')  # nop, b 40155212, nop
text_entry=int(sys.argv[3],16)
assert 0x40594f90<=text_entry<0x40595400 and text_entry%4==0
# This call is in the stock Now Playing painter. The six other calls once
# hooked here belong to shared Music list/menu banks and must keep their text.
for site,pool in [(0x380a,0x3b24)]:
    load=0xb200+((pool-((site+2)&~3))//4)
    assert data[site:site+6]==struct.pack('<HHH',0x6f03,load,0xeac0)
    assert struct.unpack_from('<I',data,pool)[0]==0xbfc16405
    data[site:site+6]=jal(text_entry)+bytes.fromhex('036f')
# The Now Playing painter uses the pointer in its own bank. The pointer at
# 0x2f1c belongs to the shared Music menu; filtering it hides menu rows.
scene=int(sys.argv[4],16)
assert 0x48594700<=scene<0x48594c00 and scene%4==0
for pool in (0x3b24,):
    assert struct.unpack_from('<I',data,pool)[0]==0xbfc16405
    struct.pack_into('<I',data,pool,scene|1)
assert struct.unpack_from('<I',data,0x2f1c)[0]==0xbfc16405
metadata=int(sys.argv[5],16)
assert 0x48594700<=metadata<0x48594c00 and metadata%4==0
assert data[0x375a:0x3760]==jal(0x40154c88)+bytes.fromhex('0065')
assert struct.unpack_from('<I',data,0x35c8)[0]==0x9fc15401
assert struct.unpack_from('<I',data,0x35d8)[0]==0x4845473d
data[0x375a:0x375e]=jal(metadata)
# Stock cache I/O reads its filename through these four literals. Point them at
# the shared writable name in vstate, so neighbor decodes cannot clobber the
# live track's ALBUM.PIC even when a decoder bank is reloaded.
for site,original in [(0xeb58,0x48054a28),(0xef18,0x48054a28),
                      (0xd4dc,0x40695018),(0xd610,0x40695018)]:
    assert struct.unpack_from('<I',data,site)[0]==original
    struct.pack_into('<I',data,site,0xbfc109f0)
path.write_bytes(data)

# The stock decoder can run before the first repaint hook. Initialize its
# redirected filename in the resident image, rather than relying on refresh.
key=Path('key.drv')
resident=bytearray(key.read_bytes())
offset,size,address=struct.unpack_from('<III',resident,8)
name_offset=offset+0xbfc109f0-address
assert 0<=name_offset and name_offset+12<=offset+size
assert resident[name_offset:name_offset+12]==bytes(12)
resident[name_offset:name_offset+12]=b'ALBUM.PIC\0\0\0'
key.write_bytes(resident)
