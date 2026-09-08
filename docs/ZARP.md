# ZARP -- the Zombie Apocalypse Roleplay questline

Ships in the jar as a datapack. On whenever ZombieMod is installed
(`content.zarp = auto`), or forced with `on` / `off`; applies on restart. Built
for CityWorld's APOCALYPSE cities, ZombieMod and LegendQuest's Wasteland pack,
and plays without any of them: every CityWorld lot has a vanilla stand-in,
every genus a vanilla one, and the race and class quests simply do not offer.

**Not yet played by a human** (built overnight 2026-09-08, self-tested
161/161 with the siblings and 147/147 without). Expect numbers to be wrong.

## The camp

Five people stand near the world spawn, dropped onto the surface the first
time the server starts with the pack (`near_spawn` offsets, remembered in the
giver store, so they stay put on restart):

| Who | Offset | Gives |
|---|---|---|
| **Dr Amara Okafor** | +6, 0 | The Camp, Before Dark, House Calls, Living Fire, Never Lived, Patient Zero, Triage |
| **Sarge Kowalski** | -3, -6 | The Signal, Hold the Line (party), The Armoury, The Rig, Deep Cold |
| **Wrench** | 0, +8 | Fence Power, Grease Monkey |
| **Kit** | +3, -6 | Blood Ties (Immune only) -- and she is the finale's price |
| **Mags** | -6, 0 | The Board: Cull, Scrap Run, Medicine Run, Night Shift (repeatable) |

The camp is not nailed down: a Storyteller can walk any of the five
anywhere (`/st cast behave`, or by possessing them), and releasing re-anchors
the body wherever it ended up. No quest step depends on where a person
stands -- the marks and offers follow the NPC -- so a GM moving Sarge across
town moves Sarge's quests with him, and nothing breaks. Only the first
placement uses the spawn offsets.

One person gives several quests: their mark shows whichever matters to you
now (`?` on one, `!` on offer, tick when all done), and a right-click offers
the first you could take. Wake Up is given by the campfire in the middle of the camp (placed once with a
barrel, a crafting table, a hay bale and two lanterns), offered on approach;
it is also in the journal's On Offer page from the first join.

## The main line

1. **Prologue: Wake Up** -- Wake Up (a wait, a torch, three zombies), The Camp
   (eight logs, five dead at the fence).
2. **Act I: The Streets** -- The Signal (a RadioTower lot or a pillager
   outpost, runners, copper + redstone; sets `zarp_signal_up`), Before Dark
   (a 10-minute deadline; fail and you meet what was left of Priya), Hold the
   Line (party; walkers, runners, then The Brute).
3. **Act II: The Hospital** -- House Calls: a Hospital lot (or a village),
   Harvesters dropping Tissue Samples, The First Bed dropping **the Origin
   Sample**, then the choice: give Okafor everything, or sell one to the
   Enforcers.
4. **Act III: Below** -- Living Fire: the Nether (sets `zarp_below_begun`, which
   lets Ashwalkers spawn there), a fortress (spawns the Fortress Warden;
   Ember Heart), four blaze rods, then the **Crucible rite**: a blast furnace
   with soul sand on all four sides, Ember Heart in hand, right-click the
   furnace. The Cinder comes out; it drops the Living Flame.
5. **Act IV: Beyond** -- Never Lived: the End (sets `zarp_beyond_begun`, lets
   Voidlings spawn), four end rods and dragon's breath, an End city (spawns
   the Hollow Knight; Void Shard). Home, the horde waits at the fence for the
   Origin Sample. **Give it back** -> ending *truce* (the sample is gone, and
   with it the finale). **Keep it** -> the finale opens.
6. **Finale: Patient Zero** -- bring the flame and the shard, then the rite:
   soul sand in a cross with a wither skeleton skull on the centre, the Origin
   Sample in hand, right-click the skull. Patient Zero (ZombieMod's own; a
   Wither without it). Kill it. Then the price: **make the cure** (Kit is
   removed from the camp; ending *cure*) or **not her** (ending *mercy*). The
   finale chapter is replayable: `/quest replay zarp:finale`.

Three endings across the last two chapters: truce, cure, mercy.

## Survivors (side chapter, does not count toward progress)

- **Fence Power** -- Wrench asks for a redstone block and eight iron within
  fifteen minutes. Say *not my problem* (or run out of time) and, five minutes
  later, the garage is a nest: a Broodmother and six others, and Wrench is
  gone for good. Two endings: *fence_up*, *fence_down*.
- **Grease Monkey** -- Mechanic race, or Builder / Miner / Labourer class.
- **Triage** -- Paramedic race, or Doc / Combat Medic / Chemist class.
- **Blood Ties** -- Immune race only. Kit's scarf, the ward, three zombies.
- **The Armoury** -- level 3 and Ex-Military / Prepper race or Enforcer /
  Sharpshooter / Veteran / Mercenary class: a Bunker (or Vault, or ancient
  city), Vault Dwellers, dog tags.
- **The Rig** -- level 6: an OilPlatform lot (or a shipwreck / monument), The
  Diver, a Fuel Cell.
- **Deep Cold** -- level 8: an ancient city, and the Warden.

## Testing it quickly

```
/quest accept zarp:wake_up
/chronicler item give <you> zarp:ember_heart       # skip a hunt
/chronicler item list
/chronicler flag set zarp_below_begun               # let Ashwalkers spawn
/quest replay zarp:finale                          # after an ending
```

To jump a requirement chain, `/chronicler reset <you>` clears the journal;
there is no "complete by hand" command yet, so play the acts in order or edit
`requires` in a copy of the pack under `config/chronicler/quests/`.

## Genera the pack adds

| Genus | Where | What |
|---|---|---|
| **Patient** | Hospital lots, and the hospital quests | A zombie in a gown and a surgical mask (the surgical-mask head ZombieMod once tried on the Nightstalker and rightly took off); weakness on touch. |
| **Ashwalker** | The Nether once Act III begins | A burning husk that keeps walking. |
| **Fortress Warden** | Spawned by Living Fire at a fortress | Mini-boss; drops the Ember Heart. |
| **The Cinder** | Called by the Crucible rite | An infected blaze, boss bar; drops the Living Flame. |
| **Voidling** | The End once Act IV begins | Never alive, so cannot be dead. |
| **The Hollow Knight** | Spawned by Never Lived at an End city | Mini-boss; drops the Void Shard. |

Every spawn in the pack names a genus and a vanilla stand-in, and the
stand-ins wear leather caps so a daytime fight is not won by the sun.

## What the pack exercises

Rituals, waits, kill drops, tagged spawns with vanilla stand-ins, quest items,
`place` alternatives, deadlines with fall-back beats, decision beats, endings
and replay, world and player flags (ZombieMod genera gate on
`chronicler:flag`), reputation on the `camp` standing, karma, class XP, race
and class gates, shared NPCs, `npc_say` / `npc_remove`, party scope, bounties
with cooldowns. Which is to say: everything.
