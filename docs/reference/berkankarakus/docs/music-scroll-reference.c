/*
 * Readable reference model for the verified music.ap scroll path.
 *
 * This is not replacement firmware and is intentionally not compiled into a
 * HEX. It gives names to fields observed in the MIPS16 execution probe while
 * leaving unknown/pointer fields explicit.
 *
 * Verified entry points:
 *   0x40d54c00  advance active text position
 *   0x40d54cc0  retreat active text position
 *   0x40d54d80  consume one key/event
 *   0x40d54f2c  enter the now-playing renderer/event loop
 */

#include <stdint.h>

typedef struct M30ScrollEntry M30ScrollEntry;

/* Size 0x18 is verified from indexed accesses in music.ap. */
struct M30ScrollEntry {
    uint16_t style_or_resource;       /* +0x00; probe sees 0x2005 */
    int16_t  wrap_start;              /* +0x02; exact meaning unresolved */
    int16_t  right_limit;             /* +0x04 */
    int16_t  pixel_step;              /* +0x06 */
    int16_t  pixel_position;          /* +0x08 */
    uint8_t  wrap_enabled;            /* +0x0a */
    uint8_t  reserved_0b[5];          /* +0x0b..0x0f */
    int8_t (*on_state)(void *entries, uint8_t active, uint8_t direction);
                                        /* +0x10; only called when non-null */
    void (*on_position)(int16_t position); /* +0x14 */
};

typedef struct {
    uint8_t unknown_00[6];             /* +0x00..0x05 */
    uint8_t slot_count;                /* +0x06 */
    uint8_t active_slot;               /* +0x07 */
    uint8_t previous_slot;             /* +0x08 */
    uint8_t unknown_09[3];             /* +0x09..0x0b */
    M30ScrollEntry *entries;           /* +0x0c */
} M30ScrollState;

typedef struct {
    M30ScrollState *state;             /* word 0 */
    uint8_t status;                    /* byte 4; 3 initial, 2 changed, 1 redraw */
    uint8_t unknown_05[3];
} M30ScrollContext;

enum {
    M30_EVENT_NEXT = 0x2901,
    M30_EVENT_PREVIOUS = 0x2902,
    M30_EVENT_REDRAW = 0x2903,
    M30_EVENT_EXIT = 0x2905,
    M30_EVENT_CANCEL = 0x2906,
};

void select_next(M30ScrollState *state)
{
    if (state->slot_count > 1) {
        state->previous_slot = state->active_slot;
        state->active_slot = (uint8_t)((state->active_slot + 1) % state->slot_count);
    }
}

void select_previous(M30ScrollState *state)
{
    if (state->slot_count > 1) {
        state->previous_slot = state->active_slot;
        state->active_slot = state->active_slot == 0
            ? (uint8_t)(state->slot_count - 1)
            : (uint8_t)(state->active_slot - 1);
    }
}

/* Reconstructed from FUN_40d54c00 at 0x40d54c00. */
void advance_text(M30ScrollContext *context)
{
    M30ScrollEntry *entry = &context->state->entries[context->state->active_slot];
    if (entry->pixel_position < entry->right_limit) {
        entry->pixel_position += entry->pixel_step;
        if (entry->on_position != 0) entry->on_position(entry->pixel_position);
        context->status = 1;
    } else if (entry->wrap_enabled == 1) {
        entry->pixel_position = entry->wrap_start;
        if (entry->on_position != 0) entry->on_position(entry->pixel_position);
        context->status = 1;
    }
    if (context->status == 1 && entry->on_state != 0 &&
        entry->on_state(context->state->entries, context->state->active_slot, 1) == 2) {
        context->status = 3;
    }
}

/* Reconstructed from FUN_40d54cc0 at 0x40d54cc0. */
void retreat_text(M30ScrollContext *context)
{
    M30ScrollEntry *entry = &context->state->entries[context->state->active_slot];
    if (entry->wrap_start < entry->pixel_position) {
        entry->pixel_position -= entry->pixel_step;
        if (entry->on_position != 0) entry->on_position(entry->pixel_position);
        context->status = 1;
    } else if (entry->wrap_enabled == 1) {
        entry->pixel_position = entry->right_limit;
        if (entry->on_position != 0) entry->on_position(entry->pixel_position);
        context->status = 1;
    }
    if (context->status == 1 && entry->on_state != 0 &&
        entry->on_state(context->state->entries, context->state->active_slot, 0) == 2) {
        context->status = 3;
    }
}

/* Reconstructed event switch from FUN_40d54d80 at 0x40d54d80. */
int16_t handle_event(M30ScrollContext *context, uint16_t event)
{
    switch (event) {
    case M30_EVENT_NEXT:
        select_next(context->state);
        context->status = 2;
        return 0;
    case M30_EVENT_PREVIOUS:
        select_previous(context->state);
        context->status = 2;
        return 0;
    case M30_EVENT_REDRAW:
        advance_text(context);
        return 0;
    case M30_EVENT_EXIT:
        return (int16_t)-0x7fef;
    case M30_EVENT_CANCEL:
        return (int16_t)-0x7fec;
    default:
        return 0; /* fallback dispatcher remains unresolved */
    }
}
