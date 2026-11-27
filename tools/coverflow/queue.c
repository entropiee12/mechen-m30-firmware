#include "flow.h"
#include "../m3u/shuffle_index.h"

/* Read-only view of the native shuffle state from tools/shuffle/shuffle.c. */
#define RAM(off) (0x9fc15f00u+(off))
struct state { u32 key[4],pos,n; u8 half,magic; };
#define ST ((volatile struct state *)RAM(0x88))
#define MAGIC 0xa5
static inline __attribute__((always_inline)) u32 mix(u32 x)
{
    x^=x>>16; x*=0x7feb352du; x^=x>>15; x*=0x846ca68bu; x^=x>>16;
    return x;
}

/* Never ask the engine to advance, seed the next shuffle epoch, or seek.
 * Unknown neighbours stay empty until the engine can predict them. */
void flow_tracks(u16 tracks[FLOW_SLOTS],u32 pos,u32 count,u32 shuffle,u32 loop,u32 key,u32 cursor)
{
    for (int i=0;i<FLOW_SLOTS;i++) tracks[i]=0;
    if (!pos || pos>count) return;
    tracks[FLOW_SLOTS/2]=pos;
    if (loop==1 || loop>=6) return;
    int wrap=loop==2 || loop==4;
    u32 h=1;
    while ((1u<<(2*h))<count) h++;
    u32 end=1u<<(2*h),mask=(1u<<h)-1;
    if (shuffle!=1) {
        for (int i=0;i<FLOW_SLOTS;i++) {
            int n=(int)pos+i-FLOW_SLOTS/2;
            if (wrap) {
                while (n<1) n+=count;
                while (n>(int)count) n-=count;
            }
            if (n>=1 && n<=(int)count) tracks[i]=n;
        }
        return;
    }
    if (!key || cursor>end) return;
    for (int dir=-1;dir<=1;dir+=2) {
        u32 p=cursor;
        for (int i=1;i<=FLOW_SLOTS/2;) {
            if (dir>0 && p==end) break;
            if (dir<0 && p<=1) {
                if (!wrap) break;
                p=end;
            } else p+=dir;
            u32 n=m3u_shuffle_index(p,h,mask,key)+1;
            if (n<=count) tracks[FLOW_SLOTS/2+dir*i++]=n;
        }
    }
}

static u32 native_index(u32 p,const volatile struct state *s)
{
    u32 h=s->half,mask=(1u<<h)-1,l=p>>h,r=p&mask;
    for (int k=0;k<4;k++) {
        u32 t=l^(mix(r^s->key[k])&mask); l=r; r=t;
    }
    return (l<<h)|r;
}

/* Find the current track within this epoch before predicting either edge.
 * The engine's next-domain cursor stays ahead when the user walks backward
 * through history; using it directly would skip the intervening covers. */
void flow_native_tracks(u16 tracks[FLOW_SLOTS],u32 pos,u32 count,u32 shuffle,u32 loop)
{
    if (shuffle!=1) { flow_tracks(tracks,pos,count,0,loop,0,0); return; }
    for (int i=0;i<FLOW_SLOTS;i++) tracks[i]=0;
    if (!pos || pos>count) return;
    tracks[FLOW_SLOTS/2]=pos;
    if (loop==1 || loop>=6) return;
    volatile struct state *s=ST;
    if (s->magic!=MAGIC || s->n!=count || !s->half || s->half>8) return;
    u32 end=1u<<(2*s->half),cursor=s->pos;
    if (cursor>end) return;
    while (cursor && native_index(cursor-1,s)+1!=pos) cursor--;
    if (!cursor) return;
    for (int direction=-1;direction<=1;direction+=2) {
        u32 p=direction<0?cursor-1:cursor;
        for (int i=1;i<=FLOW_SLOTS/2;) {
            if (direction<0 ? !p : p>=end) break;
            u32 n=native_index(direction<0?--p:p++,s);
            if (n<count) tracks[FLOW_SLOTS/2+direction*i++]=n+1;
        }
    }
}
