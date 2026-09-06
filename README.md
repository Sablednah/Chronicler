# Chronicler

Data-driven quests, chapters and storylines for NeoForge. Server-authoritative:
vanilla clients play the whole thing.

Built to sit beside [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
[ZombieMod ReForged](https://github.com/Sablednah/ZombieMod),
[CityWorld ReForged](https://github.com/Sablednah/CityWorld-ReForged) and
[SableCraft Standards](https://github.com/Sablednah/SableCraft-Standards) — and
needs none of them.

**Status: engine + journal + givers + stages (0.1.0, unreleased).** Quests load from datapacks
and YAML, can be accepted, are measured (kills, items held, places reached),
complete with a title card and pay out. The journal is a written book with a
page per quest and clickable links, handed to every new player. Party quests pool progress across a party when
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

A quest can carry a **giver**: `giver: { type: position, at: [x, y, z], label: "the vault door" }`
offers it to anyone standing near that block and accepts on right-click;
`giver: { type: place, biome: "#minecraft:is_jungle" }` (or `structure`,
`dimension`, `lot` for a CityWorld lot) offers it the moment a player is in
that kind of place, with no coordinate written down. An admin can also make
any block a giver by looking at it: `/quest giver set <quest>`. A hidden quest
with a giver is *found* by walking up to it.

A quest can be told in **stages**: ordered beats, each with its own `text`
(narrated as you reach it), `objectives`, and `on_enter` / `on_complete`
effects. Effects use the reward vocabulary plus `title`, `message` and `spawn`
(a vanilla entity, or a ZombieMod `genus`). A quest with plain `objectives`
is one stage.

```yaml
stages:
  - text: Get a light. The dark is not empty.
    objectives: [ { type: collect, item: minecraft:torch, consume: false } ]
  - text: They found you. Hold until it is quiet.
    on_enter:
      - { type: title, title: "&cThey are here", subtitle: "&7Hold the line" }
      - { type: spawn, entity: minecraft:zombie, count: 2, radius: 6 }
    objectives: [ { type: kill, target: minecraft:zombie, count: 2 } ]
```

Objective types today: `kill` (an entity id, a `#tag`, `any`, or a ZombieMod
genus id), `collect` (`consume: false` to only require carrying), `visit`
(`x`/`z`, optional `y`, `radius`, `dimension`, a `label` for the text), `place`
(`biome` / `structure` / `dimension` / `lot`, any combination). Reward
types: `item`, `command` (`{player}` substituted, run with gamemaster
permission), `xp`, `money` (through Standards' economy; says so if there is
none), `reputation` (`standing` + `delta`, through Standards 1.5.0's
reputation seam; says so if there is none), and the effects `title`,
`message` (`action_bar: true` for the bar) and `spawn`. Commands substitute
`{player}`, `{uuid}`, `{quest}`, `{x}`, `{y}`, `{z}`. A `reputation` objective
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
| `/quest journal` | everyone — open the journal book, no item needed |
| `/quest journal give` | everyone — a (replacement) journal item |
| `/quest giver set <quest>` / `remove` / `list` | `chronicler.admin` or op 2 — the block you are looking at offers a quest |
| `/chronicler reload` | `chronicler.admin` or op 2 — messages only |
| `/chronicler status` | `chronicler.admin` or op 2 |
| `/chronicler journal <player>` | `chronicler.admin` or op 2 — hand someone a journal |
| `/chronicler reset <player>` | `chronicler.admin` or op 2 — wipe a journal |

## Building

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew build     # -> build/libs/chronicler-<version>+mc1.21.11.jar
```

## Licence

MIT.
