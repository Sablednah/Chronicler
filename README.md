# Chronicler

Data-driven quests, chapters and storylines for NeoForge. Server-authoritative:
vanilla clients play the whole thing.

Built to sit beside [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
[ZombieMod ReForged](https://github.com/Sablednah/ZombieMod),
[CityWorld ReForged](https://github.com/Sablednah/CityWorld-ReForged) and
[SableCraft Standards](https://github.com/Sablednah/SableCraft-Standards) — and
needs none of them.

**Status: 0.1.0 (unreleased) — engine, journal, givers, stages, conditions, choices, deadlines, bounties.** Quests load from datapacks
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
  a Patient keeps its mask -- unless `override: true`. `around: spawn` (world
  spawn) or `around: giver` (this quest's giver) centres the ring somewhere other
  than the player, and `min_radius` keeps its middle clear: `{ type: spawn,
  around: spawn, min_radius: 17, radius: 24 }` is outside a fence at 15. Quest items are not
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
  `npc_remove` effects make them speak or leave (`quest:` picks whose NPC); `leave: <block>` puts
  what is left of them where they last stood, and `giver_for: <quest>` makes that block its giver.
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

### Achievements

One vanilla advancement per chapter and one per ending, generated at pack-listing time from
chapters and quests in `config/chronicler/*.yml` and the two built-in packs -- real toasts,
real progress in the vanilla Advancements screen (`chronicler:root` is the tab; chapters chain
off `requires`, or off the previous chapter by `order` when a chapter sets none; endings hang
off their own chapter, framed as a challenge and hidden until reached). Granted automatically:
`chronicler:root` the first time a player's journal begins, a chapter's the moment every
counting quest in it is done, an ending's the moment it is reached. Any quest can also grant
one explicitly: `{"type": "advancement", "id": "chronicler:chapter/zarp/finale"}` -- any
advancement, including vanilla's own or one a third-party datapack ships. **The gap**: a
chapter or ending that lives only in a third-party datapack (never in config YAML or the
built-in packs) is not seen by the generator and gets no advancement of its own; that pack's
author can ship a real advancement JSON alongside their content, or any quest can grant one by
id regardless of where it came from. `content.achievements.enabled` (default on) turns the
whole thing off.

### Mini quests

Small errands that happen a hundred times -- walk this stranger to the nearest
village, bring that villager five of something, free a stuck door -- written once
as a **template**. Any value in a quest file may be a `{slot}` hole, and a `mini:`
block says how each slot is filled when the quest starts:

```yaml
name: "{wants.name} for {asker}"
chapter: errands
repeatable: true
hidden: true
mini:
  slots:
    first:   { type: pick, pool: [Aldous, Wen, Marisol] }
    asker:   { type: npc, name: "&f{first}", entity: minecraft:villager, min: 4, max: 8 }
    wants:   { type: pick, pool: [minecraft:bread, minecraft:wheat, minecraft:leather] }
    count:   { type: number, min: 3, max: 8 }
  spawn:     # optional: offered unasked, while a player is in the place
    place: { structure: "#minecraft:village" }
    chance: 0.05
    every: 120        # seconds between rolls, per player
    cap: 2            # standing wild offers of this template
    giver: asker      # the npc slot that makes the offer (or block: a block slot)
    lapse: 300        # seconds before an ignored offer goes, and its person with it
    say: "&7Have you got {count} {wants.name} going spare?"
objectives:
  - { type: deliver, item: "{wants}", count: "{count}", npc: "{asker.id}" }
rewards:
  - { type: xp, amount: 20 }
```

Slot types: `pick` (one of a `pool`), `number` (`min`..`max`), `here` (where it
starts), `around` (a dry spot `min`..`max` blocks off), `structure` (the nearest, an
id or `#tag`, within `radius`), `lot` (the nearest CityWorld lot whose words contain
`lot`, or a CityWorld schematic by name with `schematic: chayats-bank`), `block` (the nearest, id or `#tag`), `giver` (where another quest's giver
stands), `given` (a position whoever starts it hands over), `npc` (a person Cast
places: `name`, `skin` or `entity`, `equipment`, `min`/`max` distance, `keep`). A place
gives `{s}` (its `label`), `{s.x}`, `{s.y}`, `{s.z}`, `{s.pos}` and `{s.dim}`; a person
also `{s.id}`; a pick `{s.name}`, prettified. `near: <slot>` searches from an earlier
slot. A hole that is the whole value becomes a number or a list where one is wanted
(`count: "{count}"`, `to: "{village.pos}"`). A template is checked at load by filling
it with stand-in values, so a mistake is refused at boot like any other bad file.

What minis use: `escort` (`who: "{scholar.id}"`, `to`, `radius`, `near`: the person
follows whoever leads them, on foot along the way they walk, and it is done when they
stand at `to` with the player beside them; wander off and they wait. `hits: 6` makes it
dangerous: monsters nearby go for them, every blow is counted -- never damage -- and the
sixth fails the beat like a deadline; unsaid, they are untouchable. `settle: 3` holds the
arrival for that many seconds before it credits, so crossing a line does not end the beat
before the player is actually inside -- `visit` takes the same field, for the same reason
on a plain "go here"), `deliver` to
`npc` (a person by id) or `at` (a block, which will not open until it has what it
wants), `npc_say` / `npc_remove` with `npc`, the `block` effect (`at`, `block` and/or
`properties`: `open: "true"`), and the `mini` effect (`template`, `near`, `slots`,
`offer`), which starts the next errand where this one ended. One copy of each
template runs per player; people placed for one leave when it ends unless `keep`.
A `place` (for wild spawns, givers and the `place` objective) takes `schematic` too, so
standing in Chayat's Bank or the Winchester can offer a quest. A `kill` with a `tag` and
no `target` counts only the tagged mob (Phil). The built-in packs carry fifteen: the
archaeologist, the village errand, the stuck door, pests, the lost satchel, a rescue
from a pillager outpost, and in a CityWorld city a town-hall escort and a run to
Chayat's Bank in the prologue; bring a survivor in, medicine, the nest, the rusted
door, a warehouse run, a bank job and The Plan in ZARP.

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
