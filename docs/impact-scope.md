# Impact scope

Status: 2026-10-28. Every number below was produced in this project and can be re-run.

## The short version

- The defect is in **Sophisticated Core**, in the server-side handler for the whole-slot transfer message.
  The handler takes the slot id from the client and passes it straight to `quickMoveStack`, which never asks
  the slot whether the player may take from it. Filter (ghost) slots answer "no" — `mayPickup` returns
  `false` — but that answer is never requested on this path.
- It is present in **every release line of Sophisticated Core that publishes this message**.
- **Runtime-verified on exactly one line: 1.21.1**, on the latest published pair
  (Core `1.21.1-1.5.19.2434` + Backpacks `1.21.1-3.26.17.2232`, Minecraft 1.21.1 / NeoForge 21.1.265).
  Measured: `storage 0 -> 1`, held stack unchanged, repeat 5 → `+5`.
- Every other line is verified at **code level** (disassembled release JAR, or the published source of that
  branch). No server was run for them. That distinction is kept throughout this page on purpose.

## What "affected" means

A modified client can send `transfer_full_slot` with the index of a filter slot. The server accepts that index
and merges the sample into real storage. The sample is not consumed; a real item appears. A stock client
cannot do this — both send sites in `StorageScreenBase` restrict the slot to real storage slots.

The separate ghost-slot write (`set_ghost_slot`), which is what a server-side recipe viewer uses to plant a
sample, is a **second** issue and is version-dependent (see the last section).

## Evidence per line

`JAR` means the released mod file was disassembled with `javap` and the instructions were read directly.
`source` means the branch source was fetched and the handler body was read. `runtime` means a dedicated
server was started and the numbers were measured.

| MC line | Latest Core inspected | Evidence | Result |
| --- | --- | --- | --- |
| 1.18.2 | `1.18.2-0.6.4.604` | JAR | `getSlot(id)` → `quickMoveStack(id)`, no bounds check, no `mayPickup`, no `IFilterSlot` |
| 1.19.2 | `1.19.2-0.6.4.730` | JAR | same |
| 1.20.1 | `1.20.1-1.5.17.2435` | JAR | same |
| 1.20.4 | no release on Modrinth | source | same (class is `TransferFullSlotPacket`) |
| **1.21.1** | **`1.21.1-1.5.19.2434`** | **runtime + JAR** | **VULNERABLE measured: `gain=1`, `repeat5=5`, `slotId=-1` throws** |
| 1.21.11 | `1.21.11-1.5.2.2348` | JAR | same as 1.21.1 |
| 1.21.4 | `1.21.4-1.5.1.2350` | source | handler body has no bounds / `mayPickup` / `IFilterSlot` |
| 1.21.5 | `1.21.5-1.5.2.2345` | source | same |
| 1.21.8 | `1.21.8-1.5.1.2344` | source | same |
| 1.21.10 | `1.21.10-1.5.1.2351` | source | same |
| 26.1 | `26.1-1.4.26.1688` | source | same |
| 26.2 | `26.2-1.5.18.2438` | source | same |
| 26.3 | `26.3-1.5.20.2436` | source | same |

Reproduce the source check: on any of those branches, open
`src/main/java/net/p3pp3rf1y/sophisticatedcore/network/TransferFullSlot*.java` and look for `mayPickup`,
`IFilterSlot` or a `getTotalSlotsNumber()` bounds test before the `quickMoveStack` call. There are none.

## Builds that were disassembled (so the claims can be tied to exact bytes)

| File | SHA-256 |
| --- | --- |
| `sophisticatedcore-1.18.2-0.6.4.604.jar` | `25a32678b13b06c1b1aa0ed9e9e244eb1b33d5095989758684e51dc014125627` |
| `sophisticatedcore-1.19.2-0.6.4.730.jar` | `0de1a4c730674ad9f27611fa17b866f7ca5dca15841854d64867693c2a5862fe` |
| `sophisticatedcore-1.20.1-1.5.17.2435.jar` | `943847f6b3b8d6038f0ae22291dd303bd2604c974a1830a664eb01ad0f8299ef` |
| `sophisticatedcore-1.21.1-1.5.19.2434.jar` | `fe544662d105d9791c8a1db75f297351957d3e702546154f1493cc9869d4f2af` |
| `sophisticatedcore-1.21.11-1.5.2.2348.jar` | `e296e4657fdc2f578a2f5e73c8ae55c0d101755b7a165c783cfae2782a5cb55a` |

Runtime pair on 1.21.1:

| File | SHA-256 |
| --- | --- |
| `sophisticatedcore-1.21.1-1.5.19.2434.jar` | `fe544662d105d9791c8a1db75f297351957d3e702546154f1493cc9869d4f2af` |
| `sophisticatedbackpacks-1.21.1-3.26.17.2232.jar` | `0adf5d847a8956142f10608dc724939482dad3bde62d078ba51a1af73819ae2d` |

Each of these five Core JARs was downloaded from Modrinth and its published SHA-512 matched before it was
opened.

## Why the JAR checks are not just "the source says so"

The released JARs are the thing servers actually run. For 1.21.1 the handler body is 113 bytecode lines:
`getSlot` (offset 6), then `quickMoveStack` (offset 47), and **no comparison instruction** anywhere in the
method (`if_icmp` count 0, `iflt`/`ifge` count 0). `quickMoveStack` itself is 113 lines with zero calls to
`mayPickup` and zero `IFilterSlot` checks. The filter slot class returns `false` from `mayPickup` in every
one of the five JARs (`iconst_0; ireturn`).

## Secondary issue: the ghost-slot write

`set_ghost_slot` is how a recipe viewer plants a sample. On some builds it is a bare `Slot.set`:

| Build | Handler |
| --- | --- |
| 1.18.2 released JAR | `getSlot(n).set(stack)` after only a menu-type check |
| 1.19.2 released JAR | same |
| 1.20.1 and later released JARs | validated `setGhostSlot(n, stack)` |
| 1.21.1 Core `1.4.18.1629` | bare `Slot.set` (fixed later in the 1.5.x line) |

So the secondary issue affects 1.18.2, 1.19.2, and 1.21.1 builds up to `1.4.18`; from 1.20.1 onward and from
Core `1.5.x` onward the write is validated. It only applies when the server also registers a recipe-viewer
ghost-slot message.

## What this page does not claim

- It does not claim a runtime reproduction outside 1.21.1. Only 1.21.1 was run.
- It does not claim a stock client can trigger any of this. It cannot.
- It does not claim anything about a specific server, player, or the maintainer's intent.
- It does not claim Backpacks itself is the defect. Backpacks supplies the menu and the filter slots; the
  unvalidated handler is in Core.

## Patching

The two-condition guard used in the upstream PR closes the transfer path and was measured to leave normal
storage transfers and crafting slots untouched. `backpack-transfer-fix` 1.2.0 implements the same guard as an
out-of-tree mixin for the 1.21.1 line, and also covers the bare ghost-slot write for older Core builds on that
line. Other lines use different class names (`TransferFullSlotMessage`, `TransferFullSlotPacket`) and need
their own build; do not install the 1.21.1 build on a 1.20.1 or older server.
