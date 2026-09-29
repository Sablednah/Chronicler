# Changelog

## 1.2.0 — 2026-09-29

- Fix: a giver block inside a claim (Standards' Factions: "Camp Okafor") could be dead -- Factions refuses a non-member's block click at the same priority, so registration order decided the winner. Chronicler now answers giver and turn-in clicks first (HIGH), and cancels only those, so the claim still guards everything else and nobody is told off for talking to a campfire.
- `spawn` takes `around` (`player`, the default; `spawn`, world spawn in the overworld; `giver`, this quest's giver, else the player) and `min_radius` (default 2, as before): "five dead at the fence" lands outside the fence, not in the camp with you.
- API: `Quests.registerAchievementSource(anchorClass, "/datapacks/<pack>")` -- another mod's built-in pack gets chapter and ending achievements like ZARP's and the prologue's.

## 1.1.0 — 2026-09-21

- A person placed "near" a lot or structure now stands genuinely inside it -- CityWorld's own API has no bounding box or floor to ask for (a lot is chunk-granular metadata, not geometry), so this scans down from the roof for the first sheltered, walkable, floored spot instead. Mum, in her own house rather than the path outside. Fix: an unbounded scan found a spot 45 blocks under the house (a natural cave answers "sheltered, walkable, floored" just as well as a room does) -- bounded to near sea level by default, or a mini quest's own `y_min`/`y_max` on an `npc` slot.
- Fix: a `spawn` reward's placement had no opinion on elevation at all -- a beat completing while the player happened to be on or near a roof spawned the follow-up mob up there too (Phil, right after "Go round Mum's"). Now prefers a point near sea level, same band as the interior search.
- An escort's charge, arrived, now walks the rest of the way to a sheltered spot near the destination and stands there, rather than stopping wherever the arrival radius happened to catch them.
- A boss bar shows an escort's hits taken while it is in danger, so missing the action-bar line does not mean losing track of how close it is to failing.
- Waypoints/JourneyMap: every person a mini quest has placed is marked for as long as it is active, not only whichever one the current beat's objective happens to name (Ed, met once at the start and never an objective again, was missing entirely).
- Fix: `own_kill`'s respawn safety net was an in-memory record only -- a server restart between a mob's spawn and an unowned death left nothing to respawn from, stranding the kill quest anyway (reported: Phil, twice). It now falls back to the beat's own `on_enter` spawn (matched by tag) when nothing live is remembered.
- Fix: an `own_kill` target that mutates into something else (ZombieMod: a Walker turning Runner) never fires a death event at all -- it is a silent `discard()`, not a kill, and the replacement carries none of the original's tags. Reported a third time on Phil, this time with no death and no respawn either. Now caught on `EntityLeaveLevelEvent` (a genuine discard, never a chunk unload or an already-credited kill).
- **JourneyMap markers.** A quest's "go here" (`visit`, `escort`), a giver waiting to be found, and an escort's charge (live, moving as they do) are sent to the client once a second and drawn on JourneyMap if it is installed -- optional both ways, discovered through JourneyMap's own plugin annotation, never a real mods.toml dependency.
- `settle` on `visit` and `escort`: the condition must hold continuously for this many seconds before it credits, rather than the instant a line is crossed -- reported in play as an escort ending, and an ambush appearing, while still on the path outside.
- Mini quests: an `npc` slot placed `near` a found lot or structure now steps 4-10 blocks away before finding a dry surface, rather than using that place's own coordinate directly -- a building's coordinate is its roof.
- **Achievements.** A vanilla advancement per chapter and per ending, generated from config YAML and the built-in packs: `chronicler:root` (granted with the first journal), one per chapter (granted on completing every counting quest in it), one per ending (granted on reaching it, hidden, framed as a challenge). Chains off `requires`, or the previous chapter by `order` when a chapter sets none. A new `advancement` reward type grants any advancement, Chronicler's own or anyone else's, by id. `content.achievements.enabled` (default on).
- **Mini quests.** A quest file with a `mini:` block is a template: any value may be a `{slot}` hole, filled when it starts -- a pick from a pool, a number, the nearest structure, CityWorld lot or block, a spot nearby, another quest's giver, a position handed over, or a person Cast places. The values are saved on the journal entry, so the journal, the panel, `/quest` and parties show and measure the filled-in quest. Templates are checked at load with stand-in values. One copy per template per player.
- **Found in the wild**: a template's `spawn` offers it while a player is in a place (a structure, biome, dimension or lot), on a chance and a cap, through a person placed for it or a block found for it; ignored, the offer lapses and the person leaves (`minis.wild`).
- **`escort`**: bring a person somewhere with you. They follow whoever leads them, on foot along the way they walk (Cast 1.1.0), wait when left behind (`minis.escortPickup`), and it is done when they stand there with you.
- `deliver` takes `npc` (a person by id) or `at` (a block -- which then will not be used any other way until it gets what it wants); `npc_say` and `npc_remove` take `npc`.
- Effects: `block` (set a block, or properties on one: `open: "true"` opens a door) and `mini` (start or offer the next errand, anchored where this one ended).
- `/quest mini` (list) and `/quest mini <template> [players] [offer]`, admin. API: `Quests.minis`, `isMini`, `startMini`, `offerMini`.
- Content: six errands in the prologue (the archaeologist and the village chain, the stuck door, pests, the lost satchel, a town-hall escort in a CityWorld city) and five in ZARP (bring a survivor in to the camp, medicine, the nest, the rusted door, the warehouse run), all in an Errands chapter, hidden until found.
- **Dangerous escorts**: `hits: N` on an escort sets nearby monsters on the charge (Cast's expose), counts every blow -- still no damage -- and fails the beat at N. Unsaid, escorts are as safe as before.
- **CityWorld schematics**: `schematic: chayats-bank` in a `place` (wild spawns, `place` givers and objectives) and in a `lot` slot, matched by name whatever the case, spaces or dashes.
- A `kill` with a `tag` and no `target` counts only the tagged mob; with neither, anything, as before.
- Content: a rescue from a pillager outpost and a run to Chayat's Bank (prologue); a bank job and **The Plan** (ZARP) -- take a car, go round Mum's, kill Phil, grab Liz, get to the Winchester, have a nice cold pint and wait for it all to blow over.
- Requires Cast 1.1.0 (follow, expose, hits).

## 1.0.1 — 2026-09-15

- **The journal panel.** A player whose client has Chronicler gets the journal as a panel instead of the book: chapters down the left with their quests under them, a quest map for the selected chapter (a column per step of prerequisites, prerequisites from other chapters as stand-ins, green lines along the path already walked; drag to look around), and a page per quest with its buttons. Open it with the **backtick** key (`` ` ``, rebindable under Controls > Chronicler), the journal item or `/quest journal`. The text is the server's own (`panel.*` in `messages.yml`), every button runs the `/quest` command a chat button would, and it refreshes while open. Vanilla clients get the book as before; `journal.panel = false` gives everyone the book.
- `visibility` on a quest: `always` (listed from the start, locked or not), `unlocked` (listed once every quest it `requires` is done) or `found` (listed once started). `hidden: true` still means `found`. The panel, the book and `/quests` all follow it.
- `icon` on a quest: an item drawn for it in the panel (a chapter's `icon` otherwise).
- `npc_remove` takes `leave` (a block placed where the NPC last stood) and `giver_for` (a quest that block then gives). ZARP: when the garage falls, Wrench leaves his bench, and it offers **Wrench's Notes** -- Grease Monkey's job for a camp that let him die, ending with zombie Wrench. Grease Monkey locks once the garage has fallen, so it is no longer a quest nobody can give.
- Fix: ZARP's campfire sat on the world spawn block, so Standards' `/spawn` (which lands on the exact block) put you in the fire. The camp now stands three blocks in front of spawn, everyone shifted with it, and the spawn block is clear. New worlds only: a world that already placed its camp keeps it -- `/setspawn` beside the fire.
- Fix: a quest whose NPC giver borrows another quest's NPC (`of`) said "From: -" in `/quest info`, the book, the panel and the offer line; it now names the NPC it borrows (Never Lived: from Dr Amara Okafor).
- Fix: a fake player (another mod's automation) reaching a client send no longer throws -- NeoForge's `FakePlayer` has a connection with no channel.

## 1.0.0 — 2026-09-13

The first release. Requires SableCraft Standards 1.5.0+ and Cast 1.0.0+; LegendQuest, ZombieMod and CityWorld stay optional. Everything below is in it.

- **ZARP**: the Zombie Apocalypse Roleplay questline, built in as a datapack
  (`content.zarp`, auto with ZombieMod): a camp of five near spawn, four acts
  through the city, the hospital, the Nether and the End, a finale with three
  endings, a side chapter whose refusals have consequences, a bounty board,
  nine quest items and five ZombieMod genera (Ashwalker, Fortress Warden, the
  Cinder, Voidling, the Hollow Knight). `docs/ZARP.md`.
- Objectives: `ritual` (a multiblock and an item, click to perform), `wait`,
  `kill` with target lists, spawn tags and quest-item drops, `collect` by tag
  or quest item, `place` with `any` alternatives.
- Effects: `spawn` with `name` / `tag` / `health` and a vanilla stand-in for a
  genus, `npc_say`, `npc_remove`, `ending`.
- Quest items: the `chronicler:item` registry, marked in `custom_data`, an
  `item` reward by `quest_item`, the `chronicler:quest_item` loot function,
  `/chronicler item give|list`.
- Endings and replay: `ending` on stages and choices, `end: true` on a stage,
  `replayable` chapters, `/quest replay <chapter>`; a progress percentage in
  `/quests` and the journal, moved only by quests that `counts`.
- Availability by LegendQuest `race` and `class`.
- Build stamp: commit, branch and time in the jar manifest, in `/chronicler/build.properties`, in the startup log line and in `/chronicler status`.
- Spawned mobs that die to something other than the player still count (`own_kill: true` respawns instead); `/quest` alone says what you are doing now; long journal pages split in two instead of clipping. ZARP: The Signal ends with a delivery to Sarge, every fight spawns spares, the First Bed must be your own kill.
- NPC givers obey gravity through Cast; `defy_gravity: true` on the giver keeps one in place.
- Genus spawns go through a seam and hand the mob back, so names, tags and equipment land on them; ZombieMod dresses first, `equipment` fills the gaps (`override: true` to replace). Quest items are not consumable unless `usable: true`.
- `deliver` objective: hand items to a giver by clicking (or standing near); `spawn` takes `equipment`. ZARP's returns are deliveries and its daytime zombies wear caps.
- Giver blocks are protected from breaking and explosions (`givers.protect`); admins sneak to break.
- Refusals are story first (`locked` on the quest, spoken by its NPC), mechanics in grey brackets.
- Position givers `near_spawn` with a placed `block` and `decor`: ZARP's campfire, which gives Wake Up.
- The sample prologue is a built-in pack (`content.prologue`), off when ZARP is on.
- NPC givers can be dressed (`equipment` on the giver, via Cast); the ZARP camp is.
- NPC givers `near_spawn` on any seed, one NPC giving several quests (`of`),
  per-player marks that show the player's own state.
- `mc26.2` branch: NeoForge 26.2.0.72, Java 25.

- Skeleton: `chronicler:chapter` and `chronicler:quest` datapack registries,
  YAML front door under `config/chronicler/`, `/quest list|info|log`,
  `/chronicler reload|status`, per-player journal attachment, `messages.yml`
  with merge-on-start, headless self-test.
- The engine: `/quest accept|abandon|track`, `kill` / `collect` / `visit`
  measured on real events and a polling tick, progress on the action bar,
  completion with a title card, `item` / `xp` / `command` / `money` rewards,
  "new quest available" with clickable buttons, `/chronicler reset`.
- Party quests: `scope` on chapters and quests, pooled progress, targets
  scaled by party size, everyone rewarded; membership through Standards'
  Groups seam, money through its economy.
- The journal: a written book regenerated from the player's log on every
  open -- contents page, a page per active quest with progress and Track /
  Abandon links, an On Offer page with Accept links, a Done page. Right-click
  the item or `/quest journal`. New players get one on first join
  (`journal.giveToNewPlayers`).
- Givers: `position` givers (a block; offer nearby, right-click accepts),
  `place` givers (biome / structure / dimension / CityWorld lot -- ambient,
  no coordinates), op-placed givers via `/quest giver set` in SavedData. A
  `place` objective on the same condition. A public `api.Quests` facade and
  registries for giver, objective and reward types, for StoryTeller and
  friends. A hidden demo quest, Hot Foot, offered on entering the Nether.
- Stages: ordered beats with narration, per-beat objectives and
  `on_enter` / `on_complete` effects; `title`, `message` and `spawn` effects;
  `{x}` `{y}` `{z}` in commands. Night Watch, a two-beat built-in quest.
- Givers make the offer on the first click and accept on the second (or the
  button); a floating, configurable marker over every giver.
- NPC givers through Cast: an `npc` giver kind placed from quest data, the
  `chronicler:giver` role on any Cast NPC, `/quest giver set` on the NPC you
  look at, a spoken greeting. Loads and lists without Cast; places nobody.
- Bounties: `cooldown` on a repeatable quest; Cull as the built-in example.
- Choices: decision beats with clickable options, effects, `next` / `end`,
  `start` another quest; `/quest choose`. Deadlines: a timed beat with a
  countdown, `on_fail`, and a fall-back stage or abandonment. Night Watch
  ends on a choice.
- Conditions: `availability` on a quest (karma / level through LegendQuest,
  world and player flags, reputation), with the unmet lines told to the
  player. World flags in SavedData, player flags in the journal, `flag`
  reward and objective, `/chronicler flag`.
- LegendQuest seam: `karma`, `class_xp`, `levels`, `skill_points` rewards
  through its own API; karma and level availability.
- ZombieMod seam, the other direction: a `chronicler:flag` spawn condition
  registered into ZombieMod, so a questline can quiet a district.
- Reputation: `reputation` reward and objective through Standards' new
  `api/reputation` (ships in Standards 1.5.0); a clean no-op on older builds.
