# Echo Show Test-Ansichten (Kiosk Satellite Plugin)

Prototyp zum Messen: Laufen native Android-Ansichten auf einem Echo Show schneller und sparsamer als Lovelace-Seiten?

## Was es kann
Vier native Vollbild-Ansichten über dem Dashboard:

| Ansicht | Inhalt |
|---|---|
| `kalender` | Tagesliste aus `calendar.get_events` (nur lesend) |
| `timer` | Countdown-Ring, sekündlich oder flüssig |
| `liste` | 50 Zeilen mit Auto-Scroll als Lasttest |
| `anwesenheit` | Nachbau einer Lovelace-Anwesenheitsseite als schwerer Vergleichstest: Personenkarten, Zeitleiste 0–24 Uhr und Woche, auf einem Canvas gezeichnet |

In der Kopfzeile zeigt jede Ansicht ihre Messwerte (bei `anwesenheit` klein zwischen Titel und Umschalter):
- Öffnungszeit
- Bilder pro Sekunde, Ruckler und längstes Bild
- freien RAM vorher und nachher

## Öffnen
- Link: `ks://plugin/echo-show-test/zeigen/kalender` (oder `/timer`, `/liste`, `/anwesenheit`), optional mit Tag oder Sekunden: `/zeigen/timer/90`
- Befehle für Gesten, Drawer oder Home Assistant: `kalender`, `timer`, `liste`, `anwesenheit`, `schliessen`
- Über die Integration „Echo Show Dashboard“: Abo `echo_show/ansicht/abo`. Das Plugin reagiert nur auf die Seiten `test-kalender`, `test-timer`, `test-liste` und `test-anwesenheit` und meldet `bereit` und `gezeigt` mit Zeiten zurück. Alle anderen Seiten bleiben beim Dashboard.

## Anwesenheit
- Personen: ein `render_template`-Abo über alle `person`-Entitäten, oder nur die in der Einstellung **Personen** genannten. Es bleibt aktiv, solange das Plugin läuft, damit die Ansicht die Personen sofort hat (wie die Zustände im Dashboard).
- Verlauf: `history/history_during_period` für die letzten 7 Tage mit `minimal_response`, `no_attributes` und `significant_changes_only: false`. Ist die Antwort größer als die 32 KB, die Kiosk Satellite erlaubt, lädt das Plugin nur echte Zustandswechsel. Das Bild bleibt gleich. Die Meldung `bereit` zeigt das mit `verlauf_nur_wechsel` an.
- Der Verlauf wird 5 Minuten gemerkt und neu geladen, sobald jemand kommt oder geht. Abschaltbar mit **Verlauf 5 Minuten merken**.
- `bereit` kommt erst, wenn der Verlauf geladen und gezeichnet ist. Die Meldung enthält `daten_ms`, `personen`, `personen_cache`, `verlauf_cache` und `modus`.
- Avatare zeigen den Anfangsbuchstaben. Profilbilder lädt das Plugin nicht.

## Rechte
- `overlay`: die Ansichten
- `ha`: Kalender lesen, Personen und ihren Verlauf lesen, Abo und Meldungen der Echo-Show-Integration
- `host.read`: freier RAM

Das Plugin schreibt nichts in Home Assistant.

## Bauen
Lokal mit `./build.sh` (JDK 17+, Android SDK). Releases baut GitHub Actions: Ein Tag `v<version>` erstellt das Release und hängt ZIP, Prüfsumme und Manifest an.

## Lizenz
Apache-2.0. Die SDK-Schnittstellen unter `sdk/` stammen aus [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0) und werden nur zum Kompilieren gebraucht.
