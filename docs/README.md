# Ploceus documentation

Ploceus groups Multiverse worlds into **projects** on the OneLiteFeather team build
server: metadata (description, tags, authors), an append-only audit trail, and on-demand
world loading/unloading, on top of Paper 1.26.2 and Multiverse-Core 5.8.1.

- Design and requirements: [`superpowers/specs/2026-09-13-ploceus-design.md`](superpowers/specs/2026-09-13-ploceus-design.md)
- Implementation plan and task history: [`superpowers/plans/2026-09-13-ploceus.md`](superpowers/plans/2026-09-13-ploceus.md)
- **Running it day to day: [`operations.md`](operations.md)** — configuration, permissions,
  the H2/PostgreSQL split, and the checklist to work through on a real server before this
  ships to `Team-Build-1`.

## Three UI channels, one contract

Every screen is declared once, in `net.onelitefeather.ploceus.ui.ProjectUi`, and
implemented three times:

- `ui.dialog.DialogUi` — Paper's native Dialog API (the primary channel).
- `ui.inventory.InventoryUi` — a chest GUI, for clients below the Dialog API's minimum
  protocol version.
- `ui.chat.ChatUi` — plain chat with clickable components, the universal fallback.

`UiRouter` picks a channel per player (explicit `/ploceus ui <channel>` choice, falling
back to a protocol-version check), and `ProjectUiContractTest` keeps all three honest:
one dynamic test per screen per channel asserts the channel overrides it, plus a bytecode
check that none of them merely satisfies the interface with an
`UnsupportedOperationException` stub.
