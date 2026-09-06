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
- Reputation: `reputation` reward and objective through Standards' new
  `api/reputation` (ships in Standards 1.5.0); a clean no-op on older builds.
