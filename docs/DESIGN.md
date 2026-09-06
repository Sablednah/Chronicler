# Chronicler — design

A configurable quest system for NeoForge. Built to sit beside LegendQuest,
ZombieMod, CityWorld and SableCraft Standards; needs none of them. This is the
thinking document: what makes it *not* FTB Quests rehashed, what the data
looks like, and the order to build it in. `CLAUDE.md` holds the working
conventions; `README.md` will hold what a server owner needs once there is
something to run.

**Status: skeleton built and verified 2026-09-05** (jar builds, dev server boots clean, self-test 19/19). Registries, YAML front door, `/quest
list|info|log`, `/chronicler reload|status`, the journal attachment, Lang with
merge, and a self-test all exist. **No objective is measured and no reward is
granted yet** — that is step 1 below.

## What FTB Quests is, honestly

FTB Quests is a *checklist with a graph view*: a grid of icons, each a task
(collect, kill, visit, advancement…), lines between them for dependencies,
rewards on completion, and a GUI that is the whole experience. It is excellent
at what modpacks want — "go make a blast furnace" — and every quest mod since
has copied its shape.

Its limits are exactly where an RPG lives:

- **It has no world.** A quest is a card in a book, not a person in a place.
  Nothing in the world knows a quest exists.
- **It has no time.** Nothing happens *to* you; you tick boxes at your own pace.
- **It has no memory.** The quest book does not know what you did, only what
  you completed.
- **It needs its client.** Vanilla players cannot play it at all — which for
  this family is disqualifying on its own (see `[[vanilla-first]]` in
  `CLAUDE.md`).

So: keep the *structure* everyone expects (chapters, prerequisites, objectives,
rewards, a journal) because it is the right structure. Change what a quest
**is**.

## The five ideas that make it feel like an RPG

### 1. Quests are given by *someone*, *somewhere* — the Chronicle Marker

Every quest can have a **giver**: a vanilla-visible presence in the world that
offers it. Not a custom NPC entity (vanilla clients could not see it — the
ZombieMod rule), but built from things vanilla already draws:

- a **named villager or armor stand with a player-head and a `text_display`
  plate** (the LegendQuest nameplate mechanism, already proven);
- a **lectern with a written book** (the quest text *is* the book; vanilla
  renders it perfectly);
- a **sign, a chest, a banner, a block you right-click**;
- or a **ZombieMod boss** — the quest to kill Patient Zero is *given by* the
  ritual altar that summons him.

The giver is placed by data (`giver: { at: [x,y,z], kind: lectern }`) or by an
op standing there (`/quest giver set <quest>` — no coordinates in a text file
unless you want them). Walking up to it shows the offer on the action bar;
right-click accepts. **This is the single biggest feel change**: you are not
opening a menu, you are walking to the doctor in the hospital and being asked.

### 2. Stages, not just checklists — quests have a *shape*

An FTB task list is flat: all tasks, any order, done when all are done.
Chronicler quests have **stages**: ordered beats, each with its own objectives,
its own text, and its own on-enter/on-complete effects.

```yaml
name: The Doctor's Notes
giver: { kind: lectern, label: "Dr Okafor's desk" }
stages:
  - text: "Dr Okafor's notes mention samples in the hospital basement."
    objectives:
      - { type: visit, label: "the hospital basement", lot: hospital, depth: -1 }
  - text: "The samples are gone. Something took them east."
    on_enter:
      - { type: spawn, genus: zombiemod:harvester, count: 3, near: player }
    objectives:
      - { type: kill, target: zombiemod:harvester, count: 3 }
  - text: "One of them was carrying a vial."
    objectives:
      - { type: collect, item: chronicler:sample_vial, count: 1 }
```

Stage text is narrated *as you reach it*, on the action bar and in the
journal book — so the quest tells a story in order, and the story is what you
were doing, not a paragraph you read before you started.

A quest with one stage is a flat FTB-style quest. Nothing is lost.

### 3. Choices and consequences — branches, factions, karma

An RPG quest can end more than one way. A stage may offer **choices**, each
leading to a different next stage or a different quest:

```yaml
  - text: "The survivor begs you to let her keep the vial."
    choices:
      - { label: "Take it",      next: take_the_vial,  karma: -10 }
      - { label: "Let her keep it", next: mercy,        karma: +10, reputation: { survivors: +5 } }
```

Choices render as **clickable chat buttons** (Standards proved these work on
vanilla clients for `/tpa`), or as which giver you go back to. The consequence
mechanisms already exist next door:

- **Karma** — LegendQuest's karma is the moral axis; a quest choice can move it
  and a quest can *require* it (`requires: { karma_min: 20 }`). The Plague
  Caller class is karma-gated already; a questline can be the story of how
  you fell to it.
- **Reputation** — a small per-player standing with named factions
  (`survivors`, `raiders`, `the_hospital`) kept by Chronicler itself, because
  nothing else owns it. Standards' Groups API answers "which faction are you
  *in*"; reputation answers "what do they think of you". Both matter.
- **World state flags** — `flags: { bridge_repaired: true }` set by a quest and
  readable by any other quest, giver, or ZombieMod spawn condition. A finished
  questline can turn a district's spawns down (via a
  `chronicler:flag` spawn condition registered into ZombieMod's public
  `SpawnConditionTypes`). **The city gets safer because you made it safer.**

### 4. The world pushes back — timed, ambient and triggered quests

Not every quest is picked up from a giver. Some **arrive**:

- **Ambient**: entering a *place* for the first time offers a quest on the
  action bar. Places are anything the world already knows how to name, so no
  coordinate is ever written down:
  - a **CityWorld lot** (`lot: hospital`, `context: industrial`) — the city
    becomes a quest board; CityWorld's API answers "what is this place" for
    any chunk, generated or not;
  - a **biome** (`biome: minecraft:mushroom_fields`, or a `#tag`) — "find a
    mushroom island";
  - a **vanilla structure** (`structure: minecraft:pillager_outpost`,
    `minecraft:stronghold`, `minecraft:ancient_city`) — "find the illager
    outpost", or "the cure needs something from the End" that sends the
    player through the stronghold portal. CityWorld keeps strongholds, trial
    chambers and ancient cities on purpose, so these exist in ZARP worlds too;
  - a **dimension** (`dimension: minecraft:the_end`).
  All four are one `place` condition with alternatives, checked on the same
  interval as the `visit` objective, and the same condition doubles as an
  objective (`type: place`) so "go there" and "you are there, here is a quest"
  are one mechanism.
- **Triggered**: a ZombieMod horde ending, a boss phase change, a player death,
  nightfall, a date (ZombieMod has a `date` condition already), a LegendQuest
  level-up, a Factions raid. Chronicler listens; quests declare `trigger:`.
- **Timed**: a stage can have a clock (`deadline: 10m`) with a *fail* branch.
  Rescue quests where the survivor dies if you dawdle. Standards' action-bar
  countdown is the display.
- **Recurring**: daily/weekly resets for repeatable "bounty board" quests, so
  a server has something to do on day 40.

### 5. The journal is a *book*, and the book is the UI

The vanilla-client surface is the **written book**: `/quest journal` hands the
player a book (or opens one via `ClientboundOpenBookPacket`) with a page per
active quest — name, current stage text, objectives with progress, and
clickable `[Track]` / `[Abandon]` / `[Where?]` links. ZombieMod's dex book is
the prior art and it works.

The action bar is the **tracker**: one line, the tracked objective, updated
when it changes (never every tick — that buries chat). Titles mark stage
changes and completions (`Feedback.fanfare`, already written).

A modded client, later, gets a HUD panel and a map pin. Same answers, nicer
surface — Standards' `CLIENT.md` rule. **Not step 1.**

## Main questline and side quests — the framing

The shape every RPG player already knows: **one main questline** that carries
the story from the first step to the ending, and **side quests** that arrive
from the world around it. Chronicler makes that a first-class distinction
rather than a naming convention:

- A **chapter** is `main: true` or not. Main chapters are the spine; the
  journal lists them first and the tracker defaults to the main quest when
  nothing else is tracked.
- **Side quests** are everything triggered ambiently (a place, a biome, a
  structure), by a giver you happened to walk past, or by an event. They
  may feed the main line (`sets flags the main line reads`) but never block
  it unless a main quest says `requires` on them.
- **Bounties** are repeatable side quests on a reset clock, offered from a
  board-style giver (a sign or lectern at the survivor camp): clear a block,
  fetch medicine, kill *n* of a genus. They are what a server does on day 40,
  and they pay through Standards' economy so the money loop closes.

So a ZARP player sees: *Main — Find Patient Zero (Act II: The Hospital)*,
then a handful of side quests they tripped over, then the bounty board.

## Smaller ideas worth keeping

- **Objective progress is *narrated*, not tallied.** "3 of 5 zombies" is a
  counter; "Two left. They know you're here." is a story. Objectives can carry
  `progress_text` with `{done}`/`{left}`/`{target}` — and a default exists so
  nobody has to write one.
- **Quests can be *found*.** A `hidden` quest with a giver is discovered by
  walking past it. Exploration rewards.
- **Party quests.** LegendQuest parties (via Standards' Groups API) share
  progress on `shared: true` quests, so a crew clears the mall together.
  Kill credit uses the same radius rule LegendQuest uses for XP.
- **Quest items.** A `chronicler:quest_item` with a data component naming the
  quest, dropped as loot by a genus or found in a chest, that the `collect`
  objective wants and that vanishes on completion. No custom item *types* —
  one item id, data-driven look via `minecraft:item_model`/`custom_name`, so a
  vanilla client renders it.
- **A rewards vocabulary that drives the siblings.** `money` → Standards'
  economy; `class_xp`/`karma`/`skill_points` → LegendQuest; `fly 30s` →
  Standards' explicit `on`/`off` switches (the reason those exist);
  `reputation`, `flag`, `unlock_chapter`, `spawn`, `command` → ours.
- **Genre re-skin for free.** Every noun is in `messages.yml`: a ZARP server
  says *Missions* and *Acts*; a fantasy server says *Quests* and *Chapters*;
  a sci-fi server says *Contracts* and *Episodes*. Same mechanism LegendQuest
  uses for Race/Archetype/Species.
- **Storyteller mode is the same system wearing a hat.** LegendQuest's banked
  StoryTeller idea (`docs/STORYTELLER.md` there) — a live GM placing scenes and
  firing cues — is a quest with `on_enter` effects and an op typing
  `/quest stage next @a`. Build the data model right and the GM tool is a
  command surface over it, not a second engine.

## Data model (target shape)

```
chronicler:chapter   name, description, order, requires[chapters], icon, main
chronicler:quest     name, description, chapter, order
                     giver?          { kind, at?, lot?, label }
                     availability?   { requires[quests], karma_min/max, level_min,
                                       flags{}, reputation{}, trigger?, place?, repeat? }
                     stages[]        { text, objectives[], on_enter[], on_complete[],
                                       choices[]?, deadline?, fail?: stage|quest }
                     rewards[]       on final completion
                     hidden, repeatable, shared
objective types      kill, collect, visit, place (lot | biome | structure | dimension),
                     interact, craft, advancement, command, genus_kill (ZombieMod),
                     level (LegendQuest), flag, reputation, wait
reward/effect types  item, xp, money, command, karma, class_xp, reputation, flag,
                     spawn, teleport, title, unlock, run_quest
```

Today's `Quest` record is the flat subset (objectives + rewards, no stages).
`stages` arrives as an `optionalFieldOf`, and a quest with only `objectives`
is treated as a single stage — so every file written now keeps loading.

Progress lives in the player's **journal attachment** (`copyOnDeath`, exists),
world flags and reputation in **SavedData** (they must answer for offline
players and for the world), givers in SavedData keyed by position.

## Integration seams — one pattern

Each sibling is a soft dependency behind the pattern LegendQuest settled on:
**a neutral bridge that answers sensibly when the sibling is absent, and one
guarded class per sibling that may import it**, wired through
`Chronicler.optionalIntegration` so an old sibling costs a seam, not the
server. Nothing is wired until there is a consumer on the other side.

| Sibling | What Chronicler asks it | What Chronicler offers it |
|---|---|---|
| **Standards** | `Economy` for `money` rewards; `Groups` for party-shared quests; chat `NameDecorator` for a `[Chronicler]` title; explicit `/fly on|off` via `PlayerSwitches` | nothing yet — a quest is not a seam Standards needs |
| **LegendQuest** | karma, level, class for availability; `class_xp`/`karma`/`skill_points` rewards; parties | quest completion as a **karma trigger** (their banked idea) |
| **ZombieMod** | genus ids for `genus_kill`; horde end and boss phase as **triggers**; rituals as givers | `chronicler:flag` / `chronicler:quest_stage` **spawn conditions** into its public registry, so finishing a questline changes what spawns |
| **CityWorld** | `lotAt` for `lot` objectives and ambient givers; `naturePercent` for "in the wilds" | nothing — read-only |

**Permissions** need no seam: nodes go through NeoForge's `PermissionAPI`, which
Standards and LuckPerms both handle.

**What Chronicler owns and nobody else does**: reputation, world flags, givers,
the journal. Anything a sibling already owns (karma, money, membership,
district) is *asked for*, never duplicated.

## ZARP — the first questline, as a test of the design

*Zombie Apocalypse Roleplay*: CityWorld APOCALYPSE style + ZombieMod + the
LegendQuest Wasteland pack. The story is "find Patient Zero and end it":

1. **Prologue — Wake Up.** Ambient: you spawn in the vault (CityWorld's
   APOCALYPSE spawn hub). The vault door is a giver. Learn the journal, the
   tracker, find the first survivors. *(tests: giver, stages, visit)*
2. **Act I — The Streets.** Bounty-board quests from a survivor camp: clear a
   block, fetch medicine from a pharmacy (`lot: shop, kind: pharmacy`), rescue
   someone before dark (`deadline`). *(tests: lot objectives, timers, repeatables)*
3. **Act II — The Hospital.** Dr Okafor's notes: the origin. Kill Harvesters,
   recover samples, choose whether to share them. *(tests: choices, karma,
   reputation, quest items)*
4. **Act III — The Bosses.** Each ZombieMod boss is a quest given by its
   ritual site; the flag each sets quiets a district. *(tests: triggers,
   flags → ZombieMod spawn condition, boss phases)*
5. **Finale — Patient Zero.** The ritual needs the three trophies. Ending it
   sets `apocalypse_over`, which the server owner can hook to anything.
   *(tests: everything, party-shared)*

Each act is the acceptance test for one design feature, in the order they
need building. The fantasy default (D&D pack, MODERN or CLASSIC city, no
zombies) ships a short chapter proving the mod stands alone.

## Build order

Every step ends with something Sable can see in game. The cheap end-to-end
spike comes first because that is how this family works
(`prove-value-before-long-groundwork`).

| # | Step | Proves |
|---|---|---|
| 0 | **Skeleton** — registries, YAML, commands, journal, self-test. *Done 2026-09-05: builds, boots, self-test 19/19, JSON and YAML content both load.* | content loads, vanilla surface exists |
| 1 | **The engine** — accept/abandon/track, `kill`/`collect`/`visit` measured on real events, progress on the action bar, completion fanfare, `item`/`xp`/`command`/`money` rewards granted, party pooling via Standards Groups. *Built 2026-09-06; self-test drives it end to end with FakePlayers; play-tested by Sable the same evening, both built-in quests completed.* | a quest can be played start to finish on a vanilla client |
| 2 | **The book** — `/quest journal` as a written book with clickable links; `[Track]`. *Built 2026-09-07: a marked written-book item refreshed on right-click, a virtual open with no item, new players handed one on first join.* | the vanilla UI |
| 3 | **Givers** — block givers, ambient place givers, `/quest giver set`, proximity offer, `api.Quests` for StoryTeller. *Built 2026-09-07 (overnight); no floating labels yet.* | quests live in the world |
| 4 | **Stages** — ordered beats, per-stage text and effects, `on_enter` spawn/command | quests tell a story |
| 5 | **Standards + LegendQuest seams** — `money`, `class_xp`, `karma` rewards; karma/level availability; party-shared progress | RPG consequences |
| 6 | **CityWorld + ZombieMod seams** — `lot` objectives, ambient givers, `genus_kill`, horde/boss triggers, our spawn condition into their registry | the city and the zombies are quest-aware |
| 7 | **Choices, flags, reputation, deadlines** | branching and the world pushing back |
| 8 | **ZARP questline** as a shipped datapack; fantasy prologue built in | the reason for all of it |
| 9 | **Modded-client sugar** — HUD tracker, journal screen | prettier, never required |
| 10 | **Version branches** `mc26.1` / `mc26.2`, CI matrix, store copy | the treadmill |

Steps 1–4 are the mod. Steps 5–7 are what make it *this* mod. Step 8 is what
it is for.

## Decisions (Sable, 2026-09-06)

The four open questions were put to Sable and answered. These are settled;
the design above is read through them.

### 1. Givers: all three placements, and a *modular* giver seam

Lot/place-relative, op-placed and data-declared coordinates are all wanted,
built in that order of value (place-relative is what makes a shipped ZARP pack
portable across seeds; op-placed is what a server owner authoring their own
story reaches for; coordinates are for fixed sets like the vault).

**And givers are a registry, like LegendQuest skills.** `GiverSpec` is a
codec-dispatched record with a public `GiverTypes.register`, exactly the shape
objectives and rewards already have, so other mods add giver kinds *and
trigger kinds* Chronicler could not have guessed at. Built-in kinds: `lectern`,
`stand` (named armour stand + head + nameplate), `block`, `sign`, `place`
(ambient), `event`. The first external kind is already known:

**StoryTeller NPCs are quest givers.** `../LegendQuest-StoryTeller` exists
(milestone 1 and possession shipped 2026-09-06; the NPC cast is its roadmap
§3, with `quest-giver` listed as a preset behaviour). An NPC there is a full
LegendQuest character sheet with a nameplate, and StoryTeller has a voice
(`/st say`). So Chronicler offers a small API — `Quests.offer(player, quest)`,
`Quests.accept`, `Quests.progress`, and `GiverTypes.register` — and
StoryTeller registers an `npc` giver kind that spawns its cast member and
speaks the offer through its own chat. Chronicler never imports StoryTeller;
StoryTeller imports Chronicler's `api` package, guarded, the same way every
sibling seam works. **Division to confirm with the StoryTeller session:**
Chronicler owns the beat/stage data model and the journal; StoryTeller's
"story planner" (its roadmap §5) drives Chronicler stages live rather than
growing a second engine. Two planners with different rules would be the
FTB Teams / LQ party collision again.

### 2. Reputation is a shared concept, and Standards owns it

Sable wants StoryTeller to grant reputation as a reward too, so it cannot be
Chronicler's private state. **It becomes a Standards seam**, `api/reputation`,
in the mould of the economy: a facade other mods call, a store Standards keeps
(SavedData, so it answers for offline players), named standings created on
first use (`survivors`, `raiders`, `the_hospital`) with no registry to
declare. `docs/REPUTATION-API.md` here was the proposal; **the Standards session built
it the same day** (`com.sablednah.standards.api.reputation.Reputation`, ships
in Standards 1.5.0). Their changes, all accepted: standing names are
normalised in the facade (lower-cased, trimmed); bands are config and display
only, never quest logic -- a threshold is a number and `ReputationEvent.crossed(n)`
is the hook; one provider owns every standing; the clamp lives in the provider;
zero is stored as absence. `adjust` returns where the value landed, so the
reward text prints the real movement, not the number in the file. Chronicler's
`reputation` reward and objective are wired through `neoforge/Rep` and
`compat/StandardsReputation`, and answer "nothing keeps reputation here"
without Standards.

### 3. Quest items: both, tagged invisibly

A single `chronicler:quest_item` for story items (data-driven look and name,
cannot be crafted, vanishes on completion) **and** `collect` accepting a
renamed vanilla item for cheap bounties (`match: name`).

**Identity lives in `CUSTOM_DATA`, never in the name.** Factions learned this
with captured standards: a name is something anybody can type into an anvil,
so "rename a banner and claim the flag" is a day-one exploit. Custom data is
not player-writable. Chronicler writes `{"chronicler:quest": "<quest id>",
"chronicler:item": "<item key>"}` into the stack's `CUSTOM_DATA`; the `collect`
objective checks the marker first and the name only when the quest says
`match: name`. Loot tables can emit the marked stack with vanilla's
`set_custom_data` function, so a genus or a chest can drop one with no code.
Same `DAMAGE_RESISTANT` fire-proofing as the trophy: losing the vial to lava
is a shrug, not danger.

### 4. Party scope: per-chapter default, quest override, and the whole thing
multiplayer-aware

Chapters carry `scope: solo | party`; quests override. Main chapters will
default to `party`, bounties and personal side quests to `solo`.

A **party** quest is one quest for the whole party, not one copy each:

- every member has it active and sees the same progress;
- progress is **pooled** — any member's kill counts — and the target is
  **scaled by party size at acceptance** (`scale: true` by default): "kill 5
  zombies" becomes 15 for three people, which is Sable's "x per player";
- it completes for everyone at once and **every member is rewarded**;
- a member who joins mid-quest joins at current progress with the target
  rescaled; one who leaves keeps a solo copy at their share, so nobody loses
  a quest by leaving a party.

Membership comes through Standards' Groups seam (LegendQuest parties are the
intended provider). Without Standards, every quest is effectively solo and
`scope: party` quests say so once when accepted. A quest marked `scope: solo`
in a party chapter stays personal — the escape hatch for "your own choice"
beats.

**What this changes in the build order:** the Groups seam moves up from step 5
into step 1's design (the journal has to know about pooled progress from the
start, or it gets rewritten), and a `GiverSpec` registry joins step 3.
