# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

**Chronicler** — a data-driven quest mod for NeoForge: chapters, quests, stages,
givers in the world, a journal. It is the family's answer to FTB Quests, built
for tight compatibility with **LegendQuest**, **ZombieMod**, **CityWorld** and
**SableCraft Standards** — requiring Standards and Cast, nothing else. First use: the *Zombie
Apocalypse Roleplay* (ZARP) questline. `docs/DESIGN.md` is the thinking, the
data model and the build order — **read it before adding a feature.**

| | |
|---|---|
| Minecraft | 1.21.11 (`main`), 26.1.2 (`mc26.1`), 26.2 (`mc26.2`), 26.3 (`mc26.3`, NeoForge beta) |
| Loader | NeoForge 21.11.42 |
| Java | 21 (25 on 26.x) |
| Build | Gradle 9.2.1 + ModDevGradle 2.0.141 |
| Licence | MIT |
| Mod id | `chronicler`, package `com.sablednah.chronicler` |

This is the **seventh** mod in the series. `../LegendQuest-ReForged` is the
closest architectural relative (datapack registries fed from YAML, Lang +
`messages.yml`, soft Standards seams, optional client), `../ZombieMod/ZombieMod`
has the codec-dispatch data pattern and the networking lessons,
`../SableCraft-Standards` has the seams we consume and the two-player test rig,
`../CityWorld-ReForged/PORTING.md` is the richest 1.21.11 API reference. **Read
their `CLAUDE.md` before inventing anything** — every trap below was paid for
next door.

## Build & run

**There is no system Java.** Set this every time, *before* the gradle command:

```bash
export JAVA_HOME=/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew compileJava             # fast inner loop
./gradlew build                   # -> build/libs/chronicler-<ver>+mc<mc>.jar
./gradlew runServer               # headless dedicated server on port 25573
./gradlew runServer -Pselftest    # the same, running neoforge/SelfTest on ServerStartedEvent
./deploy.sh                       # build + copy the EXACT versioned jar into every CurseForge instance on this
                                  # line that already has the mod; refuses a running one (run on each branch)
.\TestClient.cmd                  # (Windows) TestBuddy dev client, auto-joins the dev server
```

- **Never report success from a command that prints it unconditionally.**
  `./gradlew build -q 2>&1 | grep -E "error:|BUILD FAIL"` and read what it
  prints; an `echo OK` after a build announces success over a real failure.
- **Boot checks print the ERROR lines, never a count.** `grep -nE "ERROR|FATAL|
  RegistryDataLoader"` on the log. A `RegistryDataLoader` error means the
  dedicated server still says "Done" while the client refuses to open the
  world — a bad quest file is a world-killer, and a `grep -c` once shipped one.
- `-D` on the `gradlew` line sets a property on **Gradle's** JVM, not the
  forked server; `-Pselftest` exists because it is translated in `build.gradle`.
- Versions and metadata live in `gradle.properties` and expand into
  `src/main/templates/META-INF/neoforge.mods.toml`. **Edit the template, never
  a generated `mods.toml`.** `minecraft_version` is what we build against;
  `minecraft_version_range` / `neo_version_range` are what the jar runs on.
- `run/` is gitignored. A fresh checkout needs `run/eula.txt` and a
  `run/server.properties` with `server-port=25573`, `enable-rcon=true`,
  `rcon.port=25583`, `rcon.password=chrdev`, `online-mode=false`.
- **Compiling with the dev server running takes minutes; stopped, seconds.**
  Stop it before reaching for `wsl --shutdown`. If Gradle genuinely hangs on
  `:compileJava` with no CPU, that is the known `/mnt/d` degradation.
- **Config hot-reload does not work on `/mnt/d`** (inotify does not cross
  drvfs). Restart the dev server after any config change. It works on a real
  server; do not "fix" it.

### Dev-server ports — one pair per project

Five sibling mods were once all on 25565/25575; whichever started second lost,
and RCON's only symptom is "auth failed". Chronicler owns:

| Project | game | RCON |
|---|---|---|
| Standards | 25569 (26.1: 25571, 26.2: 25572) | 25575 |
| LegendQuest | 25566 | 25576 |
| ZombieMod | 25567 | 25577 |
| CityWorld | 25568 (default 25565 in places) | 25578 |
| MobHealth | 25569 | 25579 |
| StoryTeller | 25570 | 25580 |
| **Chronicler** | **25573** | **25583** |

Chronicler was on 25570/25580 for one day; the StoryTeller session picked the
identical "next free pair" the same afternoon and its server was already
holding it, so Chronicler moved. **The table above is what each repo claims,
not a registry** -- two sessions reading the same table and adding one will
collide again. Raise a shared port file with Sable rather than editing a
sibling's `CLAUDE.md`.

Before assuming a port is yours, `ss -ltnp | grep 2557`. **Never kill a JVM
without checking whose it is** — match on this repo's classes directory, not on
`java` or `fml.modFolders` (that pattern matches every sibling's dev server):

```bash
ps -eo pid,etime,args | grep "[f]ml.modFolders" | grep "Chronicler/build/classes"
```

`pkill -f "gradlew runServer"` kills the shell you type it in. Don't.

### The self-test boot, as it is actually run

```bash
cp ../SableCraft-Standards/build/libs/standards-1.8.0+mc1.21.11.jar \
   ../Cast/build/libs/cast-1.0.0+mc1.21.11.jar \      # these two are REQUIRED: without them NeoForge refuses to start
   ../LegendQuest-ReForged/build/libs/legendquest-2.5.0+mc1.21.11.jar \
   ../CityWorld-ReForged/build/libs/cityworld-5.7.1+mc1.21.11.jar \
   ../ZombieMod/ZombieMod/build/libs/zombiemod-3.4.1+mc1.21.11.jar run/mods/   # the seams only link with a consumer present
./gradlew runServer -Pselftest > server.log 2>&1 & # then wait for "Chronicler SelfTest:"
grep -nE "SelfTest:|FAILED:" server.log; grep -nE "ERROR|FATAL" server.log
kill <the JVM whose cmdline has Chronicler/build/classes>; rm run/mods/*.jar
```

- **Run it in the foreground of a single command, not as a harness background
  task.** With five sibling dev servers and their Gradle daemons on the box,
  the background-task memory monitor killed two boots mid world-load and
  reported "BUILD FAILED" with no cause. The server heap is capped at 2G in
  `build.gradle` for the same reason.
- **Boot with every sibling AND with only the required two** (Standards and Cast; without
  them NeoForge refuses to start). The compat classes only link when the mod is present, and
  both counts matter (192 and 166 at 1.0.0; 280 and 252 with mini quests).
- **`libs/` (gitignored) holds a sibling jar that has no `build/libs` for this
  line** -- ZombieMod's 26.1.2 jar is copied there from the CurseForge `26.1.2`
  instance (its 1.21.11 jar does build in `../ZombieMod/ZombieMod/build/libs`
  now; `libs/` is the fallback). `build.gradle` compiles against the NEWEST jar of each
  sibling by mtime, never an alphabetical fileTree (which quietly picked the
  oldest and "lost" a method added last week).
- **A FakePlayer is a real ServerPlayer with a journal**, and the self-test
  drives the real engine with three of them: solo, and a two-player party.
  What it cannot see: anything drawn, and anything a second real client does.

### Testing

`SelfTest` runs headless and is the only route to "does the command work"
without a client (Gradle cannot pipe stdin to the server console). Rules it
keeps: **parse AND execute**; **test both directions** (a deliberately bad
command must be refused); **call the real code**, never a re-derivation.

What it cannot see are the two bug families this family keeps producing:

1. **Code that has never met real input.** The self-test proves "computes the
   right answer", not "anything ever called it". When adding a seam or gate,
   ask what the first real input is and where it comes from — and get a real
   consumer on the far side before calling it done.
2. **The server is right and the client was never told.** Minecraft predicts
   locally; a cancelled action already happened on screen. Resend what the
   client believes (block state and neighbours, inventory), put feedback where
   the cursor was.

For those: the dev client (`TestClient.cmd`), then a **genuinely vanilla
client** for anything that sends a packet — see ZombieMod's `CLAUDE.md` for the
procedure. Direct Connect `127.0.0.1`, not `localhost`. `/zm observe on` makes
a test player damage-immune if ZombieMod is in `run/mods`.

## Design principles (standing requirements, not preferences)

**Vanilla first, modded as sugar.** Can an unmodded client use the whole
feature? Quests are offered on the action bar, accepted by right-click or a
clickable chat line, tracked on the action bar, read in a written book,
completed with a title card. A modded client may get a HUD later. Anything
that must be client-side to work at all is a signal to find a server-side
substitute, not to add a footnote.

**"Don't make me think."** Explain at the moment of the change. A message that
names the remedy beats one that names the problem. Never ship output that
needs decoding. A visible correction is itself a defect.

**Sensible defaults, highly configurable.** Every number is somebody's wrong
number: expose it, in `chronicler-common.toml` or `messages.yml`. Defaults are
still opinionated.

**Rebuild, don't copy.** FTB Quests, Quests (Bukkit), BetonQuest are read for
intent, never lifted — even where the licence would allow it.

**Tone:** dry, deadpan; a refusal leaves you knowing what to do next. A
well-chosen emoji is polish, a row of them is clutter.

## Architecture

```
data/<pack>/chronicler/{chapter,quest}/<name>.json   datapack registries
config/chronicler/{chapters,quests}/<name>.yml       the YAML front door (same schema)
        ↓  Chapter.CODEC / Quest.CODEC
data/                loader-light records: Quest, Chapter, Stage, Choice, Place, Availability;
                     ObjectiveSpec / RewardSpec / GiverSpec dispatch on "type" via SpecTypes
core/QuestLog        the player's journal (attachment, copyOnDeath): entries with stage,
                     counters, targets, deadline; completions; flags; tracked
neoforge/QuestEngine accept / measure / advance / choose / fail / complete / reward
neoforge/Trackers    how an objective is measured (poll or event), keyed by spec class
neoforge/Rewards     how a reward or effect is granted, keyed by spec class
neoforge/Givers      offers near a block or in a kind of place; GiverStore = op-placed (SavedData)
neoforge/Journal     the written book; FlagStore = world flags (SavedData, cached for off-thread)
neoforge/JournalPanel the same journal as one payload for a modded client (journal.panel): text resolved
                     here, buttons are /quest commands the client sends back (RUN runs only "quest ...")
network/             JournalPayload (clientbound, whole journal) + JournalRequestPayload (open/refresh/run) +
                     WaypointsPayload (clientbound, once a second: quest targets/givers/led NPCs, for a map) +
                     HudPayload (clientbound, only when it changes: active quests and objective lines, worded here)
neoforge/Hud         builds HudPayload from the same currentObjectives/describe the action bar uses; caps at
                     tracker.hudMaxQuests with a "+N more" line; remembers what each client was last sent
neoforge/Waypoints   plain data for WaypointsPayload -- reads the same currentObjectives/Givers/Npcs every
                     tracker and page already reads; knows nothing of JourneyMap or any client at all
neoforge/EscortBars  a vanilla boss bar per escorted charge in danger, showing hits taken; recomputed
                     each poll like Waypoints, no separate cleanup path when the escort ends
client/              dist=CLIENT entrypoint: JournalScreen (chapters | quest map or page), ` key (never J: JourneyMap), ClientJournal, ClientWaypoints,
                     ClientHud (the quest tracker HUD, a GUI layer under the chat; Shift+` toggles; decides nothing),
                     ChroniclerClientConfig (chronicler-client.toml: hud.shown/top/width -- the player's own screen)
client/compat/       ONE guarded pair for JourneyMap: JourneyMapPlugin (its own annotation-discovered
                     plugin, storing IClientAPI), JourneyMapWaypoints (creates/moves/removes Waypoints).
                     The client-side twin of neoforge/compat/ -- same "only this may import it" rule.
                     Also StandardsHudButton: Standards' quest-tracker button toggles the HUD, lit while shown
core/QuestMap        prerequisite-depth layout for the map; loader-light so the self-test checks it
neoforge/Party|Money|Rep|Sheet|Lots   neutral bridges, "nothing here" without a sibling
neoforge/compat/     ONE guarded class per sibling: StandardsGroups, StandardsEconomy, StandardsButtons (the bar's
                     quest tracker + journal buttons; they run /quest and /quest journal, so vanilla loses nothing),
                     StandardsReputation, LegendQuestCharacter, CityWorldLots, ZombieModConditions,
                     ZombieModSpawns (Genera bridge: spawn a genus and get the Mob back -- never via the
                     command, which hands nothing back and whose mob no lookup can find this tick)
api/Quests           the door for other mods (StoryTeller): offer / accept / flags / registries
yaml/                YAML -> JSON pack
data/QuestItem       chronicler:item registry; marked stacks built at use time; QuestItemLoot function
data/Mini, Slots     mini quests: a quest file with a mini: block is a template whose {slot} holes are filled at
                     start; Quest.CODEC checks it filled with stand-ins and keeps the raw JSON (Extras.template)
neoforge/Minis       slot resolution (structure/lot/block/npc/...), the filled instance (cached: objectives are
                     found by identity), pending offers, wild spawns, and sending placed people away when it ends
datapacks/prologue/  the sample prologue, a built-in pack (content.prologue: auto = on unless ZARP is on;
                     -Pselftest forces it because the self-test drives it)
datapacks/zarp/      the ZARP questline, a built-in pack (AddPackFindersEvent; path is from the JAR ROOT,
                     not data/); registered only when content.zarp says so, because a world remembers
                     an enabled pack by name and would keep running it
```

- **Content is a frozen registry.** `/reload` rebuilds tags and functions,
  never the registry loader; chapters and quests apply on **restart**.
  `messages.yml` is not a registry and does reload. The reload notice says so.
- **Objective and reward types are codec-dispatched records** with a public
  `SpecTypes.register`, so the interesting types (a LegendQuest level, a
  ZombieMod genus, a CityWorld lot) live in `compat/` or other mods. A bare
  `"type": "kill"` means `chronicler:kill`; bare ids elsewhere mean ours too
  (`ChroniclerIds.CODEC`).
- **No `ItemStack` in a codec.** Items are held as ids and built at use time —
  on 26.x a stack cannot be constructed while a datapack registry is loading.
- **Text is resolved server-side** through `Lang` → `messages.yml`, merged on
  every start via `messages.known` so keys added later reach existing servers.
  `{term.*}` re-skins vocabulary wholesale. **A hardcoded player-facing string
  is a bug.** `&` codes become real styles in `Feedback.colored`, never `§`
  in a literal — the console, log and RCON read `getString()`. Audit:
  `grep -rnE '§|\\u00[aA]7' src/main/java` (both spellings).
- **Every clientbound send goes through `Net.sendIfAble`** — written before any
  payload exists, because `optional()` makes the handshake tolerant and does
  not make sends droppable; an unguarded send kicks vanilla clients at login.
  `Net.listening` also refuses a fake player: NeoForge's `FakePlayer` HAS a connection
  whose channel is null, and `hasChannel` throws on it (the first panel send in the
  self-test crashed the boot). A bare `connection != null` check is not enough; the
  guard is `!isFakePlayer()` plus `getConnection().isConnected()`, as ZombieMod's now is.
- **Where state lives:** the journal is an attachment (belongs to the player,
  survives death). World flags, reputation and givers go in **SavedData** when
  built — they must answer for offline players. Pending offers and countdowns
  are static maps; surviving a restart would be worse than losing them.
- **Commands live at their plain names** (`/quest`, `/quests`); `/chronicler`
  administers the mod and nothing else lives under it. `/quests` is a second
  literal, not a redirect: a redirect's requirement ANDs with every child and
  ignores merged children. Each subcommand carries its own bar; roots carry
  none. **Any argument that can contain punctuation must not be `word()`.**
- **Permissions** go through NeoForge's `PermissionAPI` — Standards and
  LuckPerms are both handlers, so nothing lives in `compat/` for them. Boolean
  nodes only; every default resolver reproduces `NODES.md`.

## The build stamp

Family format (agreed 2026-09-10; LegendQuest holds the reference). `build.gradle`
reads the short commit (`-dirty` when uncommitted), branch and the COMMIT's UTC
time (never the wall clock: that would change the resource every run and keep
`jar` from ever being up to date) at configure time, writes them into the manifest (`Build-Commit` / `Build-Branch` /
`Build-Time`, for `unzip -p <jar> META-INF/MANIFEST.MF` without loading it) and
into `/chronicler/build.properties` (namespaced; read by `BuildInfo` at runtime,
because a dev run has no jar). The startup line and `/chronicler status` print it:
`Chronicler 1.0.0+mc1.21.11 (build 32dac07e on main, 2026-09-10T07:30:11Z)`. A missing
stamp reads `unknown` and never fails a load. **Same filename, different bytes**
bit three sessions in one day; the stamp is the answer, and the log line is the
half that matters because it says what ran.

## Soft dependencies — the seam pattern

**Standards and Cast are `type="required"`** (Sable, 2026-09-13: a modpack that pulls Chronicler
must pull the economy, groups and reputation it pays through and the people who give its quests;
CurseForge carries the same as a required-dependency relation on each upload, via the repository
variable `CURSEFORGE_REQUIRED_DEPENDENCIES`, comma-separated slugs -- Standards only until Cast
has a page). LegendQuest, ZombieMod and CityWorld stay `type="optional"`. All five are
`compileOnly` from their `build/libs`, and the code still treats every one as a seam. **Only a class under `neoforge/compat/` may import
`com.sablednah.standards`, `com.sablednah.legendquest`, `com.sablednah.zombiemod`
or `me.daddychurchill.CityWorld`.** Everything else talks to a neutral bridge
that answers sensibly when the sibling is absent (no economy → reward skipped
and said so; no CityWorld → `lot` objective never completes and the file is
rejected at load with a clear message). **JourneyMap is the same rule, client-side**: only
`client/compat/` may import `journeymap.*`, and it is `compileOnly` against a vendored jar
(see Known traps) rather than a sibling's `build/libs`, since it is not one of ours.

**Floors are what is consumed, not what is newest.** Standards' floor is `[1.5.0,)` because
reputation (1.5.0) is consumed; Cast's is `[1.1.0,)` because `follow` (the escort objective) is.
NeoForge refuses to start when a dependency is present but below its floor, so raise a floor only
when a seam genuinely needs the newer build.

Wire each seam through `Chronicler.optionalIntegration(name, runnable)`, which
catches **`LinkageError`** — `ModList.isLoaded` says "present", not "new
enough", and an older Standards once took a whole server down during
construction. Set a version floor in the mods.toml only when a seam is actually
consumed. **Build the seam with a real consumer on the other side**, never
speculatively: the docs say what is wired, and `/chronicler status` says what
is detected.

Seam-by-seam detail, and what we *offer* back (spawn conditions to ZombieMod,
karma triggers to LegendQuest), is in `docs/DESIGN.md`.

## Versions

Branch per Minecraft version is the house pattern: `main` = 1.21.11 (Java 21),
`mc26.1` = 26.1.2 on NeoForge 26.1.2.95 and `mc26.2` = 26.2 on NeoForge
26.2.0.72 (both Java 25, `/home/sable/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`).
26.1 carries the 26.2 drift below EXCEPT the entity-type constants, which it
still has (`EntityType.TEXT_DISPLAY`); its run dir is `run-mc26.1.2`, and its
ZombieMod jar (`zombiemod-3.4.0+mc26.1.2.jar`) is copied from the CurseForge
`26.1.2` instance into `libs/`. Docs on `main` only, features cherry-picked forward, jar named
`chronicler-<ver>+mc<mc>.jar`; both jars sit in `build/libs` and `newestJar`
picks siblings by the `+mc` suffix, so the two lines never cross. The 26.2
branch differs in `gradle.properties` (four lines), `build.gradle` (plugin
2.0.144, toolchain 25, `gameDirectory = run-mc26.2` so a 1.21.11 world is
never upgraded in place; it needs its own `eula.txt` and `server.properties`)
and these bodies: `sendSystemMessage(text, overlay)` for `displayClientMessage`
(in `Feedback`), `SavedDataType` ids are `Identifier`s (`chronicler:flags`,
`chronicler:givers`) **and the file moves to a namespaced folder** (a migration
that copies to the wrong place logs nothing), `EntityTypes.*` for
`EntityType.*`, `ChatFormatting` is a bare enum so `Feedback` owns the five
formatting codes, `entityTags()` for `getTags()`, the loot-function
registry holds the `MapCodec` itself (no `LootItemFunctionType`), the block-break event is
`event.level.block.BreakBlockEvent` (with `setNotifyClient(true)` on cancel, so the client's
predicted removal is undone), and `ItemParser.parse` returns an `ItemInput` record. Sibling 26.2 jars: Standards 1.6.0, LegendQuest 2.4.1,
ZombieMod 3.4.0 (in its `build/libs`), Cast 1.0.0 (the instances now carry Standards 1.8.0, LegendQuest 2.5.0, ZombieMod 3.4.1). A fresh 26.2 world starts
at tick 0 and loads no spawn chunks without a player; the self-test allows for
both.

**`mc26.3`** (2026-09-29) is 26.3 on **NeoForge 26.3.0.33-beta**, MDG 2.0.147, Java 25, run dir
`run-mc26.3`. The neo range is `[26.3.0.33-beta,26.3.0.37-beta)`: floored at the tested build, not the series, and
**capped below .37-beta** (Sable, 2026-09-30) because FML 12.0.8 there renamed `ModConfig.Type` `COMMON` -> `LOCAL`
(`SERVER` -> `SYNCED`) and every jar using `COMMON` dies with `NoSuchFieldError` -- a mechanical rename to port once
NeoForge 26.3 is stable. The cap is written `-beta` on purpose: in Maven order `26.3.0.37-beta` sorts before `26.3.0.37`.
The floor matters too:
`NewDatapackRegistryEvent` only exists from .20-beta and CityWorld crashed at construction below it.
On top of the 26.2 drift: `DataPackRegistryEvent.NewRegistry`/`dataPackRegistry` ->
`NewDatapackRegistryEvent`/`worldRegistry` (26.3 also offers `reloadableRegistry`, which could one
day make content `/reload`-able); `drop(stack, false)` -> `drop(stack, false, Prediction.SERVER_ONLY)`;
`BlockState#blocksMotion()` -> `is(BlockTags.BLOCKS_MOTION)`; `DisplayInfo` is a record (`hidden()`,
`type()`); `clearOrCountMatchingItems(pred, countingOnly, amount, craft)`; `Pack.ResourcesSupplier` is
`openMetadata` + `openResources` (a `Stream`). **The one that compiles and is wrong: 26.3 moved input
to SDL, which numbers the mouse from 1** (left=1, right=3, back=4), so `JournalScreen` compares
against `InputConstants.MOUSE_BUTTON_*`, never a literal -- `!= 0` made the journal unclickable on 26.3
alone, and no self-test can see it (Standards' `CROSS-VERSION.md` has the whole story). Sibling jars
for this line: Standards, Cast, LegendQuest and ZombieMod from their `build/libs`; CityWorld and the
JourneyMap API (`26.3-2.0.0`) out of the CurseForge `26.3` instance into `libs/`. The merged jar in
`build/moddev/artifacts` carries `.java` sources on 26.3 -- `unzip -p` it to read the real API.

## Releasing

Artwork lives in `docs/`: `wordmark-850.png` (CurseForge caps description images at 850 wide; the store page and README use it), `wordmark.png` (full size), `icon.png` (1254 square), `icon-512.png` and `icon-256.png`.

`CHANGELOG.md`, `CURSEFORGE.md` (the store page: https://www.curseforge.com/minecraft/mc-mods/sablecraft-chronicler, project 1690352), `mod_version`, tag, GitHub
release — publishing fires `.github/workflows/curseforge.yml` (live since 1.0.0:
`CURSEFORGE_TOKEN` and `CURSEFORGE_PROJECT_ID` are set, so a published release
uploads for real). Modrinth is dropped (2026-09-29: it refused every one of Sable's
projects as AI content), so there is no second store to publish to. The workflow's `only` input re-uploads one
Minecraft line after a partial failure without duplicating the others
(CurseForge has 500'd one of three jars twice across these repos). A 200 from CurseForge is acceptance, not
publication; the changelog sanitiser 500s on blockquotes and autolinks. Never
handle the tokens.

**A CurseForge project's file list is its identity to every other mod.** The app
resolves a required dependency to the dependency project's newest APPROVED file
for that Minecraft version and does not care what it is named, so a companion jar
uploaded beside the mod is what dependents install -- most likely right after a
release, while the mod's own file is still in moderation. LegendQuest shipped its
example pack to StoryTeller users that way (2026-09-15). Both workflows here now
upload `chronicler-*.jar` only; attach anything else to the GitHub release alone.

## Known traps (paid for next door, and some here)

- **`own_kill`'s respawn record (`Rewards.SPAWNED`) is one of the in-memory maps the "surviving a
  restart would be worse than losing them" rule above is about -- except here losing it is worse.**
  `own_kill` exists so a kill quest cannot be stranded by a death that was not the player's; a
  server restart between the spawn and that death is exactly the ordinary case it must survive,
  and the live record cannot (paid for twice in play: Phil, on The Plan). The fallback is
  `Quest.onEnterAt(stage)` -- the same `spawn` reward that made the mob in the first place, matched
  by its `tag`, re-read from the frozen (and, for a mini quest, already slot-resolved) quest
  content rather than remembered. Reach for the same trick before adding another in-memory record
  a stranded-quest guard depends on.
- **Not every way a tracked mob disappears is a death.** ZombieMod's mutation (a Walker turning
  Runner at low health) replaces the entity with `Entity#discard()` -- no `LivingDeathEvent`, no
  damage source, and the replacement inherits only persistent-data NBT, never Chronicler's vanilla
  scoreboard tags. `own_kill` paid for this a third time on the same Phil before it was caught:
  `QuestEvents.onLeave` (`EntityLeaveLevelEvent`, reason `DISCARDED` specifically -- never `KILLED`,
  already credited by `onDeath` an instant earlier, and never a chunk unload, where the mob is
  still there) is the only hook that ever sees a mutation happen to a tagged target.
- **JourneyMap's API jar is not published anywhere an unattended build can fetch it from** --
  it is vendored under `libs/journeymap-api-neoforge+mc<version>.jar` (gitignored, one per line),
  extracted straight out of the JourneyMap jar the CurseForge instances already carry
  (`unzip -j -p <journeymap jar> "META-INF/jarjar/journeymap-api-neoforge-*.jar"`). A fresh
  checkout on a machine without those instances needs this redone by hand before `client/compat`
  compiles. It is discovered at runtime purely by JourneyMap scanning every mod's classes for one
  annotated `@journeymap.api.v2.common.JourneyMapPlugin` -- nothing registers it, and the
  `neoforge.mods.toml` entry is informational only; deleting it changes nothing.
- **`WaypointFactory.createClientWaypoint(String, BlockPos, ResourceKey, boolean)`'s lone
  `String` is the calling mod's id, not a display name** -- shipped once as if it were one
  (Sable, in play: "they are just co-ords"), because an unnamed waypoint falls back to its
  coordinates. It is also deprecated. `createWaypoint(modId, pos, name, dim, persistent)`, with
  an explicit `name`, is the real call, and it exists on every line this repo builds for -- the
  method that looked missing on 26.2 was `createClientWaypoint`, never `createWaypoint`.
- **Resolving a cherry-pick conflict with `git add -A` resurrects files the commit deleted.**
  Cast paid for it: `Proxies.java`, deleted on main, came back on both 26.x branches as an
  orphan nothing called. After `cherry-pick --continue`, `git grep` for the name of the thing
  the commit removed.

- Giver marks are per-player packets (`Markers`), not entities: a `TextDisplay` built with
  `EntityType.TEXT_DISPLAY.create(level, COMMAND)`, configured by NBT `load`, then
  `ClientboundAddEntityPacket` + `ClientboundSetEntityDataPacket(getNonDefaultValues())`.
  FakePlayers are not in the player list; the self-test adds them to `Markers.EXTRA_VIEWERS`.

- **A mini quest's holes are filled per player, so read a quest through `QuestEngine.questFor(player, id)`,**
  never `quest(server, id)`, wherever a player is in hand: the registry copy holds stand-in values
  ("Bread for Aldous" in every list). The filled copy is cached per slot set because `wait` and
  `escort` find their journal entry by the objective object's identity.
- An escort template's `radius` is the size of "there": a test that hands over a village 40 blocks
  away finishes a 64-block escort on its first poll.
- **`Class#getResource` on a bare directory path does not reliably resolve through FML's
  classloader** (it indexes files, not directories) -- resolve a known file inside it (`pack.mcmeta`)
  and take its parent instead. Paid for by the achievement generator scanning the built-in packs.
- **A lot or structure's own coordinate is its roof, not its ground, and "near" a building does not
  mean inside it either** -- both the coordinate and the heightmap are the highest block, the roof.
  First fixed by stepping away to a dry OUTDOOR spot (still not what "near a house" should mean for
  a person); CityWorld's API has no bounding box or floor level to ask for a real fix from (a lot is
  chunk-granular metadata, not geometry -- there is no upstream answer to reach for). `Minis.interiorNear`
  asks the world instead: scan down from each column's roof for the first walkable block that cannot
  see the sky (under cover) with solid ground beneath it. Falls back to the old outdoor spot if
  nothing sheltered turns up nearby -- CityWorld generates whatever shape it likes, and not every
  "near" reference is a real building.
- **An objective satisfied the instant a line is crossed has no story in it** -- an escort ending,
  or an ambush firing, while the player is still on the path outside. `settle` (visit, escort)
  holds the condition true for real seconds, continuously, before it credits.
- **`PlayerAdvancements#award` is a silent no-op on a `FakePlayer`**, even for a genuinely vanilla
  advancement -- it is never sent through `PlayerList#placeNewPlayer`, which is what seeds a real
  join's advancement progress from the manager. The self-test proves generation and lookup, not
  that a grant lands; that wants a real client, same family as the FakePlayer connection quirks.
- A `static final` collection declared after the fields that fill it is null
  when they initialise. Declare collections first. (Same trap, other spelling:
  a static counter declared after the static block that bumps it is an
  "illegal forward reference". `Rewards.lastSpawned` paid for it.)
- **Entities added before a forced chunk's first tick are invisible to every
  lookup** -- `getEntitiesOfClass`, `getAllEntities`, all of them -- so a
  self-test cannot see what a `spawn` effect just placed. `Rewards.lastSpawned()`
  reports what the granter did; the kill path is driven with entities the
  test holds itself.
- **The fantasy prologue and ZARP both have a chapter whose path is `prologue`**
  (`chronicler:prologue`, `zarp:prologue`); the boot log lists paths, so
  "prologue, prologue" is not a duplicate.
- **Do not name a class `Character`** (or `Process`, `Thread`, ...): it shadows
  `java.lang` inside its own package and the error lands in an unrelated file.
  The sheet bridge is `Sheet` for that reason.
- **A decision beat has no objectives, so `Entry.done()` is vacuously true.**
  `set()` guards on a non-empty target list; forget that and a choice screen
  completes itself.
- **Config cannot be read during mod construction** -- `Cannot get config
  value before config is loaded` fails construction and takes the server
  down. Read config values lazily at use time, or register on
  `FMLCommonSetupEvent`. Hit here on the first Standards boot.
- `/execute as <player> run …` does not test that player's permissions.
- `doImmediateRespawn` is the wrong death to test with; transient attribute
  modifiers die on respawn — repair on `PlayerEvent.Clone`.
- An entity search on a dev server with no player needs the chunk
  **force**-loaded and a tick to pass; `getMaxLocalRawBrightness` on an
  unloaded chunk answers 15.
- A negative check ("no offer shown") needs a positive control in the same
  run, or a dead client passes it.
- After a rewrite, grep for the name of the thing you **removed**.
- Never copy a jar into a running instance; `deploy.sh` refuses. **Two faults it had on 1.0.0
  day, fixed in both repos on every branch:** a glob with `head -1` picks the ALPHABETICALLY
  first jar (it deployed 0.1.0 over a freshly built 1.0.0; now the exact
  `chronicler-<mod_version>+mc<mc>.jar`), and the running-instance regex stopped only at a
  backslash while the launcher passes `--gameDir` unquoted, so it captured "26.2 --assetsDir C:"
  and refused nothing (now it also stops at `" --"`). Windows happened to lock the jar that day;
  do not count on it.
- `git cherry-pick` has no `-q`; it errors, and a `| tail -1` behind it hides the error while the
  loop moves on and reports the OLD head. Show cherry-pick output, and check the head moved.
- **Release day, as run (1.0.0, 2026-09-13):** edit template mods.toml + `mod_version` + dated
  CHANGELOG heading + rot sweep (`grep -rn "0\.1\.0\|not yet\|to come"` over docs), build, the
  two self-test boots, commit main, cherry-pick to `mc26.1`/`mc26.2` (docs conflicts: `--ours`
  on CLAUDE.md/README.md, `rm` CURSEFORGE.md, which 26.x does not carry), build a jar per branch
  and read `Build-Commit` from each manifest (no `-dirty`), `git tag -a`, `gh release create`
  with the three jars and the CHANGELOG section as notes, watch `gh run list
  --workflow=curseforge.yml`, then `./deploy.sh` on each branch.
- Never edit a sibling's `CLAUDE.md`; raise it with Sable instead.
