![Chronicler](docs/wordmark-850.png)

# Chronicler

Data-driven quests, chapters and storylines for NeoForge, given by someone,
somewhere, told in beats, with choices that branch and consequences the world
remembers. Server-authoritative: **a vanilla client plays the whole thing**.

Built to sit beside [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
[ZombieMod ReForged](https://github.com/Sablednah/ZombieMod),
[CityWorld ReForged](https://github.com/Sablednah/CityWorld-ReForged),
[SableCraft Standards](https://github.com/Sablednah/SableCraft-Standards) and
[Cast](https://github.com/Sablednah/Cast) -- and needs none of them.

**Status: 0.1.0, heading for a first release.** The engine, the journal, givers
(blocks, places, NPCs), stages, choices, deadlines, endings and replay, quest
items, rituals, deliveries, party quests, reputation, progress, and the shipped
**ZARP** questline, all self-tested headlessly and played through to the Nether.
The store page is [CURSEFORGE.md](CURSEFORGE.md); the design and build order are
in [docs/DESIGN.md](docs/DESIGN.md); the questline walkthrough is
[docs/ZARP.md](docs/ZARP.md).

## Playing

- A giver floats a mark: `!` on offer, `?` while you are on it, a tick when done.
  Right-click to hear the offer; right-click again to accept.
- `/quest` alone says what you are doing now. `/quests` lists everything with
  your progress. The journal is a written book in your inventory (right-click
  it, or `/quest journal`), with clickable Track / Abandon / Accept links.
- Choices arrive as buttons in chat and in the book. `/quest replay <chapter>`
  starts a replayable chapter over, keeping the endings you found.

## Requirements

| Minecraft | NeoForge | Java | branch |
|---|---|---|---|
| 1.21.11 | 21.11.42+ | 21 | `main` |
| 26.1.2 | 26.1.2.95+ | 25 | `mc26.1` |
| 26.2 | 26.2.0.72+ | 25 | `mc26.2` |

Install on the server; players need nothing. Every sibling mod is optional.

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
with a giver is *found* by walking up to it. Every giver floats a mark over it, sent to
each player privately so it shows *their* state: `!` on offer, `?` while on
it, a tick once done, nothing while locked (`givers.markerText` /
`markerActive` / `markerComplete` / `markerLocked`, `&` colours). A right-click makes the offer, with Accept and Info buttons; a
second click within `givers.secondClickSeconds` accepts. A giver block refuses
to break and is skipped by explosions (`givers.protect`); an admin sneaking
while breaking it still can.

With [Cast](https://github.com/Sablednah/Cast) installed, givers can be
**people**: `giver: { type: npc, name: "Dr Okafor", skin: Sablednah, at: [x, y, z], greeting: "You look like you can hold a torch." }`
places a human NPC (or `entity: minecraft:villager` for a creature) the first
time the server starts with the quest, and right-clicking them offers it.
`/quest giver set <quest>` while looking at any Cast NPC does the same by
hand. Without Cast the quest still loads and lists; nobody is placed.

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

A quest can carry **availability** conditions beyond `requires`:

```yaml
availability:
  karma_min: 20          # LegendQuest karma (also karma_max, level_min, level_max)
  flags: { bridge_repaired: true }     # world flags a questline set
  player_flags: { chose_mercy: true }  # this player's own choices
  reputation: { survivors: 10 }        # Standards 1.5.0 standing
```

**World flags** are named facts a quest sets (`{ type: flag, name: bridge_repaired }`
as a reward or effect; `player: true` for the player's own) and anything can
read. ZombieMod genus files can gate spawning on one with
`{ "type": "chronicler:flag", "flag": "hospital_cleared", "value": false }`, so
finishing a questline changes what spawns. `/chronicler flag set|list` for admins.

A stage with `choices` and no objectives is a **decision**: the options are
put to the player as clickable buttons (and in the journal), each with its
own `text`, `effects`, and either `next: <stage number>`, `end: true`, or
nothing (the following stage). A stage with a `deadline` (seconds) shows a
countdown on the action bar and, when it runs out, fires `on_fail` and falls
back to stage `fail` or drops the quest.

```yaml
  - text: A stranger asks for your torch.
    choices:
      - { label: Give it, effects: [ { type: karma, delta: 5 } ], end: true }
      - { label: Keep it, effects: [ { type: karma, delta: -5 } ], end: true }
```

A `repeatable` quest with a `cooldown` (seconds) is a **bounty**: done again
and again, but not at once. Built-in examples in the Prologue: First Steps,
Things in the Dark (party), Night Watch (three beats, ends on a choice), Hot
Foot (hidden, found on entering the Nether), Cull (a bounty).

Objective types today: `kill` (an entity id, a `#tag`, `any`, or a ZombieMod
genus id), `collect` (`consume: false` to only require carrying), `visit`
(`x`/`z`, optional `y`, `radius`, `dimension`, a `label` for the text), `place`
(`biome` / `structure` / `dimension` / `lot`, any combination), `flag`
(wait for a flag), `reputation` (`standing` + `at_least`). Reward
types: `item`, `command` (`{player}` substituted, run with gamemaster
permission), `xp`, `money` (through Standards' economy; says so if there is
none), `reputation` (`standing` + `delta`, through Standards 1.5.0's
reputation seam; says so if there is none), `karma`, `class_xp`, `levels` and `skill_points` through LegendQuest (say so
without it), `flag`, and the effects `title`, `message` (`action_bar: true`
for the bar) and `spawn`. Commands substitute
`{player}`, `{uuid}`, `{quest}`, `{x}`, `{y}`, `{z}`. A `reputation` objective
(`standing` + `at_least`) waits on a standing. A chapter carries `scope: solo | party` and `main: true`; a quest may
override `scope`. Every word a player sees lives in
`config/chronicler/messages.yml`.

### The rest of the vocabulary (2026-09-08)

- **`kill`** takes a list: `target: [zombiemod:harvester, minecraft:zombie_villager]`
  counts any of them, so a file names the genus first and the vanilla stand-in
  second. `tag: warden` also counts anything a `spawn` effect tagged. `drop:
  { quest_item: zarp:ember_heart, chance: 1.0 }` makes counted kills drop a
  quest item, no loot table needed. A mob a quest spawned that dies to
  anything else (a fall, the sun, itself) still counts for the player it was
  spawned for; `own_kill: true` refuses that and spawns another instead, so a
  boss that blows itself up can be tried again.
- **`spawn`** takes `name`, `tag` and `health`; with a `genus` *and* an
  `entity`, the entity stands in when ZombieMod is absent -- one file, two servers.
- **`ritual`** is a multiblock: right-click `block` while every `pattern`
  entry (`offset: [x, y, z]`, `block`) is in place, holding `item` if named
  (`consume: true` takes it). A click with the pattern wrong says which block
  is missing where. The beat's `on_complete` is where the boss comes out.
- **`deliver`** is the hand-over: `{ type: deliver, quest_item: zarp:insulin, count: 2, to: zarp:the_camp }`
  completes when the player clicks the giver of quest `to` holding the items
  (or, with `radius`, stands near them); the items go then. A bare `collect` is
  satisfied in your pack, which is not the same thing. `spawn` takes `equipment`
  (a leather cap keeps a daytime zombie alive). With a `genus`, ZombieMod
  dresses the mob first and `equipment` only fills the slots it left empty --
  a Patient keeps its mask -- unless `override: true`. Quest items are not
  eatable or drinkable unless the entry says `usable: true`.
- **`wait`** (`seconds`) lets time pass from entering the beat -- "come back later".
- **`collect`** takes `tag: minecraft:logs` or `quest_item: zarp:insulin`.
- **`place`** takes `any: [ { lot: Hospital }, { structure: "#minecraft:village" } ]`
  -- alternatives, so a CityWorld lot has a vanilla fallback.
- **Quest items** live in `chronicler:item` (`data/<pack>/chronicler/item/<name>.json`
  or `config/chronicler/items/`): `{ item: minecraft:nether_star, name: "&5The Origin
  Sample", lore: [...], glint: true, max_stack: 1 }`. They are marked invisibly
  in `custom_data`, so renaming one in an anvil changes nothing. `{ type: item,
  quest_item: zarp:insulin }` rewards one; loot tables use `{ "function":
  "chronicler:quest_item", "id": "zarp:ember_heart" }`; `/chronicler item give|list`.
- **Position givers** can stand `near_spawn: [dx, dz]` too, and place their own
  `block` (a campfire) with `decor` around it, once, remembered -- a camp on any
  seed, offering the first quest on approach. A fixed seed or a schematic can
  replace it later without touching the quest.
- **NPC givers** can stand `near_spawn: [dx, dz]` instead of `at`, dropped onto
  the surface on any seed, and `of: zarp:the_camp` makes one person give
  several quests (their mark shows whichever matters now). `npc_say` and
  `npc_remove` effects make them speak or leave (`quest:` picks whose NPC).
  `equipment: { mainhand: "minecraft:potion[potion_contents={potion:'minecraft:healing'}]", head: minecraft:iron_helmet }`
  dresses them (slots mainhand, offhand, head, chest, legs, feet; items as
  `/give` takes them), applied on placement and re-applied on restart if the
  file changes; `/cast equip` does it by hand. NPCs obey gravity (mine the
  block under one and it lands); `defy_gravity: true` keeps one exactly where
  it was put.
- **Availability** takes `race: [immune]` and `class: [doc, combat_medic]`
  (LegendQuest ids, bare or namespaced; any of the list), beside `level_min`.
- **Endings.** A stage or a choice with `ending: cure` records an ending for
  the chapter; `end: true` on a stage finishes the quest there. A chapter with
  `replayable: true` can be started over with `/quest replay <chapter>` --
  completions, cooldowns and the chapter's own player flags go, endings stay.
- **`locked`** on a quest is the in-character line for a refusal ("Kit shakes
  her head. 'They'd look at you.'"), spoken by the NPC if there is one; what it
  really means follows in grey brackets, generated from the requirements and
  conditions ("finish The Camp; be Immune"). Without `locked`, a plain sentence.
- **Progress.** `/quests` and the journal show a percentage. Only quests that
  count move it: `counts: true|false`, unsaid means "main chapter and not
  repeatable", so bounties and side lines never hold anyone short of 100%.

### ZARP

The Zombie Apocalypse Roleplay questline ships in the jar as a datapack and is
on whenever ZombieMod is installed (`content.zarp = auto | on | off`). Five
people at a camp near spawn, four acts, a finale with three endings, a side
chapter that remembers what you refused, and a bounty board. It plays without
CityWorld or LegendQuest (lots have vanilla fallbacks; class and race quests
simply do not offer). `docs/ZARP.md` is the walkthrough. The sample fantasy
prologue is a built-in pack too (`content.prologue`), on unless ZARP is, so a
ZARP world is not offered two kinds of log run.

## Commands

| Command | Who |
|---|---|
| `/quest list` (`/quests list`) | everyone |
| `/quest info <quest>` | everyone |
| `/quest log` | everyone |
| `/quest accept <quest>` | everyone |
| `/quest abandon <quest>` | everyone |
| `/quest track <quest>` | everyone — follow it on the action bar |
| `/quest choose <quest> <n>` | everyone — pick an option at a decision |
| `/quest journal` | everyone — open the journal book, no item needed |
| `/quest journal give` | everyone — a (replacement) journal item |
| `/quest giver set <quest>` / `remove` / `list` | `chronicler.admin` or op 2 — the block you are looking at offers a quest |
| `/chronicler reload` | `chronicler.admin` or op 2 — messages only |
| `/chronicler status` | `chronicler.admin` or op 2 |
| `/chronicler journal <player>` | `chronicler.admin` or op 2 — hand someone a journal |
| `/chronicler flag set <flag> [true\|false]` / `list` | `chronicler.admin` or op 2 — world flags |
| `/chronicler reset <player>` | `chronicler.admin` or op 2 — wipe a journal |

## Building

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew build     # -> build/libs/chronicler-<version>+mc1.21.11.jar
```

## Licence

MIT.
