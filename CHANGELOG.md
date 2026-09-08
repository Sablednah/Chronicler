# Changelog

## Unreleased — 0.1.0

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
