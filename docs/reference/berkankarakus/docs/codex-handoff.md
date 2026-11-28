# M30 çalışma checkpoint'i

Bu belge yalnızca güncel çalışma durumunu ve değişmez güvenlik kurallarını
tutar. Ayrıntılı kanıtlar ilgili `findings-*.md` belgelerinde, emulator
kapsamı [`qemu-coverage.md`](qemu-coverage.md) içinde tutulur.

## Güncel durum — 2026-11-27

Bu tarihte çalışma dalı `main`; aşağıdaki 2026-11-25 dal/worktree bilgisi
tarihsel checkpoint'tir.

| Konu | Düzey | Bugünkü sınır / sonraki kanıt |
| --- | --- | --- |
| Custom slider → RAM kaynağı → `{7,7}` mesajı | VERIFIED (sentetik servisli p-code) | Gerçek DSP ses etkisi için cihaz gözlemi gerekir. |
| Config varsayılanları → EQ builder → aud_dev | VERIFIED (ayrı MIPS16 p-code probe'ları, sentetik servislerle) | Off ve Custom(0) için 16 bant aynı; çıkış kazancı alanı Off `00000000000000000000`, Custom(0) `fbffffff020000001e00`. Ön ayar selector 1–6'nın her biri farklı bant çıktısı verdi. Tek bir ortak SHM örneğinde uçtan uca koşulmadı; ses etkisi için cihaz gözlemi gerekir. |
| Config öğesi `0x96` → state `+0x18` → DAE `+2/+3` → op `0x47`, id 3 çağrısı | VERIFIED (ayrı MIPS16 p-code probe'ları, servisler sentetik) | `tools/ghidra/run_config_item96_shm_probe.sh` ve `run_config_to_mengine_dae_chain_probe.sh` PASS: öğe 0/1/2 için DAE ve MEngine yerel yapı `+2/+3` sırasıyla `00/01`, `01/00`, `00/01`; builder bu baytları koruyor. Kernel op `0x47` işaretçiyi kaydediyor, `0x49` aynı işaretçiyi döndürüyor. ROM dağıtıcısı ve flash servisleri sentetik/INFERENCE; gerçek kalıcılık UNKNOWN. |
| Kalıcı state `+0x18/+0x19` yazarları | BOUNDED VERIFIED / UNKNOWN | `run_persistent_dae_writers_probe.sh` 2.300 işlevi sıfır decompile hatasıyla taradı. `+0x19` yazarları music/mainmenu/MEngine'de bulundu; id `0xf0000` için config'te iki, MEngine ilk kurulumunda bir op-`0x13` çağrısı doğrulandı. Başlatıcı sonrası hedef blok `+0x18` yazarı bulunmadı; dolaylı/whole-block ve taranmayan kod yolları UNKNOWN. [`findings-persistent-dae-writers.md`](findings-persistent-dae-writers.md). |
| MUSIC.LIB seedsiz stock MIPS16 koşusu | VERIFIED (QEMU; sentetik metin servisi) | 2026-11-19 koşusu repoda kayıtlı: ALLSONG 3963/4000; diğer indeks sayımlarıyla birlikte ayrıntılar [`findings-music-lib-node-format.md`](findings-music-lib-node-format.md) içinde. Bu, gerçek ROM dönüştürücüyü tanımlamaz. |
| EQ slider azaltma sınırı | VERIFIED (sentetik UI servisli MIPS16 p-code) | `tools/ghidra/run_mainmenu_eq_slider_probe.sh` PASS: SLTI için MIPS16e işaret genişletmesi override edilerek `−3` tabanı ölçüldü; test girdileri `−3…+3`. Fiziksel tuş ABI'si sentetik. |
| aud_dev tablo indeksi 18, örnekleme hızı sınırı | VERIFIED (sentetik kopya servisli p-code) | `tools/ghidra/run_auddev_idx18_rate_probe.sh` altı koşuda PASS: 48.000 Hz'de iç EQ bayrağı `cfg+0x3a=1`, 48.001/96.000 Hz'de `0`; her hızda 16 bant yine kopyalanır. DSP ses etkisi UNKNOWN. |
| aud_dev `cfg+0x40` DRC bayrağı | VERIFIED (sentetik kopya servisli p-code) | `tools/ghidra/run_auddev_idx18_drc_probe.sh` altı koşuda PASS: `dae_cfg+0xc=1` iken 48.000 Hz'de `cfg+0x40=1` kalır; 48.001/96.000 Hz'de hız dalı sıfırlar. `+0xc=0` bütün hızlarda alanı 0'a kopyalar. 16 EQ bandı her koşuda kopyalanır. |
| Config `0x20000` bloğu geçersizken `0x2f0000` bayrağının set edilmesi | VERIFIED (sentetik kayıtlı p-code) | Stock/community ikisinde de `0x10000` bayrağı bir kez yazıldı. Stock gönderici bayrak setken `{0x201,10,0x4e}`, community `{0x201,9,0x0e}` gönderdi. `tools/ghidra/run_config_boot_flag_probe.sh` PASS; cihazdaki upgrade etkisi için cihaz gözlemi gerekir. |
| Kart değişiminin `0xb0 → 0x861c → playlist.ap` yolunu tetiklemesi | INFERENCE | `0xb0` üreticisi bulunmadı; ROM/çekirdek gövdesi veya cihaz olay izi gerekir. |
| `0xb0 → 0x861c → {0x201, app 10, param}` AP zinciri | VERIFIED (sentetik servisli p-code) | `tools/ghidra/run_music_b0_app10_handoff_probe.sh` PASS: durum `0x10/0x07/0x00` için param `0x42/0x82/0xc2`. Mesaj üreticisi ve cihazdaki indeksleme etkisi UNKNOWN. |
| MUSIC.LIB `TERMINAL_OOR_ZERO` ile iki 4.000 kayıtlı kütüphanenin indeks tabloları | VERIFIED (stock MIPS16 + sentetik dönüştürücü) | Gerçek dönüştürücü kuralı UNKNOWN; gövde için ROM gerekir. Seedsiz koşu aşağıda zaten kayıtlıdır. |
| `0xb0` mesajının üreticisi ve kart takma bağlantısı | UNKNOWN | ROM/çekirdek gövdesi veya cihaz olay izi gerekir. |
| Off / Custom(0) farkının duyulması | UNKNOWN | Cihaz gözlemi gerekir; p-code yalnızca aud_dev'e giden baytları ölçebilir. |
| DSP'nin filtreleri ses çıkışına uygulaması | UNKNOWN | Cihaz gözlemi veya DSP/donanım içeriği gerekir. |
| `mmm_pp.al` `FUN_68013258` DSP dosya adı seçimi | VERIFIED (MIPS16 p-code; açma servisi sentetik) | `tools/ghidra/run_mmm_pp_dsp_select_probe.sh` PASS: SHM `+2=0` → `adppext.dsp`, sıfır dışı → `audiopp.dsp`; iki dalda ilk açılış `adWAVH.dsp`. Gerçek dosya yükleme etkisi ROM/kernel gerektirir. |
| `kernel.drv` slot `0x0f` işlem tablosu (77 giriş, dosya `0x200`) | VERIFIED (dosya baytları); ROM dağıtıcıya bağlantı INFERENCE | 29 ROM, 34 bankalı, 11 resident, 2 segment dışı (`VM_READ/VM_WRITE` `0xbfc19bad/cbd`), 1 boşluk. İlk 67 giriş PD196 `kernel_cmd_e` sırasıyla uyuşuyor (SDK yalnızca ipucu). Slot `0x10/0x11` için sağlayıcı yok. Ayrıntı: [`findings-kernel-op-table.md`](findings-kernel-op-table.md), model `tools/sim/kernel_ops.py`. |
| `kernel.drv` op 0..3 yükleyicileri | VERIFIED (decompile); ROM yardımcıları INFERENCE | Op 0 (`0x9408`) gerçek `yqhx` AP yükleyicisi: ada göre açar, `0x4c` baytlık başlıktan dört `{ofset, uzunluk, hedef}` kaydını kopyalar, BSS'i siler, `+0x40` girişini çağırır. Op 3 SDK'daki `FREE_AP` değil, görüntü yükler (SDK adları ipucu). `{0x201, appId, param}` mesajının `(ad, slot, param)`'a nasıl döndüğü UNKNOWN. |
| Op `0x47/0x48/0x49` SHM kaydı, `0x4a` görev oluşturma | VERIFIED (kernel decompile + p-code, IRQ kilitleri sentetik); dağıtıcı bağı INFERENCE | `0x47` işaretçiyi kopyalamadan 8 girişli tabloya yazar, `0x49` aynı işaretçiyi döndürür; config, MEngine ve `mmm_mp.al` id 3 üzerinden aynı işaretçide buluşur. Host break-0 sınırı hash-kilitli `kernel.drv` tablosunu kullanır: SHM işlemlerini modeller, 29 ROM girişini `synthetic=rom-zero`, bankalı/resident girişleri gerçek hedef bilgisiyle kaydeder; `0x4a` kaydını yalnız çağıranın verdiği baytlardan çözer ve çalıştırmaz. |
| MEngine builder kapısı, `_play` sırası ve `music.ap` başlangıç EQ gönderimi | VERIFIED (statik decompile) | Builder hem `0x303` işleyicisinden hem her `_play`'den (`AOUT_OPEN`/`PLAY` öncesi) çalışır; kapısı `eq_type` değil, kalıcı ayar bloğunun MEngine kopyasındaki `+0x18` baytı (`0x9fc15db4`; config'in öğe `0x96` ile doldurduğu bayt) ve yerel `+0x0c != 1`. Off de bayrak açıkken kopyalar (önceki 'Off yazmaz' notu yanlıştı). Kopyadan sonra mmm_mp komut `0x1f` aud_dev'e gider. `music.ap` `FUN_40094dd4` kayıtlı seçiciyi dosya/oynat isteklerinden önce gönderir (`0x9fc1a1c8` baytı 1 değilse). Model: `tools/sim/dae_shm_flow.py`. |
| İlk çalmada hangi DSP'nin açılacağı | UNKNOWN (dosya adı isteği VERIFIED, zincir INFERENCE) | `tools/ghidra/run_mmm_mp_dsp_start_probe.sh` PASS: gerçek `mmm_mp.al` `FUN_68312d00` p-code'u SHM `+2=0` için dört hızda `adppext.dsp`, `+2=1` için 44100/48000 Hz'de `adppdrc.dsp`, 48001/96000 Hz'de `audiopp.dsp` açma isteği yaptı. Dosya açma, doldurma, RAM ve MMIO sentetik; gerçek yükleme ve ses etkisi UNKNOWN. `config.ap` `FUN_40054f38` `+2/+3` yamalı DAE görüntüsünü ve ayar bloğunu flash'a yazıyor (ids `0xf0000`, `0`), MEngine `FUN_60050d00` aynı ids'i okuyor; builder kapısı = kalıcı ayar bloğu `+0x18`. Varsayılanda kapı açık, yerel `+2=1`, kopya `+2`'yi koruyor (ayrı p-code oturumu). `mmm_pp.al` aynı adreste duran sadeleşmiş varyant. ROM flash servisleri ve araya giren başka yazar bilinmiyor. |
| Config kaydı → MEngine flash okuması → builder zinciri | VERIFIED (iki ayrı MIPS16 p-code oturumu; flash, config okuyucusu, kopya ve komut stub'ları sentetik) | `tools/ghidra/run_config_to_mengine_dae_chain_probe.sh` PASS: öğe `0x96` = 0/1/2 için kaydedilen `dae+2/+3` = `0/1`, `1/0`, `0/1`; MEngine yerel yapısı aynısını okuyor; builder yalnızca ayar kopyası `+0x18 != 0` iken gövdesini çalıştırıyor (öğe 0'da atlanıyor), `+2/+3`'ü hiçbir zaman yazmıyor. DSP adı kuralı uygulanınca: 0 ve 2 → `adppext.dsp`, 1 → `adppdrc.dsp` (≤48 kHz) / `audiopp.dsp`. `mmm_mp.al` `FUN_68312d00` ve gerçek flash/ROM bu koşuda yok. |
| `driver_banks.py` ve banka tablosu biçimi | VERIFIED (dosya baytları için); donanım yükleyicisi UNKNOWN | `kernel.drv`/`aud_dev.drv` tabloları küçük-endian `{boyut nibble, başlangıç}` birim `0x100` olarak ardışık ve dosya sonuna oturuyor (`tools/sim/driver_bank_table.py`). Gerçek `drv_ui.drv` B2 işaretçisi `0x26089471` mevcut büyük-endian çift çözücüsüyle dosya `0x8af1` (kod), birim çözücüsüyle `0x7a71` (sıfır) verir. UI B3 pool'u da yalnız mevcut eşlemeyle B3'e düşer. Biçimler bu gözlemle ayrı tutuldu; donanım yükleme kuralı ölçülmedi. |
| PLAY sırası ve SHM işaretçisi | VERIFIED (ayrı MIPS16 p-code probe'ları, servisler sentetik) | `run_mengine_play_order_probe.sh`: gerçek `_play` + builder kodu `SHM 0xf0 kopya → id 3 attach → komut 0x1f → komut 0x02 → komut 0x19` sırasını verdi. `run_mmm_mp_dsp_start_probe.sh`: mmm_mp komut 0x1f, SHM hücresindeki işaretçiyi aud_dev `(1,ptr,0,0x12)` çağrısına verdi; komut 0x19 gerçek PLAY işleyicisine, o da DSP başlatıcısına ulaştı. İki AP ayrı p-code oturumunda çalıştı; ortak SHM hücresi, ROM/kerneli, kopya ve dosya açma servisleri sentetik. Cihazda yükleme ve ses etkisi UNKNOWN. |
| MEngine `_play` ön çağrısı `0x60b50f19` | VERIFIED (başlık tablosu, dosya baytı, decompile) | Hedef dışarıda değil: `mengine.ap` başlık `0x31c` girdisi dosya `0xe100..0xe900` bölümünü `0x60b50d00` bankasına koyuyor; `FUN_60b50f18` dosya `0xe318`'de. İşlev `(1,3,callback)` çağrısında durum bayrağını güncelleyip geri çağrıyı çalıştırabiliyor. PLAY sırası probe'u bu çağrıyı sentetik başarı dönüşüyle geçti; gerçek yan etkileri hâlâ UNKNOWN. |
| `music.ap` oynatma durumu baytı ve `0x204` | VERIFIED gönderici, UNKNOWN üretici/alıcı | `FUN_40094d10` `0x9fc1a1c8` baytını okuyor: değer 2 ise `0x204` mesajı isteniyor; 1 değilse kayıtlı EQ seçicisi ve ses gönderiliyor. Bu baytın yazarı ve `0x204` alıcısı bulunmadı. `FUN_40614f30`'un `0x304` mesajı kurduğu önceki `findings-asrc-drain.md` p-code bulgusudur; çalma kipi baytının anlamı hâlâ INFERENCE. Literal birçok AP'nin ortak bankalarında var, tek başına yazar kanıtı değil. |
| `mmm_mp.al` ve `mmm_pp.al` seçimi | VERIFIED ad isteği, UNKNOWN ikinci modülün kullanımı | Her iki `.al` stock/community korpusta mevcut. MEngine `FUN_60051278` `mmm_mp.al` adını resident yükleyiciye veriyor (statik decompile/literal); başarıyla yüklendiği INFERENCE. Taranan üye dosyalarında `mmm_pp.al` adını doğrudan veren kod bulunmadı; ölü kod olduğu çıkarılamaz. [`findings-mmm-module-selection.md`](findings-mmm-module-selection.md). |
| Host EQ/DAE oynatma oturumu | VERIFIED model bileşimi; fiziksel etkiler UNKNOWN | `FirmwareEqAudioSession` slider editini builder/SHM ile birleştiriyor, `music_start` kayıtlı seçiciyi uyguluyor, `play(rate)` `mmm_mp` DSP adını ve ölçülmüş `aud_dev` `cfg+0x3a/+0x28/+0x40` durumunu çıkarıyor. İptal UI değerlerini geri alırken son önizleme SHM'de kalıyor. Busy giriş, speaker compensation ve gain-adjustment yolları fail-closed; ROM, DSP ve ses etkisi çalıştırılmıyor. `M30PreviewSession` bu izi JSON-safe olarak yayımlıyor. |

Bu tablo kanıtların sınırını gösterir. `VERIFIED` satırları belirtilen statik
veya sentetik koşu kapsamındadır; gerçek cihaz davranışı anlamına gelmez.

### Önceki checkpoint — 2026-11-25

- Dal: `codex/fix-model-coverage`; linked worktree'nin Git deposu
  `/Users/berkankarakus/Projects/mechen-m30-firmware/.git` ile ortak. Bu turdaki
  p-code commit'leri `1c19b59` (scroll import-15 op-`0x31` çağrı ABI'si) ve
  `b96f78a` (kernel ROM copy yönü kontrolü). Bu handoff güncellemesi
  commit'lendikten sonra çalışma ağacı temiz olmalı; push yapılmadı. Yeni
  worktree açılırsa `git status --short --branch` ve
  `git log --oneline origin/main..HEAD` ile teyit et.
- Cihaza firmware, ROM payload veya başka veri yazılmadı.
- Son p-code çalışmaları ROM gövdesi yerine gerçek caller davranışını ölçüyor:
  `music.ap` scroll-next yolu import `0x0f`, selector `0x31`'e
  `(1,0,0,0x31)` ile ulaşıyor; aynı selector'ün playlist hata/yeniden deneme
  bağlamında `a0=0x23` kullanımı ve public `actions_flash` notundaki
  `sleep_ms(a0)` eşlemesi M30 için güçlü ama doğrulanmamış bir hipotez.
  Playlist op-9 çağrısı `(1,0,0,9)` için `0`/`-1` dönüşleri sınandı;
  US212D notundaki “Close Driver / File” etiketi M30'a aktarılmış dış kaynak
  hipotezi olarak kalıyor.
- `ker_init.bin` `FUN_bfc322f8` p-code probe'u için `forward` modunda gerçek
  caller'ın kaynak `0x9fc12000`'dan hedef `0x9fc1aad8`'e sentetik kopyayla
  callback'e ulaştığı, `reverse` kontrolünde hedef sıfır kalıp null indirect
  target'a gittiği doğrulandı. Bu, caller'ın kopya yönünü gerektirdiğini
  gösterir; `0xbfc04635` ROM gövdesinin kopyalama yaptığı kanıtı değildir.
  Ayrıntı: [`findings-kernel-loader.md`](findings-kernel-loader.md) ve
  [`findings-qemu-dispatch.md`](findings-qemu-dispatch.md).
- Aynı gerçek caller üzerinde `0xbfc045f5` clear adayı için sentetik
  zero-fill/no-effect karşılaştırması stock ve community `ker_init.bin`'de
  geçti. Gerçek çağrı `(0x9fc1a48c,0,0x7f4)`; iki model de aynı callback
  yoluna ulaşıyor. Zero-fill modeli aralıktaki her 32-bit sözcüğü sıfırlayıp
  exclusive-end canary'sini koruyor; no-effect modeli sentinel'leri koruyor.
  Sentetik dönüş değerini `0`, hedef adres ve `0xdeadbeef` yapınca da
  community caller aynı callback'e aynı argümanlarla ulaşıyor; stock'ta
  sentinel dönüşü tekrarlandı. Bu, yalnızca sınanan caller devam yolunda
  dönüşe bağlı kontrol akışı veya callback argümanı değişikliği
  gözlenmediğini gösterir.
  ROM gövdesinin gerçek etkisini veya açılış gerekliliğini çözmez. Sabit ROM
  dump'ı yok; ADFU okuma yolu yalnızca kaynakta incelendi, cihazda denenmedi.
- `FUN_bfc322f8` callback'ini sentetik no-op dönüşle geçince gerçek extracted
  caller `0xbfc323d4`'ten devam edip ikinci clear çağrısına
  `(0x13d00,0,0xe0)` ile ulaşıyor. Bu aralıkta exception vector'ın yazdığı
  `0x13dcc/0x13dd0/0x13dd4` sayısal adresleri var. Zero-fill/no-effect
  sentinel modelleri ve final `v0` dönüşü community'de; no-effect/sentinel
  kontrolü stock'ta geçti. Bu adres çakışması fiziksel RAM alias'ını,
  cihazdaki gerçek clear etkisini veya boot sırasını kanıtlamaz. Aralık
  taraması `ker_xsys.bin` içindeki `FUN_bfc344f4`'ün `0x13dcc..0x13ddc`
  aralığından beş sözcük okuyup her birini `exc:` descriptor'ıyla bir ROM
  callback'ine verdiğini gösterdi. Ghidra p-code bunu sentetik callback ve
  canary'lerle doğruladı. Aynı prob iki yolu da çalıştırdı: `0x9fc1aa5c`
  bit 1 clear → MMIO adresine `0x5d`; set → `0x69`. Peripheral rolü/etkisi ve
  missing fixed-ROM handler'la bağlantısı kanıtlanmadı. `FUN_bfc3416c` guard
  dalı da sentetik ROM dönüşleriyle gerçek p-code'da çalıştırıldı; iki tanı
  callback'inin argümanları, flag temizliği ve default-handler sınırı geçti.
  Guard sözcüğü eşleşirken mesafe `0x93` hata, `0x94` normal dönüş verdi;
  `0x180` mesafede sentinel uyuşmazlığı da hata dalına girdi. ROM servislerinin
  doğal dönüşleri ve bu fonksiyonlara gerçek caller bağı bilinmiyor.
  Ayrı `FUN_bfc340a4` p-code profili gerçek alternatif stack selector'ını
  doğruladı: `table=0x12000` için `sp&0x3ffff` değerleri `0x11fff` ve
  `0x12000` alternate stack'e, `0x12001` doğrudan ROM sınırına gidiyor.
  Alternate yolda 184-byte slot ayrılıyor, eski SP kaydediliyor ve iki sayaç
  artıyor; `0xbfc05f90` handler gövdesi çalıştırılmıyor. Aynı selector'ın
  gerçek MIPS32 talimatları QEMU Malta 24Kf/34Kf'de de geçti; index-0 sınır
  profilleri ve farklı ikinci tablo girdisini ayırt eden index-1 profili SP,
  sayaç ve canary kontrolleriyle doğrulandı. Yazılabilir operandlar RAM'e
  yönlendirildi, sabit-ROM hedefi yalnız `jr ra` stub'ı, tablo eşikleri sentetik.
  QEMU aynı zamanda `0xbfc34074` girişini doğrudan çağırdı: CP0 EPC ve dönüş
  RA'sı TLB ile RAM'e eşlenen `0x13dd8/0x13ddc` alanlarına yazıldı; `0xbfc06d6c`
  yalnız dönüş stub'ı. Bu doğal exception/caller veya consumer bağlantısını
  kanıtlamıyor.
  Daha güçlü birleşik Malta koşusunda gerçek `break 0` olayı kernel vector'ını
  çalıştırdı; slot 9'daki sentetik `j 0xbfc34074` bridge'i gerçek xsys girişine
  aktardı. Beş frame sözcüğü ve çevre canary'leri 24Kf/34Kf'de doğrulandı.
  Gerçek ROM handoff'u veya boot sırası kanıtlanmış değil.
  Callback bir diagnostic-format/emission helper adayı, çıkış hedefi bilinmiyor.
  Yerel tarihli firmware kopyalarında `ker_xsys.bin` byte-identical;
  raw literal taraması iki fonksiyonun doğrudan entry pointer'ını göstermedi.
  Sonraki adım missing
  fixed-ROM exception caller edge'ini mevcut dispatch ile eşleştirmek; bunu
  kanıtlamadan varsayımsal call edge eklememek.
- `bash tools/emu/run_static_suite.sh --host-only` 2026-11-24'te geçti:
  toplam 563 test, 3 skip. Tam `bash tools/emu/run_static_suite.sh` bu
  checkpoint'te çalıştırılmadı; bu yüzden merge edilmiş yeni Ghidra/QEMU
  probe'larının tümü için tam-suite pass iddiası yok.
- Tam suite; Ghidra driver/kernel, gerçek MIPS16 scroll/navigation ve altı
  firmware corpus'u üzerindeki host kontrollerini kapsar. QEMU leaf/chain
  probe'ları ayrıca çalıştırılır; bunlar tam ATJ2127 boot iddiası değildir.
- Loader sınırı: `kernel.drv` içindeki `FUN_bfc0a408`/`FUN_bfc0a578`
  adaylarının loader değil resident-table yardımcıları olduğu doğrulandı.
  `yqhx.py` ve sparse materializer yalnızca header/segment/BSS sözleşmesini
  modeller.
- `analysis/community/dec.bin` kritik extracted üyeleri birer kez içerir ve
  `0xbfc06d6c` handler işaretçisi içermez. `tools/emu/dec_container_probe.py`
  bu sınırı kontrol eder; eksik fixed-ROM handler'ı mevcut dump'tan çıkarılamaz.
- `tools/emu/kernel_syscall_scope_probe.py`, `ker_text.bin`, `ker_init.bin`
  ve `kernel.drv` içinde sıfır MIPS16 `break` noktası doğrular. Selector
  dispatcher'ı bu extracted kernel üyelerinde yoktur; `break 0` etkisi fixed
  ROM sınırında kalır.
- Upgrade sınırı da artık on iki dated `upgrade.ap` imajında tekrarlandı:
  `0x40014c30` entry, beş iterasyonlu screen loop'u ve `upgrade.sty`/
  `common.sty` resource-name literal'ları bütün corpus'ta aynı kaldı.
  `tools/ghidra/run_upgrade_ap_corpus_probe.sh` bunu geçici Ghidra çıktısını
  saklamadan yeniden üretir. `tools/emu/upgrade_flow.py` selector ve
  decrypt/search olaylarını trace-only taşır; fixed-ROM anlamı çözülmeden
  flash/reboot/success iddiası üretmez.
- `tools/sim/usound_callback.py` `break 0` zarfındaki modül kimliğini ve dört
  argümanı kaydeder. `0x10` libc modülü, `0x0f` çekirdek modülüdür; servis
  gövdeleri/etkileri host modelinde çalıştırılmaz.
- Yeni ses/indeks bulgularının ayrıntısı [`findings-auddev-driver.md`](findings-auddev-driver.md),
  [`findings-eq-usound.md`](findings-eq-usound.md),
  [`findings-music-lib-node-format.md`](findings-music-lib-node-format.md) ve
  [`findings-upgrade-flow.md`](findings-upgrade-flow.md)'te: YQHX dört segmenti
  parse edilir; stock/community EQ producer→MEngine builder zinciri iki hash
  eşleşmesinde p-code ile yürütüldü, fakat ROM copy-service etkisi sentetik;
  `usound`/`aud_dev` için `table[op]` hâlâ inference. MUSIC.LIB'de bir terminal
  converter adayı tek cihaz kütüphanesinin indeks tablolarını eşleştiriyor,
  ikinci public örneğe genellenmiyor.
- EQ Custom slider kodu artık community `mainmenu.ap` içinde bulundu:
  `FUN_40154f44` artış olayıyla p-code'da çalıştı ve `{7,7}` producer mesajına
  ulaştı; servisler sentetik. Ek disassembly raporu state pointer tablosunun
  selector/editor'e ulaştığını ve `0x38` bloğun yalnızca AP çıkışında
  kalıcılaştırıldığını bildiriyor. State-1 giriş öğesi bilinmiyor; iptal
  motoru yeniden güncellemeden yerel yedeği geri yüklüyor; üç yollu yerel
  p-code koşusu da RAM tamponlarının ayrışmasını doğruladı. Azaltma taraması
  sentetik `0x2904` için başlangıç `-3`'te no-op, `-2..+3`'te doğrudan `-3`
  gösterdi. Event ABI çıkarım olduğundan cihaz tuş semantiği açık.
  Ayrıntı ve tekrar üretim: [`findings-eq-selector-writer.md`](findings-eq-selector-writer.md)
  ve [`p-code çıktısı`](evidence/mainmenu-eq-slider-pcode-2026-11-24/README.md).
- `config.ap` `FUN_40dd4d60` factory band kayıtlarını `config.bin` öğe
  `0x86..0x95` içinden `dae_cfg` 16-band alanına kuruyor. EQ builder çağrısı
  bu 16 alanı yeniden yazar ve son dokuz bandı sıfırlar; FullSound/hoparlör
  telafisi olduğu veya açılışta duyulduğu kanıtlanmadı. Hash ve decode:
  [`config dae_cfg kanıtı`](evidence/config-dae-factory-2026-11-24/README.md).
- Config item `0x96` seeds DAE byte `+2`; config saves that DAE image and
  MEngine reads the same record before its builder copies it to id-3 SHM
  (p-code with synthetic flash/copy services). The music path requests
  `mmm_mp.al`: `+2=0` selects `adppext.dsp`, while nonzero selects
  `adppdrc.dsp` through 48 kHz and `audiopp.dsp` above it. Item `0x98` maps
  to builder guard `+0x0c` (default zero). Actual persistence, module loading,
  DSP role and audible effect remain unproven.
- DAC caller taraması da tekrar üretilebilir durumda:
  `tools/emu/dac_caller_scan.py`. Doğrudan helper pointer'ları yalnızca
  sürücü/container kopyalarında bulunur; codec register anlamı çözülmüş
  değildir.
- Başka bilgisayara geçiş için [`portable-handoff.md`](portable-handoff.md)
  ve `python3 tools/verify_persistent_evidence.py <evidence-root>` kullanılır;
  firmware, cihaz SD dosyaları ve Ghidra çıktıları Git dışında kalıcı kanıt
  kökünde tutulur.

## Önceki belgelerdeki düzeltmeler ve kanıt kökeni

- **Previous yaması parçayı başa döndürmeyi kanıtlamıyor.** `mengine.ap`
  içindeki `0x3a64` sabiti, `_play` fonksiyonunun hızlı geri sarma yolunda
  `bp_time_offset = -5000` olarak kullanılıyor; Previous mesajı `0x319` için
  motor handler'ında süre eşiği yok. `tools/patch_prev_restart.py` bu sabiti
  sıfırlasa da gerçek "Previous = başa sar" davranışını sağlamaz ve önceki
  parçanın sonuna yakın bir noktadan başlama/parça atlama riski taşır. Güvenli
  aday kapsamına alma. Ayrıntı: [`findings-artwork-start.md`](findings-artwork-start.md)
  §3 ve [`community-integration.md`](community-integration.md).
- **UTF-16 satır sonu:** `drv_ui.drv::FUN_300007c4`, `0x0000` değerini EOF,
  `0xfeff` değerini Unicode bayrağı, `0x000a` değerini LF ve `0x000d` ile
  `0x000d 0x000a` dizilerini CR/CRLF satır sonu olarak sınıflandırıyor.
  Host hizalama modeli `tools/sim/text_alignment.py` ve testleriyle bu
  davranışı kapsıyor; bu, firmware yaması veya cihaz testi değildir.
  Ayrıntı: [`discovery-environment.md`](discovery-environment.md).
- **İsim slotu:** SDK'daki 52 baytlık sabit, M30 browser'ın kullandığı 50
  baytlık slotla karıştırılmamalı. İsim, kayan yazı taramasından önce kesiliyor;
  slotu kaynağını anlamadan büyütmek güvenli değil. Ayrıntı:
  [`findings-browser-names-sort.md`](findings-browser-names-sort.md).
- **Artwork gecikmesi:** gözlenen artwork decode'u PLAY yanıtından sonra UI
  thread'inde çalışıyor. Bu nedenle UI'nin donması ile sesin başlama gecikmesi
  aynı iddia değildir; süre/CPU etkisi ölçülmedi. Ayrıntı:
  [`findings-artwork-start.md`](findings-artwork-start.md).

### Tekrarlanabilir test girişi

Tam host-only doğrulama için kanonik komut:

```sh
bash tools/emu/run_static_suite.sh --host-only
```

Komut `tools/sim`, `tools/emu` ve `tools/rom` testlerini çalıştırır ve başarılı
olduğunda `STATIC SUITE PASS host-only` yazar. Tam statik suite farklı
firmware corpus'ları ve Ghidra gerektirir. Bazı host testleri yerel
`common.sty`/RU3 fixture'ları olmadığında skip olur; skip sayısını test
çıktısından kontrol et.

Tek tek Python paketlerini çalıştırırken repo kökünü ve iki araç dizinini
import yolunda tut; `-I` kullanma, çünkü Python isolated mode `PYTHONPATH`'i
yok sayar:

```sh
PYTHONPATH=.:tools/emu:tools/sim python3 -m unittest discover \
  -s tools/emu -p 'test_*.py'
PYTHONPATH=.:tools/emu:tools/sim python3 -m unittest discover \
  -s tools/sim -p 'test_*.py'
```

2026-11-17 host-only suite çalıştırmasında sim için 268 test, emu için 229 test
(3 skip) ve ROM için 6 test geçti. Sayılar o çalıştırmaya aittir; sabit suite
sözleşmesi değildir. CI/review kapısı olarak yukarıdaki `run_static_suite.sh`
komutunu tercih et.

### MUSIC.LIB ve playlist.ap girdilerini seçme

- M30 SD kartından alınmış doğrulanmış kütüphane
  `<persistent-evidence-root>/device-sd/MUSIC.LIB` dosyasıdır; boyutu
  2,625,536 bayt, SHA-256'sı
  `e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`.
- Kanıt kökündeki `<persistent-evidence-root>/MUSIC.LIB` başka kaynaktan
  alınmış 1,536,000 baytlık, 251 kayıtlı karşılaştırma örneğidir; SHA-256'sı
  `cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe`.
  Bu dosya M30 dump'ı değildir; cihaz indeksi/record eşleştirmesi için
  `device-sd/MUSIC.LIB` yerine kullanma.
- `playlist.ap` SHA-256 `7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`
  2023-07-26, 2024-03-19 ve 2025-06-26 tarihli extracted kopyalarda aynıdır.
  Bu hash payload'ı doğrular, fakat tarih tek başına firmware sürümünü
  tanımlamaz. Kaynağın tarihini ve tüm firmware imajının hash'ini ayrıca
  kaydet.

## Son dinamik kanıt checkpoint'i

- `9da84ad` ile `FUN_40154ddc` initial paint girişi gerçek MIPS16 p-code'da
  çalıştırıldı; üç firmware ailesinde 7 dispatcher çağrısı ve gerçek return
  sınırı aynı kaldı. `tools/ghidra/run_now_playing_paint_corpus_probe.sh` ile
  bu sınır on iki dated image'ın tamamında tekrarlandı: `0x18c3`/`0x39e7`
  band çağrıları, üç `0x12` clear payload'ı ve son `0x1002` artwork
  payload'ı aynı sırada kaldı.
  Paint wrapper artık Ghidra script error raporunu da başarısızlık sayıyor.
- `3ed9368` ile aynı girişin metadata `0x100`, transport/EQ `0x20` ve status
  `0x200`/`0x800` dalları üç firmware ailesinde gerçek kodla çalıştırıldı.
  Sıra sırasıyla `0x2005`, `0x1004`/`0x1003`, `0x1000`/`0x1001` kontrol
  kimlikleriyle doğrulandı; decoder/DAC çağrısı bu sınırda yok.
- Directory callback matrix probe'u `FUN_71352820` için before/inside/after
  current-index dallarını çalıştırdı. Üç dalda da 0x0c row stride, 0x32-byte
  slot, 25-unit FS çağrısı, mode 2 ve 0x4 converter limiti aynı kaldı; yalnızca
  provider preflight çağrı sayısı dal konumuna göre değişti.
- `RunBrowserRowProviderProbe.java`, gerçek `FUN_48714a20` → `FUN_48594510`
  zincirinde 20-byte kaydın little-endian `+0x02` halfword'unu converter'ın
  ilk argümanı olarak doğruladı. Sentinel kayıt `a0a1a2a3...` ile gözlenen
  değer `0xa3a2`; community ve stock AP'lerinde aynı. Bu alanın playlist
  anahtarı, directory kimliği veya başka bir fsel semantiği olduğu henüz
  adlandırılmadı; emulator'a yeni semantik eklenmedi.
- `RunNowPlayingStateProbe.java`, paint dispatcher payload'larında operation
  `0x02` için `a1 = a0 + 0x08`, operation `0x03` için `a1 = a0 + 0x10`
  ilişkisini gerçek stack bytes ile assertion olarak kontrol ediyor. Kontrol
  ID'si payload `+0x00` halfword'unda; bu, shared scroll renderer'daki
  `0x1012/0x1013` trace'inden ayrı bir paint sınırı. `flags=0x08` dalı ayrıca
  `0x1007,0x1006,0x1008,0x1005,0x1004` sırasını ve metadata glyph çağrılarında
  `a1=0` biçimini community/stock AP'lerinde doğruluyor. `metadata-table`
  dalı `state+0x19` ile seçilen sekiz op-`0x03` payload değerini ve
  `state+0x0e` byte kopyasını aynı corpus'larda doğruluyor; değerler host'ta
  opaque tutuluyor. 12-image taramasında 10 tablo aynı; `2024-04-24` sürümü
  indeks 1 ve 6'yı `0x004f`/`0x005e` olarak değiştiriyor ve gerçek p-code
  probe'u bu iki payload farkını üretiyor. Host değerleri AP'den okuyor,
  sabit anlam atamıyor.
- Community, stock ve 2024-04-24 AP'lerinde metadata payload'ın ilk 12 byte'ı
  sentinel-backed assertion ile kontrol ediliyor: firmware `+0x00`, `+0x02` ve
  `+0x04` alanlarını yazıyor; `+0x03` ve `+0x08` probe sentinel'ını koruyor.
  Bu, public SDK `textbox_private_t` yapısıyla sınırlı bir yapısal
  karşılaştırma sağlıyor; pointer/color semantiği kanıtlanmadığı için host'ta
  yalnızca yazıldığı kanıtlanan alanlar taşınıyor.
- `op=0x02` için `a1=a0+0x08` secondary payload'ı da sentinel ile ayrıştırıldı:
  `0x1000/0x1001` için word `0xff00`, `0x1002..0x1004` için `0xffff`; sonraki
  byte yalnızca `0x1003/0x1004` için `1`. Host bunu
  `NowPlaying.control_payload_trace()` ile opaque trace olarak taşır.
- Public PD196 header'ındaki `style_infor_t` + `picbox_private_t` ve
  `style_infor_t` + `textbox_private_t` düzenleri M30 payload'larıyla yapısal
  olarak eşleşiyor: `a0` style ID/type, `a1` ise op'a göre PicBox/TextBox
  adayı. Bu henüz ABI/semantic kanıt değil; özellikle `0xff00`, language ve
  color alanları bilinçli olarak opaque bırakılıyor.
- On iki dated image'ın tamamında style type byte'ı sabit eşleşiyor: `0x1003`
  için `1`, gözlenen diğer control/text ID'leri ve `0x2005` için `0`. Bu,
  public SDK'deki `UI_AP=0` / `UI_COM=1` düzeniyle yapısal olarak uyumlu; host
  alanı yalnızca ham `style_resource_type` olarak taşıyor ve tam resource-type
  semantiği ilan etmiyor. Wrapper ayrıca geçici logdan compact `PAYLOAD TYPES`
  özeti yazıyor.
- `common.sty` içinde doğrudan `0x1003/0x1004` PicBox ID'si bulunmadığı için
  bu byte'ın common-resource yönlendirmesi olduğu varsayılmıyor; `0x1000 +
  PicBox index` eşleşmesi ayrı M30 dispatcher kanıtı olarak korunuyor.
- `tools/ghidra/run_now_playing_corpus_probe.sh <evidence-root>` aynı kontrolü
  dated corpus'taki tüm `music.ap` dosyalarına uygular. Son tam tarama 12/12
  imajda 8/8 metadata index'i geçti. Ghidra 12.2 için Java seçimi
  `JAVA_HOME`/`JAVA_HOME_OVERRIDE` üzerinden yapılır.
- Dış PD196 SDK referansındaki `common_res.h` ve `music_res.h`, standart M30
  metadata tablosundaki sekiz halfword'ün tamamını string resource ID olarak
  adlandırıyor (`S_SECOND`, `S_SET_MENU`, `S_GENRE`,
  `S_IS_OVERRIDE_FAVLIST`, `S_LICENSE_FAIL`, `S_CLASSIC`, `S_THIS_ALBUM`,
  `S_CUSTOM`). Bu, ilk halfword için güçlü bir string-ID hipotezidir; M30
  `string_desc_t` ABI'si kanıtlanmadığından emulator'daki `table_value` hâlâ
  opaque kalır. Ayrıntı [`findings-now-playing-layout.md`](findings-now-playing-layout.md)
  içindedir.
- Sıradaki yüksek getirili iş resource/font pointer'larını gerçek LCD sink'e
  bağlamak. EOF mode-4 için dinamik callback patch'i, bank sonundaki
  kod/veri örtüşmesi nedeniyle güvenli bir probe sınırı oluşturmadı; bu alan
  mevcut statik delta ile fail-closed tutuluyor.

## Kanıt → emulator karşılıkları

- `tools/emu/folder_ui.py` ve `folder_input.py`: gerçek liste geometrisi,
  50-byte destination slotu ve 25-unit provider isteği; provider'ın uzun ad
  davranışı bilinmiyor. Marquee zamanlaması, navigation ve key-filter akışı.
- `tools/sim/browser_row_record.py`: gerçek `0x14` row stride ve
  `list_state + 4 + row * 0x14` kaynağını korur; yalnızca converter'a giden
  `+0x02` halfword'u açar, geri kalan alanları opaque bırakır.
- `tools/sim/key_filter.py`: ekran kapalı stock wake davranışı ve community
  keypass'in beş medya tuşu.
- `tools/sim/key_driver.py`: resident key driver ADC için 7-bit maske, yedi
  eşik, `0xff` unmapped sonucu, dışarıdan gelen normalize değer tablosu ve
  doğrulanmış repeat/special event sınırı.
- `tools/sim/usb_audio.py`, `usb_ring.py`, `usb_audio_stream.py`: doğrulanmış
  USB Audio descriptor, 200-byte azami paket ve ring sınırları; `RingConsumer`
  gerçek 0x1000-byte kaynak üzerinden wrap-aware bytes okuyabilir.
- `tools/sim/dac_bus.py`: `drv_dac.drv` içindeki kanıtlanmış byte dizisi
  gönderme/alma ve byte başına 8-bit transfer sınırı; bit sırası, codec
  register map'i ve timing modellenmez.
- `tools/sim/usb_mass_storage.py`: `uhost.drv` içindeki `USBC`, READ(10)/WRITE(10),
  block address ve 512-byte transfer birimi; request bytes'ları
  `SdCardBlockDevice` sektörlerine taşınabilir; raw `0x1f`/`0x80`–`0x88`
  transport event'leri de anlam atanmadan korunur. CBW/CSW framing ve
  controller davranışı modellenmez.
- `tools/sim/decoder_output.py`: unconfigured/community decoder callback için
  fail-closed `-1`; bilinmeyen callback çalıştırılmaz.
- `tools/sim/m30_model.py`: EQ `0x303` mesaj zarfı ve `{eq_type, selector}`
  çifti yanında, p-code ile doğrulanan yedi bantlı builder çıktısını ve erken
  dönüş koşullarını modeller. `run_eq_builder_probe.sh` stock/community
  MIPS16 çıktısını byte-byte karşılaştırır. `mmm_pp.al` SHM+2'den DSP seçimini
  yapıp SHM işaretçisini modül `0x0c` op `0x12`'ye verir; ROM alıcısı,
  katsayı dönüşümü ve duyulabilir DSP etkisi hâlâ çözülmedi.
- `tools/sim/sd_block_device.py`: kanıtlanmış 512-byte SD sektör sınırı,
  card presence, bounds ve `sector_count * 512` capacity davranışı.
- `tools/emu/driver_loader.py`: doğrulanmış `yqhx` segment bytes'larını sparse
  resident memory'ye kopyalama ve BSS sıfırlama; entry/fixed-ROM çağrısı yok.
- `tools/sim/mmm_bp_model.py`: `mmm_bp` ring read sınırı ve Ghidra ile doğrulanan
  60-step fade phase / ±1000 level arithmetic; wrap-aware source-byte read
  mevcut, callback ve attenuation opaque.
- `tools/emu/lcd_transfer.py`: LCD `0x36` orientation komutunun Ghidra’da
  görülen ham değerlerini ve `0x2a/0x2b/0x2c` window/pixel sınırını kaydeder;
  fiziksel yön semantiği ve panel davranışı modellenmez.
- `tools/sim/asrc_model.py`: `music.ap` EOF branch'inin doğrulanmış mode-3
  advance yolu ve community/V1.101.11 için eklenen mode-4 yolu; ASRC/DAC
  örnek kuyruğu ve audible tail modellenmez.
- `tools/sim/metadata_reader.py`: V1.101.11/community metadata okuyucusunun
  doğrulanmış 0x200 buffer boundary split planı, concrete source-byte read'i ve
  trailing-skip aritmetiği; source/skip callback ABI'si ve parser modellenmez.
- `tools/sim/artwork_fallback.py`: community JPEG fallback için doğrulanmış
  `0xff` tetikleyici, 150x150, 1/3-component ve pozitif `0x1000`-inclusive
  block sınırı; JPEG decoder ve UI zamanlaması çalıştırılmaz. Ham decompile
  yalnızca ignored `analysis/local-evidence/artwork-fallback-community.c`
  altında yerel olarak saklanır; public repository'ye alınmaz.
- `tools/sim/artwork_cache.py`: decompile ile doğrulanmış `OK`/`NO`, `x,y,w,h`
  ve RGB565 byte-payload sınırı ile cihaz SD görüntüsünde doğrulanan ayrı,
  250-sektör `ALBUM.PIC` container sınırı; pixel renk sırası ve container
  başlık alanlarının anlamı yorumlanmaz. Probe:
  `tools/emu/album_pic_evidence_probe.py`.
- `tools/sim/filesystem_extension.py`: FAT32/exFAT için byte kanıtlı 31-slot
  extension sözlüğü, explicit-slot kabul filtresi ve driver high-bit-first mask
  decoder; caller product policy ve directory callback ABI modellenmez.
- `tools/sim/filesystem_extension.py` içindeki raw library maskeleri ve
  `tools/emu/library_mask_probe.py`: MUSIC `0x7e190ea0` (`0x2a20`), M3U
  `0x00080400` (`0x2a28`); resident matcher için slot 0→bit 31, slot 30→bit
  1 sırası `tools/ghidra/run_library_mask_mapping_probe.sh` ile doğrulanır.
- `tools/sim/library_tag_reader.py`: `playlist.ap` extension→reader type
  seçimi; reader ABI ve metadata parsing çalıştırılmaz.
- `tools/sim/library_record.py`: tag başarısızlığında short-name title ve sabit
  fallback text alanları; raw 8.3 `NAME.EXT` formatter ve dört haneli track
  prefix selector modeli vardır.
- `tools/sim/library_record_codec.py`: documented 256-byte `MUSIC.LIB` record
  alanları; `index.c`'nin gerçekten kullandığı text/track alanları ile
  file-location byte dizileri ayrıştırılır, `+0xf4..+0xfb` US212A
  `cluster_no`/`dir_entry` çifti olarak okunur, primary location blob opaque
  tutulur.
- `tools/sim/library_index_sort.py`: `index.c` karşılaştırma sırasının raw
  record üzerinde çalışan candidate order modeli ile ALBUM→ARTIST→GENRE
  nested run/group türetimi; ayrıca gerçek `FUN_40114d54` bounded tie
  refinement'ı ve stock quicksort'ı `refine_sort_record_indices()` adıyla
  opt-in taşır. `stale-tail` varsayılan conversion modelidir; SD corpus’unda
  ALBUM/ALLSONG eşleşmesini artıran `stop-zero` modeli ayrıca opt-in'dir.
  Varsayılan sorter değişmez; yazma veya M30 UI policy iddiası yoktur.
- `tools/sim/library_scan.py`: stock fixed-array scanının 4,000-record
  kapasite sınırı; `MUSIC.LIB` formatı veya experimental large-library yolu
  bu modelle birleştirilmez.
- `tools/sim/library_file.py`: `MUSIC.LIB` 0x200 header, 256-byte record area,
  four section directory ve raw sector references; caller-supplied SD
  başlangıç sektöründen byte image, bounded `LibraryRecord`, raw/typed section
  `u16` table, typed section snapshot ve fail-closed table/node-link validation
  ile 16-byte section node kayıtları okunabilir.
  `interpret_library_section_index()` doğrulanmış node run'larını raw record
  indekslerine read-only olarak eşler; UI albüm/sanatçı anlamı atamaz.
  `tools/emu/musiclib_evidence_probe.py` harici `MUSIC.LIB` örneğinin hash'ini,
  SD sektör yolunu, örnek `cluster_no`/`dir_entry` değerlerini, reader lookup
  round-trip'lerini ve section span coverage'ını tek komutta doğrular.
  `find_library_record_index()` aktif kayıtlar arasında firmware'in 4,000
  kayıtlık bounded eight-byte key aramasını modeller.
  `decode_library_section_node()`
  local builder ve bağımsız public hex örneğiyle örtüşen little-endian alanları
  açar; gerçek cihaz `MUSIC.LIB` görüntüsünde tüm section node/table coverage'ı
  doğrulanmıştır. Cihazın duplicate-key tie-break sırası ve location semantics
  hâlâ doğrulanmamıştır. `tools/emu/library_order_probe.py` önceki-section
  rank'ı sonraki candidate pass'e vererek comparator farkını izole eder; mevcut
  görüntüde GENRE sırası 4,000/4,000 eşleşir. Ayrıntı:
  [`findings-device-sd-library.md`](findings-device-sd-library.md).
- `tools/sim/library_walker.py`: stock walker’ın DFS, depth-8, `RECORD` skip ve
  target-count sözleşmesi; filesystem callback ABI’si modellenmez.
- MUSIC.LIB açık TODO'ları `docs/findings-music-lib-node-format.md` ve
  `docs/findings-device-sd-library.md` içindedir: `0xa9..0xf1` ve
  `0xfc..0xff` alanları opaque kalır; `0xf4..0xfb`
  `cluster_no`/`dir_entry` olarak okunur. Section reference `5` deepest-level
  mapping'i gerçek M30 görüntüsünde doğrulanmıştır, fakat duplicate-key
  tie-break ve primary location semantics açık kalır; reader lookup'ın
  doğrulanmış key boundary'si host'ta modellenmiştir.
- Son güvenli araç değişikliği `f09a4d5` (`feat: preserve library ordering on
  safe edits`): `tools/library/edit_library.py` değişikliksiz düzenlemeyi
  gerçek girdiyi byte-byte kopyalayarak sonlandırır; gerçek metadata
  değişikliklerinde mevcut section order tablolarını eşit anahtarlar için
  korur. `/Volumes` doğrudan çıktı yolu olarak reddedilir. Metadata değiştiren
  çıktı hâlâ yalnızca adaydır; `tools/library/libdiff.py` farkları görülmeden
  cihaza yazılmamalıdır.
- `c02057e` (`fix: model short-key library refinement`) ile host model,
  Ghidra'da doğrulanan kısa başlangıç anahtarı + eşit kümelerde artan yeniden
  karşılaştırma akışını ve section içi track/önceki-rank sıralamasını izliyor.
  Bu, gerçek metadata değişikliklerinin indeks farklarını azaltmak için yüksek
  getirili bir model düzeltmesidir; yine de cihaz üzerinde yeniden indeksleme
  ile doğrulanmadığı için edit çıktısını release/flash adayı yapmaz.
- `8d5c75a` (`feat: validate metadata library candidates`) ile editor; title,
  artist, album ve genre değerlerinde taşma/NUL girişini reddediyor, UTF-16/
  Latin-1 temsilini koruyor, unresolved location/link tail'i değiştirmiyor ve
  rebuilt section tabloları ile node bağlantılarını doğrulamadan çıktı
  üretmiyor. Kullanım: `python3 tools/library/edit_library.py INPUT OUTPUT
  --set RECORD.FIELD=VALUE`. OUTPUT `/Volumes` altında olamaz.
- `tools/emu/ui_resources.py`: altı corpus'ta doğrulanmış MCG node ve RU3
  group metadata'sı; user-facing isim veya node anlamı uydurulmaz.
- `tools/emu/ui_dispatcher.py`: now-playing op-`0x03` payload'ındaki doğrulanmış
  halfword, byte ve zero-word alanlarını `OpaqueMetadataPayload` olarak trace'e
  taşır; payload framebuffer'a çizilmez ve semantik atanmaz.
- `tools/emu/ui_dispatcher.py`, public SDK ile yapılan operation cross-check
  sayesinde `0x14` değerini arka plan rengi setter'ı, `0x12` değerini mevcut
  arka planla rectangle clear olarak modelliyor. M30 resident consumer ABI'si
  doğrudan çözülmediği için bu eşleme host'ta açıkça cross-build hypothesis
  olarak tutuluyor; M30 color/rectangle byte kanıtı ayrıdır.
- `NowPlaying.paint_dispatch_trace()` gerçek paint sınırını shared scroll
  trace'inden ayırır: üç doğrulanmış `(x,y,w,h)` clear rectangle'ı ve
  `0x1002` artwork control'ü yedi çağrılık sırayla taşır. Rectangle header'ı
  kanıtlanmıştır; payload tail'i opaque kalır. `observed_dispatch` adı artık
  yalnızca `0x1012`/`0x1013` içeren shared scroll zarfını ifade eder.
- `NowPlaying.state_dispatch_trace()` gerçek p-code state probe'unda ayrı ayrı
  çalıştırılan `0x100`, `0x20`, `0x200`, `0x800` ve `0x08` dallarının ham
  operation/identifier sırasını host JSON'daki `state_dispatch` altında taşır.
  Bu yalnızca dispatcher sınırıdır; frame/resource görsel anlamı tahmin
  edilmez.
- `lcd_preview.py` ve `m30_preview_session.py` aynı `state_dispatch` sözleşmesini
  LCD sink ve birleşik session trace'lerine de taşır; doğrulanmış RGB565 bant
  renkleri LCD round-trip testinde korunur.
- `tools/ghidra/run_now_playing_paint_decompile_probe.sh`, `FUN_40154ddc`
  decompile’ındaki dört halfword rectangle yazımını ve `&uStack_18` → op-`0x12`
  çağrısını koruyan dar bir static guard’dır. Probe dump’ındaki header sonrası
  stack byte’ları rectangle alanı kabul edilmez.
- `tools/emu/music_bitmap.py`: 12 corpus'ta doğrulanmış `music.sty` resource
  table'ının format `0x2`, boyut ve doğrudan little-endian RGB565 sözleşmesi;
  bilinmeyen formatlar reddedilir. `NowPlaying` resource 0 bitmap'ini gerçek
  framebuffer çıktısına taşır.
- `tools/emu/build_safe_ui_members.py`: yalnızca `music.sty` ve `setting.sty`
  değiştiren extracted-member candidate üretimi; iki bilinen `music.sty`
  layout'ını ve iki bilinen `setting.sty` kaynağını payload hash'leriyle
  doğrular, bilinmeyen düzenlerde durur; flashable HEX üretmez. UI bulguları:
  [`findings-ui-resources.md`](findings-ui-resources.md).

## Bilinçli olarak uygulanmayanlar

- Tam ATJ2127 boot, bank loader'ın çözülmemiş exception yolu, gerçek LCD/DAC,
  fiziksel key ADC eşlemesi ve controller register/IRQ zamanlaması.
- Tam USB mass-storage CBW/CSW framing'i, status anlamları ve controller
  timing'i; mevcut host modeli yalnızca doğrulanmış block-request alanlarını
  taşır.
- MCG localized-name ve node→application eşlemesi.
- Decoder callback'i başarılı PCM yolu gibi kullanmak.
- Cihazda doğrulanmamış firmware veya HEX üretimini release olarak sunmak.

## Sıradaki çalışma kuralı

Yeni bir davranış ancak şu sırayla eklenir:

1. Kanıtı ilgili binary/probe/Ghidra bulgusuyla sınırla.
2. Kanıtlanmış kısmı host emulator modeline ekle.
3. Belirsiz kısmı fail-closed bırak.
4. Regression test ve ilgili finding dokümanını güncelle.
5. Tek anlamlı kapsamla commit et; push yapma.

## Kanonik belgeler

- [`qemu-coverage.md`](qemu-coverage.md)
- [`community-integration.md`](community-integration.md)
- [`release-candidate.md`](release-candidate.md)
- [`findings-kernel-loader.md`](findings-kernel-loader.md)
- [`findings-filesystem-drivers.md`](findings-filesystem-drivers.md)
- [`findings-music-lib-node-format.md`](findings-music-lib-node-format.md)
- [`library-builder.md`](library-builder.md): record storage, key conversion,
  index passes, node writer, and large-library constraints.
- [`findings-library-record-locations.md`](findings-library-record-locations.md)
- [`findings-ui-resources.md`](findings-ui-resources.md)
- [`findings-decoder-callback.md`](findings-decoder-callback.md)
- [`findings-cards-driver.md`](findings-cards-driver.md)
- [`findings-device-artifacts.md`](findings-device-artifacts.md): çevrimiçi
  M30 kaynakları, eksik cihaz artefaktları ve güvenlik sınırları.
- [`provenance-risk.md`](provenance-risk.md): public/private reverse-engineering
  artifact ayrımı ve MIT lisansının kapsam sınırı.
- [`discovery-environment.md`](discovery-environment.md)
- [`corpus-downloads.md`](corpus-downloads.md): dated Drive folder URLs,
  hashes, download verification, and extraction boundary.
- [`../tools/rom/README.md`](../tools/rom/README.md)

## Değişmez kurallar

- Orijinal HEX'i silme veya üzerine yazma; her değişiklikten önce ve sonra
  SHA-256 üret.
- FWU boyutu, sektör düzeni ve checksum/trailer korunur.
- Donanımda test edilmeyen davranışı kesin çalışır diye yazma.
- Test anahtarlarını ve gereksiz community anahtarlarını final HEX'e koyma.
