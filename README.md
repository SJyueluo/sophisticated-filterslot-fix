# Sophisticated Backpacks / Core — filter-slot transfer defect

Server-side check, server-side fix, and a locally-restricted client probe for a source-slot
validation defect in Sophisticated Backpacks / Sophisticated Core.

The defect: a filter (ghost) sample slot denies pickup, but the whole-slot transfer path never
consults that permission, so a client-supplied slot id can turn a sample into a real item.

**Status (2026-10-30): FIXED upstream, partially.** The maintainer committed a fix on 2026-10-29
(`cf8aa31` — "Fixed forged requests bypassing inventory access restrictions"), released it, and closed
`#517` on 2026-10-30 with "I took a different approach to the fix but this is properly validated now".
Our own check tool reports **FIXED** on the new `1.21.1-1.5.21.2439`: `tier1 BLOCKED`, `gain=0 repeat5=0`,
`slotId=-1` no longer throws, normal transfer still `moved=3`.

Fixed lines (2026-10-30 releases): 1.21.1, 1.20.1, 26.1.2, 26.2, 26.3.
**Still unfixed:** 1.18.2 and 1.19.2 (last releases 2024-11-17, branches untouched), and 1.21.4 / 1.21.5 /
1.21.8 / 1.21.10 / 1.21.11 (last releases 2026-09-27, before the fix).

**Two defect channels — fixing one does not fix the other.**

| # | Channel | Present in | Fixed in |
| --- | --- | --- | --- |
| 1 | whole-slot transfer (`transfer_full_slot`, filter sample merged into a real item) | every line, 1.18.2 → 26.3 | the 2026-10-30 releases listed above; **not** in 1.18.2, 1.19.2, 1.21.4–1.21.11 |
| 2 | ghost-slot write (`set_ghost_slot`, client stack written into a slot) | 1.18.2, 1.19.2, 1.21.1 ≤ `1.4.18` | 1.20.1+ (validated `setGhostSlot`), 1.21.1 from `1.5.x`; **not** in 1.18.2 / 1.19.2 |

The clearest example is 1.20.1 before its 2026-10-30 build: `SetGhostSlotMessage` was already validated while
the transfer handler was not, so "the ghost-slot write is fixed there" never meant "1.20.1 has no defect".

**Impact scope:** the unvalidated handler shipped on every line from 1.18.2 to 26.3. Runtime reproduction was
done on **1.21.1 only**; the other lines are verified at code level (disassembled JARs, or branch source).
Per-line table, evidence type and exact JAR hashes:
[`docs/impact-scope.md`](docs/impact-scope.md) (English) /
[`docs/impact-scope.zh-CN.md`](docs/impact-scope.zh-CN.md) (中文).

- Upstream report: [P3pp3rF1y/SophisticatedCore#517](https://github.com/P3pp3rF1y/SophisticatedCore/issues/517)
- Reviewed versions: Sophisticated Backpacks `1.21.1-3.26.17.2232`,
  Sophisticated Core `1.21.1-1.5.19.2434` (Minecraft 1.21.1, NeoForge 21.1.265)

> This repository is a verification and mitigation project. It contains no client that works
> against arbitrary remote servers, and no completion of an attack tool.

## The defect in one screen

`TransferFullSlotPayload.handlePayload` takes the slot id straight from the client and calls
`quickMoveStack`:

```java
Slot slot = storageContainer.getSlot(payload.slotId);
do {
    transferResult = storageContainer.quickMoveStack(player, payload.slotId);
} while (!transferResult.isEmpty() && ItemStack.isSameItem(slot.getItem(), transferResult));
```

`quickMoveStack` has no `mayPickup()` call and does not reject `IFilterSlot`; the upgrade-settings
branch merges the sample into real storage through `mergeItemStack` — `Slot.set`. See
[`docs/defect-and-fix.md`](docs/defect-and-fix.md) for the annotated chain with pinned links.

A stock client cannot reach it: both send sites in `StorageScreenBase` guard the slot
(`slot instanceof StorageInventorySlot`, and `mayPickup`). A modified client can, because the
server does not re-validate.

## Contents

| Directory | What it is |
| --- | --- |
| `backpack-check` | Server-side self-check. Runs once at startup, prints a two-tier verdict, writes JSON, leaves no items behind. |
| `backpack-transfer-fix` | Server-side mixin fix. Rejects filter/upgrade-settings sources at the payload entry and in `quickMoveStack`. |
| `backpack-client-demo` | Client probe for local reproduction. Single-player or loopback only; filter slots only; unbound key. |
| `backpack-wire-test` | Harness that feeds a real `ServerboundCustomPayloadPacket` through NeoForge's dispatch (mock connection), so the packet path can be tested without a graphical client. |

## Build

Needs JDK 21 (`JAVA_HOME`), and a classpath with Minecraft/NeoForge plus both mod jars.
`compile-classpath.example.txt` shows the layout: one jar path per line, pointing at your own NeoForge
libraries and the two mod jars. Copy it to `compile-classpath.txt` (git-ignored) and fill in real paths.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
# optional, needs client-mapped Minecraft classes:
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -ClientClasspath "C:\path\to\client.jar"
```

Output lands in `built/`. javac `@argfiles` are avoided on purpose: they are read with the system
charset and mangle non-ASCII paths.

## Usage

**Check a server** — drop `backpack-check-1.0.0.jar` into `mods/`, start once, read
`[backpack-check]` in the log or `backpack-check/result-*.json`. It builds a throwaway backpack on
a fake player, exercises the real handlers, then removes the throwaway storage. Add
`-Dbackpack.check.exit=true` to shut down afterwards, and
`-Dbackpack.check.item=minecraft:dirt` to probe with a different item.

**Fix a server** — drop `backpack-transfer-fix-1.2.0.jar` into `mods/` and restart. Server side
only; clients do not need it. Normal storage, player-inventory and upgrade-item transfers are
unchanged. The mixin targets two methods; if a future release renames them the server fails to
start instead of silently losing the guard.

**Reproduce locally** — see [`docs/repro.md`](docs/repro.md). It runs a dedicated server on
`127.0.0.1`, which is what the client probe permits.

## Observed results

Dedicated server, fake player, `minecraft:dirt` as probe item:

| State | Owned-item gain | Gain over 5 repeats | Normal click | Storage-slot transfer |
| --- | ---: | ---: | ---: | ---: |
| Backpacks + Core | +1 | +5 | 0 | unaffected |
| + `backpack-transfer-fix` | 0 | 0 | 0 | unaffected |
| patched upstream source (`1.21.x` + PR #518, built with Gradle) | 0 | 0 | 0 | unaffected |
| official `1.21.1-1.5.21.2439` (upstream fix `cf8aa31`) | 0 | 0 | 0 | unaffected |

Packet path (real `ServerboundCustomPayloadPacket` → `NetworkRegistry.handleModdedPayload`, dirt probe):

| State | Storage before → after | Verdict |
| --- | --- | --- |
| Backpacks + Core | 0 → 1 | DUPLICATED |
| + `backpack-transfer-fix` | 0 → 0 | BLOCKED |

With a server-side recipe viewer (JEI in these runs) the same path can also create an item the
player never owned, because the recipe-viewer ghost-slot message is registered and does not
require ownership. On Core 1.4.18 that message writes `Slot.set` on any menu slot with no
validation at all; on 1.5.18 it goes through validated `setGhostSlot`.

Raw records: [`docs/evidence/`](docs/evidence).

## Limitations

- The client probe was compiled but not executed here: this machine has no graphical environment
  and only server-side mappings. The wire format was verified separately (a single int: `164` — `00 00 00 A4`).
- The fix stops new items from being created through this path. It does not remove items that
  already exist.
- No claim is made about any particular player's conduct or about a specific production server.

## License

MIT — see [LICENSE](LICENSE).

## Version timeline (verified by running the published JARs)

| Build | Ghost-slot message | Whole-slot transfer from a filter slot |
| --- | --- | --- |
| Core 1.4.18.1629 / Backpacks 3.25.36.1629 | writes any menu slot via a bare `Slot.set` — no filter check, no count check | present |
| Core 1.5.18.2431 / Backpacks 3.26.15.2227 | routed through validated `setGhostSlot`; direct storage writes rejected | **still present** |

Both rows were measured on a dedicated server with a dirt probe: the older build accepted a 64-item write into a storage slot
(net +58 over an existing stack), the newer build rejected it (delta 0). The transfer path returns +1 in both.

So one part of the surface was tightened in 1.5.18 and the transfer path was not. That is a statement about the shipped
code, not about intent.

## Scope of the claims in this repository

Confirmed by execution:

- a filter sample can be merged into real storage through `transfer_full_slot` (dirt, +1, repeatable, survives a restart);
- the message is registered server-side as `playToServer` with or without a recipe viewer;
- with a server-side recipe viewer the same path can also create an item the player never owned;
- a server-side guard removes all of it and leaves normal transfers working.

Not claimed:

- that an unmodified client can trigger it — it cannot; both send sites in `StorageScreenBase` guard the slot;
- anything about a specific server, player, or the maintainer's intent.

## Reporting

The upstream ticket is [SophisticatedCore#517](https://github.com/P3pp3rF1y/SophisticatedCore/issues/517).
It is open, and the reproduction described there is a modified-client one. If that is out of scope for the maintainer,
the two-line guard in `docs/defect-and-fix.md` is the smallest change that closes the path regardless of what a client sends.
## Probe policy (fixed)

The client probe is written so that it **cannot** be pointed at a remote server:

- loopback is checked before the key handler does anything, and again immediately before the packet is sent;
- the check resolves the host and accepts only loopback addresses, not a string match;
- both failures print the address that was seen;
- there is exactly one send site in the whole mod.

Any future edit that removes either check is a regression, not a feature.
