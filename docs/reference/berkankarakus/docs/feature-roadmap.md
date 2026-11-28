# M30 evidence-driven emulator roadmap

Bu liste yalnızca mevcut kanıtın emulator geliştirmesine dönüşebileceği
alanları tutar. Firmware özelliği veya cihazda güvenli patch önerisi değildir.

## Sıradaki uygulanabilir alanlar

| Öncelik | Alan | Bir sonraki kanıt/çıktı |
|---|---|---|
| P0 | Klasör marquee veri akışı | 50-byte slot, nonzero browser context, gerçek `0x14` row-provider → `0x1c` converter zinciri ve directory callback'in üç dalı dinamik doğrulandı; `drv_ui` B3 fonksiyonu p-code’da çalıştırıldı: branch byte `0x17` injected width 80/180 için 0x26409401 descriptor’ını ve X koordinatını değiştiriyor. Browser row callback → B3 bağı hâlâ kanıtlanmadı; sırada listbox/framework çağrı zincirini bağlamak var |
| P0 | Now-playing UI | initial + metadata/transport/status state branch'leri dinamik doğrulandı; host resource renderer → LCD command/pixel sink hibriti mevcut; sırada gerçek AP payload'larının resource/font consumer'larına bağlandığını kanıtlamak var |
| P1 | MUSIC.LIB sort/index | Hash-pinned stock MIPS/QEMU koşuları seedless cihaz kütüphanesinde ve seeded karşılaştırmalarda kaydedildi. Seedless `TERMINAL_OOR_ZERO` adayı tek 4.000 kayıtlı cihaz görüntüsünün bütün indeks tablolarını ve node sayılarını eşleştiriyor, ancak tam dosya/gerçek converter eşleşmesi değil. Aynı aday ikinci 251 kayıtlı public örnekte ARTIST/GENRE tablolarını eşleştirmedi; genellenebilir kural kanıtlanmadı. Eski 56-entry Python-model farkı model artefaktı. Sonraki ucuz ayırt edici iş: farklı aralık-dışı karakterle sentetik terminal/orta konum kontrolü; aynı seedless koşuyu tekrar etme |
| P1 | USB-DAC | Descriptor/EP0 ve producer-consumer state probe'ları mevcut. Hash-pinned p-code probe gerçek `usound.ap` callback + sender kodunu beş state/input koşulunda yürüttü; `0x201` payload dalları doğrulandı, **sentetik olarak eklenen** app-ID-8 endpoint kaydı lookup'tan geçti ve resident çağrı argümanları yakalandı. Sender sınırındaki 24 baytın ilk 8'i sentetik copy shim ile callback mesajından geliyor; kalan 16 bayt sıfır başlangıçlı host RAM. Bu buffer ayrı p-code'da gerçek manager `0x201` handler'ına sentetik enjekte edildi; eşleşen state koşulunda handler gerçek module-`0x0c` op 7/2 dalından geçti. Resident copy/send dönüşleri ve AP'ler arası teslim sentetik kaldı; bu lifecycle/control-status yolunu PCM handoff'ından ayırmayı destekliyor, USB-DAC'i kanıtlamıyor. Ayrı QEMU probe'u gerçek producer→threshold→DMA-setup talimatlarını RAM-backed register'larla yürüttü; `FUN_bfc31654` ring consumer ve module `0x10` trap argümanları da ayrı ayrı doğrulandı. Producer→consumer çağrı bağı, ROM servis etkileri, gerçek MMIO/DMA ve DAC çıkışı hâlâ bilinmiyor. Sonraki corpus içi iş bu çağrı bağını aramak; cihaz ROM readback ayrı çalışma |
| P1 | DAC caller/codec register | `drv_dac.drv` byte-transfer sınırını koru; caller ve register tablosu bulunmadan volume/fade patch’i üretme |
| P1 | Candidate ATJ2127 board schematic | User-provided NEM_V3 drawing explicitly names ATJ2127-ID and shows an 8-bit LCD/NAND shared bus plus LRADC1/SD_DET, but its CTP interface is not documented in the M30 manual; resolve that mismatch before applying the wiring. See [schematic review](nem-v3-schematic-review.md); exact M30 identity is unverified |
| P1 | EQ UI ve duyulabilir yol | `config.ap` 16 factory bandını config öğe `0x86..0x95`'ten `dae_cfg`'ye kopyalıyor; öğe `0x96` varsayılanı `audiopp.dsp` seçiyor, `0x98` builder guard `+0x0c` alanına gidiyor. Mainmenu state table EQ ve Custom ekranlarına ulaşıyor; kök menüde EQ'yu açan öğe bilinmiyor. Slider artış p-code'da `{7,7}` producer'a ulaştı (servisler sentetik); cancel eski preview'ı güncellemeden bırakabilir. `0x38` blok yalnız AP çıkışında yazılıyor; güç kesilmesi davranışı bilinmiyor. `+1→-3` probe sonucu ile signed `slti` yorumu uyuşmuyor; DSP tüketimi ve duyulur etki açık. Ayrıntı: [EQ bulguları](findings-eq-usound.md), [config kanıtı](evidence/config-dae-factory-2026-11-24/README.md), [slider probe'u](evidence/mainmenu-eq-slider-pcode-2026-11-24/README.md).
| P1 | QEMU/exception | Segment-entry p-code, `FUN_bfc32224` CP0 ve interrupt yazmalarını doğruladı; constructor/vector zinciri ayrı Ghidra/QEMU probe’larında çalıştı. `mmm_bp` entry→dispatcher→init stock/community p-code’da sekiz resident çağrıyla geçti; no-effect stub altında `+0xd0` için AP yazımı görülmedi. `FUN_bfc322f8` stock/community native MIPS p-code’da MIPS16e (`RELP=1`) dönüşlerini doğruladı. Callback’e kadar olan dört ROM çağrısı ve sentetik source→destination copy kontrolü mevcut. `ker_xsys.bin` içindeki `FUN_bfc344f4` exception frame'den beş sözcüğü `exc:` descriptor'ıyla callback'e verdi; iki state profili `0xc012001c`'ye `0x5d/0x69` yazdı. `FUN_bfc3416c`'in gerçek guard-failure talimatları sentetik ROM dönüşleriyle p-code'da yürütüldü: `sp `/`stk overflow` callback argümanları, bit-8 state temizliği ve default-handler sınırı; sentinel eşleşirken 0x93 hata/0x94 normal sınırı, 0x180 mesafede sentinel uyuşmazlığının hata dalı doğrulandı. Bunlar çıkarılmış fonksiyon davranışını sentetik koşullarda kanıtlar; doğal çağrı, ROM callback gövdesi/çıktısı, MMIO peripheral rolü ve fixed-ROM handler bağı hâlâ bilinmiyor. `FUN_bfc340a4` ayrıca p-code’da sınandı: `table=0x12000` için `sp&0x3ffff` `0x11fff/0x12000` alternate stack, `0x12001` doğrudan ROM sınırını seçiyor; 184-byte slot ve eski-SP kaydı doğrulandı, handler çalıştırılmadı; QEMU Malta 24Kf/34Kf'de gerçek xsys talimatları index-0 alt/eşit/üst ve farklı table[1] ile index-1 profillerinde yürüdü (global operandlar RAM'e yönlendirildi, ROM sınırı stub). `NORBREC` setup aynı `0x9fc12000/14` kaynaklarına helper çağrısı yapıyor; gerçek boot sırası bilinmiyor. `mengine.ap` içindeki `mmm_mp.al` isteği resident `0xbfc11cc8`'e gidiyor; `MMM_BP` ile bağı kanıtlanmadı. Sonraki yüksek getirili adım: corpus literal taraması iki fonksiyonun doğrudan entry pointer'ını bulmadı; missing fixed-ROM exception caller edge'ini mevcut dispatch ile eşleştir, tahmini call edge ekleme ve `0xc012001c` rolünü kanıtlamadan adlandırma. `FUN_bfc32440` MMIO, `0xbfc21a39` ABI ve bootloader transferi açık |
| P1 | Sabit ROM kaynağı | [ROM-dump bulgusu](findings-rom-dump-availability.md): public `actions_dump.c::dump_mem2` kodu incelendi; MIPS16 copy helper `0xbfc1e000`'a yükleniyor, `0xbfc1e020` scratch'inden en fazla `0x1d8` baytlık ROM parçaları kopyalanıp `READRAM` ile host'a alınıyor. Source-derived helper QEMU'da azami tampon ve argüman ABI'siyle doğrulandı. NAND/SD yazma komutu yok; fakat ADFU payload yürütülüyor ve M30 uyumluluğu doğrulanmadı. Sırada `adfus` server protokolü ve bu M30'un ADFU kimliği/memory map'ini çapraz doğrulamak var; cihazda çalıştırılmadı |
| P2 | Playback | `run_asrc_music_tick_probe.sh` gerçek `music.ap` tick→mode-4 yolunu doğruluyor; yeni status-3 p-code probe'u sentetik `+0xd0` status=3 ile `0xbfc217a2` compare'ını, state 2 yazımını ve refill olmadan terminal dönüşü doğruluyor. Resident giriş ile `FUN_bfc27520` ring-processing helper stub; gerçek status üreticisi, downstream drain davranışı ve audible fade sonucu bilinmiyor. Hash-pinned modüller arasında doğrudan cross-module pointer/shared-state literal'i yok. `0xbfc164dc` resident servisinin music.ap'te beş çağrısı var; dörtü zaman/ilerleme benzeri çıktı buffer'ı kullanıyor, biri iki argümanlı. Aynı giriş `usound.ap` ve `udisk.ap` event/result-buffer yollarında da çağrılıyor. Sıradaki yüksek getirili iş decoder context `+0xd0` status pointer'ının üreticisini bulmak ve bu ROM servisinin ABI'sini doğrulamak; mevcut iki iz arasında bağ kurulduğu kanıtlanmadı. Ayrıntı: [bridge bulgusu](findings-mmm-bp-music-bridge.md) |
| P3 | Metadata ve sayaç | MUSIC.LIB writer/recovery ve kalıcı sayaç formatını çöz; offline düzenleyiciyi cihaz eşdeğeri kabul etme |

QEMU exception güncellemesi: `ker_xsys.bin` içindeki gerçek `0xbfc34074`
prologue’u Malta 24Kf/34Kf’de sentetik CP0 EPC ve TLB eşlemesiyle yürütüldü;
`0x13dd8/0x13ddc` EPC/RA kayıtları ve frame çevresi canary’leri doğrulandı.
Ardından gerçek QEMU `break 0` → `kernel.drv` vector → slot 9 (`0xbfc06f18`)
→ sentetik doğrudan-jump bridge → gerçek xsys entry birleşimi çalıştı; beş frame
sözcüğü doğrulandı. Gerçek ROM handoff'u ve `FUN_bfc344f4` çağrı bağı hâlâ açık.

## Dinamik inceleme havuzu

Statik decompile’dan ayrılan bu işler, gerçek AP/driver fonksiyonlarını
Ghidra p-code veya mevcut QEMU leaf harness’lerinde yürütür. Her satırda
resident/ROM/peripheral sınırı stub’lanır ve gözlenen callback/byte/state
değeri kaydedilir; bu sonuçlar tam cihaz boot’u sayılmaz.

| Öncelik | Alan | Dinamik deney | Beklenen yüksek getirili çıktı |
|---|---|---|---|
| P0 | Browser/fsel | Scene caller → AP scene → refresh → row provider zincirinde isim üretim kaynağını çöz | Birleşik p-code izi gerçek `FUN_bfc16600`, `FUN_40cd4e00` ve `FUN_40cd5218` talimatlarını seçilmiş stub dönüşleri ve yedi sentetik satırla yürütüyor; doğal servis dönüşleriyle refresh'e ulaşma kanıtlanmadı. 20-byte kaydın `+0x02` alanı ayrı probda doğrulandı. Folder/playlist ayrımı ve gerçek filesystem-name kaynağı hâlâ açık |
| P0 | Now-playing UI | `music.ap` çizim girişini gerçek `music.sty`/font/resource pointer’larıyla çalıştır; LCD sink’ine trace al | ikon hizası, üst/alt bant, marquee clipping ve gecikmenin gerçek çağrı sırası |
| P0 | Playback/EOF | `run_asrc_music_tick_probe.sh`: gerçek tick wrapper'ını stock/community hash-pinned AP'lerde çalıştır; emulated callback literal'ını doğrula ve resident sınırlarını intercept et | `lw v0,0xc4(pc)` → literal `0x400d509c` → `jalrc v0` → mode-4 handler gerçek talimatlarda ve emülatör belleğiyle doğrulandı. Callback gövdesi, decoder/ASRC/DAC ve örnek kuyruğu hâlâ bağlı değil |
| P1 | USB-DAC | Gerçek `usound.drv` producer/consumer zincirini exception ve DAC sink ile birleştir | module `0x10` sonrasında PCM sahibinin ve DAC’a giden byte akışının kanıtı |
| P1 | EQ producer/engine probes | Mainmenu code 3 returns 0x8505 and enters the EQ selector; config item 0xe2 places it fifth in the default order. Its localized label remains unknown. Slider edits reach the producer in p-code with synthetic services. Raw MIPS16 implements decrement-by-one with a -3 floor; Ghidra p-code mishandles the extended signed SLTI. The 0x38-byte persistent write occurs on AP exit, not confirm/cancel; cancel restores the editor buffer without a compensating producer update. ROM service effects, DSP consumption, and audible output are open. See [EQ findings](findings-eq-usound.md) and [selector/editor findings](findings-eq-selector-writer.md).
| P1 | Browser marquee/B3 | Gerçek `FUN_260e4ff4` kodunu injected branch/width sonuçlarıyla çalıştır; B16-qualified dış hedef çağrı argümanlarını yakala | `0x17` dalında descriptor `+8=0x7c`; kısa injected width region X’ini 5→45 kaydırıyor. Browser callback→B3 caller bağı ve 0x26409401 hedefinin çizim etkisi hâlâ açık |
| P1 | Upgrade | Gerçek `setting.ap` yolu `input=1` için 155 adımda `0x100a` UI sınırına, `input=0` için 178 adımda `0x1009` event-poll sınırına ulaşıyor. Altı fallback event-map kaydı dispatcher girişinde doğrulandı | Gerçek `FUN_40054f78` → `FUN_48414600` → `FUN_48494600` zinciri stock/community’de 212 adımda descriptor ve callback hücresini kurdu. Aynı bellekte gerçek `FUN_48914600` event yolu, sentetik servis yanıtları ve `0x0102` payload ile 219 adımda bu callback’e ulaştı. Sırada callback gövdesini ve resident etkilerini çöz; fiziksel event davranışı, prompt geometrisi, flash/reboot hâlâ bilinmiyor |
| P1 | MUSIC.LIB metadata | gerçek writer/reader fonksiyonlarını RAM-backed `MUSIC.LIB` üzerinde yürüt | record alanları, tie-break ve sayaç için güvenli kalıcı alan adayı |
| P2 | Key/screen-off | gerçek key/common-filter path’ine event dizileri enjekte et | wake/media-key ayrımı, debounce/repeat state ve ekran açılma nedeni |
| P2 | SD/index trigger | Resident event source `0xb0`'ı ve playlist parametrelerini çöz; updater'ın ID `0x20000` kaydını neden geçersiz kıldığını bul | `config.ap` invalid ID-`0x20000` kaydında ID `0x2f0000` bayrağını set ediyor; `playlist.ap` update finalizer'ında temizliyor. `udisk.ap` resident API'ye `(0,0xb0,callback,8)` çağrısı yapıyor ve event poller'da 0xb0/0xb1'i ayırıyor. Ortak handler 0x861c döndürüyor; manager tablosu app 10'un `playlist.ap` olduğunu doğruluyor. Boot parametresi 0x4e indeks güncellemesi başlatıyor; music dispatcher parametreleri 0x42/0x82/0xc2'nin anlamı, resident event üreticisi ve kart takmayla ilişkisi bilinmiyor. Powered-card gözlemi sürüyor; updater invalidasyonu açık. Bkz. [indeksleme bulguları](findings-index-guards.md) |

## Ara verilen noktadaki TODO

Bu liste bir sonraki oturumun başlangıç noktasıdır. Sıra, getirisi ve kanıt
kapısı dikkate alınarak yazılmıştır; hiçbir madde tek başına cihazda flash
denemesi anlamına gelmez.

1. **M30 sabit-ROM exception/dispatch yolunu daralt:** `ker_xsys` içindeki
   `0xbfc34074 -> 0xbfc06d6c` handoff'u için AP/DRV kopya taraması tamamlandı.
   Kernel exception slot 9'un seçtiği `0xbfc06f18` de Boot ROM probe kapsamına
   eklendi; handler baytları hâlâ yok:
   346 artifact içinde `0xbfc166ad` ham pointer'ı yalnız 12 byte-identical
   `usound.ap` kopyasında bulundu; bunlarda `a3=4` çağrısı yok. Bu, `a3` ile
   operation-table slot'u arasındaki eşlemeyi çözmüyor. `module=0x10` etkisini
   fail-closed bırak; sıradaki belirleyici kaynak aynı M30 kartından alınmış
   ROM veya sabit-ROM handler gövdesinin eşdeğer kanıtı. ROM okuma payload'ı
   yerel olarak bulunmadığı ve uyumluluğu doğrulanmadığı için cihaza yükleme
   yapılmadı.
2. **DONE — upgrade akışını trace-only modelle:** doğrulanmış beş seçenekli
   ekran, `FUN_40014c30` selector'ları, decrypt/search metinleri ve
   `FUN_bfc37408` ilerleme normalizer'ı `tools/emu/upgrade_flow.py` ve
   `tools/emu/upgrade_ui.py` içinde sınırlandırılmış olarak taşınıyor.
   `run_upgrade_ap_corpus_probe.sh` bu ekran/selector sınırını on iki dated
   imajda doğruluyor. Indirect return değerleri çözülmeden `flash`, `reboot`
   veya başarılı update olayı üretilmiyor.
3. **Now-playing UI adayını paketlemeden önce delta kapısını güçlendir:**
   initial paint ve `0x100`/`0x20`/`0x200`/`0x800` state dalları üç firmware
   ailesinde dinamik doğrulandı. Sıradaki iş gerçek resource/font/LCD sink
   çağrılarını bağlamak; yalnızca `music.sty`/gerçek kaynak üyeleri üzerinde
   değişen byte'ları, boyutları, checksum'ları ve rollback kaynağını
   karşılaştır; host PNG'sini firmware çalışması gibi raporlama.
4. **QEMU kapsamını gerçekçi şekilde genişlet:** sabit exception handler veya
   ROM kanıtı gelirse CP0/stack handoff ve overlay-entry probe'u ekle; bu kanıt
   yokken tam ATJ2127 boot/machine modeline yatırım yapma.
5. **Ses tarafını sınırda tut:** `usound` producer/consumer ve DAC byte
   transferi geçişlerini birlikte trace et; decoder/DSP/ASRC/DAC'ın audible
   etkisi kanıtlanmadan USB-DAC, gapless veya fade-out sonucu çıkarma.
6. **EOF kontrol akışı ve caller edge dinamik olarak doğrulandı:**
   `run_asrc_music_tick_probe.sh` gerçek stock/community `music.ap`
   talimatlarını `FUN_400d4edc` tick wrapper'ından başlatıp
   `0x400d4fda` literal load → `0x400d4fdc jalrc v0` üzerinden
   `FUN_40494e94` girişine ulaşıyor; hedef register ve literal değerinin eşit
   olduğunu çalışırken denetliyor. Son öğede stock mode-4 end notification, community
   mode-4 ise index/UI callback argümanı 0 gözlendi. Ayrı handler probe'unda
   9/10/11 threshold vakaları doğrulandı. Sırada index/UI callback'ten gerçek
   player/decoder/ASRC veri yoluna bir çağrı bağı bulmak var; sesin uzadığı
   veya kuyruk örneklerinin çalındığı sonucu çıkarma.
7. **Düşük öncelikli işler:** MUSIC.LIB metadata yeniden kurma/tie-break,
   sayaç kalıcılığı, yeni EQ presetleri ve filename sıralama değişikliği;
   bunlar mevcut kanıtla cihaz eşdeğeri kabul edilemez.
8. **TODO — EQ Custom/Off farkını daralt:** Cihaza flash'lanan HEX bu
   bilgisayarda yok; HEX ve cihaz sürümü için read-only kimlik bilgisi
   beklemede. Aynı kaynaktan çıkarılan `music.ap` hash'i ve `0x86f8..0x86fb`
   baytları ayrımı yapar (`20 57 c1 9f` = yamasız;
   `00 4f 3d 40` = sabit eğri yönlendirmesi). Slider yazıcısı artık
   `mainmenu.ap` `FUN_40154f44` olarak bulundu; state-pointer tablosu selector
   ve slider ekranlarına gidiyor. Kalanlar: state-1 menü öğesini bulmak, mainmenu
   stilindeki EQ adlarını MEngine presetleriyle uzlaştırmak ve azaltma kolundaki
   `+1 → -3` probe sonucu / signed `slti` yorumunu çözmek. Blok kaydı AP çıkışında
   yapılıyor; cihazdaki güç kesilmesi ve ses davranışı ölçülmedi. Hash'siz sonuç
   kabul etme; firmware yazma/flash, commit veya push yapma.

## Önceliklendirilmiş QEMU/exception TODO

Bu sıra, gerçek firmware kontrol akışına en çok yaklaşan ve cihazı
değiştirmeden ölçülebilen işlerden düşük getirili tam donanım emülasyonuna
doğru gider. Her madde geçerli firmware corpus'unda tekrarlanabilir bir
PASS/FAIL koşuluyla kapatılacak.

| Öncelik | İş | Neden şimdi | Kapanış ölçütü |
|---|---|---|---|
| P0 | **DONE:** Gerçek `FUN_bfc31654` ring consumer’ını `break 0` modül çağrısıyla çalıştır | `0xbfc326a9` QEMU’da modül kimliği `0x10` olarak yakalanıyor | lineer ve wrap çağrılarında gerçek trap, iki modül çağrısı ve dört argüman kaydı altı corpus’ta doğrulandı |
| P0 | **DONE:** LCD resident komut/data ve window akışını QEMU’da çalıştır | `drv_lcd.drv` fonksiyonları ve `0x2a/0x2b/0x2c/0x36` akışı ekran sink’ine bağlandı | gerçek window/orientation akışı ve komut/data izleri altı corpus’ta geçti |
| P1 | MIPS16 `break 0` modül dispatch’ini resident exception yoluna bağla | zarf çözüldü, fakat modül `0x10` servisinin sabit-ROM etkisi bilinmiyor | exception handler veya kernel kaynaklı dispatch tablosu modül ve dönüş sözleşmesini kanıtlar; PCM/DAC sonucu iddia edilmez |
| P1 | `drv_dac.drv` gerçek byte-transfer fonksiyonlarını sentetik GPIO context ile çalıştır | DAC sürücüsü ortak ve küçük; register anlamı bilinmese de bit/byte transfer sınırı ölçülebilir | **DONE:** six corpus QEMU probe executes real 8-bit transmit, two-byte send, and all-high/all-low receive boundary; electrical/codec semantics remain unresolved |
| P1 | `usound.drv` producer/consumer ring ilişkisini sınırla | QEMU gerçek event callback → producer → `FUN_bfc318cc` yolunu çalıştırdı; harness consumer'ı ayrıca çağırdı. DMA halkasına A/B tam 192-byte paketler yazıldı; consumer'ın ikinci kaynağı `0x9fc340c0` başlangıçtaki `0x11` olarak kaldı | **PARTIAL:** sentetik bileşim doğrudan producer→consumer varsayımını doğrulamadı; consumer'ın gerçek firmware çağrı rotası, FIFO, module-0x10 ROM etkisi ve PCM/DAC bağlantısı bekliyor |
| P2 | Kernel loader/overlay entry ve exception handoff | Gerçek `ker_init.bin` constructor/vector zinciri ve `FUN_bfc32224` CP0/MMIO yazmaları p-code’da çalıştı. Hash-pinned `NORMBREC.BIN` p-code’da aday `0xbfc08000` tabanında iç `j → stack setup → jalx/MIPS16` yolunu, küçük/büyük uzunluk dallarını ve nonzero dönüş sonrası hata callback’ini izliyor; callback’ler sentetik ve AFI load adresi/gerçek açılış yürütmesi bilinmiyor. `kernel.drv` aynı adres penceresini farklı baytlarla tanımlıyor; sıra/overlay kanıtı yok. `NORMBREC`, `NORFWSC` ve `udisk.ap` içindeki üç çağrı yolu da `0xbfc002a0`'daki sözcüğü pointer slot'u olarak okuyor; UDisk p-code probu sentetik slot değeriyle gerçek caller'ın dolaylı çağrısını ve `0xc0020000` yazmasını doğruladı. Gerçek ROM sözcüğü/hedefi hâlâ bilinmiyor. AFI `M` tipi yükleme anlamı vermiyor; `NORBREC +0x1c` kullanımı bilinmiyor | Gerçek ROM slot içeriği/hedefi ve `0xbfc08000` aşama/overlay sırası kanıtlanır; cihaz boot davranışı iddia edilmez |
| P3 | Tam ATJ2127 QEMU machine / boot | upstream model ve ROM/peripheral kodu yok; maliyeti yüksek, şu anki kanıtı az artırır | yalnızca ATJ2127 MMIO/ROM kaynağı veya cihaz dump’ı gelirse başlatılır; mevcut haliyle ertelenir |

| Alan | Mevcut kanıt | Güvenli emulator kapsamı |
|---|---|---|
| FAT32/exFAT | 31 extension slotu, raw playlist maskeleri, reader-type seçimi, directory dispatch ve cluster/block sınırı | explicit-slot filtre, driver high-bit-first mask decoder, reader selector ve depth-8 walker modeli mevcut; callback ABI beklemede |
| Library scan capacity | stock `playlist.ap` RAM arrays and 4,000-record stop | fixed 4,000-record scan boundary modeled; file format and experimental large-library path remain separate |
| MUSIC.LIB layout | 0x200 header, 256-byte records, four section directories, 512-byte sectors, a repeated `<BBHHHHHHH` node shape, identical index-core Ghidra output across all twelve dated releases, reader-side use of `+0xf4..+0xfb` as an eight-byte file key, US212A `cluster_no`/`dir_entry` correspondence, and a real 4,000-record multi-group M30 image | header/section/record-area parser, bounded record codec, decoded little-endian file-key pair, bounded active-record lookup, typed section snapshots, read-only node-to-record span interpretation, fail-closed table/node-link validation, short-key tie-refinement plus nested run/group model, and a copy-safe offline editor; primary location blob, exact duplicate-key rebuild policy, and record links remain unverified |
| SD controller | 512-byte transfer, card detect/capacity yolları | sektör cihazı ve `sector_count * 512` capacity view mevcut; register polling/IRQ beklemede |
| MCG/RU3 UI | altı corpus'ta byte-identical node/group metadata | katalog mevcut; localized name ve node→app eşlemesi beklemede |
| USB host | altı `USBC` marker'ı, READ(10)/WRITE(10), 512-byte transfer birimi, CSW yok | block-request alanları ve SD byte aktarımı modelleniyor; testli 31-byte CBW-shaped educated guess mevcut; CSW, status anlamları ve controller davranışı beklemede |
| USB Audio ring | `usound.drv` 0x1000 ring, wrap/short-read sınırı, 0x6e40 producer ring'i ve DMA tamamlanma doluluk eşikleri | QEMU'da gerçek EP0→producer→threshold/DMA-setup→completion state akışı ve ayrı wrap/boundary probe'ları RAM-backed MMIO ile çalışıyor; Ghidra p-code gerçek setup fonksiyonunun 10 module-0x0f ve 1 module-0x10 çağrısını sentetik servislerle yakalıyor. AP callback'lerinden manager app-id 8'e `0x201` aday rotası bulundu, ancak opak resident service yüzünden handler'a teslim doğrulanmadı; callback gövdesinin kendisi PCM taşımıyor. Seed edilmiş 48/32 kHz koşullarında producer/ring/DMA gözlemleri aynı; producer yolu bu state'i kullanmıyor gibi görünüyor. Module-0x0f çağrılarında iki MIPS16 function pointer argümanı gözlendi; sabit handler'ın anlamı/dispatch olayı, PCM/decoder ve fiziksel DMA semantiği beklemede |
| USB Audio descriptors | stock `usound.drv` MIPS16 `FUN_bfc31f94` p-code; VID/PID, UTF-16LE strings, interface flag, 16/48 and 24/96 descriptor fields | dört sentetik config vakasıyla hash-pinned real-code probe mevcut; ROM config getter and USB hardware behavior remain outside scope |
| Key driver | ADC threshold tablosu, normalized event/repeat sınırı ve changelog'daki ekran-kapalı politikaları | ADC→repeat→application-filter host session ve 512 gerçek-MIPS classifier matrix'i mevcut; gerçek `FUN_bfc10220` p-code sample 1/5/6 repeat payload'larını ve `0x30/0x32/0x34` özel payload'larını doğruladı. Slot 3 range predicate'i, slot 7 RAM/MMIO etkisi, `FUN_bfc10518` iki config durumu ve parent init'in `nor_ud.drv` sonra `key.drv` için import-15/a3=10 çağrıları ölçüldü. Handler semantiği ve B+8..B+15 initializer'ı, parent-init'in gerçek boot'tan çağrılması, fiziksel ADC→key mapping ve gerçek zamanlama beklemede |
| Screen-off controls / volume limit / sleep setting | 12 HEX changelog'i, lock-screen control varyantları, `setting.mcg` node `182`, `setting.ap` içindeki 100 üst sınır/clamp akışı ve 2023-02 persistent sleep-timer notu | `WAKE_ONLY` / `MEDIA_KEYS_PASS`, decompile ile desteklenen UI/control-level `VolumeLimitState` ve opaque sleep-setting lifetime modeli mevcut; fiziksel button mapping, DAC ölçeği, timer unit/countdown ve volume setting storage layout beklemede |
| ASRC/EOF | `music.ap` mode-3 advance branch'i, community/V1.101.11 mode-4 delta'sı | mode-3/mode-4 control-flow boundary modeli mevcut; gerçek ASRC/DAC drain ve audible tail ölçümü beklemede |
| `mmm_bp` ring/fade | altı corpus'ta ortak ring/refill ve fade kontrol akışı | wrap-aware source-byte read ve doğrulanmış fade aritmetiği mevcut; DSP/callback semantiği beklemede |
| Metadata reader | community/V1.101.11 0x200 buffer boundary loop'u ve bounded caller'lar | boundary-split source-byte read ve trailing-skip aritmetiği mevcut; source/skip callback ABI'si ve metadata parser beklemede |
| Artwork fallback | community JPEG fallback'in 0xff yolu, 150x150 RGB565 cache sınırı, component/block guard'ları ve cihazdaki sabit 250-sektör `ALBUM.PIC` container'ı | bounded fallback policy, `OK`/`NO` cache record modeli ve ayrı `ALBUM.PIC` container decoder mevcut; JPEG decode, SD/CPU timing ve UI render beklemede |
| Decoder callback | community callback kesin `-1` döndürüyor | fail-closed boundary mevcut; PCM decoder yolu yok |
| Artwork playback | `FUN_40154ddc` koşullu dalında artwork wrapper'ı, `0x311 PLAY` wrapper'ından önce çalışabiliyor; gerçek p-code koşusunda ayrı event/player flag'leriyle sıra doğrulandı | flag producer/ilk-play ilişkisi, gerçek decode süresi ve ses çıktısına etkisi hâlâ bilinmiyor; tam çağrı-tetikleyici zinciri gerekli |
| LCD transfer | `drv_lcd.drv` address-window commands `0x2a/0x2b/0x2c` | bounded RGB565 window-to-framebuffer sink and real-code QEMU window/orientation probe; controller timing, MMIO meanings and electrical behavior remain unresolved |
| YQHX header/materializer | kernel `yqhx` header, segment ve BSS alanları | sparse host memory applies declared segments and BSS clearing; gerçek resident loader, entry execution ve fixed-ROM handoff remain unresolved |

## MUSIC.LIB TODO

- Exact device sort/tie-break policy'yi, aynı görünen metadata anahtarlarını
  farklı firmware sürümleri veya kontrollü kart görüntüleriyle karşılaştır.
- `tools/library/edit_library.py` yalnızca offline aday üretir: değişikliksiz
  düzenleme gerçek M30 görüntüsünü byte-byte kopyalar; metadata değişikliğinde
  mevcut section sıralarını tie-break olarak korur ama yeniden kurulan düğüm ve
  grup tabloları henüz cihazla eşdeğer kabul edilmez. Başlık, sanatçı, albüm ve
  tür alanları taşma/NUL kontrolüyle düzenlenir; location/link tail korunur ve
  dört section yapısı doğrulanır. Çıktı `libdiff.py` ile incelenmeden flash
  adayı değildir.
- `0xa9..0xf1` primary location blob'unu source path/cluster değişimleriyle
  çöz; decoded `cluster_no`/`dir_entry` çiftini gerçek directory dump ile
  bağımsız doğrula.
- `0xfc..0xff` record predecessor/successor alanlarının gerçekten hangi
  consumer tarafından okunduğunu trace et.
- Reference `5` node/group table'ının local writer'daki deepest-level index
  davranışını gerçek multi-group M30 görüntüsüyle doğrula; level-zero artist
  group scratch akışını ayrıca takip et.

## Bilinçli olarak ertelenenler

- Tam ATJ2127 boot ve upstream QEMU machine model.
- Gerçek LCD, DAC, codec/DSP ve DMA davranışı.
- Filename natural sort için caller-side mask ve browser state modeli.
- Persistent play counter; MUSIC.LIB içinde güvenli boş alan yok. 8-byte
  `cluster_no`/`dir_entry` anahtarı sıralamada kalır ama taşınabilir dosya kimliği
  değil; sidecar için digest + normalize edilmiş yol düşünülüyor. İlk journal
  prototipinde bozuk `MOVE` atlanınca sonraki `SET` eski yolu yeniden
  gösterebiliyor; fail-closed recovery düzeltilip doğrulanana kadar güvenli
  sayılmayacak.
- Yeni EQ preset'leri: mevcut selector zinciri `0..6` built-in ve `7` Custom olarak kanıtlandı; 7×7 katsayı bloğu yerel stok/resmî/topluluk kopyalarında aynıdır. Yeni seçim için AP callback/menü ağacı ve engine katsayı eşlemesini genişletmek gerekir. Katsayı tablosu sonrasındaki sıfırlar boş patch alanı sayılmaz; duyulur sonuç ve güvenli paketleme kanıtı yok.
- Gerçek gapless playback ve ASRC/DAC audible tail ölçümü.

Her yeni satır için önce ilgili `findings-*.md` dosyası güncellenir; ardından
yalnızca doğrulanmış bölüm testli host modeline alınır.

## Kanıt gelmeden teslim edilemeyecek sonuçlar

Bu çalışma ağacındaki mevcut binary, QEMU ve host kanıtı aşağıdaki sonuçları
güvenilir biçimde üretemez. Bunlar eksik bir host modeliyle tamamlanacak işler
değil; yeni firmware corpus'u, donanım gözlemi veya cihaz testi gerektiren
sınırlardır:

- M30 firmware'ini gerçekten boot eden tam ATJ2127/QEMU makinesi ve overlay
  loader'ı;
- decoder, DSP, ASRC, DAC, codec register'ları ve audible fade/gapless playback
  davranışı;
- fiziksel ADC tuşlarının gerçek isim, debounce ve repeat zamanlaması ile LCD
  panel/elektriksel aktarım davranışı;
- MCG/RU3 localized name ve node-to-application eşlemesinin kesin anlamı;
- eksik resident/provider bankalarının gerçek çağrı ABI'leri ve bunların
  çalıştırılması;
- cihaz üzerinde boot, playback, tuş, ekran ve güvenli firmware release
  kabulü;
- firmware, resource dump, decompile veya türetilmiş patch'lerin public
  dağıtımı için hukuki/telif temizliği.

Bu sınırlar çözülmüş gibi görünmesi için host modeline varsayımsal davranış
eklenmez. Yeni kanıt geldiğinde önce ilgili bulgu belgesi, sonra fail-closed
model ve test güncellenir.
