# Projekt-Screen: Metadaten, Tags, Weltzuordnung, Welterstellung

Status: **geplant, noch nicht ausgeführt.** Frühestens starten, wenn der World-Import-Agent
gemergt ist — beide bauen am Dialog-Projektbildschirm und beide können den `ProjectUi`-Vertrag
erweitern.

## Ausgangsbefund

Die Service-Schicht kann bereits alles Geforderte, auditiert und getestet:

| Service-Methode | heute im Dialog erreichbar |
|---|---|
| `ProjectService.addTag` / `removeTag` | nein |
| `ProjectService.addAuthor` / `removeAuthor` | nein |
| `ProjectService.setWorldDefaults` | nein |
| `WorldService.assign` / `unassign` | nein (nur `/ploceus world assign`) |
| `WorldService.setPinned` / `setIdleMinutes` | nein (nur Befehl) |
| `DialogUi.createWorldForm` | ja, aber nur über `/ploceus world create <projectKey>` |

`DialogUi.projectDetail` hat heute vier Knöpfe: Welten, Bearbeiten, Audit, Löschen.

**Das ist reine Oberflächenarbeit.** Kein neuer Unterbau, keine Schema-Änderung, kein neuer
Service. Entsprechend sollte der Diff überwiegend in `ui/dialog/` und `Messages` liegen.

## Umfang

### 1. Metadaten anzeigen (`projectDetail`)

Beschreibung, Tags, Autoren mit Rolle, Status, Weltenzahl und das Generator-Preset als
`DialogBody` auf dem Projektbildschirm. Heute zeigt der Bildschirm nur den Namen.

Achtung: `project.getWorlds()` ist nicht benutzbar — die Session ist geschlossen, das war ein
behobener Fehler. Die Weltenzahl kommt wie bisher als Parameter herein.

### 2. Tags bearbeiten

Hinzufügen über ein Text-Eingabefeld (`FormInputs.parseTags` existiert und trennt an Kommas),
Entfernen über eine `MultiAction`-Liste mit einem Knopf je Tag. Berechtigung
`ploceus.project.edit`.

### 3. Welt zuordnen

Liste der nicht zugeordneten Welten (`WorldService.unassigned()`), paginiert, Klick ordnet zu.
Berechtigung `ploceus.world.assign`. Bei ~70 importierbaren Welten auf dem Zielserver ist
Paginierung Pflicht, nicht optional.

### 4. Welt erstellen im Projektkontext

`createWorldForm` existiert vollständig inklusive Generator-Auswahl und Faker-Namensvorschlag.
Es fehlt nur ein Knopf auf dem Projektbildschirm, der sie mit diesem Projekt aufruft.
Berechtigung `ploceus.world.create`.

### 5. Optional, gleicher Bildschirm

Autoren pflegen (`addAuthor`/`removeAuthor`) und das Generator-Preset setzen
(`setWorldDefaults`). Beide liegen fachlich hier und der Service kann sie. Mitnehmen, wenn es
den Bildschirm nicht überlädt — sonst als eigener Unterbildschirm.

## Was den Umfang treibt

**Jede neue `ProjectUi`-Methode kostet drei Implementierungen.** Der Vertrag wird von Chat,
Inventar und Dialog erfüllt, und `ProjectUiContractTest` erzwingt das. Chat und Inventar dürfen
auf den passenden Befehl verweisen — aber die Methode muss dort existieren.

Erwägen: Lässt sich das meiste ohne Vertragserweiterung als privater Unterbildschirm innerhalb
von `DialogUi` lösen? Der Vertrag beschreibt, was ein Kanal können muss, nicht jeden inneren
Bildschirm. Ein `projectDetail`, das intern weitere Dialoge öffnet, erweitert den Vertrag nicht.
Das ist vermutlich der richtige Schnitt und sollte zuerst geprüft werden.

## Bindende Regeln

- `DialogCallbacks.button(...)` verlangt `Player` und Berechtigungsknoten **by construction** —
  es gibt bewusst keine Überladung ohne. Ein Autorisierungs-Bypass wurde mitten im Projekt
  gefunden und so geschlossen. Keine Hintertür einbauen.
- Prüfung sowohl beim Zeichnen als auch beim Ausführen. Nur die Ausführungsprüfung übersteht ein
  gefälschtes Client-Payload, weil Dialog-Callbacks clientgetrieben sind.
- Berechtigungsknoten wörtlich aus `command/PloceusCommands.java`, nicht neu erfinden.
- Jeder sichtbare Text aus `Messages`. Ein fehlender Schlüssel rendert als
  `Missing message: <key>` und kein Test merkt es — per Inspektion gegenprüfen.
- Dialoge scrollen nicht: Paginierung von Hand über `DialogCallbacks.paginationButtons`.
- Jeder Datenbankzugriff über `scheduler.async`, jeder Bukkit-Aufruf über `.sync`.
- ArchUnit läuft scharf: `ui` darf `persistence` nicht sehen, die drei Kanäle einander nicht.

## Tests

- Ausführungsseitige Berechtigungsprüfung für jede neue Aktion, in der Form von
  `DialogPermissionsTest` — ein `Player`-Double ohne Recht darf den Service nicht erreichen.
- Keine Tests, die nur das Muster nachbauen statt `DialogUi` aufzurufen. Genau das wurde in
  diesem Projekt schon einmal beanstandet.

## Danach

Auf dem Bau-Server gegenprüfen: Projekt anlegen, Tags setzen, eine der importierten Welten
zuordnen, eine Welt im Projektkontext erstellen. `docs/operations.md` um die neuen Bildschirme
ergänzen.
