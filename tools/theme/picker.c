/* Native setting.mcg callbacks. Stock setting.ap uses this VFS context
 * and module entry in its MUSIC.LIB existence check (file offset 0x1464). */
#include "icon_cache.h"
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
#ifndef PICKER_FIXTURE
#ifdef MUSIC_RESTORE
#define FS ((u32 (*)(u32,u32,u32,u32,u32))0xbfc16bb5u)
#define CTX ctx
#else
#define FS ((u32 (*)(u32,u32,u32,u32,u32))0xbfc1658du)
#define CTX (*(volatile u32 *)0x9fc1550cu)
#endif
#endif
#ifndef PICKER_FIXTURE
#define preferences ((int (*)(u32,char*,int*,unsigned,const char*))PREF_ENTRY)
#endif
static u32 named(u32 ctx,const char *s, u32 op, u32 mode)
{
    u16 path[16], *p=path;
    *p++=0xfeff;
    while (*s) *p++=(u8)*s++;
    *p=0;
    if (!op) return FS(ctx,'/',(u32)path,0,0);
    return FS(ctx,(u32)path,mode,0,op);
}
static u32 filesize(u32 ctx,u32 h)
{
    u32 size=0;
    FS(ctx,(u32)&size,h,0,9);
    return size;
}
static int themes(u32 ctx)
{
    FS(ctx,':',0,0,0);
    return named(ctx,"themes",0,0);
}
/* Read the same manifest and style resource as the boot scanner, using
 * one sector of Settings stack space. Slot 37 spans three sectors. */
static int read_pack(u32 ctx,u8 *sector, struct icon_cache *next)
{
    static const char prefix[]="M30THEME 1\nmusic.sty 85c88fb159ae3dbbe19fb31282afb8f7831d541be9b8d931678e815320ea4ca1 ";
    u32 h=named(ctx,"theme.cfg",5,0), expected=0;
    if (!h) return 0;
    int ok=filesize(ctx,h)==sizeof(prefix)+8 && FS(ctx,(u32)sector,512,h,13);
    FS(ctx,h,0,0,7);
    if (!ok || sector[sizeof(prefix)+7]!='\n') return 0;
    for (u32 i=0;i<sizeof(prefix)-1;i++) if (sector[i]!=(u8)prefix[i]) return 0;
    for (u32 i=0;i<8;i++) {
        u8 digit=sector[sizeof(prefix)-1+i];
        if (digit>='0' && digit<='9') digit-='0';
        else if (digit>='a' && digit<='f') digit-='a'-10;
        else return 0;
        expected=expected*16+digit;
    }
    h=named(ctx,"music.sty",5,0);
    if (!h) return 0;
    ok=filesize(ctx,h)==330240;
    u32 crc=~0u, colors=0;
    for (u32 pos=0;ok && pos<330240;pos+=512) {
        if (!FS(ctx,(u32)sector,512,h,13)) { ok=0; break; }
        for (u32 i=0;i<512;i++) {
            crc^=sector[i];
            for (int bit=0;bit<8;bit++) crc=(crc>>1)^((0u-(crc&1)) & 0xedb88320u);
        }
        if (pos+512<=0x191cc || pos>=0x194ec) continue;
        for (u32 j=0;j<512;j+=2) {
            u32 offset=pos+j-0x191cc;
            if (offset>=800) continue;
            u16 color=sector[j] | sector[j+1]<<8;
            u32 k=0;
            while (k<colors && next->palette[k]!=color) ++k;
            if (k==colors) {
                if (colors==4) { ok=0; break; }
                next->palette[colors++]=color;
            }
            u32 pixel=offset/2, shift=2*(pixel%4);
            if (!shift) next->indices[pixel/4]=0;
            next->indices[pixel/4]|=k<<shift;
        }
    }
    FS(ctx,h,0,0,7);
    return ok && ~crc==expected;
}
#ifdef MUSIC_RESTORE
#include "theme_id.h"
static void restore_theme(u32 ctx)
{
    union { u32 words[128]; u8 data[512]; } sector;
    char id[9];
    struct icon_cache next;
    for (u32 i=0;i<4;i++) next.palette[i]=0;
    int shuffle;
    preferences(ctx,id,&shuffle,0,0);
    if (id[0] && !(id[0]=='s' && id[1]=='t' && id[2]=='o' && id[3]=='c' && id[4]=='k' && !id[5]) && themes(ctx) && named(ctx,id,0,0) && read_pack(ctx,sector.data,&next)) {
        for (u32 i=0;i<4;i++) ICON_CACHE->palette[i]=next.palette[i];
        for (u32 i=0;i<100;i++) ICON_CACHE->indices[i]=next.indices[i];
        ICON_CACHE->ready=1;
    }
    FS(ctx,':',0,0,0);
}
/* fsel_init invokes system operation 41 to create its filesystem mount.
 * The stock caller sign-extends the returned byte and tests it against -1. */
u32 music_mount(u32 a,u32 b,u32 c,u32 op)
{
    u32 result=((u32 (*)(u32,u32,u32,u32))0xbfc16bb1u)(a,b,c,op);
    if (op==41 && (signed char)result>=0 && ICON_CACHE->signature==ICON_SIGNATURE &&
        !ICON_CACHE->ready) restore_theme((u8)result);
    return result;
}
#else
int choose(const char *id)
{
    union { u32 words[128]; u8 data[512]; } sector;
    struct icon_cache next;
    for (u32 i=0;i<4;i++) next.palette[i]=0;
    int themed=!(id[0]=='s' && id[1]=='t' && id[2]=='o' && id[3]=='c' && id[4]=='k' && !id[5]);
    int result=0x8203; /* stay in this menu on I/O error */
    if (ICON_CACHE->signature!=ICON_SIGNATURE || CTX==~0u) goto done;
    if (themed) {
        if (!themes(CTX) || !named(CTX,id,0,0)) goto done;
        if (!read_pack(CTX,sector.data,&next)) goto done;
        if (!themes(CTX)) goto done;
    }
    char previous[9]; int shuffle;
    /* Only RAM pointers can be dereferenced after the window changes banks. */
    char choice[9];
    u32 n=0;
    while (n<8 && id[n]) { choice[n]=id[n]; n++; }
    choice[n]=0;
    if (preferences(CTX,previous,&shuffle,1,choice)) {
        ICON_CACHE->ready=0;
        if (themed) {
            for (u32 i=0;i<4;i++) ICON_CACHE->palette[i]=next.palette[i];
            for (u32 i=0;i<100;i++) ICON_CACHE->indices[i]=next.indices[i];
            ICON_CACHE->ready=1;
        }
        result=0x8201;
    }
done:
    if (CTX!=~0u) FS(CTX,':',0,0,0);
    return result;
}
#ifdef FLOW_PREF
#include "../m3u/m3uplay.h"
static int flow_set(int enabled)
{
    char theme[9], value[4];
    int shuffle;
    /* The preferences call can replace this overlay's string literals. */
    value[0]='o';
    value[1]=enabled?'n':'f';
    value[2]=enabled?0:'f';
    value[3]=0;
    int saved=CTX!=~0u && preferences(CTX,theme,&shuffle,3,value);
    VS->flow_override=!saved;
    VS->flow_disabled=!enabled;
    VS->native_visible=0;
    return saved?0x8201:0x8203;
}
int flow_menu(u16 *title) {title[1]=title[6]=0xffff; title[3]=VS->flow_disabled?226:225; ((u32 *)title)[2]=0; return 0x8202;}
int flow_on(void) {return flow_set(1);}
int flow_off(void) {return flow_set(0);}
#endif
int theme_stock(void) { return choose("stock"); }
#endif
