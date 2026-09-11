![Chronicler](https://raw.githubusercontent.com/Sablednah/Chronicler/main/docs/wordmark-850.png)

# Chronicler — quests that are given by someone, somewhere

**Chapters, quests and storylines from datapack files, played entirely on a vanilla client.**
A quest is offered by a person standing in the world, or a block, or a place you walk into; it
is told in beats, with choices that branch and consequences the world remembers; and it ends,
sometimes, in one of several endings. Chronicler is the quest mod for people who found FTB
Quests a checklist and wanted a story.

**Your players do not need to install anything.** Offers arrive on the action bar and in chat
with clickable buttons, progress is tracked on the action bar, the journal is a written book
handed to every new player, choices are buttons, and completions are a title card. Everything a
modded client might add later is sugar; nothing is required.

---

## What a quest can be

- **Given by someone.** An NPC with a name, a skin and a greeting (through
  [Cast](https://github.com/Sablednah/Cast)), a block you walk up to, or a *kind of place*: a biome,
  a structure, a dimension, a CityWorld lot. A hidden quest with a giver is *found* by walking
  past it. A floating mark over every giver shows *your* state: `!` on offer, `?` while you are on
  it, a tick once it is done.
- **Told in beats.** Stages with their own text, objectives, effects on entry and completion,
  deadlines with a fall-back, and decision beats whose choices set flags, pay karma, spawn things,
  send an NPC away, or open another quest.
- **Measured honestly.** Kill this, carry that, reach there, *hand it over* (a bare "collect" is
  satisfied in your pack; a delivery means putting it in someone's hands), build a multiblock and
  light it, wait for something, wait for the world to change.
- **Remembered.** World flags any quest can set and any quest can read; the player's own flags;
  reputation with a standing; endings reached per chapter, and chapters you can play again to find
  the others, with a progress figure that only counts the quests that should count.
- **Shared.** A party quest pools progress across a party, scales targets to its size, and pays
  everyone.

![The camp near spawn: five people, a fire, and the marks over the ones with work for you](https://raw.githubusercontent.com/Sablednah/Chronicler/main/docs/screenshots/camp.png)

## The Zombie Apocalypse Roleplay questline

Chronicler ships with **ZARP**, built for CityWorld's apocalypse cities, ZombieMod and the
LegendQuest Wasteland pack, and playable without any of them. A camp of five near spawn, a fire in
the middle of it, four acts through the streets, the hospital, the Nether and the End, a finale
with three endings, a side chapter whose refusals have consequences (leave the mechanic without his
parts and come back to find a nest where the garage was), a bounty board, and a handful of new
undead for the wards and the fortresses, including a Patient in a gown and a surgical mask. It
turns itself on when ZombieMod is present. It is also the worked example: every mechanism above
is in it, in readable JSON.

![Dr Okafor, dressed, with the mark that says she has something for you](https://raw.githubusercontent.com/Sablednah/Chronicler/main/docs/screenshots/okafor-mark.png)

![The journal: a written book, regenerated every time you open it](https://raw.githubusercontent.com/Sablednah/Chronicler/main/docs/screenshots/journal.png)

## Writing your own

A quest is a JSON file in a datapack, or YAML under `config/chronicler/`. Every word a player sees
lives in `messages.yml`, so a fantasy server says *Quests* and *Chapters* while a wasteland says
*Missions* and *Acts*.

```yaml
name: Night Watch
chapter: prologue
stages:
  - text: Get a light. The dark is not empty.
    objectives: [ { type: collect, item: minecraft:torch, consume: false } ]
  - text: They found you. Hold until it is quiet.
    on_enter: [ { type: spawn, entity: minecraft:zombie, count: 2 } ]
    objectives: [ { type: kill, target: minecraft:zombie, count: 2 } ]
  - text: A stranger asks for your torch.
    choices:
      - { label: Give it, effects: [ { type: karma, delta: 5 } ], ending: kindness }
      - { label: Keep it, effects: [ { type: karma, delta: -5 } ], ending: cold }
```

Restart the server and it is there. `/quests` lists what is loaded, `/quest` alone says what you
are doing now, the journal book does the rest.

## Plays well with others

Every one of these is optional; Chronicler asks each for what it has and says so when it is absent.

- **[SableCraft Standards](https://www.curseforge.com/minecraft/mc-mods/sablecraft-standards)** —
  money rewards through its economy, party quests through its groups, reputation through its
  standings.
- **[LegendQuest ReForged](https://www.curseforge.com/minecraft/mc-mods/legendquest-reforged)** —
  karma, class experience, levels and skill points as rewards; quests gated on level, karma,
  race or class, so a Mechanic and a Paramedic are offered different work.
- **[ZombieMod ReForged](https://www.curseforge.com/minecraft/mc-mods/zombiemod-reforged)** — kill
  a genus, spawn a genus, and a `chronicler:flag` spawn condition it can use, so finishing a
  storyline changes what spawns.
- **[CityWorld ReForged](https://www.curseforge.com/minecraft/mc-mods/cityworld-reforged)** — lots
  as places: a hospital, a bunker, an oil platform.
- **[Cast](https://github.com/Sablednah/Cast)** — NPC quest givers, dressed and standing where you
  put them.

## Commands

`/quest` (and `/quests`) is for players: `list`, `info`, `accept`, `abandon`, `track`, `choose`,
`journal`, `replay`, and alone, what you are doing now. `/chronicler` administers: `status` (what
is loaded, what is detected, which build this is), `reload` (messages), flags, quest items, a
journal for someone, a reset.

## Requirements

| Minecraft | NeoForge | Java |
|---|---|---|
| 1.21.11 | 21.11.42+ | 21 |
| 26.1.2 | 26.1.2.95+ | 25 |
| 26.2 | 26.2.0.72+ | 25 |

There is **a jar per Minecraft version**, named for the one it was built against — take the one
that matches your server.

**Install on the server. That is all.** No dependencies, and nothing your players have to do.

## Credits and licence

Chronicler is licensed under **MIT**, by **Sablednah**. Source, docs and the full authoring
reference: **[github.com/Sablednah/Chronicler](https://github.com/Sablednah/Chronicler)**.
