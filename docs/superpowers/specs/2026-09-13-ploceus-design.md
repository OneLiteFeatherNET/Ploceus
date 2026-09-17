# Ploceus — Design

Project and world management for the OneLiteFeather team build server.

- **Date:** 2026-09-13
- **Status:** Approved, ready for implementation planning
- **Target:** Paper 26.2, Java 25
- **Repository:** `OneLiteFeatherNET/Ploceus`

## 1. Context

The team build server (`Team-Build-1`) runs Paper 26.2 with Multiverse-Core 5.8.1. Its
`worlds/` directory holds 76 world folders — named things like `elytrarace-alien`,
`elytrarace-aqua`, `archive-newlobby`, `bak_Survival` — but Multiverse-Core is only
configured to manage 6 of them; the rest are untracked leftovers on disk that Multiverse
never touches, and Ploceus only ever sees the 6. There is no record of who built what, no
description, no tags, and every *managed* world with `auto-load: true` is loaded at server
start.

Ploceus introduces a **project** as the grouping unit: a project owns one or more worlds
and carries authors, tags, descriptions and an audit trail. It also stops the server from
loading every world at boot.

The name follows the organisation's naming scheme — latin genus names, predominantly
birds. *Ploceus* is the weaverbird genus: birds that build elaborate nests and hang many
of them in a single tree, which is exactly the project/world relationship.

## 2. Goals

- Group worlds into projects with metadata: description, tags, authors.
- Record an append-only audit trail of every change Ploceus makes.
- Load worlds on demand and unload them when idle, so server start is fast.
- Provide the whole workflow through the Paper Dialog API, with graceful degradation.
- Create worlds with the correct generator, pre-filled from a per-project preset.

## 3. Non-goals

Deliberately out of scope:

- **No permission or role system.** Authors are credits only; access control stays with
  LuckPerms.
- **No visit history.** Only `lastVisitedAt` per world, which the unload policy needs.
- **No build activity tracking.** That is CoreProtect's job.
- **No world templates or clone features.**
- **No web API.**
- **No world renaming on disk.** See section 8.3.

## 4. Architecture

Single Gradle module. Layers are packages, and an **ArchUnit test enforces them in CI** —
the layering lives in the build, not only in this document.

```
net.onelitefeather.ploceus
├─ domain/        entities, records, sealed results and events   — depends on NOTHING
├─ persistence/   Hibernate bootstrap, repositories
├─ service/       ProjectService, WorldService, AuditService,
│                 SearchService, WorldLifecycleService
├─ platform/      MultiverseAdapter, SchedulerAdapter            — only Bukkit/MV imports
├─ ui/            ProjectUi interface + dialog/, inventory/, chat/
├─ command/       Cloud commands
└─ di/            Guice modules, PloceusPlugin
```

Enforced rules:

- `domain` imports neither Bukkit, nor Multiverse, nor Hibernate, nor Vavr.
- `ui` and `command` may reach `service` and `domain`, never `persistence`.
- The three `ui` channel implementations must not know each other.
- Only `platform` may import `org.mvplugins.*` or `org.bukkit.*`, apart from `ui` and
  `command`, which legitimately handle `Player`.
- `service` must not import `ui` — the dependency runs from `ui` to `service`, never back.

## 5. Domain model

### 5.1 Identity: Adventure Keys

Every addressable entity is identified by a `net.kyori.adventure.key.Key`
(Adventure 5.2.0 ships with Paper 26.2). The namespace is fixed to `ploceus`; users type
only the value.

```
Project       key: Key            ploceus:elytrarace
ProjectWorld  key: Key            ploceus:elytrarace-desert     ← stable identity
              worldName: String   elytrarace-desert             ← bridge to Multiverse
Tag           key: Key            ploceus:winter
```

All three implement Adventure's `Keyed`, so a single `findByKey(Key)` repository method,
a single `AttributeConverter`, and a single Cloud argument parser serve all of them.

`ProjectWorld` carries both a key and a world name on purpose: the key stays stable even
if the Multiverse world is renamed, while `worldName` is the bridge to Multiverse. Without
the key, project membership, tags and audit entries would hang off a mutable name.

### 5.2 Entities

```
Project
    key: Key (unique)
    displayName: String
    description: String
    status: PLANNING | ACTIVE | ARCHIVED
    tags: Set<Tag>                   (many-to-many)
    authors: List<Author>
    worldDefaults: WorldDefaults     (embeddable, nullable)
    created/updated by + at

ProjectWorld
    key: Key (unique)
    worldName: String (unique)       immutable after creation
    project: Project                 NULLABLE → "unassigned"
    displayName: String              mirrored onto the Multiverse alias
    description: String
    tags: Set<Tag>
    authors: List<Author>
    lastVisitedAt: Instant
    idleUnloadMinutes: Integer       nullable → falls back to the global default
    pinned: boolean
    created/updated by + at

Author
    playerUuid: UUID
    lastKnownName: String
    role: String                     free text: "Terrain", "Redstone", "Lead"

Tag
    key: Key (unique)

WorldDefaults (embeddable)
    generator: String                e.g. "VoidGen"
    generatorSettings: String
    environment: NORMAL | NETHER | THE_END
    generateStructures: boolean
    biome: String (nullable)

AuditEntry
    targetType: PROJECT | WORLD
    targetId: Key
    actorUuid: UUID
    actorName: String
    action: enum
    field: String
    oldValue: String
    newValue: String
    at: Instant
```

### 5.3 Polymorphism and DRY

- `@MappedSuperclass AuditableEntity` carries created/updated by and at.
- Interfaces `Taggable`, `Authored`, `Describable` are implemented by both `Project` and
  `ProjectWorld`, so tag search and audit writing are each implemented **once**,
  generically, rather than twice.

## 6. Persistence

- **Hibernate ORM 7** with **HikariCP**.
- **H2 by default** at `plugins/Ploceus/data.mv.db`; **PostgreSQL** via `config.yml`.
  One `DatabaseConfig` record, two dialects, otherwise identical code.
- **Liquibase** owns the schema; `hbm2ddl.auto=validate`. Without migrations a later
  H2→PostgreSQL move risks data loss.
- `KeyAttributeConverter implements AttributeConverter<Key, String>` stores `asString()`
  and reads back through `Key.key(...)`. Column `VARCHAR(255)`, unique.
- **All database access is asynchronous** on a dedicated virtual-thread executor. Results
  return to the main thread before any Bukkit call.

### 6.1 Dependency loading

| Dependency | Mechanism | Reason |
|---|---|---|
| Hibernate, HikariCP, H2, PostgreSQL driver, Liquibase | Paper `MavenLibraryResolver` in the plugin bootstrap | avoids a 20 MB shaded jar |
| Guice, Cloud, InventoryFramework, Datafaker | shaded **and relocated** | these collide with other plugins |

**Known pitfall:** Hibernate must be built with the thread context classloader set to the
plugin classloader, otherwise it cannot resolve its dialects. Set it around
`SessionFactory` construction and restore it afterwards.

## 7. Multiverse integration

Multiverse stays the owner of world lifecycle and world settings. Ploceus stores only
project membership and metadata, and calls the Multiverse API for actions. No duplicated
state, no migration pressure on the 6 worlds Multiverse already manages.

Verified against `multiverse-core-5.8.1.jar`:

| API | Use |
|---|---|
| `MultiverseCoreApi.whenLoaded(Consumer)` | startup timing |
| `MultiverseCoreApi.get().getWorldManager()` | entry point |
| `WorldManager.loadWorld` / `unloadWorld` | on-demand loading |
| `WorldManager.getLoadedWorlds` / `getUnloadedWorlds` | inventory of worlds |
| `WorldManager.createWorld(CreateWorldOptions)` | world creation |
| `MultiverseWorld.isAutoLoad()` / `setAutoLoad(boolean)` | suppress boot loading |
| `MultiverseWorld.setAlias(String)` | display name |
| `GeneratorProvider.getRegisteredGeneratorPlugins()` | populate the generator picker |
| `GeneratorProvider.suggestGeneratorString(String)` | suggest generator settings |
| `AsyncSafetyTeleporter` | safe teleport after load |

`WorldManager` returns `Attempt<T, FailureReason>` and `Try<Void>` (shaded Vavr). The
`platform` package translates these into Ploceus' own result type so that `domain` and
`service` never see Vavr.

Multiverse 5.8 already models worlds with `NamespacedKey` — `CreateWorldOptions.worldKey`,
`CloneWorldOptions.fromTo(world, NamespacedKey)`, and a `WorldKeyOrName` type. The
Ploceus key design follows Multiverse's own direction.

## 8. World lifecycle

### 8.1 Import of existing worlds

On enable, inside `MultiverseCoreApi.whenLoaded`, read all Multiverse worlds and create a
`ProjectWorld` with `project = null` ("unassigned") for each unknown one. Nothing is
guessed and nothing is lost; operators assign them to projects afterwards.

Adventure keys only permit `[a-z0-9_.-/]` in the value, so the key is derived from the
world name: lowercase, illegal characters to `_`, suffix `-2` on collision.

| Multiverse world name | derived key |
|---|---|
| `elytrarace-alien` | `ploceus:elytrarace-alien` |
| `BikiniRusska` | `ploceus:bikinirusska` |
| `bak_Survival` | `ploceus:bak_survival` |
| `Hammersbuild-1` | `ploceus:hammersbuild-1` |

The key is editable afterwards; the world name is not.

### 8.2 On-demand loading and idle unloading

1. For every managed world, set `setAutoLoad(false)`.
2. On join or teleport, `WorldLifecycleService.ensureLoaded(worldName)` loads the world if
   needed, then teleports through Multiverse's `AsyncSafetyTeleporter`.
3. A reaper task runs every 60 s and unloads a world when it is loaded, holds no players,
   was last visited longer than `idleUnloadMinutes` ago (default 30), is not `pinned`, and
   is not the default world.
4. `lastVisitedAt` is updated from `PlayerChangedWorldEvent` and `PlayerQuitEvent`, then
   **batched to the database every 30 s** — never one write per event.

**Caveat to document for operators:** Multiverse loads its worlds before Ploceus is
enabled, so `setAutoLoad(false)` only takes effect from the *next* server start. The first
start after installation still loads all 6 managed worlds, exactly as before — a small
number on `Team-Build-1` today, but the import and suppression still matter: it is what
keeps a growing set of build worlds from silently costing more boot time every time one is
added, and it is what makes the later idle-unload story possible at all.

### 8.3 Renaming

Multiverse 5.8.1 has **no** `renameWorld`. The available alternatives are
`setAlias(String)`, or `cloneWorld` followed by `removeWorld`.

Ploceus therefore renames display names only: `displayName` is freely editable and is
mirrored onto `MultiverseWorld.setAlias()`. `worldName` and the world folder are immutable
after creation. This keeps WorldGuard regions (`plugins/WorldGuard/worlds/<name>/`), Axiom
data and BlueMap renders intact — all of which are keyed by folder name. The Ploceus key
carries stable identity instead.

## 9. World creation

### 9.1 Generator presets

`Project.worldDefaults` pre-fills the creation dialog. Resolution order:

**project preset → global default from `config.yml` → vanilla**

The preset is **pre-filled, not enforced**: anyone with creation rights may override it
per world (for example a single nether world inside an otherwise VoidGen project), and the
deviation is recorded in the audit log.

The generator picker is populated from `GeneratorProvider.getRegisteredGeneratorPlugins()`,
so `VoidGen-2.3.8` — the common case on a build server — appears by itself. Nothing is
hardcoded.

`CreateWorldOptions` supports `worldKey(NamespacedKey)`, so the Ploceus key is passed
straight through as the world key.

### 9.2 Name suggestions

`net.datafaker:datafaker` with its built-in providers, combined through an
`expression()` template. The suggestion is normalised to a valid world name immediately
(lowercase, `_` for illegal characters); a collision triggers a re-roll.

The creation dialog pre-fills the name field with a suggestion and offers a **re-roll
button**: a `CustomClickAction` that reopens the dialog with a fresh suggestion, since the
Dialog API cannot change a value inside an open dialog.

## 10. User interface

### 10.1 One interface, three channels

```java
interface ProjectUi {
    void projectList(Player player, Page<Project> page);
    void projectDetail(Player player, Project project);
    void worldList(Player player, Project project);
    void createProjectForm(Player player);
    void confirm(Player player, ConfirmRequest request);
}

sealed interface UiChannel permits DialogUi, InventoryUi, ChatUi
```

`UiRouter.forPlayer(player)` decides in this order:

1. **Player preference** (`/ploceus ui dialog|inventory|chat`, stored per player) — always wins.
2. `player.getProtocolVersion() >= minDialogProtocol` → **DialogUi**.
3. otherwise → **InventoryUi**.
4. fallback → **ChatUi**.

`minDialogProtocol` is a `config.yml` value so the threshold can be adjusted after a
protocol bump without waiting for a plugin release. The server runs ViaVersion and
ViaBackwards, so clients older than the Dialog API do connect — this fallback is not
hypothetical.

Paper 26.2 provides everything needed on `Player`: `getProtocolVersion()`,
`showDialog(DialogLike)`, `closeDialog()`.

The dependency runs **one way**: `ui` and `command` call services; a service never calls
the UI. This is what keeps services Bukkit-free. `ProjectUi` and its `Player` parameters
live entirely in `ui`, and `UiRouter` is resolved by the command layer and the event
listeners, not by a service. Long-running work returns a result to the caller, which then
renders it through the channel it already holds.

All three channels run against the same contract test suite, which keeps them functionally
equivalent.

### 10.2 DialogUi — the primary channel

Paper 26.2 offers five dialog types: `NoticeType`, `ConfirmationType`, `MultiActionType`,
`DialogListType`, `ServerLinksType`. `MultiActionType` holds an arbitrary list of
`ActionButton`s in a grid (`columns`) plus an `exitAction`; each button carries `label`,
`tooltip` (both Adventure `Component`), `width` and an action. Lists are therefore fully
representable as dialogs.

| Flow | Dialog type |
|---|---|
| Main menu | MultiAction — projects, search, unassigned, admin |
| Project list | MultiAction, paginated, one button per project |
| Project detail | MultiAction + body — worlds, edit, authors, tags, audit, delete |
| World list | MultiAction; click loads and teleports; tooltip shows load state |
| Create/edit project | Notice with text and single-option inputs |
| Create world | Notice with inputs, re-roll button, "advanced" button |
| Search | Inputs (text + single-option field selector) → results as MultiAction |
| Delete | Confirmation |
| Audit trail | Notice with PlainMessageBody, paginated |

Available inputs: text, boolean, number range, single option. Bodies: plain message, item.
Actions: custom click, command template, static.

Four limitations that shape the design:

1. Buttons are text plus tooltip — **no item icons as click targets**. `ItemDialogBody`
   displays items in the body only, not clickable.
2. No scrolling — pagination is built by hand (previous/next buttons, ~10 entries per page).
3. Every navigation opens a new dialog; there is no in-place refresh.
4. Dialogs are built per player because content is user-dependent; they are not
   pre-registered statically.

### 10.3 InventoryUi — fallback and world browser

InventoryFramework (`com.github.stefvanschie.inventoryframework`) serves two purposes: it
is the fallback for clients without dialog support, and it is offered alongside the dialog
for browsing worlds, where item icons genuinely help — grass block for overworld,
netherrack for nether, barrier for VoidGen, player heads for authors.

### 10.4 ChatUi — last resort

Adventure components with `ClickEvent.runCommand`, including pagination links. A real
third presentation rather than bare commands, and the only channel that also works for the
console.

### 10.5 Text

All user-facing text is MiniMessage, held centrally in one `Messages` class.

## 11. Commands

Cloud (`PaperCommandManager`, annotation parser).

```
/ploceus project create|edit|info|list|delete <key>
/ploceus world   assign <world> <project> | unassign <world> | info <world> | import
/ploceus search  <query>
/ploceus ui      dialog|inventory|chat
/ploceus admin   reload | unload <world> | stats
```

Aliases: `/projects`, `/pj`. Commands mostly just open a dialog or GUI. Permission nodes
(`ploceus.project.create`, `ploceus.world.create`, …) are resolved by LuckPerms.

A `KeyArgumentParser` with repository-backed suggestions accepts the bare value
(`elytrarace`) and prepends the `ploceus` namespace.

## 12. Error handling

The `platform` package translates Multiverse's `Attempt<T, Reason>` and Vavr types into a
Ploceus-owned `sealed interface OperationResult` with `Success` and `Failure` records,
evaluated through Java 25 pattern matching. `domain` and `service` never see Vavr.

Database failures surface as a `Failure` result; the player gets a MiniMessage, the full
stack trace goes to the log. No exception reaches a Bukkit event handler.

## 13. Java 25 usage

- Records for DTOs, value objects and dialog payloads.
- Sealed interfaces and pattern matching for results and domain events.
- Virtual-thread executor for database work (HikariCP still bounds the connections).
- Text blocks for HQL.
- Compact constructors for validation.
- **No preview features** — the server must not need `--enable-preview`.

## 14. Testing

- JUnit 5.
- Testcontainers (PostgreSQL) plus in-memory H2 for fast runs.
- ArchUnit for the layering rules in section 4.
- The unload policy is a pure function and is tested without Bukkit.
- Services are Bukkit-free and therefore testable without MockBukkit.
- One shared contract test suite across all three `ProjectUi` channels.

## 15. Build and release

Per the OneLiteFeather standards, with `Cygnus` as the reference implementation:

```
group            = "net.onelitefeather"
rootProject.name = "Ploceus"
version          = "1.0.0" // x-release-please-version
```

- **Renovate:** extend `github>OneLiteFeatherNET/renovate:default(...)` with the `:paper` flavor.
- **Release:** Release Please, `release-type: java`, `extra-files: gradle.properties`.
  Publishing is chained inside `release-please.yml` on `release_created`.
- **CI:** the org's reusable workflows at a full SemVer tag (`@v2.8.1` as of 2026-08) —
  `gradle-build-pr`, `gradle-publish`, `release-please`, `pr-lint`, `security-scan`,
  `sbom-publish`.
- **Git flow:** trunk-based on `main`, short-lived `feat/`|`fix/`|`chore/` branches,
  squash merge, Conventional Commits.
- **Docs:** Diátaxis structure.

## 16. Risks

1. **Hibernate classloading.** The thread context classloader must be set during
   `SessionFactory` construction or dialect resolution fails. Mitigation: a dedicated
   bootstrap helper plus an integration test that starts the plugin.
2. **`setAutoLoad(false)` lags by one restart.** Documented for operators; the first start
   after installation still loads everything.
3. **`lastVisitedAt` write volume.** Mitigated by 30 s batching.
4. **Dialog protocol threshold.** The minimum protocol version is a guess until verified
   against a real client; it is configurable for that reason.
5. **Three UI channels to keep in sync.** Mitigated by the shared contract test suite.
