# Echo Show Test-Ansichten (Kiosk Satellite Plugin)

Prototyp zum Messen: Laufen native Android-Ansichten auf einem Echo Show schneller und sparsamer als Lovelace-Seiten?

## Was es kann
Fünf native Vollbild-Ansichten über dem Dashboard:

| Ansicht | Inhalt |
|---|---|
| `kalender` | Tagesliste aus `calendar.get_events` (nur lesend) |
| `timer` | Countdown-Ring, sekündlich oder flüssig |
| `liste` | 50 Zeilen mit Auto-Scroll als Lasttest |
| `anwesenheit` | Nachbau einer Lovelace-Anwesenheitsseite als schwerer Vergleichstest: Personenkarten, Zeitleiste 0–24 Uhr und Woche, auf einem Canvas gezeichnet |
| `wetter` | Live-Wetterszene am Meer: Himmel nach der echten Sonnenhöhe, Sonne, Mond, Sterne, ziehende Wolken, Regen und Schnee, auf einem Canvas gezeichnet |

In der Kopfzeile zeigt jede Ansicht ihre Messwerte (bei `anwesenheit` klein zwischen Titel und Umschalter, bei `wetter` unter der Szene):
- Öffnungszeit
- Bilder pro Sekunde, Ruckler und längstes Bild
- freien RAM vorher und nachher

## Öffnen
- Link: `ks://plugin/echo-show-test/zeigen/kalender` (oder `/timer`, `/liste`, `/anwesenheit`, `/wetter`), optional mit Tag oder Sekunden: `/zeigen/timer/90`
- Befehle für Gesten, Drawer oder Home Assistant: `kalender`, `timer`, `liste`, `anwesenheit`, `wetter`, `schliessen`
- Über die Integration „Echo Show Dashboard“: Abo `echo_show/ansicht/abo`. Das Plugin reagiert nur auf die Seiten `test-kalender`, `test-timer`, `test-liste`, `test-anwesenheit` und `test-wetter` und meldet `bereit` und `gezeigt` mit Zeiten zurück. Alle anderen Seiten bleiben beim Dashboard.

## Anwesenheit
- Personen: ein `render_template`-Abo über alle `person`-Entitäten, oder nur die in der Einstellung **Personen** genannten. Es bleibt aktiv, solange das Plugin läuft, damit die Ansicht die Personen sofort hat (wie die Zustände im Dashboard).
- Verlauf: `history/history_during_period` für die letzten 7 Tage mit `minimal_response`, `no_attributes` und `significant_changes_only: false`. Ist die Antwort größer als die 32 KB, die Kiosk Satellite erlaubt, lädt das Plugin nur echte Zustandswechsel. Das Bild bleibt gleich. Die Meldung `bereit` zeigt das mit `verlauf_nur_wechsel` an.
- Der Verlauf wird 5 Minuten gemerkt und neu geladen, sobald jemand kommt oder geht. Abschaltbar mit **Verlauf 5 Minuten merken**.
- `bereit` kommt erst, wenn der Verlauf geladen und gezeichnet ist. Die Meldung enthält `daten_ms`, `personen`, `personen_cache`, `verlauf_cache` und `modus`.
- Avatare zeigen den Anfangsbuchstaben. Profilbilder lädt das Plugin nicht.

## Wetter
- Szene „Meer“: Himmel, Meer mit Horizont, Leuchtturm-Insel (nachts mit Licht), Boot, Strand und Düne.
- Himmel stufenlos nach der Sonnenhöhe: Nacht unter −12°, blaue Stunde um −6°, Abendrot oder Morgenröte von 0 bis 3° (je nachdem, ob die Sonne steigt), Tag über 10°.
- Sonne und Mond stehen nach Azimut und Höhe am Himmel. Der Mond hat seine echte Phase. Sterne und Sternbilder stehen an ihrer echten Stelle und funkeln ab etwa −4°. Wolken verdecken Sterne und Mond.
- Wolken ziehen. Regen, Schnee, Hagel, Nebel, Wind und Gewitter folgen der Wetterlage. Das Meer nimmt die Farbe des Himmels an und hat einen Glitzerpfad unter Sonne oder Mond. Das Boot schaukelt unregelmäßig.
- Daten: ein `render_template`-Abo, nur lesend, mit der Wetter-Entität (Einstellung **Wetter-Entität**, sonst die erste `weather.*`), `sun.sun` und den Koordinaten von `zone.home`. Die Koordinaten kommen nur zur Laufzeit und bleiben im Speicher.
- Zwischen den Aktualisierungen von `sun.sun` rechnet das Plugin die Sonnenbahn selbst weiter. Ohne Koordinaten gibt es keinen Mond und keine Sterne.
- `zeigen/wetter/zeitraffer` spielt 24 Stunden in etwa 60 Sekunden ab. `zeigen/wetter/21:30` zeigt eine feste Uhrzeit von heute.
- Last: Land, Meer, Wolkenreihen, Mond und Kopfzeile werden in Bitmaps vorgezeichnet und nur bei Wetter- oder Lichtwechsel im Hintergrund neu gezeichnet. Ein Bild zeichnet nur das Bewegte. Die Szene läuft mit 30 oder 24 fps und pausiert, solange sie nicht sichtbar ist, der Bildschirmschoner läuft oder der Bildschirm aus ist. Die vorgezeichneten Ebenen bleiben nach dem Schließen 2 Minuten erhalten.
- `bereit` kommt nach dem ersten vollständigen Bild mit Wetterdaten. Die Meldung enthält `daten_ms`, `aufbau_ms`, `daten_cache`, `zustand` und `modus`.

## Abos
Alle Home-Assistant-Abos (Ansicht-Anfragen, Personen, Wetter) melden sich selbst neu an, wenn Home Assistant sie beendet oder ablehnt, zum Beispiel nach einem Neustart: nach 5 s, 10 s, 30 s und danach alle 60 s.

## Rechte
- `overlay`: die Ansichten
- `ha`: Kalender lesen, Personen und ihren Verlauf lesen, Wetter, Sonne und Heimkoordinaten lesen, Abo und Meldungen der Echo-Show-Integration
- `host.read`: freier RAM, Bildschirmschoner und Bildschirm (Pause der Wetterszene)

Das Plugin schreibt nichts in Home Assistant.

## Bauen
Lokal mit `./build.sh` (JDK 17+, Android SDK). Releases baut GitHub Actions: Ein Tag `v<version>` erstellt das Release und hängt ZIP, Prüfsumme und Manifest an.

## Lizenz
Apache-2.0. Die SDK-Schnittstellen unter `sdk/` stammen aus [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0) und werden nur zum Kompilieren gebraucht.
