<!-- Statik analiz bulgusu (cihazda test edilmedi). Alt ajan çıktısı; ana oturum şunu baytlardan doğruladı: mengine.ap içinde `li 5000` (82f3 086a) yalnızca 0x3a64'te, community ve stock aynı, aday 0x3a64-0x3a67'de farklı; `_play` içinde mod 3 yolunda -5000 (0x78 0xec 0xff 0xff) ofseti. Diğer iddialar ajanın okumasına dayanır; VERIFIED = dump veya baytta okundu, HYPOTHESIS = çıkarım. Kaynak: m30-work/findings/artwork_start.md. -->

# Artwork start delay and Previous-key findings (read-only discovery)

Labels: VERIFIED = read in code/bytes. HYPOTHESIS = inferred.
Dumps: M = ghidra/mengine_community.c, U = ghidra/music_community.c (line numbers are file lines).
Address mapping: LoadAP table at 0x100..0x1000 (12-byte {file_off, load_addr, size}), resident via header 0x08.
Mapper script used: scratchpad map.py (same rule as tools/ghidra/LoadAP.java).

## 1. Where the delay can come from

### Engine side (mengine.ap, mmm_mp.al) - runs BEFORE the UI sees the reply
- VERIFIED: dispatcher FUN_60090d64 (M:321). Ids follow SDK app_msg.h order from 0x300:
  0x311 PLAY -> FUN_600911b0 (M:506) -> ptr 0x60091204 = 0x60190eec = _play (FUN_60190eec, M:1455).
  0x318 PLAY_NEXT -> FUN_6009134c (M:612); 0x319 PLAY_PREV -> FUN_60091370 (M:624); both -> FUN_600912cc (M:589)
  -> mengine_file_switch (ptr 0x60091344 = 0x60191368) -> reply via 0xbfc1197c.
- VERIFIED: _play (M:1455) calls FUN_60190d58 (M:1375: set file) -> FUN_60150fec (M:1273): mmm_mp_cmd 0x11 SET_FILE,
  0x2a, 0x13 MEDIA_INFO; then cmd 0x19 PLAY (M:1507); only then reply (FUN_600911b0 -> 0xbfc1197c).
  Same order as SDK mengine_play_deal.c _set_file/_get_file_info/_play and mengine_message_done.c mengine_play
  (reply after _play). The UI send_sync_msg blocks until that reply.
- SDK reference only (not M30-binary proof): `music_scene_playing.c::_scene_play_init` calls `music_play()` before
  `_playing_loop_deal`. Do not use this SDK order alone to claim that every M30 `0x311` handoff precedes artwork.
- VERIFIED: engine has no tag/artwork code of its own: no "APIC", "ID3", "PICTURE", "image/jpeg", "ALBUM.PIC",
  "mmm_id.al" strings in mengine.ap (only "mmm_mp.al"). mmm_mp.al and mmm_id.al are byte-identical to stock
  (sha256 57b541ad.. and 04fa4563..). Any ID3 skipping inside SET_FILE/MEDIA_INFO is stock behaviour in the
  unchanged parser/DSP libs, so it cannot be what the community changed.
- VERIFIED: the 7 mengine diff regions (248 B) are not artwork. Mapped: 0x98c/0xabc -> 0xbfc1168c/0xbfc117bc in
  FUN_bfc11504 (M:7062, resident periodic status/FF handler); 0x30ae,0x30fe,0x3160 -> 0x60150dae/dfe/e60 (FUN_60150d78,
  FUN_60150dd4, entry of func_0x60150e60); 0x3332 -> 0x60151032 in FUN_60150fec; 0x3404-0x34db (216 B) ->
  0x60151104-0x601511db = FUN_6015111c (M:1322): reads index*0x1a00 records (0xe00 + 0xa0c bytes) from a file,
  i.e. a table loader, not tag parsing. HYPOTHESIS: DSP/EQ/frequency table work.
- Conclusion (b) engine-side artwork scan: not present in community diff. Any engine-side delay is identical to stock.

### UI side (music.ap) - scene loop and conditional play handoff
- VERIFIED chain per track (UI thread):
  FUN_4011508c playing loop (U:1158): flag 0x200 -> FUN_400d4edc (engine status) -> flag 1 -> FUN_400951e4 (U:681)
  -> FUN_401d51b8 = play_scene_getid3 (U:2222) -> get_id3_info = FUN_781db200 (U:27153, ptr at 0x401d52cc = 0x781db201)
  -> per-format parser; sets apic_type (+0x1e) and offset (+0x28), flag |= 4.
  Then FUN_40154ddc = play_scene_paint (U:1345, called U:1208): if flag 0x10 and 4 -> FUN_40154c00 (U:1262)
  -> FUN_480546e8 (U:9135) = music_paint_albumart analogue; then FUN_4805447c (U:9026) blits.
- VERIFIED: FUN_480546e8 differs from the SDK: it does sys_load_mmm("mmm_id.al") (string at 0x480549f0), mmm_id_cmd
  open (cmd 0) / set info (2) / decode (7) / close (1), and NO sys_os_time_dly(2) polling loop (the only
  (2,0,0,0x31) calls are in unrelated fns at U:1245, 5097, 5494, 15688). Decode is therefore a blocking
  mmm_id_cmd in the UI thread. Result goes to a cache file ALBUM.PIC (string 0x48054a28; header 'O','K'+x,y,w,h,
  then RGB565 w*h*2, limit 45000 B = 150x150 test at FUN_4805447c U:9077), which FUN_4805447c re-reads and blits.
  The ALBUM.PIC cache already exists in stock (same strings/pointers).
- VERIFIED conditional order in `FUN_40154ddc`: at `0x401551d6`, global flag `0x10` enters the refresh block; if flag
  `0x04` is also set, it calls `FUN_40154c00`, which synchronously calls `FUN_480546e8`. At `0x401551e6`, flag
  `0x800` controls whether the following call is skipped. When `0x800` is clear, the call at `0x401551ea` reaches
  pointer `0x40155348 = 0xbfc16589` (`FUN_bfc16588`), whose body sends message `0x311`. Thus this reachable branch
  orders artwork decode before that `0x311` send. When `0x800` is set, this `0x311` call is skipped; a later,
  separate `0x800` branch calls `FUN_40614f9c`, which sends `0x337`, not `0x311`.
- The checked-in `tools/ghidra/run_artwork_play_order_probe.sh` executes the real `FUN_40154ddc` with separate,
  synthetic UI-event and player-state flags (`0x08` and `0x14`). It confirms the wrapper event order
  `FUN_40154c00 -> FUN_bfc16588 -> post-play 0x32d wrapper -> artwork blit`. The probe stubs the artwork wrapper,
  play wrapper, and surrounding boundaries; it proves conditional control-flow reachability, not decode duration,
  real message delivery, or that these flags occur on first play.
- VERIFIED ID3/FLAC artwork locating (UI thread, music.ap banks, not mmm_mp): 
  FUN_71412a9c (U:19762) ID3v2 header; FUN_714125ac (v2.2 frames, U:19574) and FUN_714127dc (v2.3/2.4, U:19666) stop after
  <7 text frames; for APIC they read at most 0x80 bytes of the frame and look for FFD8 with FUN_71412790 (U:19641,
  max 128-byte window), then seek-skip with FUN_781db4d8 (U:27273; seek + 512-byte read). FLAC: FUN_714d284c (U:20706) reads
  PICTURE block, 128-byte window search (FUN_714d2800), block walker FUN_714d266c (U:20602). ID3v1: FUN_713d25b0 (U:19455).
  So the APIC scan is bounded and cheap (a handful of 512-byte SD reads); it does not read the whole picture.
- Handoff conclusion: on the conditional path above, the blocking decode is called before the `0x311` wrapper, so its
  runtime can delay that particular message handoff. The flag producer/caller that makes this the first-play path
  has not been identified; this does not prove that artwork delays every initial start or that it starves audio.
- HYPOTHESIS (not measured): perceived "start delay" = UI frozen (no repaint, time stuck at 0:00, keys unserviced) for the
  whole decode, plus possible CPU/SD (FS lock 0x33/0x34 pairs) contention starving the engine thread and the DSP feed.
  Needs on-device timing (log sys time before/after FUN_480546e8) to separate these.

## 2. Community diff regions that implement the fallback (music.ap, 42 regions)

VERIFIED hook: music.ap file 0xed50 (4 B, 4e6a58c1 -> 20f10610 = extended B) at 0x48054850 inside FUN_480546e8, on the
branch where decode status byte (+0x11) == 0xFF (stock: write 'N','O' to cache = "no art"). File 0xefa0-0xeff3 (84 B,
stock zero fill) at 0x48054aa0-0x48054af3 = new trampoline: it re-executes the displaced li 'N'/li 'O' stores,
then jumps through literal at 0x48054af0 = 0x7111295d = FUN_7111295e (U:16736), and returns via literal 0x48054aec =
0x48054859 (back into FUN_480546e8). The exact instruction-level logic of the cave is HYPOTHESIS (no MIPS16e
disassembler here; I read the bytes and the Ghidra "indirect jump" note at U:9207), the two literals are VERIFIED.
FUN_7111295e has the same argument list as FUN_480546e8 (mode, offset, in-region, out-region), so it is a drop-in.

Fallback body (all UI thread, music.ap banks, no DSP/engine involvement; VERIFIED by addresses):
- 0x16550-0x168fb (940 B) -> 0x71112750-0x711128fb: FUN_71112752, FUN_71112782 (U:16620). Bounds: target window w,h in 1..0x96
  (150); JPEG must have 1 or 3 components (+0x13); block count after FUN_71892538 (ceil-div by sampling*8) must be
  < 0x1000 (4096 blocks of 8x8 MCU units, about <=1024x1024 for 4:2:0); output = (width>>3, height>>3), i.e. 1/8
  DC-only style downscale, then fit to <=150x150. Whether decode is DC-only is HYPOTHESIS from the >>3 sizes.
- FUN_7111295e (U:16736, unchanged bytes): opens the file, sets up reader (+0xc = 0xc000 byte budget, HYPOTHESIS meaning),
  calls FUN_71892b2c/FUN_71892c04, FUN_71112782, then FUN_71752a44 (U:23225) writes ALBUM.PIC ('OK' header + RGB565).
  Sets flag |= 4 when ok.
- 0x20410-0x20ad7 -> 0x71752510-0x71752bd7: FUN_71752510 (U:23012) (output/colour conversion),
  FUN_71752a44, FUN_71752b14 (U:23282: header probe, w < 0x281, 3 components), FUN_71752b98 (file open helper).
- 0x2240c-0x22abb, 0x22c0d-0x2335f, 0x2340c-0x239db -> banks 0x7185/0x7189/0x718d: JPEG core: FUN_718d26b8 (U:25424) marker
  loop (SOI 0xFFD8 search, SOF0 0xC0 case, DHT FUN_718d252c), FUN_718d2990 (U:25636), FUN_7189273c (U:24871), FUN_71892xxx
  helpers, FUN_7185283c (U:24496). VERIFIED these hold the JPEG parser; exact per-function role HYPOTHESIS.
- Metadata scan changes (also UI thread): 0x1a6b0-0x1a81f (ID3v1, FUN_713d25b0), 0x1aeac-0x1b00b (FUN_714125ac),
  0x1b090-0x1b0cb (FUN_71412790 FFD8 window), 0x1b0dc-0x1b313 (FUN_714127dc APIC), 0x1c66c-0x1c7f7 (FLAC walker/PICTURE),
  0x1b600-0x1bde0, 0x1bffc-0x1c377, 0x1cac4-0x1d3ab, 0x1d400-0x1de1b, 0x1df2c-0x1e332 (other format tag parsers and
  helper FUN_7161296c; HYPOTHESIS), 0x154f0 (FUN_710525ec), 0x115cc..0x132a4 (get_id3_info table/dispatch banks
  0x7815b/0x781db/0x7825b), 0x21e4 (FUN_400951e4 getid3 trigger), 0x3116, 0x9e00-0xab2c (0x40494xxx/0x4051xxxx),
  0xc488, 0xd615 (literal 300 -> 500 before "ALBUM.PIC", use unknown), 0x38 (header: 0x9fc15400 data segment size
  0x678 -> 0x6d8, +96 B for new globals; HYPOTHESIS), 0x2d76a/0x2d7f0 (1 B each, 4 -> 8).
- Blocks the audio start? VERIFIED: not the reply (see section 1). Fallback runs in the UI thread, never in the engine
  thread or a DSP lib; the JPEG code is MIPS code in music.ap. Remaining unknown: decode time per image size and whether
  it starves the engine (needs device timing or QEMU with SD image + tracks of known artwork sizes).
  Caveat: stock FUN_480546e8 also decodes in the UI thread (mmm_id), so large artwork cost exists in stock; the community
  adds a second, CPU-bound attempt only when mmm_id fails (status 0xFF), i.e. it makes the failure path slower, not the success path.

The host emulator records the static fallback guard in
`tools/sim/artwork_fallback.py`: the community path is attempted only for
status `0xff`, accepts dimensions from 1 through 150, accepts one or three
JPEG components, accepts a positive block product up to and including
`0x1000`, and limits the RGB565 output to 45,000 bytes. The decompile excerpt
is preserved locally at `analysis/local-evidence/artwork-fallback-community.c`;
it is ignored and is not a public repository artifact.
JPEG decoding and UI timing remain outside the model.

`ArtworkSession` in `tools/sim/m30_model.py` now keeps the two proven timing
facts separate: `audio-start` precedes artwork work, while an active decode is
marked `ui_blocked` until the cache record is complete. This is a host trace
contract, not a measured duration and not evidence of asynchronous decoding.

The cache writer's observable record is modeled separately in
`tools/sim/artwork_cache.py`: successful records use the six-byte `OK` plus
`x,y,w,h` header followed by `w*h*2` RGB565 bytes; failed records use `NO`
with zero geometry and no pixel payload. The byte order and color interpretation
of the RGB565 payload remain opaque.

## 3. Previous key

- VERIFIED: bytes at mengine.ap 0x3A64 are 82f3 086a 4bea in BOTH stock and community (patch not pre-applied).
  0x3A64 maps to 0x60190f64, inside _play = FUN_60190eec (M:1455). Decompile at M:1481: for play_mode 3
  (PLAY_FBON) it stores bp_time_offset = 0xffffec78 (= -5000), bp_file_offset = 0, bp_info_ext = 0, then
  mmm_mp_cmd 0x15 (SET_BREAK_POINT) and 0x20 (SET_FFB, -4). Matches SDK mengine_play_deal.c _play(PLAY_FBON).
  So 5000 is the fast-backward start offset for the previous file after fast-backward ran past 0:00 (called from
  mengine status handler: ENGINE_STOPPED + fast_status==FB -> mengine_file_switch(..., FORCE_SWITCH_PREV, PLAY_FBON)).
  It is NOT a Previous-key threshold, and compares against nothing.
- VERIFIED: the Previous-key message handler has no time test. 0x319 PLAY_PREV -> FUN_60091370 (M:624, file 0x1e70):
  bytes decode to: load play_status into a local (unused), jal 0x600912cc with switch_mode 3 (FORCE_SWITCH_PREV).
  FUN_600912cc (M:589): if status==1 (playing) clear bk_infor and play_mode=4 else 0; call mengine_file_switch; reply.
  Only two imm-5000 sites exist in mengine.ap (scan of MIPS16e EXTEND 0xf382/0xf47d): 0x3A64 (above) and 0x9a0
  (0xbfc116a0, FUN_bfc11504 M:7124 "total-5000 <= cur_time": FF/end-of-track window, +250 ms steps). No cur_time>5000
  restart branch exists in the engine binary (stock or community), unlike the PD196 SDK mengine_play_prev
  (mengine_message_done.c:652, cur_time > 5000 -> _stop, _play, reply TRUE).
- Consequence (VERIFIED by reading, not tested on device): tools/patch_prev_restart.py does not make Previous restart
  the track. Applying it changes the FFB break point offset -5000 -> 0, HYPOTHESIS: a backward-scan hand-over into the
  previous track starting at/near its end instead of 5 s before the end (could chain-skip tracks). Previous still
  switches to the previous file (mode 3).
- UI side: Previous handler FUN_bfc16718 (U:28658, music.ap resident): sends message 0x319 via (*0xbfc16810)(0,0,0x319),
  after decrementing the displayed track index at (+8) (wrap/shuffle via fn 0xbfc16804, flag +2==0x10 && +1!=3), sets
  redraw bits 0xcec. No time check. Next = FUN_bfc16614 (U:28611) with 0x318 (also U:5307 for an error path).
  Any real "restart" feature must change BOTH: the engine handler and this UI handler (else the track number shown
  becomes wrong and a shuffle-history entry is consumed).
- Edge cases (SDK source, binary not individually decoded for these, HYPOTHESIS for the binary):
  * First track: _next_or_prev forces FSEL_MODE_LOOPALL for FORCE switches, so Previous on track 1 wraps to the LAST
    track in every repeat mode (single/folder/all/intro/shuffle), then restores repeat_mode (mengine_play_deal.c:456-540).
  * SDK threshold semantics if implemented as in PD196 with 0: "cur_time > 0" restarts, but at exactly 0:00 it still
    switches to the previous track, so threshold 0 would NOT "always restart".
  * Paused/stopped: SDK restart does _stop(STOP_NORMAL) and only calls _play if play_status was PlaySta, so it stays
    stopped at 0:00 (no breakpoint); the switch path uses play_mode 0 when not playing (no autoplay) and clears bk_infor.
  * Intro (browse) mode: _play re-reads GET_STATUS into g_intro_start, so restart re-arms the intro timer; shuffle:
    restart should not pop shuffle history (UI index code above does).
  * Audible/AA files: SDK skips the restart branch (g_is_audible).
- Options to get "Previous always restarts" (HYPOTHESIS, needs code space): (1) rewrite FUN_60091370 to run _stop(0) then
  _play(0)/cur_time=0/clear bk_infor and reply TRUE (handler is 0x34 bytes; needs a cave or a shared tail); (2) UI-only:
  make FUN_bfc16718 send STOP(0x312)+PLAY(0x311) instead of 0x319 and skip the index decrement.
