# Chronicler

Data-driven quests, chapters and storylines for NeoForge. Server-authoritative:
vanilla clients play the whole thing.

Built to sit beside [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
[ZombieMod ReForged](https://github.com/Sablednah/ZombieMod),
[CityWorld ReForged](https://github.com/Sablednah/CityWorld-ReForged) and
[SableCraft Standards](https://github.com/Sablednah/SableCraft-Standards) — and
needs none of them.

**Status: skeleton (0.1.0, unreleased).** Content loads from datapacks and
YAML, `/quest list` and `/quest info` work, nothing is tracked yet. The design
and build order are in [docs/DESIGN.md](docs/DESIGN.md).

## Writing content

Drop a chapter and a quest into `config/chronicler/chapters/` and
`config/chronicler/quests/` as YAML, or into any datapack at
`data/<pack>/chronicler/{chapter,quest}/<name>.json`. Restart the server —
content is a frozen registry, `/reload` will not apply it.

```yaml
# config/chronicler/quests/things_in_the_dark.yml
name: Things in the Dark
description: They come out at night. Make it fewer of them.
chapter: prologue
requires: [first_steps]
objectives:
  - { type: kill, target: minecraft:zombie, count: 5 }
rewards:
  - { type: item, item: minecraft:iron_sword }
  - { type: money, amount: 25 }
```

Objective types today: `kill`, `collect`, `visit`. Reward types: `item`,
`command`, `xp`, `money`. Every word a player sees lives in
`config/chronicler/messages.yml`.

## Commands

| Command | Who |
|---|---|
| `/quest list` (`/quests list`) | everyone |
| `/quest info <quest>` | everyone |
| `/quest log` | everyone |
| `/chronicler reload` | `chronicler.admin` or op 2 — messages only |
| `/chronicler status` | `chronicler.admin` or op 2 |

## Building

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew build     # -> build/libs/chronicler-<version>+mc1.21.11.jar
```

## Licence

MIT.
