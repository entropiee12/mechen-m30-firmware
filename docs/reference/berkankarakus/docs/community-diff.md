# Community V1.292.00: stock'a göre ikili fark

Bu dosya `tools/` ile değil, `atjboottool` ile açılmış üyeler karşılaştırılarak
elle üretildi. Kaynak: Dev-Zetta community firmware V1.292.00
(`5e17d883f697a67d4face6f624ddc30952c5542c918b219be974bf380383ed2c`), stock
V1.101.10 (`6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258`).
Bölgeler, aralarında 16 bayttan az eşit bayt kalan farklı baytların birleşimidir.
Hiçbir üyenin boyutu değişmiyor. Diğer 91 üye stock ile bayt bayt aynı.
Donanımda test: community sürümü kendi notlarında kendi cihazında kabul edildiğini
söylüyor; bu depodaki cihazda ayrıca test edilmedi.

Risk sınıfı yalnızca bayt sayısına ve belgelenmiş amaca göre tahmindir
(düşük: tek bayt/küçük; orta: yüzlerce bayt; yüksek: binlerce bayt kod). Davranış
eşlemesi için bkz. `docs/community-integration.md`.

## config.ap

- Stock SHA-256 (üye): `118c7f7af839901b803e331da19359ddf842fd956b4186290c727eb31bbc5470`
- Community SHA-256 (üye): `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580`
- Boyut: 107520 (değişmedi)
- Farklı bayt: 1, bölge: 1
- Risk (tahmin): düşük

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x1e0d` | 1 | `22` | `10` |

## udisk.ap

- Stock SHA-256 (üye): `0cc4c679d956f56e29d9fd3f9d5fe53e2134b072db07eba8e542468d63a56548`
- Community SHA-256 (üye): `a7c5a1fe9b73601bfad849fc71cff7f5c919b39f740e92d5e1864435f0c1cae4`
- Boyut: 23040 (değişmedi)
- Farklı bayt: 1, bölge: 1
- Risk (tahmin): düşük

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x1909` | 1 | `22` | `10` |

## setting.ap

- Stock SHA-256 (üye): `c0693d3750e1b92ad18b0ed741e893e5cffe3bc4517abdc086c54d539e2eb7d5`
- Community SHA-256 (üye): `ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7`
- Boyut: 101888 (değişmedi)
- Farklı bayt: 7, bölge: 1
- Risk (tahmin): düşük

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x310d` | 17 | `3130312e313000323032352d30362d3235` | `3239322e303000323032362d30392d3239` |

## mainmenu.ap

- Stock SHA-256 (üye): `260294b3d7ce422c1d6f936be5111e5100ac15e511f7a1fad3b65c7e8cc5e708`
- Community SHA-256 (üye): `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`
- Boyut: 147968 (değişmedi)
- Farklı bayt: 3, bölge: 1
- Risk (tahmin): düşük

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x93f0` | 7 | `4467a7d9c8d94c` | `0824a7d9c8d98c` |

## playlist.ap

- Stock SHA-256 (üye): `7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`
- Community SHA-256 (üye): `184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403`
- Boyut: 133632 (değişmedi)
- Farklı bayt: 137, bölge: 7
- Risk (tahmin): orta

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x310c` | 22 | `4367483269e24432e0f072ac49e33ff6004ae0f052cd` | `e0f052ac8267483289e2443269e2d04ae0f052cd0065` |
| `0x8e2e` | 4 | `44996599` | `80f20110` |
| `0x8ff4` | 8 | `5867015a40f10960` | `03600a7240f10961` |
| `0x9078` | 4 | `44998267` | `60f11410` |
| `0x92ac` | 4 | `44994142` | `20f01410` |
| `0x9318`-`0x9381` | 106 | `34dbd6bf55d0d075…` (SHA-256 ön eki) | `45d0b7aff53c74f2…` |
| `0xa5ed` | 3 | `844082` | `a440a2` |

## mengine.ap

- Stock SHA-256 (üye): `1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d`
- Community SHA-256 (üye): `b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49`
- Boyut: 59904 (değişmedi)
- Farklı bayt: 248, bölge: 7
- Risk (tahmin): orta

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x98c` | 14 | `42b2409a609a47996eea015a0961` | `4fb2409a086b6cea00f00a2a0065` |
| `0xabc` | 20 | `91643d6703b3016a40c3b9651164a0e83e5cc19f` | `02b2016b60c220e83e5cc19f500016c000650065` |
| `0x30ae` | 6 | `08b308b240a2` | `032207b34467` |
| `0x30fe` | 5 | `06b3006a40` | `022205b380` |
| `0x3160` | 4 | `d6640401` | `5d110065` |
| `0x3332` | 4 | `26b246d9` | `68100065` |
| `0x3404`-`0x34db` | 216 | `a5645e7a3fa0866c…` (SHA-256 ön eki) | `f864f0291ee28be3…` |

## browser.ap

- Stock SHA-256 (üye): `b6e25204d0844b3731292016936b327d70c958e8f2ba46c59216c7633eb8f2f4`
- Community SHA-256 (üye): `10b0e5d8ed725a76ebce0bfa6e843427115d55b59216fd461a22703e3fed34b3`
- Boyut: 140288 (değişmedi)
- Farklı bayt: 1200, bölge: 4
- Risk (tahmin): orta

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x46cc` | 3 | `e12641` | `59264d` |
| `0xec58`-`0xf12f` | 1240 | `be35dae855452c2d…` (SHA-256 ön eki) | `793226ddb95ae4d0…` |
| `0x18e6a` | 1 | `04` | `08` |
| `0x18ef0` | 1 | `04` | `08` |

## music.ap

- Stock SHA-256 (üye): `faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193`
- Community SHA-256 (üye): `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3`
- Boyut: 224256 (değişmedi)
- Farklı bayt: 17028, bölge: 42
- Risk (tahmin): yüksek

| Ofset | Uzunluk | Stock | Community |
|---|---|---|---|
| `0x38` | 1 | `78` | `d8` |
| `0x21e4`-`0x2233` | 80 | `5b6fb58e61fa4759…` (SHA-256 ön eki) | `5be83d9593dc698c…` |
| `0x3116` | 3 | `e0186e` | `401879` |
| `0x9e00` | 4 | `67416143` | `a0f11a10` |
| `0x9f5c` | 6 | `5867015a2261` | `246004720a60` |
| `0xa179`-`0xa199` | 33 | `7f9c9e31ac8256ca…` (SHA-256 ön eki) | `36d2eeb3f90ca654…` |
| `0xaa12` | 21 | `5c6a40f040c15fb24dd95c6a40f041c15eb24ed95c` | `5b6a40f040c15fb24dd9336a40f041c15eb24ed933` |
| `0xaa90`-`0xaaad` | 30 | `b99989efeb74187d…` (SHA-256 ön eki) | `d5cae8ae994cbefa…` |
| `0xab2c` | 2 | `1db2` | `b217` |
| `0xc488` | 4 | `10f0066a` | `40a90065` |
| `0xd615` | 1 | `2c` | `f4` |
| `0xed50` | 4 | `4e6a58c1` | `20f10610` |
| `0xefa0`-`0xeff3` | 84 | `4fea5e6a3ec5f547…` (SHA-256 ön eki) | `0935b2cf119107e6…` |
| `0x115cc` | 3 | `e12625` | `112575` |
| `0x120c0`-`0x12193` | 212 | `601cafda85faa024…` (SHA-256 ön eki) | `d783b1b192025529…` |
| `0x12340` | 10 | `9927557109b91d785129` | `0125557109b91d780125` |
| `0x12eee` | 2 | `41c3` | `0065` |
| `0x12fa0` | 8 | `01725867015a0360` | `025a006500650361` |
| `0x13108` | 4 | `63ea2060` | `43eb2061` |
| `0x132a4` | 3 | `905f01` | `dc0500` |
| `0x154f0` | 7 | `4467a7d9c8d94c` | `0824a7d9c8d98c` |
| `0x16550`-`0x168fb` | 940 | `ed4d01b36d154179…` (SHA-256 ön eki) | `f213aab3436b11b4…` |
| `0x1a6b0`-`0x1a81f` | 368 | `5f117d68f86f5409…` (SHA-256 ön eki) | `8d9f0cde9b57cbe8…` |
| `0x1aeac`-`0x1b00b` | 352 | `434386abbdfe422c…` (SHA-256 ön eki) | `5cb7dee70ba7d318…` |
| `0x1b090`-`0x1b0cb` | 60 | `23a854d6e102a346…` (SHA-256 ön eki) | `7e74458f9bd05cfc…` |
| `0x1b0dc`-`0x1b313` | 568 | `fa8207b9dd9918d3…` (SHA-256 ön eki) | `fa6d2d3c4f02aeda…` |
| `0x1b600`-`0x1bde0` | 2017 | `aabccf0ea94d9dd2…` (SHA-256 ön eki) | `d51b4424a4545301…` |
| `0x1bffc`-`0x1c097` | 156 | `0392b906f9053ea8…` (SHA-256 ön eki) | `61a69de6efbe739b…` |
| `0x1c0b0`-`0x1c377` | 712 | `14bea2954c31e879…` (SHA-256 ön eki) | `4f78a8a3c33d5790…` |
| `0x1c66c`-`0x1c6df` | 116 | `3dbb5cdfa5b31f5a…` (SHA-256 ön eki) | `47a9e2de518b47c8…` |
| `0x1c6f0`-`0x1c7f7` | 264 | `593bf4980c90cbc6…` (SHA-256 ön eki) | `8a5a906b7bd9c8ad…` |
| `0x1cac4`-`0x1cba7` | 228 | `14cec0a20b55384a…` (SHA-256 ön eki) | `d7a86e8502bac405…` |
| `0x1cf00`-`0x1d3ab` | 1196 | `3c6ce8c2cd1f22ab…` (SHA-256 ön eki) | `89915a66ef6053d5…` |
| `0x1d400`-`0x1dac7` | 1736 | `452dfb5ed1687665…` (SHA-256 ön eki) | `3333d401db8e3b65…` |
| `0x1dc00`-`0x1de1b` | 540 | `c79b7d20b339438c…` (SHA-256 ön eki) | `57a9ca0a45ca8b5b…` |
| `0x1df2c`-`0x1e332` | 1031 | `7a1ed7758020bfd2…` (SHA-256 ön eki) | `6e6c560fa69c22fa…` |
| `0x20410`-`0x20ad7` | 1736 | `5143e23147bfac51…` (SHA-256 ön eki) | `f922967917d432d4…` |
| `0x2240c`-`0x22abb` | 1712 | `c91b51d19b6b61f2…` (SHA-256 ön eki) | `2187a231fd760be6…` |
| `0x22c0d`-`0x2335f` | 1875 | `1ffc5c777dbbac64…` (SHA-256 ön eki) | `490fc7f1c0154fad…` |
| `0x2340c`-`0x239db` | 1488 | `8aa01aabc92db2f9…` (SHA-256 ön eki) | `b3b67a2c18aa1ada…` |
| `0x2d76a` | 1 | `04` | `08` |
| `0x2d7f0` | 1 | `04` | `08` |

