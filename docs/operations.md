# Operating Ploceus

Things an operator cannot infer from the code alone.

## First start after installation

Multiverse loads its worlds before Ploceus is enabled, so disabling auto-load only takes
effect from the **next** server start. Expect the first start after installation to load
every world exactly as before, the same as it always has. The log will say how many worlds
had auto-load disabled during that first pass (`Disabled auto-load for N worlds. This takes
effect from the next server start.`) — that message describes what will happen on the
*following* restart, not what just happened.

## Worlds cannot be renamed on disk

Multiverse 5.8.1 has no `renameWorld`, and Ploceus does not attempt to work around that.
`/ploceus world rename` changes the **display name** only and mirrors it onto the
Multiverse alias. The world **folder** keeps its original name forever — that folder name
is what WorldGuard regions (`plugins/WorldGuard/worlds/<name>/`), Axiom data and BlueMap
renders are all keyed by. Treat the Ploceus key (and the folder name) as the stable
identity; the display name is cosmetic only, shown in menus and audit entries, and is safe
to change as often as needed.

## Switching from H2 to PostgreSQL

Set `database.type: postgresql` plus `host`, `port`, `name`, `user` and `password` in
`config.yml`, then restart. Liquibase creates the schema in the new database.
**Existing data is not migrated** — export it yourself first if you need it.

PostgreSQL support was broken for most of this project's development (`KeyUserType`, the
Hibernate mapping for Ploceus's key type, did not implement `EnhancedUserType`, so schema
generation failed against PostgreSQL's stricter type system). That is fixed as of this
release, and a Testcontainers-backed integration suite runs the full repository test suite
against a real PostgreSQL container in the build, alongside the H2 suite — so both database
backends are genuinely exercised by CI, not just H2.

## Tuning the unload behaviour

- `worlds.idle-unload-minutes` — global default, 30.
- Per world: `/ploceus world idle <worldkey> <minutes>`, or pin it (`/ploceus world pin
  <worldkey> true`) so it is never unloaded regardless of idle time.
- `worlds.never-unload` — world names that are always kept loaded, in addition to the
  server's default world.

To watch it work, set `idle-unload-minutes: 1` temporarily and see [Phase 2 of the checklist
below](#on-server-verification-checklist).

## When a client sees no dialogs

`ui.min-dialog-protocol` (default `771`) is the lowest client protocol version considered to
support the Dialog API. ViaVersion lets older clients connect anyway, and those fall back to
the inventory channel automatically. If a protocol bump makes the threshold wrong, adjust it
here — no plugin release needed. Players can also choose explicitly with `/ploceus ui
dialog|inventory|chat`, which overrides the protocol-based default until they choose again.

## Permissions

The table below is read directly off `@Permission` annotations in
`command/PloceusCommands.java` — it is the actual command surface, not an aspirational list.

| Node | Grants |
|---|---|
| `ploceus.use` | open `/ploceus` (the main menu) and switch UI channel with `/ploceus ui <channel>` |
| `ploceus.project.list` | list projects (`project list`) and view a project's detail screen, including its worlds (`project info`) |
| `ploceus.project.create` | open the create-project form and create projects |
| `ploceus.project.edit` | rename projects (`project rename`) |
| `ploceus.project.delete` | delete projects (`project delete ... --confirm`) |
| `ploceus.world.list` | list unassigned worlds (`world list-unassigned`) |
| `ploceus.world.create` | open the create-world form and create worlds |
| `ploceus.world.assign` | assign and unassign worlds to/from a project |
| `ploceus.world.edit` | rename a world, set its idle-unload minutes, pin/unpin it, and add tags |
| `ploceus.world.teleport` | teleport into a world (`world tp`) |
| `ploceus.search` | search projects and worlds |
| `ploceus.audit` | read a project's or world's audit trail |

Authors recorded on a project are credits only and grant nothing — access control stays
entirely with the permission nodes above (see the design doc's non-goals).

`ProjectService.describe` and `WorldService.describe` (setting a free-text description)
exist at the service layer but have no command wired to them yet — there is currently no
way to set a description from any UI channel or command. This is a real gap in the command
surface, not a documentation omission; flag it if description-setting is expected to work.

Both the dialog and inventory channels re-check the relevant permission a second time, at
the moment a button/item is actually clicked, via the shared `ui.UiPermissions` helper —
not only when the screen was rendered. This closes the gap where a client could replay a
stale callback id, or a permission could be revoked while a screen was already open on
screen. `ui.dialog.DialogCallbacks.button(...)` requires a `Player` and a permission node by
construction, so there is no way to build a dialog button that skips this check.

## On-server verification checklist

Several tasks in this project's build deliberately skipped their in-game verification step:
running Ploceus against the real `Team-Build-1` server rewrites `worlds.yml` for all 76
worlds and creates a new database on first enable — an irreversible change that was never
approved mid-build. Every check below is still outstanding. **Do this against a throwaway
copy of `Team-Build-1`, never the live server directly**, until it has been worked through
once.

The order matters: earlier phases would expose a fundamental problem (the plugin fails to
boot, or corrupts the world list) before later phases are worth attempting at all. Each step
has a concrete, observable pass condition — not a vague "check that it works".

### Phase 1 — First boot (most likely to reveal a fundamental problem)

1. Copy the built jar into the throwaway server's `plugins/` folder and start it for the
   first time.
   **Expect:** no Hibernate-dialect stack trace during startup (this would indicate the
   plugin classloader/context-classloader wiring for Guice + Hibernate + Liquibase under
   Paper is broken — nothing in the unit tests can catch this).
2. Read the startup log.
   **Expect:** a line reading `Imported 76 worlds, 0 already known, N key collisions` —
   confirms `MultiverseAdapter.snapshotAll()` enumerates every real Multiverse world, not
   just the fixture worlds the unit tests use.
3. Still in the startup log.
   **Expect:** a line reading `Disabled auto-load for N worlds. This takes effect from the
   next server start.` — confirms `setAutoLoad` succeeds against the real `WorldManager`,
   not a test double. Per "First start after installation" above, worlds still loaded this
   time regardless.
4. Check the plugin's data folder.
   **Expect:** `plugins/Ploceus/data.mv.db` exists — confirms the H2 path resolution and the
   Liquibase migration produced a working database file.

### Phase 2 — Second boot (confirms the lifecycle actually engages)

5. Restart the same server a second time.
   **Expect:** startup is noticeably faster and prepares far fewer worlds than the first
   boot — confirms the auto-load suppression from Phase 1 actually took effect for
   Multiverse.
6. Set `worlds.idle-unload-minutes: 1` in `config.yml` and restart once more. Teleport into
   any world, then leave it (change world or disconnect), then wait just over a minute.
   **Expect:** the world unloads on its own, and `/mv list` shows it unloaded — confirms
   `WorldVisitListener` records real join/quit/world-change events, `VisitTracker` flushes
   them on schedule, and the reaper correctly threads its snapshot/decision/unload steps
   against the real Bukkit scheduler.

### Phase 3 — Commands and permissions

7. `/ploceus project create testproject "Test Project"`.
   **Expect:** a success message in chat.
8. `/ploceus project list`, `/projects project list`, `/pj project list`.
   **Expect:** all three resolve to the same command and show identical output — the first
   live confirmation of the alias mechanism (previously verified only at the
   syntax-parser level, never against a real `CommandManager`).
9. `/ploceus project delete testproject` (no `--confirm`), then
   `/ploceus project delete testproject --confirm`.
   **Expect:** the first is rejected as invalid syntax/unknown command; the second actually
   deletes the project. This is the live confirmation of the hard requirement documented in
   `PloceusCommands`' class Javadoc.
10. `/ploceus world list-unassigned`, then `/ploceus world assign <key> <project>`, then
    `/ploceus world tp <key>`; also try `tp` into a world Multiverse cannot load.
    **Expect:** list, assign and teleport all work; the failing teleport shows feedback
    instead of leaving the player stuck.
11. Tab-complete every `<key>` argument across the commands above.
    **Expect:** real project/world keys are offered, completion resolves without a
    main-thread hitch (this is what `registerAsynchronousCompletions` exists to guarantee),
    and the offered keys update live as projects/worlds are created or deleted.
12. Typing a command with a `PloceusKey` or enum argument, before pressing enter.
    **Expect:** the client shows red/green argument-validity feedback while typing — the
    visible sign that native Brigadier registered successfully. Its absence, or a
    `WARNING`/`INFO` log line from `CommandRegistrar` at startup naming Brigadier as
    unavailable, means the classic fallback path is active instead (commands still work,
    just without client-side validation).
13. Repeat a representative sample of the commands above as a non-OP account holding none of
    the `ploceus.*` nodes.
    **Expect:** every command is rejected per whatever permission-default convention the
    server uses (nothing in `plugin.yml` declares default permission values).

### Phase 4 — Dialog channel

14. `/ploceus ui dialog`, then `/ploceus`.
    **Expect:** a dialog opens (not a silent no-op) with working main-menu buttons.
15. Paginate the project list, world list and unassigned-worlds screens to both ends.
    **Expect:** the first page offers only "Next", the last page offers only "Previous".
16. Click a world button; separately, force a teleport failure (e.g. a world Multiverse
    can't load).
    **Expect:** the dialog closes, a "teleporting" chat line appears, and the teleport
    happens; on failure, feedback is shown instead of leaving the player stuck with a closed
    dialog.
17. Compare the loaded/unloaded badge shown per world against `/mv list` at the same moment.
    **Expect:** they match.
18. Open a project's detail screen and press Delete, then Cancel; repeat and press Confirm.
    **Expect:** Cancel closes the dialog without deleting; Confirm deletes and shows
    feedback.
19. Open the audit trail for a project or world with several entries.
    **Expect:** entries render as body text above the pagination buttons, in the same
    dialog, not disconnected from it.
20. Open the create-project form and submit a valid key, then an already-used/invalid key;
    open the edit-project form for an existing project.
    **Expect:** a valid key creates the project; an invalid key shows `form.invalidKey`
    instead of crashing; the edit form pre-fills display name, description and correctly
    selects the current status as the chosen radio option.
21. Open the search prompt, type a term, submit.
    **Expect:** the term round-trips correctly into the search-results screen.
22. Open a dialog with a button gated by a permission you then revoke from another session,
    without closing the dialog, then click that button.
    **Expect:** `error.noPermission`, not a silently successful action — this is the
    execute-time re-check working end-to-end, not just at the unit-test level.

### Phase 5 — Inventory channel

23. `/ploceus ui inventory`, then `/ploceus`; then `/ploceus ui dialog` to switch back.
    **Expect:** a chest GUI opens (not a silent no-op); switching back to dialog works and
    the router's protocol-based default doesn't fight either explicit choice.
24. Repeat checks 15–19 above (pagination, teleport, loaded/unloaded state, delete
    confirmation, audit trail) against the inventory screens.
    **Expect:** the same outcomes, rendered as chest GUI items instead of a dialog; audit
    entries render as readable paper items.
25. Look at `WorldIcons` for a loaded, an unloaded and a pinned world side by side.
    **Expect:** the three states are visually distinct and readable, not just distinct by
    `Material` in the test suite.
26. Trigger the three chat-fallback forms (search prompt, create/edit project, world-create
    stub) from the inventory channel.
    **Expect:** each sends a working suggested command that actually populates the player's
    chat input.
27. Repeat check 22 (revoke a permission while the relevant screen is already open) against
    a chest GUI item instead of a dialog button.
    **Expect:** `error.noPermission`, confirming the execute-time re-check for the inventory
    channel specifically (`InventoryPermissionsTest` only proves the denied-`Player`-double
    path at the unit level, not a live click through a real `ChestGui`).

### Phase 6 — World creation

28. Set a project's generator preset, then `/ploceus world create <project>`.
    **Expect:** the dialog opens with that generator preselected in the picker and a
    suggested name already filled into both the key and display-name fields.
29. Press "Suggest another name".
    **Expect:** the dialog reopens with a different suggested name (other fields reset —
    this is the accepted trade-off noted in `DialogUi`'s Javadoc: the Dialog API cannot
    patch a single value inside an already-open dialog).
30. Submit the form.
    **Expect:** the world is created, appears under the project, and `/mv list` shows it
    with auto-load off and its alias set to the display name.
31. Create a second world, choosing a generator different from the project's preset.
    **Expect:** `/ploceus audit <worldkey>` shows a `WORLD_CREATED` entry recording the
    deviation: field `generator`, old value the preset, new value the one you chose.
32. Attempt to reach or submit the create-world form as a player without
    `ploceus.world.create`, including replaying a captured callback id from another session.
    **Expect:** no button reachable via the command's own permission gate, and the replayed
    callback is rejected by the execute-time check rather than acting.

### Phase 7 — Cross-channel consistency

33. Set a UI channel preference with `/ploceus ui <channel>` for each of the three channels
    in turn, then disconnect and reconnect between each change.
    **Expect:** the chosen channel is still active after reconnecting — the preference is
    persisted and reapplied across a relog, for all three channels, not only the one active
    at the time this was last checked.
