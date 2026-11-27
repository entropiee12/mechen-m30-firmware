#include "icon_cache.h"
#include "wallpaper.h"
typedef unsigned int u32;
typedef unsigned short u16;
#ifndef PICKER_FIXTURE
#define FS ((u32 (*)(u32,u32,u32,u32,u32))PREF_FS)
#endif
static u32 wall_op(u32 ctx,const char *name,u32 op,u32 mode)
{
    u16 path[16], *p=path;
    *p++=0xfeff;
    while (*name) *p++=(unsigned char)*name++;
    *p=0;
    if (op) return FS(ctx,(u32)path,mode,0,op);
    return FS(ctx,'/',(u32)path,0,0);
}
/* Some card drivers only support entering the selected directory entry.
 * Try the named form first and fall back to the stock directory walk. */
int settings_folder(u32 ctx)
{
    if (!FS(ctx,':',0,0,0)) return 0;
    if (wall_op(ctx,"m30",0,0)) return 1;
    unsigned cmd=0x82;
    while (FS(ctx,cmd,0,1,1)) {
        u16 name[8]={0};
        if (FS(ctx,(u32)name,8,0,2) && name[0]==0xfeff &&
            (name[1]|32)=='m' && name[2]=='3' && name[3]=='0' && !name[4])
            return FS(ctx,'/',0,0,0)!=0;
        cmd=0x80;
    }
    return 0;
}

void load_wall(u32 ctx,const char *id)
{
    if (ICON_CACHE->signature!=ICON_SIGNATURE) return;
    WALL->magic=0;
    if (!*id || (id[0]=='s' && id[1]=='t' && id[2]=='o' && id[3]=='c' && id[4]=='k' && !id[5])) return;
    if (!FS(ctx,':',0,0,0) || !wall_op(ctx,"themes",0,0) || !wall_op(ctx,id,0,0)) return;
    u32 h=wall_op(ctx,"wall.bin",5,0);
    if (!h) return;
    u32 size=0;
    union { u32 sector[128]; struct wallpaper wall; } buf;
    FS(ctx,(u32)&size,h,0,9);
    int ok=size==sizeof(buf.wall) && FS(ctx,(u32)buf.sector,512,h,13);
    FS(ctx,h,0,0,7);
    if (!ok || buf.wall.magic!=WALL_MAGIC) return;
    WALL->sky=buf.wall.sky;
    WALL->distant=buf.wall.distant;
    WALL->mountain=buf.wall.mountain;
    WALL->accent=buf.wall.accent;
    for (unsigned i=0;i<6;i++) WALL->ridge[i]=buf.wall.ridge[i];
    WALL->magic=WALL_MAGIC;
}

/* Freshly created files are already at offset zero. Use the create/write/
 * close sequence from the record and bookmark applications. Reopen with a
 * new handle and verify the actual file contents before reporting success. */
int save(u32 ctx,unsigned char *buf,unsigned size,int existing)
{
    const char *filename="settings.cfg";
    if (!settings_folder(ctx)) {
        if (!FS(ctx,':',0,0,0)) return 0;
        wall_op(ctx,"m30",4,0);
        if (!settings_folder(ctx)) return 0;
    }
    u32 h=wall_op(ctx,filename,existing?5:6,existing?2:512);
    if (!h) return 0;
    if (existing) FS(ctx,0,0,h,11);
    for (unsigned i=size;i<512;i++) buf[i]=' ';
    buf[511]='\n';
    u32 hash=0;
    for (unsigned i=0;i<512;i++) hash=hash*31+buf[i];
    int wrote=FS(ctx,(u32)buf,512,h,14);
    FS(ctx,h,0,0,7);
    if (!wrote) return 0;
    h=wall_op(ctx,filename,5,0);
    if (!h) return 0;
    u32 bytes=0,check=0;
    FS(ctx,(u32)&bytes,h,0,9);
    int ok=bytes==512 && FS(ctx,(u32)buf,512,h,13);
    FS(ctx,h,0,0,7);
    if (!ok) return 0;
    for (unsigned i=0;i<512;i++) check=check*31+buf[i];
    return check==hash;
}

#ifdef PREF_INDEX_VM
#include "settings_line.h"
#include "../community/index_mode.h"

/* Config loads this before the stock automatic-index checks use the VM slot. */
void sync_index(const unsigned char *buf,unsigned size)
{
    unsigned start,end;
    if (setting_line(buf,size,"index_mode",&start,&end)!=1) return;
    const unsigned char *v=buf+start;
    unsigned len=end-start;
    u32 mode;
    if (len==4 && v[0]=='a' && v[1]=='u' && v[2]=='t' && v[3]=='o')
        mode=INDEX_AUTO;
    else if (len==6 && v[0]=='m' && v[1]=='a' && v[2]=='n' &&
             v[3]=='u' && v[4]=='a' && v[5]=='l')
        mode=INDEX_MANUAL;
    else return;
    u32 current=0;
    int (*vm)(u32 *,u32,u32,u32)=(void *)PREF_INDEX_VM;
    vm(&current,INDEX_VM_SLOT,sizeof(current),18);
    if (current!=mode) vm(&mode,INDEX_VM_SLOT,sizeof(mode),19);
}
#endif
