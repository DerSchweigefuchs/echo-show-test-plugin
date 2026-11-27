# Echo Show Ansichten (Kiosk Satellite Plugin)

Alle 11 nativen Vollbild-Ansichten für das Echo Show Dashboard sowie der native Bildschirmschoner „Wetterszene“ mit Live-Synchronisation der Home-Assistant-Konfiguration.

## Enthaltene Ansichten

| Ansicht | Inhalt |
|---|---|
| `kalender` | Nativer 1:1-Nachbau der Kalenderseite: 5 Ansichten (**Tag**, **3 Tage**, **Woche**, **Monat**, **Liste**), Zeitraster mit Ganztagesbalken, Feiertagen, Abfallterminen (inkl. Abhaken), Live-Jetzt-Linie, hervorgehobenen KI-Tagen, Termin-Detailkarte & Neuanlage |
| `timer` | Nativer 1:1-Nachbau der Timer-&-Wecker-Seite: großer Hero-Ring (mit pulsierendem Alarm-Halo), weitere laufende Timer, nächste Wecker (24 h) und Steuerung (`Pause`/`Weiter`, `Löschen`, `Stopp`, `Wecker aus`) |
| `anwesenheit` | Personenkarten, Zeitleiste 0–24 Uhr und 7-Tage-Verlauf aus Home Assistant |
| `wetter` | Live-Wetterszene (6 Landschaften, GPU-Hardware-Layer + 60-fps-Vordergrund, im Hintergrund vorgewärmt für sofortiges Öffnen) plus Vorhersage-Sheet (**Heute** 8-Stunden-Verlauf / **Woche** 6 Tage) |
| `morgen` | Nativer Story-Player **„Mein Tag“ v2** & Vorlese-Ansicht mit Segment-Leiste, Live-Wetterszene, Stundenverlauf, Live-Karaoke und Steuerung (`echo_show/news/subscribe` & `echo_show/news/steuern`) |
| `player` | Nativer Musik-Player mit unscharfem Albumcover-Hintergrund, Seekbar, synchronisierten Karaoke-Liedtexten (`echo_show/musik/liedtext`), Gerätewechsel & Gruppenverwaltung |
| `waschmaschine` | Live-Statusring, Restzeit/Phase, 24h-Nutzungsprofil, „Ausgeräumt“-Aktion und Verlaufsansicht (`waschmaschine/verlauf`) mit Stunden-/Tages-/Wochen-Diagrammen |
| `gaeste-wlan` | QR-Code (`QrCode` oder Router-Bild), ein-/ausblendbares Passwort, verbundene Geräte, Laufzeitwahl (+2 h, bis morgen früh, dauerhaft) & Schalter |
| `einkaufsliste` | Notizblock-, Klebezettel- und Schlicht-Stil (mit Handschrift *Caveat*), Mehrfach-Listen (`einkaufsliste/<todo.id>`), Abhaken, Eingabe und „Nochmal kaufen“-Vorschläge |
| `muell` | Nächste Abfuhrtermine mit Tonnen-Symbolen, „steht draußen ✓“-Bestätigung (`echo_show/muell/erledigt_setzen`) und 5-Wochen-Mini-Kalender |
| `server` | System-Übersicht (CPU, RAM, Strom mit 24h-Sparklines), Speicherpools, Status-Raster und VMs/Container-Tab (`server/dienste`) |
| `schoner` | Vollbild-Vorschau des nativen Bildschirmschoners „Wetterszene“ (oder direkt in Kiosk Satellite unter **Screensaver → Screensaver mode → Wetterszene** auswählbar) |

## Öffnen & Steuerung
- Deep-Links: `ks://plugin/echo-show-test/zeigen/<ansicht>`, optional mit Parameter:
  - `/zeigen/timer/90`
  - `/zeigen/kalender/wochenende` oder `/zeigen/kalender/2026-11-28`
  - `/zeigen/wetter/zeitraffer` oder `/zeigen/wetter/21:30` oder `/zeigen/wetter/woche`
  - `/zeigen/morgen/demo`, `/zeigen/morgen/story`, `/zeigen/morgen/mehr`
  - `/zeigen/einkaufsliste/<todo.id>`
  - `/zeigen/waschmaschine/verlauf`
  - `/zeigen/server/dienste`
- Befehle für Gesten, Drawer oder Home Assistant: `zeigen`, `kalender`, `timer`, `anwesenheit`, `wetter`, `morgen`, `player`, `waschmaschine`, `gaestewlan`, `einkaufsliste`, `muell`, `server`, `schoner`, `schliessen`.
- Über die Integration „Echo Show Dashboard“: Abonniert `echo_show/ansicht/abo` (inkl. `nativ_seiten`), reagiert auf `vorbau` und `schliessen`, und meldet `offen`, `gezeigt`, `bereit` sowie `geschlossen` an `echo_show/ansicht/melden`.

## Live-Konfiguration & Bildschirmschoner
- Beim Start sowie bei jedem `echo_show_changed`-Event in Home Assistant lädt das Plugin die Panel-Konfiguration aus `echo_show/plugin/konfig` (`wetter`, `wetter_name`, `szene` mit `orte`, `wechsel`, `fest`, `sterne`, `namen`, `blitze`, `figur`, `tiere`, `personen`, `einkaufsliste`).
- Über `echo_show/schoner/zusatz` werden aktive Zusatzkarten (Müll-Hinweis, nächster Termin, Einkaufsliste, Gäste-WLAN mit kompaktem QR-Code) direkt oben rechts im Bildschirmschoner „Wetterszene“ eingeblendet.
- Die Plugin-Einstellungen unter **Wettervorschau** und **Bildschirmschoner** bleiben unabhängig davon lokal nutzbar und überschreiben bei aktiver Vorschau die Live-Szene.
- Die Wolken-Ebenen der Wetterseite und des Bildschirmschoners liegen in getrennten Cache-Slots und bleiben während der gesamten Plugin-Sitzung im Speicher, sodass das Öffnen der Wetteransicht auch nach dem Aufwecken aus dem Schoner ohne Verzögerung erfolgt.

## Abos & Rechte
- Alle Home-Assistant-Abos (maximal 8 gleichzeitige `haSubscribe`-Aufrufe, deutlich unter dem KS-Limit von 16) melden sich nach Unterbrechungen mit Backoff (5 s, 10 s, 30 s, 60 s) automatisch neu an.
- Rechte: `overlay`, `screensaver`, `ha`, `host.read`.
- Lizenz: Apache-2.0. Die SDK-Schnittstellen unter `sdk/` stammen aus [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0).
