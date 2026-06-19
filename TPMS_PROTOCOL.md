# TPMS USB vevő — soros protokoll

Egy külön, aftermarket **433 MHz TPMS USB vevő** (CH340 soros chip) protokollja,
visszafejtve valós adat + a [tomaskovacik/TPMS_sensor_receiver_serial_protocol](https://github.com/tomaskovacik/TPMS_sensor_receiver_serial_protocol)
spec alapján. Ez **nem** a Prius gyári CAN TPMS-e, hanem egy önálló utólagos
készlet (4 kerék + pótkerék = 5 szenzor).

## Fizikai réteg
* CH340 USB-soros (`ch341-uart`), **19200 baud, 8N1**.
* WSL2 alól `usbipd-win`-nel csatolva: `/dev/ttyUSB0`.

## Keretformátum
```
55 AA | CMD | POS | D0 D1 D2 | XOR
└sync─┘  │     │   └payload─┘   └ XOR(minden byte 55..D2)
```
* **`55 AA`** – fix sync header.
* **`CMD`** – `0x08` = szenzor adatkeret (broadcast). Parancsoknál `0x06`,
  válasznál `0x09`.
* **`POS`** – kerék-pozíció (lent).
* **`D0 D1 D2`** – hasznos adat.
* **`XOR`** – XOR az összes byte-on a checksum előtt. (16/16 valós kereten
  validálva. A GitHub-doksi a parancs-példánál tévesen összeget ír.)

**Keret-hossz: nincs hossz-mező — CMD-függő.** A parser a 3. bájt (CMD) alapján
hosszoz: `0x08` adatkeret = **8 bájt**, `0x09` ID-válasz = **9 bájt**, parancsok =
6–7 bájt. Fix-hossz feltételezés hibás (a párosítás `0x09`-válaszát elrontaná).

## Kerék-pozíciók
| POS  | kerék |
|------|-------|
| 0x00 | első bal (FL) |
| 0x01 | első jobb (FR) |
| 0x10 | hátsó bal (RL) |
| 0x11 | hátsó jobb (RR) |
| 0x05 | pótkerék (SP) |

## Adat-dekódolás (`0x08` keret)
| mező | képlet | megjegyzés |
|------|--------|-----------|
| nyomás | `kPa = D0 × 3.44`, `bar = kPa / 100` | valós autón visszaigazolva: D0=0x40 → 2,20 bar; pótkerék D0=0x2e → 1,58 bar (~1,7) |
| hőmérséklet | `°C = D1 − 50` | hozzávetőleg stimmel (~30 °C); melegítős teszttel pontosítható |
| flag | `D2` bitmező | státusz/riasztás bitek — lásd külön szekció lent |

## D2 — flag / státusz byte

A `D2` egy bitmező. Két fontos forrás van, és **ellentmondanak egymásnak** ezen a
példányon, ezért külön kezeljük a *megfigyelt* és a *doksi-szerinti* értékeket.

**Megfigyelt értékek (valós autó, 2 felvétel):**
* 1. felvétel: `0x20` négy szenzoron (FL/FR/RL/SP), `0x00` az RR-en.
* 2. felvétel (~percekkel később): `0x00` **mindegyiken**.

A `0x20` (bit5) **magától lenullázódott** egészséges, parkoló szenzorokon → ez
nagy valószínűséggel **nem riasztás**, hanem átmeneti állapot (ébredés / mozgás /
friss-adat jelző). Egy valódi alarm álló autón nem oltódna ki magától.

### Bit-térkép (bit7 … bit0)
| bit | maszk | feltételezett jelentés | nálunk megfigyelt | bizonyosság | hogyan igazoljuk |
|-----|-------|------------------------|-------------------|-------------|------------------|
| 0 | 0x01 | nyomás-alarm (alacsony / gyors szivárgás)? | – | alacsony | egy kerékből gyors leeresztés |
| 1 | 0x02 | ? (esetleg over-pressure) | – | – | túlnyomás |
| 2 | 0x04 | ? | – | – | – |
| 3 | 0x08 | **szivárgás (leak)** — doksi szerint | – | közepes (doksi) | szelepen lassú eresztés mérés közben |
| 4 | 0x10 | **gyenge elem (low-batt)** / jel-vesztés — doksi + felhasználói tipp | – | közepes | ismert gyenge szenzorral / hosszú némaság után |
| 5 | 0x20 | **mozgás / ébredés / friss-adat** (NEM alarm) | igen: 4 szenzoron, majd magától 0 | közepes | szenzort megrázni / pörgetni, figyelni mikor áll vissza 1-be |
| 6 | 0x40 | ? | – | – | – |
| 7 | 0x80 | ? | – | – | – |

### Teendő a pontos megfejtéshez (kalibrációs menet)
A biteket csak **kontrollált eseménnyel** lehet biztosan rögzíteni, `tools/tpms.py raw`
mellett figyelve, hogy melyik `D2` bit billen:
1. **Mozgás (bit5):** egy szenzort megrázni/pörgetni → figyelni, mikor áll vissza
   a bit, és mennyi idő után esik le újra (ettől derül ki, valóban wake/motion-e).
2. **Gyenge elem (bit4 gyanús):** egy ismerten gyenge/öreg szenzorral, vagy hosszú
   álló időszak után.
3. **Nyomás-alarm (bit0/bit3 gyanús):** egy kerékből gyorsan leengedni a nyomást a
   küszöb alá → melyik bit gyullad ki.
4. **Jel-vesztés:** egy szenzort fémdobozba zárni / kivinni hatótávon kívülre.

> Amíg ezek nincsenek megerősítve, az app-integrációban a `D2`-t **nyersen is
> jelenítsük meg** (hex), és csak a megerősített biteket fordítsuk emberi
> üzenetté. A `0x20`-at **ne** kezeljük riasztásként.

## Parancsok (host → vevő)
XOR checksummal, készen küldhető byte-sorok:
| parancs | bytes | jelentés |
|---------|-------|----------|
| Query   | `55 AA 06 07 00 00 FE` | összes szenzor-ID lekérése |
| Pair FL | `55 AA 06 01 00 00 F8` | tanuld be első bal pozícióra |
| Pair FR | `55 AA 06 01 01 00 F9` | első jobb |
| Pair RL | `55 AA 06 01 10 00 E8` | hátsó bal |
| Pair RR | `55 AA 06 01 11 00 E9` | hátsó jobb |
| Pair SP | `55 AA 06 01 05 00 FD` | pótkerék |
| Pair stop | `55 AA 06 06 00 00 FF` | tanuló mód vége |
| Heartbeat | `55 AA 06 19 00 E0` | keep-alive |

> **Heartbeat opcionális:** nulla TX mellett is folyamatosan jön az adat, tehát a
> vételhez nem kell küldeni semmit. (A heartbeat csak ha a vevő idővel elnémulna.)
>
> **A doksi Reset (`55 AA 06 58 55 E0`) parancsa megbízhatatlan** — a CRC se XOR-ral
> (`F4`), se összeggel (`B2`) nem jön ki. Ne használd, amíg élesben nem igazolt.

### Checksum-fallback (ha a vevő XOR-parancsot nem fogad el)
A parancs-checksum élesben **még bizonyítatlan** (eddig csak hallgattunk). A doksi a
query-nél `0x0C`-t ír, ami **összeg** (low-byte), nem XOR. Ha az XOR-os parancsokra
nincs reakció, próbáld ugyanezeket **összeg-CRC**-vel:
| parancs | összeg-CRC változat |
|---------|---------------------|
| Query   | `55 AA 06 07 00 00 0C` |
| Pair FL | `55 AA 06 01 00 00 06` |
| Pair FR | `55 AA 06 01 01 00 07` |
| Pair RL | `55 AA 06 01 10 00 16` |
| Pair RR | `55 AA 06 01 11 00 17` |
| Pair SP | `55 AA 06 01 05 00 0B` |
| Pair stop | `55 AA 06 06 00 00 0B` |
| Heartbeat | `55 AA 06 19 00 1E` |

**Párosítás menete:** küldd a `Pair <pozíció>` parancsot, aktiváld a kívánt
szenzort (nyomásváltozás: kicsit engedj/pumpálj a gumin, ettől sugároz), a vevő
beírja a hallott ID-t arra a pozícióra; végül `Pair stop`. ID-lekérés válasza:
`55 AA 09 [POS] [ID0 ID1 ID2 ID3] [XOR]` — **9 bájtos keret**, a parsernek CMD=0x09-re
ezt a hosszt kell vennie (lásd a hossz-megjegyzést a keretformátumnál). A párosítás
sikere ezen a `0x09`-en **vagy** az adott `POS` adatkeret újraindulásán látszik —
melyik a tényleges visszajelzés, az **élesben igazolandó**.

## Eszköz
`tools/tpms.py` — élő dekóder és parancsküldő:
```
python3 tools/tpms.py monitor      # nevesített kerekek, bar + °C
python3 tools/tpms.py raw          # nyers frame dump (XOR-ellenőrzéssel)
python3 tools/tpms.py query        # szenzor-ID-k
python3 tools/tpms.py pair FL      # párosítás
python3 tools/tpms.py pair-stop
```
`tools/tpms_sniff.py` — baud-felderítő (ha ismeretlen eszközhöz kell).

## Nyitott kérdések
1. Hőmérséklet-skála pontosítása ismert hőmérsékletű (melegített) szenzorral.
2. `D2` flag-bitek jelentése (low-batt / leak / signal-lost) valós eseménnyel.
3. Parancs-checksum: ha a vevő XOR-os parancsot elutasít, próbálni a low-byte
   összeget (a doksi ezt sugallja).
4. Integráció: a dekódolt 5×(nyomás,hőm.) bekötése a `KEYS[]`/`CanState` JSON
   kontraktusba és a HA MQTT discovery-be (külön soros forrás az ESP32 mellett).
