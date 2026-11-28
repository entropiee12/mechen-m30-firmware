# Main-menu status notifier p-code run

## Input and reproduction

- `mainmenu.ap` SHA-256:
  `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`
- Probe: `tools/ghidra/RunMainmenuStatusNotifierProbe.java`
- Runner: `tools/ghidra/run_mainmenu_status_notifier_probe.sh`
- Command:

  ```sh
  bash tools/ghidra/run_mainmenu_status_notifier_probe.sh \
    /path/to/community/fw/mainmenu.ap
  ```

## Observed AP behavior

The hash-pinned probe executed the actual `FUN_48b949f4` p-code, intercepting
only key-status and message services. It observed:

- Status 0 / level 0 emits message `0x82` on call 120, then resets its timer.
- Status 2 / level 5 refreshes key status every eight calls. The same counter
  wraps at eight, so there is no `0xf2` notification during 120 calls.
- Status 2 / level 6 emits message `0xf2` on call 120 and resets its timer.
- Entering status 2 resets its counter; changing its level carries the counter.
- Status 4 normalizes to status 0 / level 6. A raw level 7 is capped to 6 for
  stored state and does not trigger the low-level refresh.

The exact filtered Ghidra output is in [`run.log`](run.log). The key and
message service returns and status samples are synthetic. This confirms the
AP control flow under those inputs; it does not identify the hardware meaning
of the status values or validate physical key/battery behavior.
