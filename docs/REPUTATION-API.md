# Reputation — a proposed Standards seam

**Status: BUILT by the Standards session on 2026-09-06**, in
`SableCraft-Standards` (`api/reputation`, docs in its `REPUTATION-API.md`,
ships in 1.5.0). This file is the original proposal, kept for the reasoning;
the differences from what shipped are listed in `DESIGN.md` under decision 2. Sable's call: reputation is a concept Chronicler *and*
StoryTeller both want to grant, so it belongs in Standards like the economy.

## What it is

Per-player standing with named groups the story cares about — `survivors`,
`raiders`, `the_hospital` — that is *not* membership (Groups answers that) and
*not* a single moral axis (LegendQuest karma answers that). "They think well of
you" is a third thing, and today nobody owns it.

## Shape, borrowed from the economy

- `api/reputation/Reputation` — a static facade: `get(uuid, standing)`,
  `adjust(uuid, standing, delta, reason)`, `set`, `standings()`, `top(standing,
  n)`. Every call degrades to a clean refusal with no provider.
- A `ReputationProvider` with a priority, **one wins outright** — a standing is
  a single fact, like a balance. Standards registers its own at
  `BUILTIN_PRIORITY` so a dedicated mod can displace it.
- Standings are **created on first use**, no registry. A quest file naming
  `the_hospital` just works; `/rep list` shows what exists.
- Store in **SavedData** (offline answers: leaderboards, admin edits), keyed by
  UUID then standing name. Clamp to a configurable range (default −100..100).
- A `ReputationEvent` after a change lands, so a consumer can react ("the
  survivors now trust you enough to open the armoury") without polling.
- Optional: named **bands** per standing in config (`hostile` / `wary` /
  `neutral` / `friendly` / `trusted`) so messages can say a word, not a number.
  Bands are display; the number is the fact.

## Consumers already waiting

| Mod | Wants |
|---|---|
| Chronicler | `reputation` reward and availability condition; quest text that says the band |
| StoryTeller | `/st reward <player> rep <standing> <n>` alongside xp/levels/sp/karma/money |
| LegendQuest (maybe) | a chat decorator epithet from a standing, like the karma one |

## Commands (Standards' call)

`/rep [player]`, `/rep top <standing>`, `/rep set|add <player> <standing> <n>`
under `standards.rep.admin`. Every command lives at its plain name.
