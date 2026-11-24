# TickSight

<img src="web/src/assets/logo.svg" width="96" height="96" alt="TickSight logo">

A fork of [tasgon's Observable](https://github.com/tasgon/observable), TickSight
profiles loaded block entities and entities so server owners can find which
objects consume tick time and where. Results include average or per-tick peak
times, with a world overlay and browser viewer. Optional stack sampling and
exact method-call counts provide more detail.

## Minecraft versions and loaders

Available builds target these Minecraft versions and loaders:

| Minecraft | Forge | NeoForge | Fabric | Java for Minecraft |
| --- | --- | --- | --- | --- |
| 1.20.1 | Yes | — | Yes | 17 |
| 1.21.1 | — | Yes | Yes | 21 |
| 1.21.4 | — | Yes | Yes | 21 |
| 26.1.2 | — | Yes | Yes | 25 |

Install **Architectury API** for your loader and Minecraft version. Fabric also needs
Fabric API and Fabric Language Kotlin. Forge and NeoForge need Kotlin for Forge.
The exact dependency versions are recorded in `versions/<minecraft>/gradle.properties`.

TickSight and the original Observable **should not** be installed together.

## Install and view a profile

From the repository root, run `./gradlew collectJars` on macOS/Linux or
`.\gradlew.bat collectJars` in Windows PowerShell. The task collects
version- and loader-specific JARs in `build/libs/`.
Install the TickSight JAR for your Minecraft version and loader, plus its required
dependencies, in the server's `mods/` directory. For the in-world overlay, install
the matching TickSight client build and its dependencies as well.

As a level 4 operator, run a short profile in chat:

```text
/ticksight run 3
```

This records tick times for all loaded ticking objects across dimensions for three
seconds and initially shows averages. When an upload succeeds, the initiator and
online operators receive a clickable report link. Open it in the browser viewer
to inspect the results. Server commands also work from an unmodified client; the
client mod is only needed for the in-world overlay.

## Profiling

The canonical command is:

```text
/ticksight run <duration> [modifiers...]
```

Use a positive whole duration: `3` or `3s` means three elapsed seconds;
`60t` means 60 server ticks at the current TPS. Modes, scope, and dimension
can be combined in either order. Each modifier may appear once.
The default collects timings for every loaded ticking object across all
dimensions, without stack sampling or exact calls, and initially shows averages.

| Modifier | Meaning and default | Compatibility |
| --- | --- | --- |
| `sample` | Add sampled stack traces; off by default | Every scope and peak; cannot combine with calls |
| `calls` | Add exact method counts and timings; off by default | Every scope and peak; needs the matching server Java agent; cannot combine with sample |
| `peak` | Initially show each object's largest total time within one server tick; default is average | Every scope, sample and calls |
| `full` | Trace unlimited method repeats (-1) for this run; default is configured repeat limit | Requires explicit calls; keeps every safety budget |
| `no-tickets` | Skip chunk ticket sources and levels for this run; recorded by default | Every mode and scope; chunk readiness, generation, tracking and players remain |
| `no-runtime`, `no-generation`, `no-ticket-levels`, `no-ticket-sources`, `no-visibility`, `no-players` | Skip the corresponding Heatmap layer for this run; all are recorded by default | Every mode and scope |
| `no-layers [runtime generation ticket-levels ticket-sources visibility players]` | Skip all layers, or keep only the adjacent names that follow | Another modifier closes the list; negative flags always win |
| `in <dimension>` | Only collect objects in that dimension | Every mode and scope; once per run |
| `radius <r> <circle or sphere> [at <x y z>]` | Radius defaults to blocks. Use `b` for blocks or `c` for chunks of 16 blocks. The center defaults to the source position | One scope per run. Choose one shape |
| `circle` | X/Z circle from the center's height down to the selected dimension's floor | Requires radius |
| `sphere` | 3D sphere around the center | Requires radius |
| `box <from x y z> <to x y z>` | Volume including both block corners, in either order | One scope per run |
| `chunks <cx cz> [to <cx cz>]` | Inclusive rectangle of chunks at full world height; defaults to one chunk | One scope per run |
| `entity <uuid or selector or numericId>` | Only that entity | One scope per run; available in every mode |
| `block <x y z>` | Only ticks at that block position | One scope per run; available in every mode |

Use `at` after `radius` and `to` after `chunks`. Other modifiers may go
between them. `0.5c` and `8b` both mean eight blocks. Conflicting scopes,
shapes or collection modes, duplicate modifiers, and missing prerequisites
are rejected before a recording starts.

```text
/ticksight run 60t sample peak
/ticksight run 5 no-tickets
/ticksight run 5 no-layers players visibility peak
/ticksight run 6t box -96 60 -176 -80 80 -160 peak calls
/ticksight run 3s radius 1c circle at ~ ~ ~ sample
/ticksight run 3s radius 0.5c sphere peak
/ticksight run 6t chunks -6 -11 to -5 -10 calls peak
/ticksight run 3s in minecraft:the_nether
/ticksight run 20t entity @e[type=minecraft:armor_stand,limit=1] sample
/ticksight run 6t block ~ ~-1 ~ calls full in minecraft:overworld
```

Areas, block positions and numeric entity IDs use the source dimension unless
`in` is supplied. Native UUID/selector lookup can find an entity in its
dimension; an explicit `in` must match the selected entity. Coordinates
support absolute, relative (`~`) and local (`^`) forms. `in` uses Minecraft's
`execute in` coordinate context. It scales the source X/Z when changing
dimensions for relative/local positions and for the default radius center.
Absolute coordinate values stay as entered. The entity argument retains Minecraft's crosshair
suggestions; block positions suggest the looked-at block when it is in the
selected dimension. Selection only examines loaded ticking objects and does
not load chunks. Entities use their position; blocks use their center. Areas
are fixed at the start and filter before allocating timing data or recording
calls. Selected objects retain the complete work they perform.

`peak` selects peak values for the Minecraft world's colored overlay and
labels when the client has TickSight, and the initial mode in the web viewer.
Both average and peak values are always saved. Repeated ticks of the same
object within one server tick are added before finding its peak. The web
Individual tab offers Average, Peak and Per tick; Stack trace opens that
object's samples for the selected tick (or its peak tick in Peak mode).

Use `stop` to export a running recording early.

Server reports also record chunk and player state at the end of each recorded
server tick. A complete first snapshot is followed by changes and removals,
independently of object timings and the selected recording mode. Inspection
reads existing holders, completed futures and ticket trackers; it never requests
a chunk or advances generation. The map distinguishes a holder, a completed
generation stage, full-chunk readiness, and block/entity ticking. Server tracking
and queued chunk sends are separate observations where the Minecraft version
exposes them; neither proves client rendering.

State follows the command's dimension and X/Z scope. Boundary chunks can extend
beyond that scope; player positions remain inside it. Real watchers and ticket
owners outside it may be identified without publishing their positions. Ticket
types and generation names come from the running server. Direct tickets remain
distinct from propagated effective levels; matching source candidates are not
claimed to be a unique cause. NeoForge's shared forced-ticket owner groups are
recorded separately when the native ticket no longer retains an owner key.

The state stream has its own estimated storage budget within the existing recording
memory and safety limits (at most 32 MiB, leaving room in the 128 MiB archive for
object data). A cutoff retains complete frames and marks later state unknown.
Lightweight client overlay exports omit this stream; the original server report
and HTTP export retain it.

The layer flags disable collection, rather than hiding data in the viewer.
`no-ticket-sources` skips ticket enumeration, owners and propagation;
`no-ticket-levels` skips exported effective levels while sources retain their own
native levels. `no-tickets` combines both. `no-layers` skips the entire collector;
`no-layers players` records only player frames without inspecting chunk holders.
Positive names must be adjacent: `no-layers players peak` is valid,
`no-layers peak players` is not. Negative flags take priority regardless of order.
The report marks disabled layers as not recorded. CPU object profiling and
client-side axes are independent; GUI/API defaults still record every layer.

The report records who started the run. For a player, TickSight saves the
account name and UUID known to the server at that moment. For a command, the
origin is the command source supplied by Minecraft. `execute as` changes the
effective entity for the command and leaves the recorded origin intact. A
console, command block or identified automation source has a source label and
no player UUID. Automation that uses the ordinary console source appears as
server console. The viewer displays the recorded identity without a network
lookup, even if someone else stopped the recording or the player left.
Client exports made when upload is unavailable retain the owner with the
client's profiling data. Exact call trees are available in the server report.

### Help and settings

`/ticksight help` is a short index; `help run`, `help scopes`,
`help calls`, `help settings`, `help commands` and individual modifier
topics explain details with clickable examples. Clicking inserts an editable
command; it executes only after you send it.

`/ticksight configure` lists the server settings by purpose. Hover for the
description and default. Click to edit.

Strings use command quotes; `allowedPlayers` is a JSON UUID array inside
that quoted string. For example:

```text
/ticksight set traceInterval 0.1
/ticksight set deviation 0.05
/ticksight set allowedPlayers "[\"01234567-89ab-4cde-9012-3456789abcde\"]"
/ticksight set allowedPlayers "[]"
/ticksight set uploadURL "https://ticksight.anixty.net/v1/add"
/ticksight set uploadURL ""
```

`traceInterval` is the base pause between stack samples (default 3 ms).
`deviation` adds random variation (default 1 ms, `0` disables it). Both
accept fractional milliseconds. The sampler rounds to nanoseconds and caps
the variation so the pause stays positive. The observed cadence also depends
on stack collection cost and JVM and OS scheduling. Exact calls are recorded
independently. `includeJvmArgs` controls whether report diagnostics include
JVM launch flags. `allPlayersAllowed`
and the UUID set `allowedPlayers` control client UI access, which refreshes
for online clients after changing those values.

| Server command | Action |
| --- | --- |
| `run <duration> [modifiers...]` | Start a recording |
| `stop` | End collection and export the data collected so far |
| `configure` | Open the interactive settings menu |
| `set <field> <value>` | Validate, save and apply one setting |
| `allow <player>` / `deny <player>` | Grant or revoke access to the profiler screen by player name or player selector |
| `tp <dimension> entity <numericId>` | Teleport to an entity |
| `tp <dimension> position <x y z>` | Teleport to coordinates |
| `reset` | Save every setting's default |

All server commands require operator level 4. To edit profiler screen access
by UUID, use `/ticksight set allowedPlayers` with a quoted JSON array of player UUIDs.

### Method call counts

To enable exact call counts, keep the agent jar next to your server launch script
and add `-javaagent:ticksight-call-trace-agent-<version>.jar` to the JVM options
before starting the server. Install the regular TickSight jar in `mods/`. Build
the agent with `./gradlew :call-trace-agent:collectJar` (`.\gradlew.bat :call-trace-agent:collectJar`
in Windows PowerShell) to place it in `build/libs`.

```text
/ticksight set callTraceTickLimitMs 1536
/ticksight set callTraceMaxRepeats 128
/ticksight run 5t calls
/ticksight run 5t calls in minecraft:overworld entity 42
/ticksight run 3s calls in minecraft:overworld block -12 64 -9
```

Run `calls` without a target to record ticking entities, block entities, and
scheduled block and fluid ticks in one report. Each object has a separate tree,
including blocks at different positions or in different dimensions. To
record one object, use its numeric entity ID, UUID, or an entity selector. The
entity argument offers the normal Minecraft crosshair suggestions. The block
argument suggests coordinates of the block you are looking at. The report's **Calls** tab shows the
method tree for each recorded object and tick. Each row shows the call count,
total time, and self time after subtracting recorded child calls. The percentages
use the object's recorded time in that tick. The Heatmap switch colors siblings
from green at zero to red at the highest sibling value, using the selected numeric
sort metric. Sorting by name keeps the previous color metric. The per-parent value is the total child
calls divided by the parent calls on that branch. Timing methods adds some overhead during recording.
`traceInterval` only controls stack sampling.

The **Profile** action on an Individual row copies a six-tick `calls` command
for that object. It uses an entity's UUID or a block's coordinates in the
recorded dimension.
The command ends with `full`, which removes the repeat limit for that recording
without changing `callTraceMaxRepeats`. The server tick safety limit still applies.
The full modifier can be added anywhere in an explicit calls command.

`callTraceTickLimitMs` in `config/ticksight.json` defaults to **1536 ms**. It limits
the full server tick during call recording. When any tick exceeds the limit,
TickSight ends the recording, reports the tick and its duration to operators and
the initiator, and uploads the partial report. The affected tick is marked in
the viewer. Set a positive limit before starting a recording.

`callTraceMaxRepeats` defaults to **128**. Set **-1** for the full call tree or a nonnegative
value to trace the first N invocations of each method path per object and tick.
Further invocations retain their exact count and inclusive elapsed time, while
their descendants are omitted. The limit resets each tick and applies separately
to each parent path. Omitted invocations are marked on the row. Their self time
is unavailable, and child counts refer to the traced invocations of the parent.
Use `/ticksight set callTraceMaxRepeats 128` to apply a limit to the next recording.

Call recordings also have the following safety settings. Existing values are
preserved when loading the config, and missing settings are written with their defaults.

```json
{
  "callTraceTickLimitMs": 1536,
  "callTraceMaxRepeats": 128,
  "callTraceHardTickLimitMs": 5000,
  "callTraceMemoryLimitMiB": 128,
  "callTraceHeapReserveMiB": 256
}
```

The hard limit uses an independent watchdog, checked every 10 ms during a tick.
It disables recording even if the server thread has not reached another method
hook. It does not interrupt Minecraft's tick or forcibly stop its thread. JVM
pauses and OS scheduling can delay watchdog execution. The partial report is
finalized after the server thread returns and safely closes the current tick.

The call-recording memory budget (`callTraceMemoryLimitMiB`) defaults to 128 MiB.
The JVM heap reserve (`callTraceHeapReserveMiB`) defaults to 256 MiB; recording
stops if free heap falls below it. Exported profile JSON is limited to 128 MiB.
`full` leaves these safeguards enabled.

Reports whose uploads are disabled or fail, and partial call reports, are saved
as JSON under `ticksight/profiles/`. The directory retains the latest 32 saved
reports. Each report records its stop reason, which is also shown to the initiator
and operators.

The agent counts entries to instrumented Java methods in Minecraft, mods, and
application libraries on the server thread. The tree links the closest recorded
parent to each method. JVM classes, native implementations, loader infrastructure,
and generated classes the agent cannot transform are outside that coverage.
Constructor bodies are counted from the point where the object is initialized.
Use short recordings to limit the overhead from recording calls.

## Profile uploads

TickSight's default hosted viewer is [ticksight.anixty.net](https://ticksight.anixty.net/).
The server posts gzip-compressed UTF-8 JSON to its `/v1/add` endpoint and expects
an absolute HTTP(S) profile URL as a plain-text response. Set `uploadURL` in
`config/ticksight.json` to use a compatible backend, or set it to an empty string
to disable uploads. Failed uploads leave the profiling results available for
local export.

The self-hosted receiver and browser viewer are in `web/`. See
[web/README.md](web/README.md) for build and Windows deployment instructions.

## Links

- [Source code](https://github.com/Anixty/TickSight)
- [Issues](https://github.com/Anixty/TickSight/issues)
- [Releases](https://github.com/Anixty/TickSight/releases)

## Credits

- [tasgon](https://github.com/tasgon) and the [Observable contributors](https://github.com/tasgon/observable/graphs/contributors)
  for the original profiler.
- [LagGoggles](https://www.curseforge.com/minecraft/mc-mods/laggoggles), the original inspiration for Observable.

## License

The TickSight mod is licensed under the **Mozilla Public License 2.0**. See
[LICENSE](LICENSE). The browser viewer is based on a separate upstream project;
its revision, TickSight adaptations, attribution, and license status are recorded
in [web/UPSTREAM.md](web/UPSTREAM.md).
