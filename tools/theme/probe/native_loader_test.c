/* 32-bit host fixture for the firmware loader's card and display callbacks. */
#define _GNU_SOURCE
#include <stdint.h>
#include <pthread.h>
#include <stdio.h>
#include <dirent.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
static struct { u32 ctx; } G;
static u8 ARENA[4096];
static u16 NAMEBUF[64];
static FILE *file;
static char root[4096];
static void name(const char *s)
{
    NAMEBUF[0] = 0xfeff;
    size_t i;
    for (i=0;s[i];++i) NAMEBUF[i+1]=(u8)s[i];
    NAMEBUF[i+1]=0;
}
static u16 rd16(const u8 *p) { return p[0] | p[1]<<8; }
static u32 FS_OP(u32 ctx,u32 a,u32 b,u32 handle,u32 op)
{
    (void)ctx;
    char path[64];
    if (op==5 || op==6 || op==4 || (op==0 && a=='/' && b)) {
        const u16 *s=(const u16 *)(op?a:b);
        if (*s++!=0xfeff) abort();
        size_t i;
        for(i=0;s[i] && i<63;i++) path[i]=(char)s[i];
        path[i]=0;
    }
    static DIR *dir;
    static struct dirent *entry;
    switch(op) {
    case 1:
        if (b!=0 || handle!=1) abort();
        if (a==0x82) { if(dir) closedir(dir); dir=opendir("."); }
        else if (a!=0x80) abort();
        while (dir && (entry=readdir(dir))) if (entry->d_type==DT_DIR) return 1;
        if (dir) closedir(dir);
        dir=NULL;
        return 0;
    case 2: {
        u16 *out=(u16 *)a;
        unsigned i=0;
        out[0]=0xfeff;
        while (i+2<b && entry->d_name[i]) {out[i+1]=(u8)entry->d_name[i];i++;}
        out[i+1]=0;
        return i+1;
    }
    case 0:
        if (a==':' ) return chdir(root)==0;
        if (!b) return entry && chdir(entry->d_name)==0;
        if (getenv("SELECTED_CD") && strcmp(path,"m30")==0) return 0;
        return chdir(path)==0;
    case 4: return mkdir(path,0777)==0;
    case 6: if(file) abort(); file=fopen(path,"wbx"); return file!=NULL;
    case 5: if(file) abort(); file=fopen(path,b==2?"r+b":"rb"); return file!=NULL;
    case 7: if(!file || a!=1) abort(); fclose(file); file=NULL; return getenv("CLOSE_ZERO")?0:1;
    case 9: if(!file || b!=1) abort(); fseek(file,0,SEEK_END);
            *(u32 *)a=ftell(file); rewind(file); return 1;
    case 11: if (getenv("NO_SEEK")) return 0; return fseek(file,a,b)==0;
    case 14: return fwrite((void *)a,1,b,file)==b;
    case 13: if (!file || handle!=1 || b%512) abort();
             return fread((void *)a,1,b,file)>0;
    default: abort();
    }
}
#include "../scan_icon.h"
#define PICKER_FIXTURE
#define CTX G.ctx
#define FS FS_OP
static unsigned installs,mounts,writes;
static u32 pref_sys(u32 group,u32 disk,u32 arg,u32 op)
{
    switch (op) {
    case 10: if (group!=1 || strcmp((char *)arg,"card.drv")) abort(); installs++; return 0;
    case 41: if (!installs || disk!=16) abort(); mounts++; return 0;
    case 42: if (!mounts--) abort(); return 0;
    case 11: if (!installs--) abort(); return 0;
    default: abort();
    }
}
static int pref_vm(void *record,u32 slot,u32 size,u32 op)
{
    if (!record || slot || size!=56 || op!=19) abort();
    writes++;
    return 0;
}
#define SYS pref_sys
#define VM pref_vm
#define STOCK_APPLY(record) (123u)
#define PREF_FIXTURE
#include "../wall_load.c"
#include "../preferences.c"
#include "../picker.c"
#ifndef MUSIC_RESTORE
#include "../discover.c"
#endif
#define STOCK_TRANSFER(fd,offset,area) 77
#include "../show_icon.c"
static FILE *bitmap;
static int bitmap_io(u32 fd,u32 ptr,u32 size,u32 op)
{
    if (!bitmap || (fd!=7 && fd!=8)) abort();
    if (op==15) return fseek(bitmap,size,SEEK_SET)?-1:0;
    if (op==17) return fread((void *)ptr,1,size,bitmap)==size?(int)size:-1;
    abort();
}
#define RESOURCE bitmap_io
#define STOCK_CLEAR(area,a,b) 73
#define WALL_DRAW_FIXTURE
#include "../wall_draw.c"
static int stock_text_calls;
#define STOCK_TEXT(p,s,m) ((void)++stock_text_calls)
#include "../text_backdrop.c"
static u16 display[64];
static int region(struct region *r,u32 mode)
{
    if (getenv("WALL_DRAW")) return !mode;
    if (mode || r->width!=20 || r->height!=20 || r->x!=160 || r->y!=40) abort();
    return 1;
}
static int submit(u32 n,u32 width)
{
    return (width==20 || getenv("WALL_DRAW")) && n<=64 && fwrite(display,2,n,stdout)==n;
}
static int probe(void)
{
    u32 pages[]={0xbfc09000,0x9fc0b000};
    for (size_t i=0;i<2;i++)
        if (mmap((void *)pages[i],4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0)==MAP_FAILED) return 2;
    ICON_CACHE->signature=ICON_SIGNATURE;
    ICON_CACHE->ready=1; /* failure must clear an earlier successful selection */
    if (!getcwd(root,sizeof(root))) abort();
#ifdef MUSIC_RESTORE
    ICON_CACHE->ready=0;
    if (getenv("RESTORE_SHUFFLE")) {
        unsigned char record[56]; memset(record,0xa5,sizeof(record));
        record[38]=0;
        if (apply_preferences(record)!=123 || installs || mounts) abort();
        int want=atoi(getenv("RESTORE_SHUFFLE"));
        if (record[38]!=want || writes!=(want!=0)) abort();
        for (unsigned i=0;i<56;i++) if (i!=38 && record[i]!=0xa5) abort();
    }
    restore_theme(G.ctx);
    char after[4096];
    if (!getcwd(after,sizeof(after)) || strcmp(after,root)) abort();
#else
    const char *choice=getenv("PICK");
    const char *discover=getenv("DISCOVER");
    const char *shuffle=getenv("SHUFFLE");
    if (shuffle) {
        char id[9]; int previous;
        return preferences(G.ctx,id,&previous,2,shuffle)?0:3;
    }
    if (discover) {
        u16 title[10]={0};
        if(theme_menu(title)!=0x8202 || title[6]!=166) abort();
        char first[9];
        copy_id(first,STATE->id);
        for (;;) {
            const char *label=(const char *)(uintptr_t)((u32 *)title)[2];
            if (strcmp(label,STATE->id[0]?STATE->id:"No SD themes")) abort();
            if (!strcmp(STATE->id,discover)) break;
            theme_next();
            if (!strcmp(first,STATE->id)) return 4;
        }
        if (theme_apply()!=0x8201) return 3;
    } else if (choice) {
        int result=choose(choice);
        if (result!=0x8201) return 3;
    } else load_sd_icon();
#endif
    if(file) abort();
    if (!ICON_CACHE->ready) return 0;
    *(u32 *)0x9fc0bff8=0x50690;
    *(u32 *)0x9fc0bf50=(uintptr_t)display;
    *(u16 *)0x9fc0bf54=sizeof(display);
    *(u32 *)0x9fc0bf98=(uintptr_t)region;
    *(u32 *)0x9fc0bf84=(uintptr_t)submit;
    const char *draw=getenv("WALL_DRAW");
    if (draw) {
        *(u32 *)0x9fc0bfd4=7;
        struct region area={0,0,320,240};
        u32 offset=0x8e40;
        if (!strcmp(draw,"row") || !strcmp(draw,"focus")) {
            area=(struct region){5,126,310,29};
            offset=!strcmp(draw,"row")?0xadda4:0x47090;
        }
        if (!strcmp(draw,"playing")) {
            const struct region bands[]={{0,0,320,28},{0,28,320,151},{0,179,320,61}};
            for (unsigned i=0;i<3;i++) if (clear_wall(&bands[i],0,0)!=1) abort();
            return 0;
        }
        if (!strcmp(draw,"header")) {
            *(u32 *)0x9fc0bff8=0x1c4330;
            *(u32 *)0x9fc0bfe8=8;
            area=(struct region){0,0,320,24};
            return draw_wall(8,0x10cfc0,&area)!=1;
        }
        if (!strcmp(draw,"counter")) {
            *(u32 *)0x9fc0bff8=0;
            area=(struct region){117,2,110,20};
            return draw_wall(7,0x3e5c8,&area)!=1;
        }
        if (!strcmp(draw,"track-count")) {
            *(u32 *)0x9fc0bfe8=8;
            bitmap=fopen(getenv("BITMAP"),"rb");
            if (!bitmap) abort();
            area=(struct region){45,181,70,12};
            int result=draw_wall(8,0x194ec,&area);
            fclose(bitmap);
            return result!=1;
        }
        if (!strcmp(draw,"status")) {
            *(u32 *)0x9fc0bff8=0;
            bitmap=fopen(getenv("BITMAP"),"rb");
            if (!bitmap) abort();
            area=(struct region){287,0,28,24};
            for (unsigned i=0;i<6;i++)
                if (draw_wall(7,0x2e640+i*1344,&area)!=1) abort();
            area=(struct region){20,0,21,24};
            if (draw_wall(7,0x1710,&area)!=1) abort();
            fclose(bitmap);
            return 0;
        }
        if (!strcmp(draw,"album")) {
            struct text_backdrop *bg=(void *)0x9fc0be4c;
            *bg=(struct text_backdrop){10,190,111,129,20};
            struct text_region slice={9,0,13,20};
            u16 column[13*20];
            text_backdrop(column,&slice,0);
            if (stock_text_calls!=1) abort();
            text_backdrop(column,&slice,2);
            if (stock_text_calls!=1) abort();
            return fwrite(column,sizeof(column),1,stdout)!=1;
        }
        if (!strcmp(draw,"home")) {
            *(u32 *)0x9fc0bfe8=8;
            *(u32 *)0x9fc0bff8=0x1c4330;
            bitmap=fopen(getenv("BITMAP"),"rb");
            if (!bitmap) abort();
            area=(struct region){0,29,320,211};
            int result=draw_wall(8,0x740,&area);
            fclose(bitmap);
            return result!=1;
        }
        if (draw_wall(8,offset,&area)!=77) abort();
        if (WALL->magic!=WALL_MAGIC) return draw_wall(7,offset,&area)!=77;
        return draw_wall(7,offset,&area)!=1;
    }
    return show_icon(37,160,40)!=1;
}

static void *worker(void *unused)
{
    (void)unused;
    return (void *)(intptr_t)probe();
}

int main(void)
{
    /* The firmware API carries pointers in u32 arguments. Keep the host
     * test's stack and the non-PIE executable's globals below 4 GiB. */
    size_t size=1<<20;
    void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_32BIT,-1,0);
    pthread_attr_t attr;
    pthread_t thread;
    void *result;
    if(stack==MAP_FAILED || pthread_attr_init(&attr) || pthread_attr_setstack(&attr,stack,size) ||
       pthread_create(&thread,&attr,worker,NULL) || pthread_join(thread,&result)) return 2;
    return (int)(intptr_t)result;
}
