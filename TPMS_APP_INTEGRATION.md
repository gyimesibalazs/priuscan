# TPMS integráció az Android appba — specifikáció

A `TPMS_PROTOCOL.md`-ben visszafejtett 433 MHz TPMS USB-vevő (CH340 @ 19200,
`55 AA | CMD | POS | D0 D1 D2 | XOR`) beépítése a `hu.codingo.priuscan` appba.
4 fázis: (1) vevő-felismerés + státusz, (2) érték-megjelenítés a renderelt autón,
(3) párosítás/csere UI, (4) — következő menet — defekt-detekció + riasztás.

## Architektúra-döntés: külön, második soros olvasó

A TPMS egy **második USB-soros eszköz** az ESP32 mellett, eltérő baud (19200 vs
115200) és **bináris** keretezéssel (nem JSON). A jelenlegi `CanService` IO-loop
(`CanService.kt:128`) egyetlen eszközt nyit és a nem-JSON forrásokat — így a
TPMS-t is — `rejected`-be teszi (`CanService.kt:169`).

**Megoldás:** dedikált TPMS olvasószál ugyanabban a foreground service-ben, ami
**csak** a CH340-et (VID `0x1A86`) célozza 19200-on, és `55 AA` keret-validációval
igazol. A két olvasó nem ütközik:
* az ESP-olvasó `exclude`-ba teszi a CH340 `deviceId`-t (ne pazaroljon rá 4 s
  reject-kört),
* a TPMS-olvasó kizárólag a CH340 VID-et fogadja el.

Ezzel az ESP-ág (`SerialLink`/JSON/`CanState`) **érintetlen** marad.

---

## Fázis 1 — vevő-felismerés és „connected" státusz

### Új: `TpmsLink.kt`
A `SerialLink` mintájára, de:
* nyitás **19200 8N1**-en (a `SerialLink.BAUD` jelenleg fix 115200 →
  paraméterezni kell: `open(exclude, baud = BAUD)`),
* eszközszűrés VID `0x1A86`-ra (`UsbSerialProber.getDefaultProber()` a CH340-et
  `Ch34xSerialDriver`-rel már felismeri),
* validáció: első helyes-XOR `55 AA … ` keret X ms-en belül → ez a TPMS.

### Keret-parser
Reszinkron `55 AA`-ra, majd a 3. bájt (CMD) alapján **CMD→hossz** leképezés, és
az utolsó bájt = `XOR(összes előző)` ellenőrzés. **Nincs hossz-mező a protokollban**,
ezért a fix-hossz feltételezés hibás — a kereteket CMD szerint kell hosszozni:

| CMD | jelentés | teljes hossz |
|-----|----------|--------------|
| 0x08 | szenzor adatkeret | 8 bájt |
| 0x09 | ID-válasz (`55 AA 09 POS ID0 ID1 ID2 ID3 XOR`) | **9 bájt** |
| 0x06 | parancs-echo (ha van) | 6–7 bájt |

Ismeretlen CMD-nél: keress a következő `55 AA`-ig, vagy próbáld a jelölt hosszokat
és fogadd el azt, amelyikre az XOR stimmel. **A párosítás visszaolvasása a 9 bájtos
`0x09`-en jön — a fix-8 parser ezt elrontaná** (ez a hiba a `tools/tpms.py frames()`-ben
is ott van, javítandó).

### Állapot + dekódolás — `TpmsState.kt`
```
enum class Wheel(val pos: Int) { FL(0x00), FR(0x01), RL(0x10), RR(0x11), SPARE(0x05) }
data class TireReading(
    val bar: Float,      // D0 * 3.44 / 100
    val tempC: Int,      // D1 - 50
    val flags: Int,      // D2
    val ts: Long,        // utolsó frissítés
)
```
`CanService.companion`-be új flow-k a meglévők mellé (`CanService.kt:36`):
```
val tpms = MutableStateFlow<Map<Wheel, TireReading>>(emptyMap())
val tpmsConnected = MutableStateFlow(false)
val tpmsDevice = MutableStateFlow<String?>(null)
```
`tpmsConnected = true` csak valódi keret beérkezésekor (mint az ESP-nél a
`connected`). Egy kerék **stale**, ha `now - ts > 30 s` (a vevő ~1 keret/s/pozíció).

### UI — Header
A `Header` (`MainActivity.kt:154`) második státuszsora: zöld/piros pötty +
„TPMS: csatlakozva · {tpmsDevice}" / „nincs jel". A meglévő ESP-pötty mellé.

---

## Fázis 2 — értékek a renderelt autón

### Elrendezés
A `MainScreen` `LazyColumn`-jába (`MainActivity.kt:118` után, a `Fields` blokk
elé) egy **TPMS-kártya**:
* **Felül**: felülnézeti autó-grafika (a meglévő `car_00.png` assetet újrahasznosítva
  háttérként — minden ajtó zárva sziluett), a 4 sarokban + a pótkeréknél overlay-elt
  felirat.
* **Kerékenként**: nagy **nyomás** (`2,2 bar`), alatta kisebb **hőmérséklet**
  (`37°C`) — pont a kért „nyomás felül, hőfok alatta" elrendezés, a megfelelő
  kerék mellé pozícionálva.

### Komponens — `TpmsCarView` (Compose)
`Box`: háttér `Image(car_00.png)`, fölötte 4 `WheelChip` abszolút pozícióval
(FL bal-fent, FR jobb-fent, RL bal-lent, RR jobb-lent) + 1 `WheelChip` a pótkeréknek
(lent középen vagy külön sor). A pozíciók a kép arányához kötve (`BoxWithConstraints`).
```
@Composable fun WheelChip(r: TireReading?, target: ClosedFloatingPointRange<Float>) {
    val color = when {
        r == null || stale -> Grey
        r.bar !in target   -> Amber/Red
        else               -> White
    }
    Column { Text("${r.bar} bar", big, color); Text("${r.tempC}°C", small, color) }
}
```

### Megjegyzés a sarok-leképezésről
A door-bitmask kvírk (`CLAUDE.md` 6. pont, 0x80↔Door_fr) **csak az ajtó-meshekre**
vonatkozik; a TPMS `POS` mezője a fizikai keréknek felel meg (FL/FR/RL/RR), itt
nincs felcserélés. A vizuális bal/jobb leképezést a valós autón **egyszer
ellenőrizni** kell (pl. egy kerékből leengedett nyomás melyik chipet mozdítja).

---

## Fázis 3 — párosítás / csere UI

### Parancsküldés — szálkezelés
A portot a TPMS-olvasószál birtokolja, így a parancsokat **annak a szálnak** kell
kiküldenie. Egy `ConcurrentLinkedQueue<ByteArray>` a `TpmsLink`-ben; az olvasó-
ciklus minden iterációban kiüríti `port.write()`-tal. Parancs = `55 AA … + XOR`
(a `tools/tpms.py build()` szerint).

### Parancstár (kész bájtsorok a `TPMS_PROTOCOL.md`-ből)
| művelet | bytes |
|---------|-------|
| Query   | `55 AA 06 07 00 00 FE` |
| Pair FL/FR/RL/RR/SP | `55 AA 06 01 {POS} 00 {XOR}` |
| Pair stop | `55 AA 06 06 00 00 FF` |
| Heartbeat | `55 AA 06 19 00 E0` |

> Checksum-óvintézkedés: elsőként XOR-t küldünk (a valós keretek és a heartbeat
> így stimmelnek). Ha a vevő nem reagál, fallback a low-byte **összegre** (a
> GitHub-doksi ott önellentmondó). `TpmsLink`-be kapcsolóval.

### UI — `SettingsScreen` „TPMS" szekció
Gombok: **ID-k lekérése** (`query`), **Párosítás: FL / FR / RL / RR / Pótkerék**,
**Párosítás vége**. A „csere" = ugyanaz a pair-gomb az adott pozícióra (felülírja a
tanult ID-t).

### Párosítási folyamat (UX)
1. Tap „Párosítás FL" → app küldi a pair-parancsot + dialog: „Aktiváld az első bal
   szenzort (engedj/pumpálj a gumin)", visszaszámláló (~30 s).
2. Olvasó figyeli a `0x09` ID-választ (`55 AA 09 POS ID0..3 XOR`) **vagy** az adott
   `POS` adatkeret újraindulását → siker, megjeleníti a tanult ID-t.
3. Auto **Pair stop**, dialog bezárul.
4. Tanult ID-k opcionális perzisztálása `Prefs`-be (megjelenítéshez):
   `tpmsIds: Map<pos, id>` JSON-stringként.

---

## Fázis 4 — defekt-detekció + riasztás (következő menet)

A meglévő riasztó-infrastruktúra újrahasznosítható:
* **`WarnGate`** (`CanService.kt:81`) — tartás-idő + nem-ismétlő + újraélesedő;
  per kerék egy gate.
* **`alert()`** (`CanService.kt:224`) — hang (`ToneGenerator`) + overlay; és a
  `notifMgr` státusz-strip.

### Riasztási feltételek
| esemény | feltétel (konfigurálható) |
|---------|---------------------------|
| alacsony nyomás | `bar < cél − tűrés` (alap: első 2,2 / hátsó 2,2 / pótkerék 1,7; tűrés 0,3) |
| gyors nyomásesés | Δbar / Δt meredekség (defekt menet közben) |
| magas hőmérséklet | `tempC > 80` (konf.) |
| flag-bit | leak / low-batt / signal-lost a `D2`-ben (előbb empirikusan azonosítani) |
| nincs jel | kerék `stale` > N s (lehúzott szenzor / akku) |

### Beállítások — `Prefs`
* cél-nyomások pozíciónként (`tpms_target_fl` …), tűrés, magas-hőfok küszöb,
* riasztás be/ki.

### HA integráció — `HaPusher`
Új MQTT discovery entitások: `sensor.tpms_{pos}_pressure` (bar),
`sensor.tpms_{pos}_temperature` (°C), `binary_sensor.tpms_{pos}_alarm`.
A TPMS külön forrás (nem az ESP `KEYS[]` JSON-kontraktus része), de a HA-pusher
ugyanúgy publikálja.

---

## Érintett fájlok
| fájl | változás |
|------|----------|
| `TpmsLink.kt` (új) | CH340 nyitás 19200-on, keret-parser, parancs-queue |
| `TpmsState.kt` (új) | `Wheel`, `TireReading`, dekódoló képletek |
| `CanService.kt` | második olvasószál, `tpms*` flow-k, (F4) riasztás-gate-ek, ESP-olvasó CH340-kizárás |
| `SerialLink.kt` | `open()` baud-paraméter (jelenleg fix 115200, `SerialLink.kt:27,61`) |
| `MainActivity.kt` | Header 2. státuszsor, `TpmsCarView` kártya |
| `TpmsCarView.kt` (új) | felülnézeti autó + sarok-chipek |
| `SettingsScreen.kt` | „TPMS" párosító szekció |
| `Prefs.kt` | (F3) tanult ID-k, (F4) cél-nyomások/küszöbök |
| `HaPusher.kt` | (F4) TPMS MQTT discovery |
| `res/values*/strings.xml` | feliratok (HU/EN) |

## Nyitott pontok / döntést igénylő
1. **Sarok-leképezés** valós ellenőrzése (melyik `POS` melyik fizikai kerék) — egy
   kerékből leeresztett nyomással.
2. **Cél-nyomások** default értékei (gyári Prius3 ajánlás vs. felhasználói).
3. **Pótkerék** megjelenítése: a felülnézeti autón külön chip, vagy önálló sor.
4. **Checksum** parancsoknál: XOR vs. összeg — élő teszten eldől (lásd F3 fallback).
