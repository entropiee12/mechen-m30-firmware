"""Install individually guarded playback fixes and the optional indexing mode."""
import argparse
import json
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent
U32 = struct.Struct('<I')
DESC = struct.Struct('<HHIHH')
ROW = struct.Struct('<HHIIII')


def replace(data, offset, old, new):
    if len(old) != len(new) or data[offset:offset+len(old)] != old:
        raise ValueError(f'unexpected bytes at {offset:#x}, expected {old.hex()}')
    data[offset:offset+len(new)] = new


def branch(pc, dest):
    delta = dest - (pc+2)
    assert delta % 2 == 0 and -2048 <= delta <= 2046
    return struct.pack('<H', 0x1000 | (delta//2 & 0x7ff))


def install_gate(data, site, start, blob, symbols, launch, skip, original):
    offset, size, addr = struct.unpack_from('<III', data, 8)
    stub = data[offset:offset+size].index(bytes.fromhex('0f6b05e8'))
    code = bytearray(blob)
    placeholder = U32.pack(0xfaceca11)
    assert code.count(placeholder) == 1
    replace(code, code.index(placeholder), placeholder, U32.pack((addr+stub)|1))
    for symbol, dest in zip(symbols, (launch, skip)):
        replace(code, symbol, bytes.fromhex('0065'), branch(start+symbol,dest))
    replace(data, start, bytes(len(code)), code)
    replace(data, site, original, branch(site,start) + bytes.fromhex('0065')*(len(original)//2-1))


def add_menu(data, callbacks, first=221):
    table, count = struct.unpack_from('<HH', data, 12)
    assert table == 16 and count in (18,19,20)
    entries = []
    for i in range(count):
        label, n, off, parent, idx = DESC.unpack_from(data,table+i*DESC.size)
        entries.append((label, bytes(data[off:off+n*ROW.size]),parent,idx))
    label, root, parent, idx = entries[0]
    stock_option = 0x40154c01
    rows = [ROW.pack(0xffff, first+1+i, 0, addr, 0, stock_option) for i,addr in enumerate(callbacks[1:])]
    entries.append((first,b''.join(rows),0,len(root)//ROW.size))
    entries[0] = (label,root+ROW.pack(count,first,0,callbacks[0],0,stock_option),parent,idx)
    struct.pack_into('<H',data,14,len(entries))
    cursor = table+DESC.size*len(entries)
    offsets = {}
    for i,(label,rows,parent,idx) in enumerate(entries):
        if rows not in offsets:
            assert cursor+len(rows)<=0x980
            offsets[rows]=cursor
            data[cursor:cursor+len(rows)]=rows
            cursor+=len(rows)
        DESC.pack_into(data,table+i*DESC.size,label,len(rows)//ROW.size,offsets[rows],parent,idx)


def add_strings(data, first=221, labels=('Library indexing','Manual','Automatic')):
    for header in (0x10,0x20):
        table,n = struct.unpack_from('<IH',data,header)
        for i in range(n):
            entry=table+16*i
            base,size,kind,count=struct.unpack_from('<IIHH',data,entry)
            if kind!=1: continue
            assert count==first
            pairs=[struct.unpack_from('<II',data,base+8*j) for j in range(count)]
            assert all(base+8*count<=p<=p+k<=base+size for p,k in pairs)
            strings=[bytes(data[p:p+k]) for p,k in pairs]
            strings.extend((s+'\0').encode('utf-16-le') for s in labels)
            cursor=base+8*len(strings)
            offsets={}
            for j,s in enumerate(strings):
                if s not in offsets:
                    assert cursor+len(s)<=base+size
                    offsets[s]=cursor
                    data[cursor:cursor+len(s)]=s
                    cursor+=len(s)
                struct.pack_into('<II',data,base+8*j,offsets[s],len(s))
            struct.pack_into('<H',data,entry+10,len(strings))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('symbols',nargs=7,type=lambda s:int(s,16))
    parser.add_argument('--flow',nargs=3,type=lambda s:int(s,16))
    args=parser.parse_args()
    images={}
    def member(n):
        if n not in images:
            images[n]=bytearray(Path(n).read_bytes())
        return images[n]

    for patch in json.loads((HERE/'playback.json').read_text()):
        replace(member(patch['member']),int(patch['offset'],16),
                bytes.fromhex(patch['before']),bytes.fromhex(patch['after']))

    flag=Path('indexflag.bin').read_bytes()
    for n,site,start,skip,old in [('config.ap',0x1e0c,0x2210,0x1e28,'0d22'),
                                   ('udisk.ap',0x1908,0x1a10,0x191a,'0822')]:
        data=member(n)
        if n=='udisk.ap':
            bank=struct.pack('<III',0x1500,0x40054c00,0x510)
            assert data[:0x200].count(bank)==1 and start+len(flag)<=0x1b00
            U32.pack_into(data,data[:0x200].index(bank)+8,0x510+len(flag))
        install_gate(data,site,start,flag,args.symbols[:2],site+2,skip,bytes.fromhex(old))

    event=Path('indexevent.bin').read_bytes()
    for n,bank in [('mainmenu.ap',0x21d00),('browser.ap',0x1ff00),('music.ap',0x34800),('setting.ap',0x16900),
                   ('config.ap',0x18000),('playlist.ap',0x1e600)]:
        data=member(n)
        assert struct.pack('<III',bank,0x48bd4600,0x600) in data[:0xc00]
        assert data[bank+0xc8:bank+0xd2]==bytes.fromhex('b0725867015a80f10661')
        site=bank+0x3de
        assert len(event)<=0x100
        install_gate(data,site,bank+0x500,event,args.symbols[2:4],site+4,site+4,bytes.fromhex('10f61c6a'))

    settings=member('setting.ap')
    code=Path('indexmenu.bin').read_bytes()
    assert len(code)<=0x400 and struct.pack('<III',0x6400,0x402d4c00,0x800) in settings[:0x800]
    replace(settings,0x6800,bytes(len(code)),code)
    add_menu(member('setting.mcg'),[v|1 for v in args.symbols[4:]])
    add_strings(member('setting.sty'))
    if args.flow:
        add_menu(member('setting.mcg'),[a|1 for a in args.flow],224)
        add_strings(member('setting.sty'),224,('Cover Flow','On','Off'))
    for name,data in images.items(): Path(name).write_bytes(data)


if __name__=='__main__': main()
