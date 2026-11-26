/* One-sector SD settings store. The caller supplies its application's VFS
 * context and restores the root before returning it to stock code. */
#include "theme_id.h"
#ifdef FLOW_PREF
#include "../m3u/m3uplay.h"
#endif
#include "settings_line.h"
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
#ifndef PREF_FIXTURE
#define FS ((u32 (*)(u32,u32,u32,u32,u32))PREF_FS)
#define SYS ((u32 (*)(u32,u32,u32,u32))PREF_SYS)
#define load_wall ((void (*)(u32,const char *))WALL_ENTRY)
#define STOCK_APPLY ((u32 (*)(u8 *))0x48b54691u)
#define VM ((int (*)(void*,u32,u32,u32))PREF_SYS)
#endif
static u32 path_op(u32 ctx,const char *name,u32 op,u32 mode)
{
    u16 path[16], *p=path;
    *p++=0xfeff;
    while (*name) *p++=(u8)*name++;
    *p=0;
    if (!op) return FS(ctx,'/',(u32)path,0,0);
    return FS(ctx,(u32)path,mode,0,op);
}
static int folder(u32 ctx,const char *name)
{
    FS(ctx,':',0,0,0);
    return path_op(ctx,name,0,0);
}
static int read_file(u32 ctx,const char *name,u8 *buf)
{
    u32 h=path_op(ctx,name,5,0),size=0;
    if (!h) return -1;
    size=~0u;
    FS(ctx,(u32)&size,h,0,9);
    int ok=size<=512 &&
           (!size || FS(ctx,(u32)buf,512,h,13));
    FS(ctx,h,0,0,7);
    return ok?(int)size:-2;
}
static int rewrite(u8 *buf,unsigned size,const char *key,const char *value)
{
    /* Trim the blank padding left by previous one-sector firmware writes. */
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
#ifndef PREF_FIXTURE
#define save ((int (*)(u32,u8*,unsigned,int))SAVE_ENTRY)
#endif
/* Reads both preferences and optionally edits one. A negative shuffle
 * result leaves the native setting in control. */
int preferences(u32 ctx,char id[9],int *shuffle,unsigned field,const char *value)
{
    union { u32 words[128]; u8 data[512]; } block;
    u8 *buf=block.data;
    int size=-1, ok=0;
    *shuffle=-1; id[0]=0;
    if (folder(ctx,"m30")) size=read_file(ctx,"settings.cfg",buf);
    int existing=size>=0;
    if (size==-2) goto done;
    if (!existing) {
        int legacy=folder(ctx,"themes")?read_file(ctx,"active.cfg",buf):-1;

        size=0;
        if (legacy>0 && parse_theme_id(buf,legacy,id)) size=rewrite(buf,0,"theme",id);
        else { id[0]=0; size=rewrite(buf,0,"theme","stock"); }
    } else {
#ifdef IMPORT_NATIVE
        if (!field && value) ((void (*)(u8 *,const u8 *,unsigned))IMPORT_NATIVE)((u8 *)value,buf,size);
#endif
#ifdef INDEX_SYNC
        ((void (*)(const u8 *,unsigned))INDEX_SYNC)(buf,size);
#endif
        unsigned a,b;
#ifdef FLOW_PREF
        if (setting_line(buf,size,"coverflow",&a,&b)==1)
            VS->flow_disabled=b-a==3 && buf[a]=='o' && buf[a+1]=='f' && buf[a+2]=='f';
#endif
        if (setting_line(buf,size,"theme",&a,&b)==1 && !parse_theme_id(buf+a,b-a,id)) id[0]=0;
        if (setting_line(buf,size,"shuffle",&a,&b)==1 && b-a>=2 && buf[a]=='o') {
            if (b-a==2 && buf[a+1]=='n') *shuffle=16;
            if (b-a==3 && buf[a+1]=='f' && buf[a+2]=='f') *shuffle=0;
        }
    }
    if (field) {
        const char *key=field==1?"theme":field==2?"shuffle":field==3?"coverflow":"index_mode";
        size=rewrite(buf,size,key,value);
    }
    if (size<0) goto done;
    ok=(!field && (existing || !size)) || save(ctx,buf,size,existing);
done:
#ifdef FLOW_PREF
    if (field==3 && ok) {VS->flow_disabled=value[1]=='f'; VS->native_visible=0;}
#endif
    if (!field || (field==1 && ok)) load_wall(ctx,field?value:id);
    FS(ctx,':',0,0,0);
    return ok;
}
/* Stock calls com_set_sys_comval after it has validated or initialized
 * the 56-byte common record. Sync only its shuffle byte before forwarding. */
u32 apply_preferences(u8 *record)
{
    /* Stock file-selector lifecycle for disk H, driver group 1. */
    if (SYS(1,0,(u32)"card.drv",10)!=0) return STOCK_APPLY(record);
    int ctx=(signed char)SYS(1,16,0,41);
    if (ctx>=0) {
        char id[9]; int shuffle;
        preferences(ctx,id,&shuffle,0,(const char *)record);
        SYS(ctx,0,0,42);
        if (shuffle>=0 && record[38]!=shuffle) {
            record[38]=shuffle;
            VM(record,0,56,19);
        }
    }
    SYS(1,0,0,11);
    return STOCK_APPLY(record);
}
#ifdef PREF_SETTING
static int set_shuffle(void *param,int on)
{
    u32 ctx=*(volatile u32 *)0x9fc1550c;
    char id[9]; int shuffle;
    int result=((int (*)(void *))(on?0x40155201u:0x401551c9u))(param);
    if (ctx!=~0u && !preferences(ctx,id,&shuffle,2,on?"on":"off")) return 0x8203;
    return result;
}
int shuffle_on(void *param) { return set_shuffle(param,1); }
int shuffle_off(void *param) { return set_shuffle(param,0); }
#endif
