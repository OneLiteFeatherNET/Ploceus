# Ploceus

Project and world management for Paper servers, built on
[Multiverse-Core](https://github.com/Multiverse/Multiverse-Core).

A *project* groups worlds that belong together — a minigame, an event, a build theme. Every
project and every world carries a description, tags, authors and a complete change history.
Worlds load when somebody enters them and unload again once nobody has been there for a while.

*Ploceus* is the weaverbird genus: they build elaborate nests and hang many of them in the
same tree.

## What it does

- **Projects** with descriptions, tags, authors and generator defaults for new worlds
- **Assign worlds** to projects, rename, pin, and set a per-world idle timeout
- **Create worlds** using the project's generator defaults, with name suggestions
- **Import worlds** that exist on disk but are not yet managed by Multiverse
- **On-demand loading**: nothing is loaded at server start that nobody needs
- **Idle unloading** of empty worlds after a configurable timeout
- **Audit trail** for every change: who, when, what before, what after

## Using it

Three equivalent interfaces, chosen automatically per client:

| Channel | When |
|---|---|
| **Dialog** | modern clients — the primary interface |
| **Inventory** | clients too old for the Dialog API, and as a visual world browser |
| **Chat** | last resort, and the only one that works from the console |

Switch manually with `/ploceus ui dialog|inventory|chat`.

## Requirements

- Paper **26.2** or newer, Java **25**
- Multiverse-Core **5.8.1**
- A database: H2 (default, no setup) or PostgreSQL

## Installation

Drop the jar into `plugins/` and start the server. The schema is created automatically and
existing Multiverse worlds are picked up as *unassigned*.

For PostgreSQL, adjust the `database` section in `plugins/Ploceus/config.yml`.

See [docs/operations.md](docs/operations.md) for operating the plugin: permissions, unload
behaviour, switching databases, OpenTelemetry wiring, and a checklist for the first run.

## Building

Multiverse-Core 5.8.1 is **not published to any public Maven repository**, so the build needs
the jar supplied locally:

```bash
mkdir -p libs
cp /path/to/your/server/plugins/multiverse-core-5.8.1.jar libs/
./gradlew build
```

`libs/` is intentionally not tracked — redistributing a third-party binary is not this
repository's job. Once the jar is mirrored to an internal Maven repository, replace the file
dependency in `build.gradle.kts` with the coordinate; the version catalog entry is already
there.

The PostgreSQL integration tests need a running Docker daemon. Without one they skip rather
than fail.

## License

MIT — see [LICENSE](LICENSE).
