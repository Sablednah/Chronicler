# Changelog

## Unreleased — 0.1.0

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
