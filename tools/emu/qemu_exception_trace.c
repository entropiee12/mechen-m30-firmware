/* Passive QEMU TCG observer for synchronous exception transitions.
 *
 * It deliberately does not read or modify guest state.  The guest-side
 * probe remains the authority for CP0 Cause/EPC/Status; this plugin is an
 * independent observation of QEMU's exception discontinuity (from_pc ->
 * to_pc) and is useful for detecting a mistaken exception-vector assumption.
 */
#include <inttypes.h>
#include <stdio.h>

#include <qemu-plugin.h>

QEMU_PLUGIN_EXPORT int qemu_plugin_version = QEMU_PLUGIN_VERSION;

static void on_discontinuity(unsigned int vcpu_index,
                             enum qemu_plugin_discon_type type,
                             uint64_t from_pc,
                             uint64_t to_pc,
                             void *userdata)
{
    (void)userdata;
    if ((type & QEMU_PLUGIN_DISCON_EXCEPTION) == 0) {
        return;
    }
    fprintf(stdout,
            "TCG EXCEPTION vcpu=%u from=0x%08" PRIx64
            " to=0x%08" PRIx64 "\n",
            vcpu_index, from_pc, to_pc);
    fflush(stdout);
}

QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id,
                                            const qemu_info_t *info,
                                            int argc,
                                            char **argv)
{
    (void)info;
    (void)argc;
    (void)argv;
    qemu_plugin_register_vcpu_discon_cb(
        id, QEMU_PLUGIN_DISCON_EXCEPTION, on_discontinuity, NULL);
    return 0;
}
