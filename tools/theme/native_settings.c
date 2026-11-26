/* Native Settings writes this 56-byte VM record on exit. Config applies it
 * before starting the UI. Only translate fields owned by known menu rows. */
#include "settings_line.h"
typedef unsigned char byte;
typedef unsigned int word;
struct field { char key[18]; byte offset, maximum; };
static const struct field fields[]={
    {"brightness",2,11},
};

static int allowed(const struct field *field,unsigned value)
{
    return value<=field->maximum;
}

static int number(const byte *buf,unsigned start,unsigned end)
{
    unsigned value=0;
    if (end<=start || end-start>3) return -1;
    while (start<end) {
        unsigned digit=buf[start++]-'0';
        if (digit>9) return -1;
        value=value*10+digit;
    }
    return value;
}

#ifndef NATIVE_EXPORT
void import_native(byte *record,const byte *buf,unsigned size)
{
    int changed=0;
    for (unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++) {
        const struct field *f=fields+i;
        unsigned start,end;
        if (setting_line(buf,size,f->key,&start,&end)!=1) continue;
        int value=number(buf,start,end);
        if (allowed(f,value) && record[f->offset]!=value) {
            record[f->offset]=value;
            changed=1;
        }
    }
    if (changed) ((int (*)(byte *,word,word,word))0xbfc16585u)(record,0,56,19);
}
#else
#define SYS ((int (*)(word,word,word,word))0xbfc16585u)
#define FOLDER ((int (*)(word,const char *))FOLDER_ENTRY)
#define READ ((int (*)(word,const char *,byte *))READ_ENTRY)
#define REWRITE ((int (*)(byte *,unsigned,const char *,const char *))REWRITE_ENTRY)
#define SAVE ((int (*)(word,byte *,unsigned,int))SAVE_ENTRY)
static void export_native(word ctx,const byte *record)
{
    union { word alignment[128]; byte data[512]; } block;
    byte *buf=block.data;
    int size=FOLDER(ctx,"m30")?READ(ctx,"settings.cfg",buf):-1;
    if (size < -1) return;
    int exists=size>=0,changed=0;
    if (!exists) size=0;
    for (unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++) {
        const struct field *f=fields+i;
        unsigned start,end,value=record[f->offset];
        if (!allowed(f,value)) continue;
        int found=setting_line(buf,size,f->key,&start,&end);
        if (found<0 || (found && number(buf,start,end)==(int)value)) continue;
        char digits[4],*p=digits+3;
        *p=0;
        do {
            unsigned tens=0;
            while (value>=10) {value-=10; tens++;}
            *--p='0'+value;
            value=tens;
        } while (value);
        size=REWRITE(buf,size,f->key,p);
        if (size<0) return;
        changed=1;
    }
    if (changed) SAVE(ctx,buf,size,exists);
}

int save_native(byte *record,word slot,word length,word op)
{
    int result=SYS((word)record,slot,length,op);
    if (SYS(1,0,(word)"card.drv",10)==0) {
        int ctx=(signed char)SYS(1,16,0,41);
        if (ctx>=0) {
            export_native(ctx,record);
            SYS(ctx,0,0,42);
        }
        SYS(1,0,0,11);
    }
    return result;
}
#endif
