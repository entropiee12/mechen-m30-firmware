# Claude promptu

Mechen M30 firmware için aşağıdaki işleri mevcut kanıtlara dayanarak
incele ve ilerlet:

1. **Custom EQ neden duyulmuyor?** Son Custom EQ denemesinde kullanıcı
   `Custom` ile `Off` arasında ses farkı duymadı. Cihaz fotoğrafında yedi
   slider da `0 dB` çizgisinde görünüyor. Son EQ değişikliklerinin Custom
   menüsünün gösterip kaydettiği değerlerle ses yoluna gönderilen değerleri
   nasıl ilişkilendirdiğini takip et. Sabit eğriye yönlendirilen producer'ın
   DSP/codec tarafında gerçekten uygulandığı kanıtlanmış mı, belirle. UI,
   saklanan değer, mesaj/payload ve ses tüketicisini tek çağrı zincirinde
   bağla. Kök nedeni bulmadan yeni EQ eğrisi ya da flash imajı üretme.

2. **Browser'da uzun isimler neden bazen kaymıyor ve kesiliyor?** Now Playing
   ekranındaki marquee'nin çalışmasını browser için kanıt sayma. Directory
   kaydından 50 byte'lık isim slotuna, ölçüm/kaydırma kararına ve yeniden
   çizime giden gerçek yolu takip et. Hangi isimlerin scroll başlatmadığını
   açıklayan koşulu bul; slotta zaten kesilmiş metni kaydırmayla kurtarmaya
   çalışma.

3. **Kaydırırken görünen kırmızı dikdörtgen nereden geliyor?** Browser
   satırının arka planını, metin çizimini, clip/window sınırlarını ve
   scroll sırasında yapılan background restore/redraw çağrılarını izleyerek
   artefaktı üreten çizim veya kaynak davranışını belirle. Genel renk değişimi
   önermek yerine kırmızı alanın hangi çağrı/veriyle çizildiğini kanıtla.

4. **SD kart takılınca indeksleme neden tetikleniyor?** Kart cihaz açıkken
   takıldığında indeksleme başladığı gözlemini, upgrade sonrası indeksleme
   başladığı ve normal kapatıp açmada başlamadığı gözlemleriyle karşılaştır.
   Card-insertion, boot, USB ve upgrade event'lerinden gerçek scan/index
   caller'ına kadar yolu izle. Tetikleyici event'i kanıtla ve otomatik
   indekslemeyi kapatmanın mevcut mimaride mümkün olup olmadığını değerlendir.

5. Bu bulgular ışığında `docs/feature-roadmap.md` içindeki UI/upgrade,
   playback/fade-out, gapless, play counter, USB-DAC ve MUSIC.LIB işlerinin
   önceliğini güncelle. En yüksek getirili sonraki işi seç ve kanıtlanabilir
   ilerleme kaydet.

Her başlıkta doğrulanmış bulguları hipotezlerden ayır; ilgili fonksiyon,
çağrı yolu, byte farkı veya mevcut probe ile destekle. Sonuçları ve kalan
belirsizlikleri dokümante et. Flash imajı ancak ilgili kök neden çözülüp paket
ve davranış doğrulaması yapılabiliyorsa hazırla.
