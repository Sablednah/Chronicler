# Chronicler

Data-driven quests, chapters and storylines for NeoForge. Server-authoritative:
vanilla clients play the whole thing.

Built to sit beside [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
[ZombieMod ReForged](https://github.com/Sablednah/ZombieMod),
[CityWorld ReForged](https://github.com/Sablednah/CityWorld-ReForged) and
[SableCraft Standards](https://github.com/Sablednah/SableCraft-Standards) — and
needs none of them.

**Status: engine (0.1.0, unreleased).** Quests load from datapacks and YAML,
can be accepted, are measured (kills, items held, places reached), complete
with a title card and pay out. Party quests pool progress across a party when
SableCraft Standards is present. The design and build order are in
[docs/DESIGN.md](docs/DESIGN.md).

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

Objective types today: `kill` (an entity id, a `#tag`, `any`, or a ZombieMod
genus id), `collect` (`consume: false` to only require carrying), `visit`
(`x`/`z`, optional `y`, `radius`, `dimension`, a `label` for the text). Reward
types: `item`, `command` (`{player}` substituted, run with gamemaster
permission), `xp`, `money` (through Standards' economy; says so if there is
none), `reputation` (`standing` + `delta`, through Standards 1.5.0's
reputation seam; says so if there is none). A `reputation` objective
(`standing` + `at_least`) waits on a standing. A chapter carries `scope: solo | party` and `main: true`; a quest may
override `scope`. Every word a player sees lives in
`config/chronicler/messages.yml`.

## Commands

| Command | Who |
|---|---|
| `/quest list` (`/quests list`) | everyone |
| `/quest info <quest>` | everyone |
| `/quest log` | everyone |
| `/quest accept <quest>` | everyone |
| `/quest abandon <quest>` | everyone |
| `/quest track <quest>` | everyone — follow it on the action bar |
| `/chronicler reload` | `chronicler.admin` or op 2 — messages only |
| `/chronicler status` | `chronicler.admin` or op 2 |
| `/chronicler reset <player>` | `chronicler.admin` or op 2 — wipe a journal |

## Building

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew build     # -> build/libs/chronicler-<version>+mc1.21.11.jar
```

## Licence

MIT.
