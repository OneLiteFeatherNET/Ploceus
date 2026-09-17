# Ploceus

Projekt- und Weltverwaltung für Paper-Server auf Basis von
[Multiverse-Core](https://github.com/Multiverse/Multiverse-Core).

Ein *Projekt* bündelt mehrere Welten, die zusammengehören — ein Minigame, ein Event, ein
Bauthema. Jedes Projekt und jede Welt trägt Beschreibung, Tags, Autoren und eine lückenlose
Änderungshistorie. Welten werden erst geladen, wenn sie jemand betritt, und wieder entladen,
wenn längere Zeit niemand darin war.

*Ploceus* ist die Gattung der Webervögel: Sie bauen kunstvolle Nester und hängen viele davon
in denselben Baum.

## Was es tut

- **Projekte** mit Beschreibung, Tags, Autoren und Generator-Vorgaben für neue Welten
- **Welten zuordnen**, umbenennen, anheften, Leerlaufzeit je Welt setzen
- **Welten anlegen** mit projektweiten Generator-Vorgaben und Namensvorschlägen
- **Welten importieren**, die auf der Platte liegen, aber von Multiverse noch nicht verwaltet werden
- **Bedarfsgesteuertes Laden**: beim Serverstart wird nichts geladen, was niemand braucht
- **Automatisches Entladen** leerer Welten nach einstellbarer Leerlaufzeit
- **Audit-Spur** für jede Änderung: wer, wann, was vorher, was nachher

## Bedienung

Drei gleichwertige Oberflächen, je nach Client automatisch gewählt:

| Kanal | wann |
|---|---|
| **Dialog** | moderne Clients — die eigentliche Oberfläche |
| **Inventar** | ältere Clients ohne Dialog-Unterstützung, dazu als visueller Welten-Browser |
| **Chat** | letzter Rückfall, funktioniert auch auf der Konsole |

Manuell umschalten mit `/ploceus ui dialog|inventory|chat`.

## Voraussetzungen

- Paper **26.2** oder neuer, Java **25**
- Multiverse-Core **5.8.1**
- Datenbank: H2 (Standard, keine Einrichtung nötig) oder PostgreSQL

## Einrichtung

Jar nach `plugins/` legen und den Server starten. Das Schema wird automatisch angelegt,
vorhandene Multiverse-Welten werden als „nicht zugeordnet" übernommen.

Für PostgreSQL in `plugins/Ploceus/config.yml` den Abschnitt `database` umstellen.

`docs/operations.md` beschreibt den Betrieb: Berechtigungen, Entlade-Verhalten,
Datenbankwechsel, OpenTelemetry-Anbindung und eine Prüfliste für die erste Inbetriebnahme.

## Bauen

```bash
./gradlew build
```

Die PostgreSQL-Integrationstests brauchen einen laufenden Docker-Daemon; ohne ihn werden sie
übersprungen statt zu scheitern.

## Lizenz

MIT — siehe [LICENSE](LICENSE).
