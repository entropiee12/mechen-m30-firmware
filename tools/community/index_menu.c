#include "index_mode.h"
typedef unsigned int u32;
typedef unsigned short u16;
#define VM ((int (*)(u32*,u32,u32,u32))0xbfc16585u)
static u32 mode(void)
{
    u32 record=0;
    VM(&record,INDEX_VM_SLOT,sizeof(record),18);
    return record;
}
int index_menu(u16 *title)
{
    title[1]=title[6]=0xffff;
    title[3]=mode()==INDEX_AUTO?223:222;
    ((u32 *)title)[2]=0;
    return 0x8202;
}
static int set_mode(u32 value)
{
    u32 record=value;
    VM(&record,INDEX_VM_SLOT,sizeof(record),19);
    if (mode()!=value) return 0x8203;
#ifdef PREF_INDEX_ENTRY
    u32 ctx=*(volatile u32 *)0x9fc1550cu;
    if (ctx!=~0u) {
        char id[9], setting[7];
        int shuffle;
        const char *name=value==INDEX_AUTO?"auto":"manual";
        for (unsigned i=0;;i++) {
            setting[i]=name[i];
            if (!setting[i]) break;
        }
        int (*prefs)(u32,char *,int *,unsigned,const char *)=(void *)PREF_INDEX_ENTRY;
        if (!prefs(ctx,id,&shuffle,4,setting)) return 0x8203;
    }
#endif
    return 0x8201;
}
int index_manual(void) { return set_mode(INDEX_MANUAL); }
int index_auto(void) { return set_mode(INDEX_AUTO); }
