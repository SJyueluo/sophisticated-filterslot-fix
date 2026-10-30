# Security note: filter samples can be merged into real storage

Status as of 2026-10-30: **fixed upstream on some lines.** The maintainer committed `cf8aa31`
("Fixed forged requests bypassing inventory access restrictions") on 2026-10-29, released it, and closed
[#517](https://github.com/P3pp3rF1y/SophisticatedCore/issues/517) on 2026-10-30. Our check tool reports
**FIXED** on `1.21.1-1.5.21.2439` (`tier1 BLOCKED`, `gain=0`, `slotId=-1` no longer throws, normal transfer
still `moved=3`).

| Status | Lines |
| --- | --- |
| fixed in the 2026-10-30 releases | 1.21.1 `1.5.21.2439`, 1.20.1 `1.5.19.2441`, 26.1.2 `1.5.22.2442`, 26.2 `1.5.20.2440`, 26.3 `1.5.22.2443` |
| **not fixed** | 1.21.4 / 1.21.5 / 1.21.8 / 1.21.10 / 1.21.11 (latest releases 2026-09-27) and 1.18.2 / 1.19.2 (latest releases 2024-11-17) |

## Two separate issues — do not read a fix for one as a fix for the other

| # | Channel | Present in | Fixed in |
| --- | --- | --- | --- |
| 1 | whole-slot transfer: `transfer_full_slot` with a filter-slot index, source not validated | every line, 1.18.2 → 26.3 | the 2026-10-30 releases above; **not** in 1.18.2, 1.19.2, 1.21.4–1.21.11 |
| 2 | ghost-slot write: `set_ghost_slot` writes the client stack into a slot | 1.18.2, 1.19.2, 1.21.1 ≤ `1.4.18` | 1.20.1+ (validated `setGhostSlot`), 1.21.1 from `1.5.x`; **not** in 1.18.2 / 1.19.2 |

Worked example: **1.20.1 before 2026-10-30** validated the ghost-slot write (channel 2 fixed) while its transfer
handler still took the slot id from the client (channel 1 not fixed). Checking only the ghost-slot handler
produced the wrong conclusion that 1.20.1 was unaffected — the missing half only shipped in `1.5.19.2441`.

## Affected

Verified by running the published JARs on a dedicated server:

| Build | Result |
| --- | --- |
| Core `1.5.18.2431` / Backpacks `3.26.15.2227` | filter sample transferable into real storage |
| Core `1.5.19.2434` (released 2026-10-27) / Backpacks `3.26.15.2227` | **still transferable** - `gain=1 repeat5=5`, `slotId=-1` still throws |
| Core `1.4.18.1629` / Backpacks `3.25.36.1629` | same transfer path; additionally the ghost-slot message writes any menu slot with a bare `Slot.set` |

## Impact

- **Duplication:** an item the player already owns can be turned into an extra real item, repeatable, and it survives a restart (`i-dirt-unfixed.json`).
- **Creation:** if the server also runs a recipe viewer, an item the player never owned can be created the same way (`g-new-jei-unfixed.json`, `f-old-cf-jei.json`).

## Preconditions

1. A storage/backpack menu that exposes a filter (ghost) slot — pickup, filter, magnet, void or feeding upgrades.
2. A client able to send `sophisticatedcore:transfer_full_slot` with that filter slot's index.
3. A server without the source-slot guard.

**A stock client cannot do step 2.** Both send sites in `StorageScreenBase` restrict the slot
(`instanceof StorageInventorySlot`, `mayPickup`). This is a modified-client path, not something a normal
player can reach through the GUI.

## Workaround

Apply the guard from [`docs/defect-and-fix.md`](docs/defect-and-fix.md), or use the server-side mod in
`backpack-transfer-fix/`. With it applied: sample transfer reports 0 gain, and normal whole-slot moves
between storage, player inventory and upgrade slots still work (`j-dirt-fixed.json`, `l-packetpath-fixed.json`).

## Detection

- Backpack storage containing items that were only ever placed as filter samples.
- On affected builds the sample slot is cleared when the transfer happens, so a filter that "loses" its
  sample without the item being deleted is the visible trace.
- Vanilla does not log payload contents; catching this in the act needs a mod that logs
  `transfer_full_slot` with `slotId >= menu.getInventorySlotsSize()`.

## Disclosure

Public issue and PR from the start, no private channel was used and no CVE was requested. No claim is made
about any specific server, player, or the maintainer's intent.

## Servers pinned to 1.4.x (addon reasons)

Some addons still require an older Core, so upgrading is not an option for every server. On 1.4.x the
recipe-viewer ghost-slot message is a bare write:

`java
if (context.player().containerMenu instanceof StorageContainerMenuBase) {
    context.player().containerMenu.getSlot(payload.slotNumber).set(payload.stack);
}
`

ackpack-transfer-fix **1.2.0** adds a SetGhostSlotPayload guard for exactly this case: the write is
only allowed when the target slot really is an active IFilterSlot the stack may be placed into.
Measured by calling the message directly against a live menu:

| Core | fix mod | real storage slot | real filter slot |
| --- | --- | --- | --- |
| 1.4.18 | none | **64 dirt written (vulnerable)** | 1x diamond (normal) |
| 1.4.18 | 1.2.0 | 0 (blocked) | 1x diamond (still works) |
| 1.5.18 | none | 0 (blocked by the core) | rejected by the core's own tab check |

So a 1.4.x server can keep its Core version and still close this path. Raw record:
docs/evidence/q-ghost-slot-old-core.json.

## Scope

The handler exists on every published Core line; runtime reproduction was done on 1.21.1 only. Per-line evidence and exact JAR hashes: [`docs/impact-scope.md`](docs/impact-scope.md).
