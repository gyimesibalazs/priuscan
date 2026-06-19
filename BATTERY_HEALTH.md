# HV akku-egészség becslése passzív, folyamatos adatból — kutatási riport

**Kontextus:** Toyota Prius Gen3 (XW30) NiMH nagyfeszültségű pakk. Az ESP32-C3
~2–4 Hz JSON-t logol; az adat Home Assistant / InfluxDB-be (vagy a helyi
JSONL-naplóba) mehet utólagos elemzésre. **Kérdés:** hogyan határozható meg a
pakk egészsége statisztikailag/adatvezérelten, *kizárólag passzívan* mért
jelekből (nincs kontrollált kisütés-teszt)?

**Elérhető jelek:** 14 blokk-feszültség (`b01..b14`), pakk-áram (`hvA`),
blokk-/akku-hőmérsékletek (`tb1..3`, `hvAir`), becsült belső ellenállás
(`maxR`), terhelés-alatti vs nyugalmi feszültség-szórás (`blkD`/`dload`/`drest`),
SoC (`soc`).

> Módszertan: a megállapítások egy multi-ágenses deep-research futásból
> származnak (6 keresési szög, 23 forrás, 104 kinyert állítás → 25 adverzálisan
> ellenőrizve, 21 megerősítve, 4 megcáfolva). A konfidencia- és szavazat-jelzések
> ezt tükrözik.

---

## 0. Tömör összefoglaló

A pakk egészsége **passzívan, kontrollált kisütés nélkül** értékelhető három
módszercsaláddal:

1. **Gyenge blokk / aszimmetria korai detektálása** — a legjobban alátámasztott,
   tisztán passzív: a blokk-feszültség-folyam **statisztikai anomáliadetektálása**
   (mozgóablakos entrópia, Z-score „abnormity coefficient", normalizált CV,
   CVA+KDE+Mahalanobis). Valós flottaadaton **12–23 nappal korábban** jelzett.
2. **Teljes pakk SOH / kapacitás-fakulás / IR-növekedés** — fizikailag
   megalapozott indikátorok: növekvő **belső ellenállás** (SoC- és hő-függő),
   terhelés-alatti **feszültség-depresszió**, fokozott **önkisülés**;
   EOL konvenció = a névleges Ah **80%-a**. Coulomb-számlálás részleges
   ciklusokból + Kalman/UKF/ANN.
3. **Hátralévő élettartam (RUL)** — adatvezérelt **ML** adja a legkisebb átlaghibát;
   **adaptív Kalman** online követi a változó degradációt; NiMH-re a
   **Palmgren–Miner** fáradás-akkumuláció a bevett wear-out modell.

**A fő korlát:** szinte minden erős statisztikai munkát **Li-ion** pakkokon
validáltak. A statisztika kémia-független és átvihető, de a **számszerű
küszöböket NiMH-re újra kell hangolni** (lapos plató, memóriaeffektus,
hőérzékenység, önkisülés).

---

## 1. Cél 1 — gyenge blokk / cella-aszimmetria korai jelzése

Ez a legjobban alátámasztott és tisztán passzív rész. Mind a 14 blokk
feszültség-folyamára épül, kontrollált kisütés nélkül.

| Módszer | Mit ad | Konf. | Forrás |
|---|---|---|---|
| **Mozgóablakos (modified Shannon/energy) entrópia** | a hibás cella **idejét ÉS helyét** előrejelzi a feszültségsorból, online | magas (3-0) | Wang+ 2017; Li+ 2023 |
| **Z-score „abnormity/risk coefficient"** | valós idejű, **adaptív (adat-relatív) küszöb**; korai hibák, amiket a hagyományos módszer kihagy (Z=2.58) | magas (3-0) | Wang+ 2017; *J. Energy Storage* 2025; *Energy* 2024 |
| **Normalizált variációs együttható (CV)** | cellák közti fluktuáció-inkonzisztencia vezetési adatból | magas (3-0) | *Energy* 299 (2024) |
| **CVA + kernel-density kontrollhatár + lokális Mahalanobis** | multivariáns **adaptív baseline** (nem fix küszöb); szétválasztja a kapcsolati hibát az IR-növekedéstől, becsüli a kontakt-ellenállást | magas (3-0) | *Energy* (2025) |

**Detektor-választás (fontos):** SD és MAD a legjobb (~99,4% valós-negatív);
az **IQR túl-jelez** (~5% fals pozitív, mert a konzervatív határ a normál
eloszlás farkát is kilógónak jelöli). → SD/MAD/Z-score, **ne IQR**
(arXiv 2511.01745, 2025, magas 3-0).

**Gyakorlati buktató + validáció:** a nyers entrópia/anomália valós flottaadaton
sok fals pozitívot ad; a **vezetési kontextus** (terhelés) a fő tévedés-ok. Egy
kontextus-tudatos korrekció az átlagos relatív téves diagnózist **81,9%-kal**
csökkentette → a küszöbnek **terhelés/kondíció-tudatosnak** kell lennie, nem a
nyers szóráson kell ülnie (Li+ *J. Energy Storage* 73, 2023, magas 3-0).

---

## 2. Cél 2 — SOH / belső ellenállás / kapacitás-fakulás

A NiMH öregedés passzívan megfigyelhető jelekből (magas, 3-0; ASME IMECE 2006 +
Serrao/Onori/Rizzoni IEEE NiMH HEV aging model):

* kapacitásvesztés, **növekvő belső ellenállás**, alacsonyabb terminálfeszültség,
  gyorsabb hőemelkedés, kisebb töltésfogadás, több önkisülés.
* **Az IR a fő jel, de SoC- és hőmérséklet-függő** → bármilyen IR-trend baseline-t
  SoC+hő binekre kell normálni (a `maxR`/`tb*`/`soc` jeleink megvannak hozzá).

**A feszültség-szórás jelentése terhelésfüggő** (magas, 3-0; Linköping thesis 2024,
V = OCV − I·R): nagy kisütőáramnál az **I·R dominál** → nagy terhelésnél a legjobb
az IR/gyenge-blokk megkülönböztetés; nyugalomhoz közel a kapacitás/SoC. A NiMH
**lapos platóján a nagy-áramú IR-diszkrimináció különösen értékes**. → a
blokk-statisztikát **áramsávra (|hvA|) kondicionálva** számold (high-load vs rest).

**ML/szűrő alapú állapotbecslés:**
* **SoC** becsülhető kis feed-forward neuronhálóval (V/I/hő bemenet, 3-7-1) NiMH-en
  (MDPI WEVJ 14(11) 312, 2023, magas 3-0). Passzív telepítéshez ground-truth
  címke kell — a Prius BMS `soc` szolgálhat tanítójelnek.
* **Kalman:** nemlineáris (UKF) > lineáris/EKF (UKF 1,66% vs EKF 4,42%); az
  **adaptív EKF** online hangolja a modell-paramétereket a változó degradációhoz;
  a **dual-EKF** (egy szűrő SoC/SOH, egy a paraméter-ID-re) a bevett séma
  (közepes, 2-1/3-0). Minden idézett munka Li-ion → NiMH-re ésszerű, de
  extrapolált átvitel.

---

## 3. Cél 3 — hátralévő élettartam (RUL)

* **Adatvezérelt ML** adja a legkisebb átlaghibát (~1,37%) a sztochasztikus
  (~1,41%) és adaptív-szűrő (~2,61%) családok előtt; „a legalkalmasabb,
  relatíve robusztus és számításilag elfogadható" (magas, 3-0; *Frontiers Mech.
  Eng.* review, 2021). Megjegyzés: a különbség marginális, heterogén tanulmányok
  átlaga, nem kontrollált benchmark.
* **Adaptív Kalman** online követi a változó degradációs ütemet (magas, 3-0).
* **NiMH-specifikus wear-out:** a kopás kisütésenként **irreverzibilisen**
  halmozódik → **Palmgren–Miner** fáradás-akkumuláció (rainflow-számlálás a
  strukturálatlan HEV-terhelésen, DOD-vs-ciklusélettartam adat ellen), EOL =
  névleges Ah **80%-a** (magas, 3-0; Serrao/Onori/Rizzoni IEEE; USABC konvenció;
  2020 ScienceDirect NiMH cycle-life). Hiányosság: a 2005-ös modell a naptári
  öregedést elhanyagolja (későbbi modellek k_calendar·t taggal bővítik).

---

## 4. NiMH-specifikus megfontolások (a kémia-transzfer a fő korlát)

* **A küszöböket NiMH-re újra kell hangolni** — a Li-ionon validált entrópia-/
  CV-/Z-score-skálákat és küszöböket **ne másold számszerűen**.
* **Memóriaeffektus** = részben **reverzibilis** feszültség-depresszió → a
  baseline-nak el kell választania a reverzibilis droopot a valódi
  (irreverzibilis) SOH-veszteségtől (pl. rövid + hosszú időállandójú EWMA-pár),
  különben túldiagnosztizál.
* **Lapos feszültség-plató** → az **ICA/DVA** (inkrementális kapacitás /
  differenciális feszültség) valószínűleg **NEM működik** NiMH-en: a plató
  elnyomja a dQ/dV csúcsokat, amikre az ICA épül. **Nyitott kérdés** — egyetlen
  forrás sem fedte le NiMH-re ebben az adathalmazban.
* **Erős hőmérséklet-érzékenység és önkisülés** → SoC/hő-normálás kötelező az
  IR- és feszültség-alapú trendeknél.

---

## 5. Megcáfolt / gyengített állítások (a verifikációból)

* **Coulomb-számlálás önmagában NEM ad** megbízható öregedés-korrigált kapacitást
  egyszerű (nem konstans-áramú) terhelésnél (0-3). → observer/szűrővel kell
  párosítani, és csak jól definiált részciklus-szegmenseken használni.
* **Coulomb-számlálás mint degradáció-kompenzáció** szintén megcáfolva (0-3).
* **„A nyers küszöb mindig elégtelen"** — **nem** élte túl (1-2). → egyszerű
  SD/MAD/Z-score küszöb **első rétegnek továbbra is jó**.
* **„IR önmagában elégtelen SOH-jel"** — nem élte túl (1-2) → az IR önmagában
  hasznosabb, mint az állítás sugallta.

---

## 6. Gyakorlati alkalmazás ebben a projektben

### 6.1 Ami már implementálva van (firmware)
* **Öntanuló gyenge-blokk réteg** (`prius_parse.h`, `learn_update`/`learn_verdict`):
  blokkonkénti Z-score a pakk-átlaghoz képest, **blokkonkénti EWMA**-val tanulva
  (adaptív, adat-relatív baseline — pontosan a kutatás által támogatott irány),
  **csak terhelés alatt** (`|hvA| > 30 A`, mert az I·R kiemeli a gyengét),
  **érettségi küszöbbel** (`LRN_NMIN`). Kimenet: `cwL` (tanult szint), `wblk`
  (leggyengébb blokk 1..14), `wz` (annak EWMA z-je). A `cellW`-be `max(fix, tanult)`
  módon folyik be, így a hő/IR safety-ág megmarad. A tanult állapot
  (`lz[14]+lz_n`) ESPHome global-ba **perzisztál** (5 perces ütem; a flash-írás
  csak változáskor, ≤1/5 perc, gyakorlatilag soha el nem kopik).
  * mean/SD-t használ (nem median/MAD): n=14-nél a MAD 0-ba degenerálódhat.
  * **RELATÍV** eltérést mér (aszimmetria/változás), **nem abszolút SOH-t**.
* **Warning-debounce (app):** a `cellW`/`wpW` csak ≥4 s tartós szintnél riaszt,
  nem ismétel — a tranziens (gyorsításkori) tüskéket elnyeli.

### 6.2 Mit NEM old meg az online tanulás
* **Abszolút SOH / kapacitás / RUL nem tanulható referencia nélkül.** Ha a pakk
  már induláskor öreg, a baseline azt tanulja „normálnak" → csak relatív
  eltérést lát. Abszolút SOH-hoz gyári/known-good referencia vagy offline-fit
  modell kell.
* **Validáció:** az onboard tanulás minőségét csak a felgyűlt **logból** lehet
  ellenőrizni (különben „magát ellenőrzi"). Ezért gyűjt a helyi JSONL-napló.

### 6.3 Javasolt offline elemzés (InfluxDB / notebook)
1. **Áramsávos blokk-Z-score** (`hvA` binek: erős kisütés / nyugalom külön) a 14
   blokkra — SD/MAD-alapú, nem IQR.
2. **`maxR` (IR) trendelés SoC+hő binben** — lassú, irreverzibilis emelkedés =
   valódi SOH-romlás.
3. **Adaptív, tanuló baseline** — blokkonként a saját előzményből; csak a tartós,
   kontextus-konzisztens eltérést riaszd.
4. A jelek **időbeli párosítása**: `hvA` (~3-5 Hz) vs blokk-feszültség (~1 Hz)
   vs delta (külön PID) — interpoláció/legközelebbi minta kell.
5. A küszöbök (`LRN_K1/K2`, `LRN_ALPHA`, `LRN_LOAD_A`) **valós logból** való
   hangolása, majd visszacsorgatás a firmware-be.

---

## 7. Források

**Megerősített, elsődleges (peer-reviewed):**
* Wang et al., *Applied Energy* 196 (2017) — entrópia + Z-score abnormity coefficient:
  https://www.sciencedirect.com/science/article/abs/pii/S0306261916319262
* Li et al., *J. Energy Storage* 73 (2023) — online mozgóablak-entrópia, fals-pozitív korrekció (−81,9%):
  https://www.sciencedirect.com/science/article/abs/pii/S2352152X23026853
* *Energy* 299 (2024) — normalizált CV, risk-coefficient (Z=2.58):
  https://www.sciencedirect.com/science/article/abs/pii/S0360544224012489
* *Energy* (2025) — CVA + KDE kontrollhatár + lokális Mahalanobis:
  https://www.sciencedirect.com/science/article/abs/pii/S0360544225002671
* *J. Energy Storage* (2025) — energy entropy + Z-score:
  https://www.sciencedirect.com/science/article/abs/pii/S2352152X25016718
* arXiv 2511.01745 (2025) — SD/MAD/IQR/Z-score/modified-Z összevetés:
  https://arxiv.org/html/2511.01745
* Serrao, Onori, Rizzoni — NiMH HEV aging / Palmgren–Miner (csak NiMH):
  https://www.researchgate.net/publication/4201365_Aging_model_of_Ni-MH_batteries_for_hybrid_electric_vehicles
* ASME IMECE 2006 — NiMH EIS aging hatások:
  https://asmedigitalcollection.asme.org/IMECE/proceedings/IMECE2006/47683/323032
* MDPI WEVJ 14(11) 312 (2023) — NiMH SoC ANN (3-7-1):
  https://www.mdpi.com/2032-6653/14/11/312
* *Frontiers Mech. Eng.* (2021) — RUL módszercsaládok összevetése:
  https://www.frontiersin.org/journals/mechanical-engineering/articles/10.3389/fmech.2021.719718/full
* NiMH SOH EIS diagnosztika:
  https://www.researchgate.net/publication/264528866_Diagnostic_methods_for_the_evaluation_of_the_state_of_health_SOH_of_NiMH_batteries_through_electrochemical_impedance_spectroscopy

**Gyenge / kontextus-források (⚠️):**
* Linköping thesis (2024) — terhelés-alatti SOH; **nem peer-reviewed, kis AA-cellás
  minta, kontrollált terhelést használ** (tehát szigorúan véve nem passzív):
  https://liu.diva-portal.org/smash/get/diva2:1841461/FULLTEXT01.pdf

**Prius-specifikus gyakorlat (műhely/közösség):**
* Art's Automotive — Prius prediktív akku-hiba-analízis / Gen3 P0A80/P0B3D/P0B83:
  https://artsautomotive.com/services/predictive-battery-failure-analysis-for-the-prius-hybrid/
  https://artsautomotive.com/services/2010-2015-gen3-prius-p0b3d-p0b83/
* PriusChat — Hybrid Assistant akku-egészség riport értelmezése:
  https://priuschat.com/threads/help-me-understand-my-hybrid-assistant-battery-health-report.250437/
* TorqueNews — hogyan dönt a Prius BMS az akku-hibakódról:
  https://www.torquenews.com/8113/how-toyota-prius-knows-when-set-hybrid-battery-trouble-code

---

## 8. Nyitott kérdések

* **ICA/DVA NiMH-en?** A lapos plató valószínűleg elnyomja a dQ/dV csúcsokat;
  dedikált NiMH-forrás kell hozzá.
* **NiMH-specifikus küszöbök** (entrópia-ablak, Z-score vágás, CV-határ) — minden
  validált küszöb Li-ionból van.
* **Reverzibilis (memóriaeffektus) vs irreverzibilis SOH-veszteség szétválasztása**
  passzívan.
* **Nagy-áramú vezetési tranziensek mint „mini load test"** (Cél 2) — milyen
  SoC/hő-ablak-normálás teszi a blokk-IR összevetést validdá a trendeléshez.
