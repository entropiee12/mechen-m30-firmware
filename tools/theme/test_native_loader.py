"""Exercise the scan-time SD loader and cached pixel draw with host card services."""
from pathlib import Path
import os
import shutil
import subprocess
import struct
import sys
import tempfile

import sdtheme

here = Path(__file__).resolve().parent
fw = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory() as temp:
    card = Path(temp)
    exe = card / 'test'
    subprocess.run(['cc', '-no-pie', '-pthread', '-Wno-pointer-to-int-cast',
                    '-Wno-int-to-pointer-cast', here/'probe/native_loader_test.c', '-o', exe], check=True)
    restore = card / 'restore'
    subprocess.run(['cc','-DMUSIC_RESTORE','-no-pie','-pthread','-Wno-pointer-to-int-cast',
                    '-Wno-int-to-pointer-cast',here/'probe/native_loader_test.c','-o',restore],check=True)
    art = card / 'alternate'
    art.mkdir()
    (art / 'music_37.ppm').write_bytes(b'P6\n20 20\n255\n' + bytes([255, 0, 0])*400)
    for name, path in [('skytrack', here/'examples/skytrack'), ('red', art)]:
        sdtheme.install(fw, path, card, name)
        sdtheme.select(fw, card, name)
        resource = card/'themes'/name/'music.sty'
        assert subprocess.check_output([exe], cwd=card) == resource.read_bytes()[0x191cc:0x194ec]
    for choice in ('skytrack', 'stock', 'red'):
        expected = (card/'themes'/choice/'music.sty').read_bytes()[0x191cc:0x194ec] if choice!='stock' else b''
        assert subprocess.check_output([exe], cwd=card, env=dict(os.environ, PICK=choice)) == expected
        assert sdtheme.active(fw, card)[0] == choice
        assert subprocess.check_output([exe], cwd=card) == expected
    for name in ('testbolt','starlit'):
        shutil.copytree(card/'themes/red',card/'themes'/name)
        assert subprocess.check_output([exe],cwd=card,env=dict(os.environ,DISCOVER=name))==expected
        assert sdtheme.active(fw,card)[0]==name
        assert subprocess.check_output([restore],cwd=card)==expected
    (card/'themes/empty').mkdir()
    assert subprocess.run([exe],cwd=card,env=dict(os.environ,DISCOVER='empty')).returncode==4
    config = card/'themes/active.cfg'
    shared=card/'m30/settings.cfg'
    for raw in (b'red', b'red\n', b'red\r\n', b'\xef\xbb\xbfred\r\n', b'red\n'.ljust(512,b'\0')):
        shared.unlink(missing_ok=True)
        config.write_bytes(raw)
        assert sdtheme.active(fw,card)[0]=='red'
        assert subprocess.check_output([restore],cwd=card)==expected
        assert subprocess.check_output([exe],cwd=card)==expected
    shared.unlink()
    config.write_bytes(b'red\nstock')
    assert not subprocess.check_output([restore],cwd=card)
    sdtheme.select(fw,card,'red')
    assert shared.read_bytes()==b'theme=red\n'
    shared.write_bytes(b'\xef\xbb\xbfshuffle=off\r\n# keep me\r\ntheme=red\r\n')
    subprocess.check_call([exe],cwd=card,env=dict(os.environ,SHUFFLE='on'))
    assert b'shuffle=on' in shared.read_bytes() and b'theme=red' in shared.read_bytes()
    assert subprocess.check_output([restore],cwd=card,env=dict(os.environ,RESTORE_SHUFFLE='16'))==expected
    subprocess.check_call([exe],cwd=card,env=dict(os.environ,SHUFFLE='off'))
    assert subprocess.check_output([restore],cwd=card,env=dict(os.environ,RESTORE_SHUFFLE='0'))==expected
    subprocess.check_call([exe],cwd=card,env=dict(os.environ,SHUFFLE='on'))
    assert subprocess.check_output([exe], cwd=card,env=dict(os.environ,PICK='stock'))==b''
    assert b'shuffle=on' in shared.read_bytes() and b'# keep me' in shared.read_bytes()
    sdtheme.select(fw,card,'red')
    assert b'shuffle=on' in shared.read_bytes()
    pixels = bytearray(resource.read_bytes())
    pixels[-1] ^= 1
    resource.write_bytes(pixels)
    assert not subprocess.check_output([exe], cwd=card)
    sdtheme.select(fw, card, 'stock')
    assert not subprocess.check_output([exe], cwd=card)
    (card/'themes/active.cfg').unlink()
    assert not subprocess.check_output([exe], cwd=card)
    print('PASS: two SD icons, damaged pack, stock selection and missing config')

    sdtheme.install(fw,here/'examples/nightbay',card,'nightbay')
    sdtheme.select(fw,card,'nightbay')
    def draw(mode):
        return subprocess.check_output([restore],cwd=card,env=dict(os.environ,WALL_DRAW=mode))
    wall=draw('full')
    assert len(wall)==320*240*2
    assert len(set(zip(wall[::2],wall[1::2])))==4
    assert draw('row')==b''.join(wall[(y*320+5)*2:(y*320+315)*2] for y in range(126,155))
    style=(card/'themes/nightbay/wall.bin').read_bytes()
    assert draw('focus')==(style[8:10]*3+style[4:6]*307)*29
    assert subprocess.check_output([exe],cwd=card,env=dict(os.environ,DISCOVER='nightbay',WALL_DRAW='full'))==wall
    assert draw('playing')==wall
    def wallpaper_region(x,y,w,h):
        return b''.join(wall[2*(j*320+x):2*(j*320+x+w)] for j in range(y,y+h))
    assert draw('counter')==wallpaper_region(117,2,110,20)
    assert draw('header')==wallpaper_region(0,0,320,29)
    assert draw('album')==b''.join(wallpaper_region(x,111,1,20) for x in range(199,212))
    track_count=subprocess.check_output([restore],cwd=card,
        env=dict(os.environ,WALL_DRAW='track-count',BITMAP=str(fw/'music.sty')))
    assert track_count==wallpaper_region(45,181,70,12)
    common=fw/'common.sty'
    art=common.read_bytes()
    status=subprocess.check_output([restore],cwd=card,
        env=dict(os.environ,WALL_DRAW='status',BITMAP=str(common)))
    expected=bytearray()
    slots=[(0x2e640+1344*i,287,28) for i in range(6)]+[(0x1710,20,21)]
    for offset,x,width in slots:
        ground=wallpaper_region(x,0,width,24)
        for j in range(0,len(ground),2):
            pixel=art[offset+j:offset+j+2]
            expected.extend(ground[j:j+2] if pixel==struct.pack('<H',0x18c3) else pixel)
    assert status==expected
    print('PASS: counter, home header strip, scrolling metadata, volume and six battery frames')
    home=fw/'mainmenu.sty'
    dial=subprocess.check_output([restore],cwd=card,
        env=dict(os.environ,WALL_DRAW='home',BITMAP=str(home)))
    source=home.read_bytes()[0x740:0x216c0]
    backing=wall[29*320*2:]
    source_pixels=struct.unpack('<67520H',source)
    wallpaper_pixels=struct.unpack('<67520H',backing)
    dial_pixels=struct.unpack('<67520H',dial)
    accent=struct.unpack_from('<H',style,8)[0]
    recolored=0
    for original,background,new in zip(source_pixels,wallpaper_pixels,dial_pixels):
        r,g,b=original>>11,(original>>6)&31,original&31
        if original==0x18c3:
            assert new==background
        elif r>g+2 and r>b+2:
            base=min(g,b)
            level=r-base
            expected=((base+((accent>>11)*level+15)//31)<<11
                      | (base*2+(((accent>>5)&63)*level+15)//31)<<5
                      | (base+((accent&31)*level+15)//31))
            assert new==expected
            recolored+=1
        else:
            assert new==original
    assert recolored>100
    print('PASS: Now Playing clear bands and stock home dial pixel composition')
    (card/'themes/nightbay/wall.bin').unlink()
    assert draw('full')==b''
    print('PASS: selected wallpaper, full framebuffer, seven-row bitmap path, focus, and stock fallback')

    # First boot with neither file. Exercise create/write without depending on
    # seek or on the return value of close; then edit and read back the sector.
    shared.unlink()
    config.unlink(missing_ok=True)
    shared.parent.rmdir()
    edge=dict(os.environ,NO_SEEK='1',CLOSE_ZERO='1',SELECTED_CD='1')
    subprocess.check_output([restore],cwd=card,env=edge)
    assert shared.read_bytes().startswith(b'theme=stock\n') and shared.stat().st_size==512
    subprocess.check_call([exe],cwd=card,env=dict(edge,SHUFFLE='on'))
    assert b'shuffle=on\n' in shared.read_bytes()
    print('PASS: fresh-card creation and verified config writes')
