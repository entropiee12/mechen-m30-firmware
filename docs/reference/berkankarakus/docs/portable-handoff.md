# Portable M30 çalışma paketi

Başka bir bilgisayarda çalışmaya devam etmek için Git deposu tek başına yeterli
değildir. Firmware ve cihaz SD kartı kanıtları telif/provenance nedeniyle Git'e
konulmadı; bunlar depo dışındaki kalıcı bir kanıt kökünde tutulur. Bu belge,
deponun ve bu kökün birlikte nasıl taşınacağını tanımlar.

## Gerekli iki parça

1. Bu Git deposu ve çalışma dalı `main`.
2. Aşağıdaki yapıyı içeren kalıcı kanıt kökü:

```text
<evidence-root>/
  MUSIC.LIB                         # bağımsız public karşılaştırma örneği
  device-sd/
    MUSIC.LIB                       # cihazdan alınan dosya
    M3U.LIB                         # cihazdan alınan dosya
    ALBUM.PIC                       # cihazdan alınan dosya
  hex/<12 tarihli release>.HEX
  modifications/<mevcut tarihli release>.txt
  extracted/<tarih>/fw/             # atjboottool ile çıkarılmış üyeler
  decompile/library-record-consumers-2025-06-26/
    consumers.c
    album.c
    run.log
    album-run.log
  decompile/library-index-2025-06-26/
    index.c
    run.log
  decompile/library-tie-2025-06-26/
    tie.c
    run.log
  references/us212a-app-design-guide-v2.pdf
```

Mevcut kökün taşınabilir doğrulama sonucu:

```text
PERSISTENT EVIDENCE PASS
```

Başka cihazda mutlak yolu değiştirmek yeterlidir. Bütün dosyaların boyutunu ve
SHA-256 değerlerini doğrulamak için:

```sh
python3 tools/verify_persistent_evidence.py /path/to/m30-evidence
```

Bu komut yalnızca okur; dosya indirmez, değiştirmez, decompile etmez ve Git'e
kanıt eklemez. Başarısız olursa eksik veya değişmiş dosyayı açıkça listeler.

## Kaynaklar ve yeniden oluşturma

12 HEX dosyasının klasör URL'leri, beklenen hash'leri ve mevcut Modification dosyaları
ve indirme sınırları [`corpus-downloads.md`](corpus-downloads.md) içindedir.
Kullanıcı tarafından sağlanan `device-sd/` dosyaları için public indirme URL'si
yoktur; bunlar SD karttan yeniden kopyalanmalı veya mevcut kanıt köküyle
birlikte taşınmalıdır. Public `MUSIC.LIB` ve M3U örneklerinin URL'leri de aynı
manifestte kayıtlıdır.

2025-02-17 için özgün Modification dosyası kanıt kökünde yoktur; o sürümün
klasör açıklaması manifestteki tek kayıtlı nottur. Doğrulama aracı bunu INFO
olarak bildirir ve sahte bir dosya üretmez.

HEX dosyaları için başka bilgisayarda izlenecek sıra:

1. Tarihli Drive klasöründen `MECHEN_M30.HEX` indir.
2. `corpus-downloads.md` içindeki SHA-256 ile doğrula.
3. `atjboottool` ile çıkar; orijinal HEX, AFI/FWIMAGE logları ve extracted
   üyeleri birlikte sakla.
4. Extracted üyeleri `extracted/<tarih>/fw/` altına koy.
5. `verify_persistent_evidence.py` komutunu tekrar çalıştır.

Ghidra decompile çıktıları yeniden üretilebilir çalışma sonucu değil, mevcut
kanıtın kayıtlı çıktılarıdır. Dosyalar taşınabiliyorsa doğrudan kopyalanmalı;
yeniden üretilecekse Ghidra sürümü ve komut ortamı aşağıdaki gibi kurulmalıdır.

## Araç kurulumu

macOS/Homebrew için:

```sh
brew install ghidra openjdk@25 mipsel-linux-gnu-binutils
```

Headless Ghidra çalıştırırken Java ve MIPS araçlarını PATH'e ekle:

```sh
export PATH="$(brew --prefix openjdk@25)/bin:$(brew --prefix mipsel-linux-gnu-binutils)/bin:$PATH"
export GHIDRA="$(brew --prefix ghidra)/libexec/support/analyzeHeadless"
```

`tools/emu/run_static_suite.sh`, Ghidra'nın geçici home/cache dizinlerini
kendisi oluşturur. Tek bir probe çalıştırılıyorsa aynı nedenle geçici bir
`JDK_JAVA_OPTIONS=-Duser.home=...` değeri gerekebilir.

## Devam komutları

Önce depo ve kanıt kökünü doğrula:

```sh
git status --short
python3 tools/verify_persistent_evidence.py /path/to/m30-evidence
```

Kanıt kökü, probe'ların beklediği `analysis/<corpus>/fw` yollarına bağlandıktan
veya kopyalandıktan sonra host-only suite her zaman çalışır:

```sh
bash tools/emu/run_static_suite.sh --host-only
```

Tam suite için ayrıca şu altı repo dışı corpus yolu gerekir:

```text
analysis/stock/fw
analysis/community/fw
analysis/first-pass-verify/fw
analysis/v1.101.11/fw
analysis/v1.101.12/fw
analysis/v5/fw
```

Bu yollar şu an repo içinde bilerek boş tutuluyor. Kanıt kökündeki 12 tarihli
çıktılar, tarih karşılaştırması için eksiksizdir; ancak altı tarihsel suite
etiketinin hangi release'e eşleneceği repo tarihçesinin provenance kuralıdır.
Eşleme yapılmadan `analysis/*` içine rastgele kopya koymak yerine ilgili
commit/findings belgesindeki kaynakla birlikte bağla.

## Taşınmayan ve eksik sayılmayan şeyler

- `build/qemu-minimal-safe/work/verify/` yeniden üretilebilir geçici build
  çıktısıdır; Git'e veya kanıt arşivine gerekmez.
- Ghidra proje cache'leri ve Java temp dizinleri taşınmaz.
- Flashlanabilir HEX, cihaz yazma işlemi ve SD kart değişikliği bu handoff'un
  parçası değildir.
- `analysis/*/fw` yolları sağlanmadıkça host-only testler çalışır; tam suite
  preflight'ta bilinçli olarak durur.

Bu sınırlar korunduğu sürece başka bilgisayarda kod, kanıt, hash ve Ghidra
çıktısı kaybı olmadan devam edilebilir.
