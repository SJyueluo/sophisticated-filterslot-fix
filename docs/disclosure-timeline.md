# Disclosure timeline

All times UTC. Every line below is either a public link or a result recorded in `docs/evidence/`.

## 2026-10-26

| Time | Event |
| --- | --- |
| 20:31 | Issue [P3pp3rF1y/SophisticatedCore#517](https://github.com/P3pp3rF1y/SophisticatedCore/issues/517) opened with the code path, pinned commit, shipped JAR hashes and the local diagnostic results. |
| 20:35 | Maintainer reply: *"Could you tell me what whole slot transfer is? How do I recreate this issue in game?"* |
| 20:39 | Answer: the gesture is Ctrl+Shift+click, it requires `StorageInventorySlot`, and **"I do not currently have a reproduction through the unmodified client GUI"** — stated explicitly. |
| 20:41 | Reply: *"I don't want an answer from codex or claude. I would like an answer from a player ... Otherwise I am going to just close."* |
| 21:42 | Pull request [#518](https://github.com/P3pp3rF1y/SophisticatedCore/pull/518) opened: two guards, bounds check plus `IFilterSlot` / `mayPickup`. |
| 21:44 | Maintainer comment on the PR: no steps, and *"not even running build let alone not testing is a huge red flag"*. |
| 21:51 | CLA assistant: status pending. The CLA has not been signed and the PR is therefore not mergeable. |
| 21:55 | Reply with in-game steps, the observed transfer (`storage 0 -> 1`, held stack unchanged), and the exact build failure (`reliquary` resolves from a private GitHub Packages registry and needs `GITHUB_ACTOR` / `GITHUB_TOKEN`). |
| 21:56 | Maintainer: *"How do you do that as a player in game?"* |
| 21:57 | Answer: *"You can't — not with a stock client, and that is the honest answer"*, plus a three-line debug patch so the server side can be observed in two minutes, plus a request for a scope decision. |
| 22:30 | `compileJava` and `jar` run on the branch; the built JAR is tested on a dedicated server: `tier1 BLOCKED`, `gain=0 repeat5=0`, regression `moved=3`. Posted to the PR. |

## 2026-10-27

| Time | Event |
| --- | --- |
| 08:00 | The `getSlotUpgradeContainer(...)` condition is withdrawn: it would have blocked crafting ingredient and result slots. Only `IFilterSlot` is rejected now. |
| 08:05 | Maintainer commits an unrelated fix (`linked backup settings ... render state`). |
| 08:07 | Crafting quick-move regression posted: released `1.5.18` and the patched build both move 1 ingredient and 4 result items — identical. |
| 08:46 | Release `1.21.1-1.5.19.2434` published. Changelog: the render-state fix only. |
| 17:12 | New issue #519 opened by someone else. |
| 17:54 | Maintainer comments on #519 and labels it. |
| 18:28 | Maintainer comments and relabels #519 again. |

## 2026-10-28

| Time | Event |
| --- | --- |
| 03:41 | Status check: #517 and #518 unchanged, no review, no inline comments, no reply. Last maintainer statement in either thread is still the 2026-10-26 21:56 question. |
| 03:41 | Release `1.21.1-1.5.19.2434` downloaded from Modrinth, SHA-512 verified against the published hash, and tested: **still vulnerable** — `tier1 gain=1 repeat5=5`, `slotId=-1` still throws `IndexOutOfBoundsException`. Bytecode of `handlePayload` is unchanged (`getSlot` → `quickMoveStack`, no `mayPickup`). Record: `docs/evidence/r-1519-vulnerable.json`. |

## Where it stands

- The defect is present in every released build tested: `1.4.18.1629`, `1.5.18.2431`, `1.5.19.2434`.
- A patch exists as PR #518. It is unresolved and blocked on an unsigned CLA.
- The one question that would settle this is unanswered: **is a modified client in scope?**
  - If no: the report is out of scope and the issue can be closed on those grounds.
  - If yes: the two-line guard in #518 closes it, and it is already verified to leave normal transfers and crafting intact.

No claim is made here about intent, about any specific server, or about any player. This page is a record of what was reported, what was answered, and what was measured.
