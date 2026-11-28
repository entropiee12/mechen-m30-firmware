# Keşif ortamı: Docker, Ghidra ve yeniden üretim

Bu belge, `docs/codex-handoff.md` içindeki bulguları yeniden üretmek için
kullanılan ortamı kaydeder. Burada üretilen Ghidra çıktıları (`*_community.c`)
satıcı firmware'inden türetildiği için depoya eklenmedi; aşağıdaki komutlarla
yeniden üretilir.

Dated HEX klasör URL'leri ve hash doğrulaması için önce
[`corpus-downloads.md`](corpus-downloads.md) manifestini kullan.

## Girdiler (repo dışında)

| Ne | Yol (yerel çalışma alanı) | SHA-256 |
|---|---|---|
| Stock V1.101.10 | `<local-workspace>/m30-inputs/stock/MECHEN_M30.HEX` (README'deki Google Drive linkinden) | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` |
| Community V1.292.00 | `<local-workspace>/m30-inputs/community/MECHEN_M30.HEX` (`dev-zetta/mechen-m30-community-firmware` release `v1.292.00`) | `5e17d883f697a67d4face6f624ddc30952c5542c918b219be974bf380383ed2c` |
| Public M30 update, AFI date 20/02/2023 | temporary `/private/tmp/MECHEN_M30.HEX` (Drive folder `1VuQF4jk-yfDUBSTfQorGSsXtDUdvYcEW`) | `ee164011fb1fe30f552348613f832c5cb0f2aee271834f891aad22be6674e6da` |
| Actions SDK kaynağı | `<local-workspace>/m30-inputs/ref/PD196` (`Suber/PD196_ATJ2127`, GBK kodlu; `iconv -f gbk -t utf-8 -c`) | - |

Not: Google Drive indirmesi ve GitHub release indirmesi bu oturumda
yapıldı; stock ve community dosyaları için yukarıdaki hash'ler README ve
`docs/community-integration.md` ile eşleşti.

## Docker imajı `m30-build`

İmaj amd64 (Fedora). Ana makine arm64 olduğu için emülasyonla çalışır:
`docker run` sırasında `--platform linux/amd64` verme ("imaj bulunamıyor"
hatası); parametresiz çalışır, yalnızca platform uyarısı basar. Aday HEX'i
ayrı bir `-v` ile değil, repo dizinini salt-okunur bağlayarak ver (iç içe
mount başarısız oluyor).

```dockerfile
FROM --platform=linux/amd64 fedora:latest
RUN dnf install -y gcc gcc-c++ make git python3 zlib-devel \
    gcc-mips64-linux-gnu binutils-mips64-linux-gnu qemu-user-static-mips \
    libusb1-devel libpng-devel && dnf clean all
RUN git clone --depth 1 --filter=blob:none https://github.com/Rockbox/rockbox.git /opt/rockbox \
    && make -C /opt/rockbox/utils/atj2137/atjboottool
ENV ATJBOOTTOOL=/opt/rockbox/utils/atj2137/atjboottool/atjboottool
WORKDIR /work

# Ghidra headless (tam JDK gerekir; headless JRE "no TTY" hatası verir)
RUN dnf install -y java-25-openjdk-devel unzip && dnf clean all
RUN curl -sL -o /tmp/ghidra.zip https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.2_build/ghidra_12.2_PUBLIC_20261016.zip \
    && unzip -q /tmp/ghidra.zip -d /opt && rm /tmp/ghidra.zip && ln -s /opt/ghidra_12.2_PUBLIC /opt/ghidra
ENV GHIDRA=/opt/ghidra/support/analyzeHeadless
```

Doğrulanan sürümler: `mips64-linux-gnu-gcc` 16.2.1 (README'deki test edilen
sürümle aynı), JDK 25.0.4.1, Ghidra 12.2. Kullanılan Dockerfile bu oturumda
`<local-workspace>/m30-docker/Dockerfile` altındaydı; yukarıdaki blok
onun içeriğidir.

## macOS ARM'da tam statik suite

Tam `tools/emu/run_static_suite.sh` akışı Ghidra 12.2, Java 25 ve Ghidra'nın
platforma özgü native decompiler bileşenini gerektirir. macOS ARM'da en kısa
kurulum yolu Homebrew'dür:

```sh
brew install ghidra
```

Ghidra'nın doğrudan indirilen dağıtımı bazı macOS ARM paketlerinde
`os/mac_arm_64/decompile` bileşenini içermeyebilir. Bu durumda Ghidra açılır,
fakat decompiler probe'ları `native decompiler does not exist` hatasıyla
başarısız olur. Homebrew kurulumu gerekli native bileşeni sağlar.

`tools/emu/run_static_suite.sh`, Homebrew kurulumunu ve Java 25 yolunu otomatik
keşfeder. Elle override etmek veya Homebrew dışı kurulum kullanmak gerekirse:

```sh
GHIDRA="$(brew --prefix ghidra)/libexec/support/analyzeHeadless"
JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
GHIDRA_USER_HOME="${TMPDIR:-/private/tmp}/m30-ghidra-home"
mkdir -p "$GHIDRA_USER_HOME"

JAVA_TOOL_OPTIONS="-Duser.home=$GHIDRA_USER_HOME" \
  GHIDRA="$GHIDRA" \
  JAVA_HOME="$JAVA_HOME" \
  bash tools/emu/run_static_suite.sh
```

`JAVA_TOOL_OPTIONS` gereklidir: Ghidra ilk çalıştırmada Java yolunu
`$HOME/Library/ghidra` altına kaydeder; kısıtlı veya geçici çalışma ortamlarında
bu dizin açıkça yazılabilir bir yere alınmalıdır. Suite yalnızca offline
firmware analizidir ve cihazla USB iletişimi kurmaz.

### Analiz girdileri

Firmware üyeleri ve tüm corpus kopyaları `.gitignore` kapsamındadır; repoya
eklenmez. Suite'in beklediği düzen:

```text
analysis/{stock,community,first-pass-verify,v1.101.11,v1.101.12,v5}/fw/
```

Bu dosyalar hazır bir yerel unpack çalışma alanında bulunuyorsa, her corpus
altındaki `fw` dizini o çalışma alanına bağlanabilir. Bağlantıların mutlak
makine yolları içermesi nedeniyle bunlar yalnızca yerel fixture olarak kalmalı;
dokümantasyonda `<local-workspace>` yer tutucusu kullanılmalıdır.
Eksik historical corpus'ları community kopyasına bağlamak geçerli değildir:
`run_decoder_callback_probe.sh` gibi karşılaştırmalar gerçek
`v1.101.12`/community byte farklarına dayanır.

### Corpus gate

Bu çalışma makinesinde beklenen altı extracted corpus
(`analysis/community`, `analysis/first-pass-verify`, `analysis/stock`,
`analysis/v1.101.11`, `analysis/v1.101.12`, `analysis/v5`) mevcut ve her biri
99 member içeriyor; bunlar Git'te tracked değildir. Tarihli özgün HEX girdileri
`/Users/berkankarakus/Projects/m30-inputs/hex/` altında tutulur. Temiz bir
checkout'ta bu yollar bulunmayabilir; o durumda tam suite, Ghidra yeniden-
dekompilasyonu veya sürüm karşılaştırmaları çalıştırılmadan önce
`corpus-downloads.md` ve `portable-handoff.md` izlenmelidir. Boş dizinleri
community kopyasına bağlamak kanıt üretmez.

2023 public update'in extracted kopyası da aynı tarihli HEX'ten yeniden
oluşturulabilir; mevcut yerel sonuçlar ayrı bir corpus olarak tutulmalıdır.

## Üyeleri açma

```sh
atjboottool -c --fwu -o dec.bin MECHEN_M30.HEX
atjboottool -c -o afi/ dec.bin
atjboottool -c --fw -o fw/ afi/FWIMAGE.FW
```

Her üç imajda sonuç: AFI 19 ok / 0 hata, FWIMAGE.FW 201 ok / 0 hata, 99 üye.

## Ghidra ile `.ap` dekompilasyonu

Repodaki `tools/ghidra/LoadAP.java` ve `DumpC.java` Ghidra 12.2'de çalışıyor:

```sh
$GHIDRA PROJE_DIZINI ad -import X.ap -loader BinaryLoader -processor "MIPS:LE:32:default" -noanalysis \
  -scriptPath tools/ghidra -postScript LoadAP.java X.ap -postScript DumpC.java out.c
```

Community corpus'taki tüm `.ap` modüllerini hash ile takip edip decompile
çıktılarını bu makinede kalıcı, Git tarafından dışlanan
`analysis/local-evidence/decompiled/community/` altında saklamak için:

```sh
bash tools/ghidra/export_local_ap_decomp.sh
```

İsteğe bağlı ilk argüman `.ap` dizinini, ikinci argüman çıktı dizinini seçer.
Her modül için `.c` dökümü ve `manifest.tsv` içindeki kaynak hash'i, fonksiyon
sayısı, `LoadAP.java`/`DumpC.java` hash'leri ve Ghidra sürümü tutulur; bu
girdiler, tüm yerel Ghidra kurulumunun içerik hash'i ve üretilen `.c` dosyasının
hash'i aynıysa yeniden çalıştırılmaz. Bu,
Ghidra'nın `LoadAP.java` tarafından tanımlanan fonksiyonların mekanik C-benzeri
dökümüdür: MIPS16 prologue taramasının kaçırdığı fonksiyonları, eksik sabit ROM'u
veya bankalar arası pointer çözümünü tamamladığı anlamına gelmez. Ham çıktılar
vendor-derived olduklarından public Git geçmişine eklenmez; doğrulanmış
davranışsal açıklamalar `docs/findings-*.md` altında tutulur.

Üretilen dump'lar (fonksiyon sayısı): `browser` 449, `music` 819, `playlist` 386,
`drv_ui` 119 (düz haritalama, aşağıya bak); `config`, `setting`, `mengine`,
`udisk`, `manager`, `usound`, `mainmenu` de üretildi. Fonksiyon adları
`FUN_<adres>`; bankalar arası çağrılar `(*_UNK_xxxx)(...)` fonksiyon
işaretçisi olduğundan çağrı grafiği eksik.

### `.drv` dosyaları (düz haritalama)

`drv_ui.drv` aynı `yqhx` başlığını taşır ama banka tablosu farklıdır. `0x80`,
`0x100` ve `0x180` bölgeleri, üç overlay grubunun byte-order `u16` başlangıç/
bitiş çiftlerini taşır; grup dosya başlangıçları başlıktaki `0x48/0x4c/0x50`
alanlarından gelir. Bu yapı artık `tools/inspect_drv.py` ile raporlanıyor.
Bank yükleme adreslerinin M30 loader tarafındaki son eşlemesi yine çözülmediği
için dosya düz haritalanıyor: adres = `0x30000000` + dosya ofseti,
0x200'den itibaren. PC-göreli literal yüklemeleri doğru çözülür; işaretçi
üzerinden bankalar arası çağrılar çözülmez. Ghidra postScript'in güncel kopyası
[`tools/ghidra/FlatLoad.java`](../tools/ghidra/FlatLoad.java) içindedir.

### `drv_ui.drv` fonksiyon eşlemesi

| Fonksiyon | Anlamı |
|---|---|
| `FUN_30000200` | Dize kimliği önbelleği (kimliğe göre son erişilen girişleri tutar) |
| `FUN_300002f0` | Dize kimliği ile kaynak okuma; kayıt uzunluğundan 2 bayt (terminator) düşer |
| `FUN_300007c4` | UTF-16 karakter sınıflandırıcı: `0x0000` EOF, `0xfeff` Unicode bayrağı, `0x000a` / `0x000d` / `0x000d 0x000a` satır sonu |
| `FUN_3000083c` | 8-bit karakter sınıflandırıcı |
| `FUN_30008600` | Çok satırlı `put_string` (hizalama `x` hesabı) |
| `FUN_30009070` | Kayan yazı başlatma: ilk 0x66 bayt içinde sonlandırıcı arar, iki boşluk ekler, BOM'u atlar |
| `FUN_30009674` | Kayan yazı adımı (`scroll_desc`; `0x7c` bayt uzunluk/işlem argümanı olabilir, piksel genişliği kanıtlanmadı) |
