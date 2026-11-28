# Public repository provenance policy

Bu belge hukuki görüş değildir. Reverse-engineering çıktılarının public GitHub
repo'sunda tutulurken hangi ayrımın yapılacağını kayıt altına alır.

## Lisans kapsamı

Kök `LICENSE` dosyasındaki MIT lisansı yalnızca bu projeye özgün kod ve
dokümantasyon katkılarını lisanslar. MECHEN, community firmware veya üçüncü
taraf araçların telifini devretmez.

## Public repo için riskli içerik

Bunlar public dağıtıma açılmadan önce ayrıca lisans/telif incelemesi ister:

- firmware üyeleri ve image/resource dump'ları (`*.ap`, `*.drv`, `*.bin`,
  `*.HEX`);
- ham Ghidra/decompiler C çıktısı, uzun disassembly ve generated project
  dosyaları;
- font, bitmap, MCG/STY/resource ve firmware string kopyaları;
- `patches/*.bps` gibi vendor kodundan türetilen instruction farkları;
- `docs/community-diff.md` içindeki geniş ham byte tabloları;
- firmware mantığını doğrudan yeniden kuran veya üçüncü taraf kaynaktan
  kopyalanan replacement C/assembly.

MIT lisansı bu sınıfları otomatik olarak güvenli hâle getirmez. Özellikle BPS
patch'leri tam image'dan küçük olsa da yeniden dağıtım ve türev eser riski
taşıyabilir.

## Daha güvenli public içerik

Tercih edilen public yüzey şunlardır:

- özgün host-side emulator modeli, test ve probe harness'ları;
- kısa davranış sözleşmeleri, adres/hash/provenance kayıtları ve gerekli kısa
  byte kontrolleri;
- bilinmeyenleri açıkça fail-closed bırakan analiz notları;
- lisansı/provenance'ı açıkça belirtilmiş üçüncü taraf araç entegrasyonları.

Model kodu firmware'in ham kodunu veya uzun decompile parçalarını taşımamalı;
yalnızca doğrulanmış gözlenebilir davranışı bağımsız bir tasarımla ifade
etmelidir.

## Bu çalışma ağacındaki kural

Ham artwork fallback decompile excerpt'i `analysis/local-evidence/` altında
tutuluyor. Bu kök `.gitignore` tarafından dışlanır; `docs/` altında ham C
excerpt'i tutulmaz. Public doküman yalnızca bulguyu, sınırları ve yeniden
üretim adresini özetler.

Firmware ve historical corpus dosyaları da tracked değildir. Public commit'ten
önce `git ls-files`, `git status --ignored` ve yayınlanacak archive/release
artifact'ları ayrıca kontrol edilmelidir. Bir dosya geçmişte push edildiyse
çalışma ağacından silmek yeterli değildir; Git geçmişi ve release asset'leri
ayrıca temizlenmeden risk ortadan kalkmaz.
