# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

**Chronicler** — a data-driven quest mod for NeoForge: chapters, quests, stages,
givers in the world, a journal. It is the family's answer to FTB Quests, built
for tight compatibility with **LegendQuest**, **ZombieMod**, **CityWorld** and
**SableCraft Standards** — and it requires none of them. First use: the *Zombie
Apocalypse Roleplay* (ZARP) questline. `docs/DESIGN.md` is the thinking, the
data model and the build order — **read it before adding a feature.**

| | |
|---|---|
| Minecraft | 1.21.11 (`main`); `mc26.1` / `mc26.2` branches to come |
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
./deploy.sh                       # build + copy into the CurseForge test instance
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
cp ../SableCraft-Standards/build/libs/standards-*+mc1.21.11.jar \
   ../LegendQuest-ReForged/build/libs/legendquest-*+mc1.21.11.jar \
   libs/zombiemod-*+mc1.21.11.jar run/mods/        # the seams only link with a consumer present
./gradlew runServer -Pselftest > server.log 2>&1 & # then wait for "Chronicler SelfTest:"
grep -nE "SelfTest:|FAILED:" server.log; grep -nE "ERROR|FATAL" server.log
kill <the JVM whose cmdline has Chronicler/build/classes>; rm run/mods/*.jar
```

- **Run it in the foreground of a single command, not as a harness background
  task.** With five sibling dev servers and their Gradle daemons on the box,
  the background-task memory monitor killed two boots mid world-load and
  reported "BUILD FAILED" with no cause. The server heap is capped at 2G in
  `build.gradle` for the same reason.
- **Boot with the siblings AND without.** The compat classes only link when
  the mod is present; 47 checks pass with nobody there, 106 with everybody,
  and both numbers matter.
- **`libs/` (gitignored) holds a sibling jar that has no `build/libs` for this
  line** -- ZombieMod builds 1.21.11 elsewhere, so its jar is copied from the
  CurseForge instance. `build.gradle` compiles against the NEWEST jar of each
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
neoforge/Party|Money|Rep|Sheet|Lots   neutral bridges, "nothing here" without a sibling
neoforge/compat/     ONE guarded class per sibling: StandardsGroups, StandardsEconomy,
                     StandardsReputation, LegendQuestCharacter, CityWorldLots, ZombieModConditions,
                     ZombieModSpawns (Genera bridge: spawn a genus and get the Mob back -- never via the
                     command, which hands nothing back and whose mob no lookup can find this tick)
api/Quests           the door for other mods (StoryTeller): offer / accept / flags / registries
yaml/                YAML -> JSON pack
data/QuestItem       chronicler:item registry; marked stacks built at use time; QuestItemLoot function
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

## Soft dependencies — the seam pattern

All four siblings are `type="optional"` in the mods.toml and `compileOnly` from
their `build/libs`. **Only a class under `neoforge/compat/` may import
`com.sablednah.standards`, `com.sablednah.legendquest`, `com.sablednah.zombiemod`
or `me.daddychurchill.CityWorld`.** Everything else talks to a neutral bridge
that answers sensibly when the sibling is absent (no economy → reward skipped
and said so; no CityWorld → `lot` objective never completes and the file is
rejected at load with a clear message).

**Floors are what is consumed, not what is newest.** Standards 1.5.0 (released
2026-09-06) carries `api/reputation`, but the floor stays `[1.2.0,)` because
economy and groups are what is consumed unconditionally and every 1.21.11 test
instance still runs 1.2.0 -- NeoForge refuses to start when an optional
dependency is present but below its floor. Raise it only when the instances
have moved and a seam genuinely needs the newer build.

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
formatting codes, `entityTags()` for `getTags()`, and the loot-function
registry holds the `MapCodec` itself (no `LootItemFunctionType`). Sibling 26.2 jars: Standards 1.6.0, LegendQuest 2.4.1,
ZombieMod 3.4.0 (in its `build/libs`), Cast 0.1.0. A fresh 26.2 world starts
at tick 0 and loads no spawn chunks without a player; the self-test allows for
both.

## Releasing

`CHANGELOG.md`, `CURSEFORGE.md` (not written yet), `mod_version`, tag, GitHub
release — publishing fires `.github/workflows/curseforge.yml` and
`modrinth.yml`, both of which skip cleanly until `CURSEFORGE_TOKEN` /
`CURSEFORGE_PROJECT_ID` / `MODRINTH_TOKEN` / `MODRINTH_PROJECT_ID` exist. Both
scripts were copied from MobHealth and renamed; **review `scripts/*.sh` before
the first real release**, and copy Standards' `only_mc` workflow input so one
Minecraft line can be re-uploaded after a partial failure without duplicating
the others (CurseForge has 500'd one of three jars twice across these repos). A 200 from CurseForge is acceptance, not
publication; the changelog sanitiser 500s on blockquotes and autolinks;
Modrinth rejects AI-looking artwork and icons over 256 KiB. Never handle the
tokens.

## Known traps (paid for next door, not yet here)

- Giver marks are per-player packets (`Markers`), not entities: a `TextDisplay` built with
  `EntityType.TEXT_DISPLAY.create(level, COMMAND)`, configured by NBT `load`, then
  `ClientboundAddEntityPacket` + `ClientboundSetEntityDataPacket(getNonDefaultValues())`.
  FakePlayers are not in the player list; the self-test adds them to `Markers.EXTRA_VIEWERS`.

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
- Never copy a jar into a running instance; `deploy.sh` refuses.
- Never edit a sibling's `CLAUDE.md`; raise it with Sable instead.
