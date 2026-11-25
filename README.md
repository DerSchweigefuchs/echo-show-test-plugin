# Echo Show Test-Ansichten (Kiosk Satellite Plugin)

Prototyp zum Messen: Laufen native Android-Ansichten auf einem Echo Show schneller und sparsamer als Lovelace-Seiten?

## Was es kann
Drei native Vollbild-Ansichten über dem Dashboard:

| Ansicht | Inhalt |
|---|---|
| `kalender` | Tagesliste aus `calendar.get_events` (nur lesend) |
| `timer` | Countdown-Ring, sekündlich oder flüssig |
| `liste` | 50 Zeilen mit Auto-Scroll als Lasttest |

In der Kopfzeile zeigt jede Ansicht ihre Messwerte:
- Öffnungszeit
- Bilder pro Sekunde, Ruckler und längstes Bild
- freien RAM vorher und nachher

## Öffnen
- Link: `ks://plugin/echo-show-test/zeigen/kalender` (oder `/timer`, `/liste`), optional mit Tag oder Sekunden: `/zeigen/timer/90`
- Befehle für Gesten, Drawer oder Home Assistant: `kalender`, `timer`, `liste`, `schliessen`
- Über die Integration „Echo Show Dashboard“: Abo `echo_show/ansicht/abo`. Das Plugin reagiert nur auf die Seiten `test-kalender`, `test-timer` und `test-liste` und meldet `bereit` und `gezeigt` mit Zeiten zurück. Alle anderen Seiten bleiben beim Dashboard.

## Rechte
- `overlay`: die Ansichten
- `ha`: Kalender lesen, Abo und Meldungen der Echo-Show-Integration
- `host.read`: freier RAM

Das Plugin schreibt nichts in Home Assistant.

## Bauen
Lokal mit `./build.sh` (JDK 17+, Android SDK). Releases baut GitHub Actions: Ein Tag `v<version>` erstellt das Release und hängt ZIP, Prüfsumme und Manifest an.

## Lizenz
Apache-2.0. Die SDK-Schnittstellen unter `sdk/` stammen aus [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0) und werden nur zum Kompilieren gebraucht.
