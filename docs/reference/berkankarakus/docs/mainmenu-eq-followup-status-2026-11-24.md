# Mainmenu EQ takip durumu — 2026-11-24

## Sonuç

`+1 → −3` davranışı firmware'in azaltma aritmetiği değil, Ghidra p-code probunun
MIPS16e genişletilmiş `SLTI` koşulunu yanlış yorumlaması. Mainmenu durum
makinesi EQ seçiciye ve Custom düzenleyiciye dolaylı işaretçi tablosuyla
ulaşıyor. EQ değişiklikleri kalıcı bloğa yalnızca mainmenu çıkış kolunda
yazılıyor.

## Azaltma kolu

- Girdi: `analysis/community/fw/mainmenu.ap`, SHA-256
  `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`.
- Menü varsayılanları: `analysis/community/fw/config.bin`, SHA-256
  `900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19`.
- `FUN_40154f44`, VA `0x401551cc` / dosya `0x3fcc`: baytlar
  `ff f7 1e 52` = `EXTEND` + genişletilmiş `SLTI` (`-2`). MIPS16e bu
  karşılaştırmayı işaretli tanımlar. `gain < -2` ise `-3` sınırına yazılır;
  aksi halde sonraki talimatlar değeri bir azaltır.
- Bu nedenle gerçek talimat akışında `+1` azaltması `0` üretir. Ham p-code
  probu koşulu `+1 < 65534` gibi ele alıp sınır koluna atladı ve hatalı olarak
  `-3` gösterdi. Tanı probunda yalnızca bu koşul işaretli karşılaştırma
  sonucuyla değiştirildi; devamındaki AP talimatları çalıştı. Başlangıç
  değerleri `-3..+3` için sonuç `-3,-3,-2,-1,0,1,2` oldu.
- Bu, cihazda tuş eşlemesinin veya ses etkisinin testi değildir. P-code
  karşılaştırmasının düzeltilmiş tanı koşusu, tam ISA emülasyonu da değildir.

## Ekran yolu ve kaydetme

- Dosya `0x1790..0x179c` işaretçi kelimeleri sırasıyla
  `0x40054d39`, `0x40094d9d`, `0x40154ced`, `0x40154f45`: durum 1 ana liste,
  durum 2 ara ekran, durum 3 EQ seçici, durum 4 Custom düzenleyici.
- Ghidra decompile'ı `FUN_bfc165e0` içindeki `0x2c03` seçim dalını doğruladı:
  seçilen u16 kodu okunuyor, kod 3 için `0x8505` dönüyor. Ana menü pool'ları
  dosya `0xf84/0xf88/0xf8c`'de sırasıyla `0x9fc155cc` (indeks),
  `0x9fc1562c` (sayı) ve `0x9fc155d0` (kod tablosu).
- Geçersiz `0x22` baytlık menü bloğunda `FUN_40014d74`, `config.bin` öğe
  `0xe2`'yi yükler. Hash doğrulanmış kayıt `[0,6,0,4,5,2,3,1]` olduğundan
  varsayılan kod sırası `0,4,5,2,3,1`; kod 3 beşinci menü öğesidir. Yerel
  etiketi hâlâ bilinmiyor. Saklanmış menü sırası varsayılandan farklı olabilir.
- Ham config kaydı: indeks girdisi dosya `0x720`, payload `0xc60`, uzunluk
  `0x10`; baytlar `00 00 06 00 00 00 04 00 05 00 02 00 03 00 01 00`.
- Durum 1 işleyicisinden dönen `0x8505`, durum 3'ü açar. Durum 3'ten
  `0x8506`, durum 4'ü açar; `0x8006` ana listeye döner. Durum 4'teki
  `0x8505`, durum 3'e döner.
- Ana liste işleyicisi `FUN_40054d38`, menü/olay yardımcısının dönüşünü
  alıyor ve yerel olarak ele almadığı kodları üst durum makinesine bırakıyor.
  `0x8622` ayrı bir gösterim eylemi.
- Girişte `FUN_40014efc`, `FUN_40014d74` yükleyicisini çağırıyor. Yükleyici
  `0x9fc155f4` adresindeki 0x38 baytlık bloğu op `0x12` ile okuyor ve magic
  `0x55aa` geçersizse varsayılanları kuruyor.
- Durum sıfıra düşüp mainmenu çıkarken `FUN_40015050`, `FUN_40014fac`'i
  çağırıyor. Bu fonksiyon `(0x9fc155f4, 0, 0x38, 0x13)` yazımını yapıyor.
  Slider onayı/iptali kendi başına kalıcı yazım yapmıyor. Yerleşik servisin
  flash'a ne zaman commit ettiği ve çıkıştan önce güç kesilirse ne olacağı
  bilinmiyor.
- Aynı çıkış fonksiyonu ayrıca menü sırasını
  `(0x9fc155d0, 0x10000, 0x22, 0x13)` ile yazıyor; magic `0x55ab`.
  Decompile ve dosya `0x1610..0x161c` pool kelimeleri bu argümanları
  doğruluyor.
  `config.ap` aynı RAM adresini ayrı indeksleme kaydı için kullanıyor:
  `(0x9fc155d0, 0x2f0000, 4)`. ID ve uzunluk mainmenu menü kaydından farklı;
  bu RAM adresi tekrar kullanımı aynı veri demek değil.

## İndeksleme bayrağı

`config.ap` `FUN_40054ccc` boot başlatıcısını Ghidra'da yeniden çözdüm.
`config.ap` SHA-256 `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580`.
Başlatıcı bayrak kaydını `(0x9fc155d0, 0x2f0000, 4, 0x12)` ile okuyor.
Ayrıca `(0x9fc156ec, 0x20000, 0x14, 0x12)` kaydını okuyor ve magic
`0x55ac`'i kontrol ediyor. Okuma başarısızsa veya magic hatalıysa, bayrağa
`0x10000` yazıp aynı dört baytlık kaydı op `0x13` ile kalıcılaştırıyor;
ardından `0x20000` bloğunu yeniden kuruyor. Başlangıçtaki bit testi bu
bayrakla `playlist.ap`'ı başlatıyor; playlist yalnızca başarılı indeksleme
sonunda biti temizliyor.

Bu, geçersiz `0x20000` kaydından sonraki boot indekslemesini kanıtlıyor.
Updater'ın bu kaydı neden geçersiz kıldığı bilinmiyor. Kod yalnızca boot'ta
çalıştığından, kartı cihaz çalışırken takınca başlayan indekslemeyi
açıklamıyor. Ayrıntı ve Ghidra çıktısı:
[index flag writer evidence](evidence/index-flag-writer-2026-11-24/README.md).

## Kanıt ve tekrar üretim

- Geniş p-code probu: [probe kaynağı](../tools/ghidra/RunMainmenuEqSliderProbe.java),
  [çalıştırıcı](../tools/ghidra/run_mainmenu_eq_slider_probe.sh),
  [kanıt notu](evidence/mainmenu-eq-slider-pcode-2026-11-24/README.md).
- Ham decrement: [MIPS16 disassembly](evidence/mainmenu-eq-slider-pcode-2026-11-24/decrement-extended-full.log),
  [p-code koşul izi](evidence/mainmenu-eq-slider-pcode-2026-11-24/trace-run.log),
  [işaretli koşul tanı koşusu](evidence/mainmenu-eq-slider-pcode-2026-11-24/isa-corrected-run.log).
- Durum ve kalıcılık: [ham disassembly](evidence/mainmenu-eq-slider-pcode-2026-11-24/route-persistence-disassembly.log),
  [çözümlenen referanslar](evidence/mainmenu-eq-slider-pcode-2026-11-24/route-refs-run.log),
  [yükleyici/çıkış xref'leri](evidence/mainmenu-eq-slider-pcode-2026-11-24/persistence-refs-run.log),
  [ana liste decompile'ı](evidence/mainmenu-eq-slider-pcode-2026-11-24/menu-callback-run.log),
  [`0x2c03` seçimi ve item `0xe2` incelemesi](evidence/mainmenu-eq-slider-pcode-2026-11-24/menu-code-3-rerun.log).
- MIPS16e `EXTEND` ve genişletilmiş `SLTI` işaretli semantiği:
  [MIPS Architecture for Programmers, Volume IV-a](https://0x04.net/~mwk/doc/mips/MD00077-2B-MIPS1664-AFP-02.50.pdf).
- Boot indeksleme bayrağı: [Ghidra çıktısı ve kayıt haritası](evidence/index-flag-writer-2026-11-24/README.md).

P-code servisleri sentetik/stub; cihaz testi, flash yazımı ve firmware değişikliği
yapılmadı. Commit veya push yapılmadı.
