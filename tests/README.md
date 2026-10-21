# Map-art supply planner regression checks

## Full Mode accuracy, wide inspection and outward/return supplies

The suite now contains 23 test groups. `CanvasRepairStrategyTest` checks bounded full-canvas perception (1024 cells per tick), 32-wide fallback routes, unknown-cell handling, sparse missing-cell selection, server revision/pending-placement completion gates and rewinding stale print cursors. `RoundTripSupplyTest` checks combined two-band demand, subtracting shared inventory once, return-only color reservation before large outward patches, capacity limits, no farther-band quotas, unavailable colors, final single bands and worker integration guards. These are pure policy/source tests, not a live server simulation.

Full Mode live acceptance for this update:

- Delay/reject the first and second bands' placements; verify prediction cannot mark them complete, the band closing checks retry safe stands, and missing cells stay in the matrix.
- Give only some colors for a pair. Verify it works both legs with available stock before returning to supplies, reserves return-leg colors, and revisits deferred pair holes after restocking. Test a full bag, insufficient full-stack capacity, empty/alternate same-color boxes, reversed order and the final unpaired band. Wrong occupied cells must not consume material quotas.
- Resume at a late saved cursor with holes in an earlier band, including a band absent from the saved route. Verify the wide prior-band patrol and actual-world route reconstruction, without cleanup pulses or trusting cached completion.
- With a fully loaded 128x128 canvas, inspection should scan in bounded batches and approach defects rather than walk a dense full snake. Lower view distance to force coarse loading travel; unread cells must never pass. Inject a server block update during the last scan and verify acceptance waits for a fresh scan.
- At carpet edges, verify ordinary and workstation travel release movement and try adjacent supported ground, without sprint/jump. Check disconnect, background use and supply-return restoration.

The broad-scan behavior above replaces older descriptions below of mandatory complete dense repair-route traversal. Failed cells may receive up to three distinct-stand visits per pass; wrong blocks remain untouched. Existing journals retain compatibility, but offline observations are invalidated and old repair paths migrate to the wide scanner.

All standalone checks are now part of `gradlew build` / `gradlew check`. Run `gradlew regressionTest` for the verification suite alone. New checks cover movement ownership, server placement receipts, organizer intent/commit recovery, low-view-distance inspection continuation and empty-map hotbar swap recovery. They verify pure policies and integration guards; actual network timing, GUI transitions and movement still require in-game testing.

Menu registration checks cover exactly five user-facing modules after removing the standalone schematic printer. The two map-art display titles are `Map Art Maker（Once）` and `Map Art Maker（Full Mode）`, while persistent names remain unchanged for existing profiles. The ordinary worker owns an unregistered placement-engine instance; start/stop invalidates external rotation tokens, and no module-registry lookup is required. Live acceptance must open Meteor with an existing profile, verify the five-item order/titles and retained settings/keybinds, run both map-art modes separately, and stop during a queued placement to confirm no late click occurs.

The category order is Once, Full Mode, Underfoot Scaffold, Chest Organizer, Auto Craft. `ModulesMixin` reapplies only the Builder Toolbox list after Meteor's `sortModules` alphabetical pass; registration order alone cannot guarantee the visible order. Verify normal startup and a later Meteor sort both preserve this order without changing other categories.

Cursor recovery and inspection repair live acceptance: test a partially held stack after an interrupted transfer, an occupied/full original slot, a full backpack with one compatible partial stack, a fully blocked backpack, Esc/background use and manual unrelated containers. Valid cursor contents must be returned without dropping/swapping unrelated items, followed by automatic continuation rather than PAUSED. Full-auto material/map hand selection uses SWAP. During repair sweeps, nearby air/replaceable cells must be filled through the normal placement engine, wrong blocks left untouched and red-marked, rejected positions tried at most once per pass, and the final count refreshed from the world. Only actual missing cells count against Full Mode's Allowed Missing Blocks setting; map creation requires missing <= the current value and zero unread cells, as specified below. Failed checks continue alternating repair/check sweeps without a fixed maximum. These cases require a live server; source/pure-matrix checks do not simulate packet rejection or delayed slot updates.

Cursor-stall regressions additionally cover safe offhand policies (carpets only in the player handler), whole-stack return to empty owned storage slots, partial-merge progress versus identical server rollback, and the exact request/type/sync-ID/receipt conditions for claiming a pending container. The space planner requires enough known missing cells to clear a non-compatible hotbar stack; three missing positions cannot justify spending a 64-stack. Live checks must inject a cursor stack while walking, fill all main slots, test empty and occupied offhand, reject a return click on the server, use a supply box behind Esc, and stop/restart while an own screen has a cursor or cartography inputs. Verify travel to a safe placement stand and return to the original work position without inserting a sweep solely for cursor recovery; an ARRIVED stand that is still out of hand range must be rejected/replaced rather than retried forever. No full-auto placement may send a close-screen packet, and critical map/glass materials must not be moved offhand or unloaded as generic carpets. These are safety policies/source guards; delayed real-server corrections still require in-game testing.

Water during canvas-cleaning lever operations:

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/fullauto/LeverWaterPolicy.java src/main/java/com/buildertoolbox/fullauto/GroundPathVisibility.java tests/java/com/buildertoolbox/fullauto/LeverWaterPolicyTest.java
java -cp build/planner-tests com.buildertoolbox.fullauto.LeverWaterPolicyTest
```

Checks lever-only water travel over supported floors, shallow buoyancy arrival, unchanged precise intermediate corners, movement drift tolerance and rejection of holes/lava. In-game verification must flood the player's position during both cleaning pulses: the lever must remain usable under current while each interaction still requires a current in-range ray hit and server-confirmed powered state. Exactly two rising edges must occur. Ordinary storage/cartography travel must retain its dry-floor rules.

Chat timing (both independent map-art modules):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtProgressTimer.java src/main/java/com/buildertoolbox/fullauto/MapArtProgressTimer.java tests/java/com/buildertoolbox/utils/MapArtProgressTimerTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtProgressTimerTest
```

Checks real-time elapsed formatting, already-built baseline exclusion, supply/walk delays, stalled progress, rollbacks, changed demand, new painting estimates and independent worker parity. The ordinary module reports this activation's elapsed time; full-auto reports durable cumulative batch time, excluding offline time. ETA covers remaining placement on the current painting, uses recent confirmed matrix progress, and excludes final inspection/cartography/deposit. In-game check: background/Esc, auto-eating and low TPS must not slow the real-time clock or the ten-second heartbeat.

Navigation recovery and movement-only supply return:

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtNavigationPolicy.java src/main/java/com/buildertoolbox/fullauto/MapArtNavigationPolicy.java tests/java/com/buildertoolbox/utils/MapArtNavigationPolicyTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtNavigationPolicyTest
```

Checks half-block endpoint tolerance without relaxing intermediate corners, vertical/ground/obstacle safety, absence of return-trip placement, separate failed-box cooldowns, stocked-band continuation, repeat-sweep behavior and bounded carpet-lip stand recovery. Live acceptance must verify a 0.5-block endpoint offset, a narrow chest interaction face, repeated inaccessible/empty same-color boxes, full backpack with future-band colors, unchanged material counts during chest return and completion after multiple repair sweeps. Logical/source checks do not simulate live movement or server acknowledgements.

Auto-eat policy and integration source checks:

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/AutoEatPolicy.java src/main/java/com/buildertoolbox/fullauto/AutoEatPolicy.java tests/java/com/buildertoolbox/utils/AutoEatPolicyTest.java
java -cp build/planner-tests com.buildertoolbox.utils.AutoEatPolicyTest
```

Checks the strict 8-point start boundary, eating past the threshold until 20 points, disabled/creative behavior, independent default-on settings, full-inventory search, cursor-free SWAP selection and bounded failure cooldown. Live checks must include hotbar-only and inventory-only food with a full hotbar, multiple food stacks, restocking while hunger drops, Esc/background use, empty food supplies, rejected item use and disabling/disconnecting mid-bite. Verify food never opens a targeted dispenser and the snake cursor/work return survives the pause.

Full-auto batch regression (no Minecraft runtime):

Full Mode's **Allowed Missing Blocks** defaults to **0**, ranges from **0 to 16384**, and is inclusive: missing <= allowed. First print, then run a reverse sweep repairing and checking together; if the threshold is not met or unread cells remain, continue forward/reverse repair checks until it passes. There is no fixed maximum, no forced third-sweep completion and no extra inspection-only walk or artificial retry wait. Tests cover zero allowed (0 pass, 1 fail), a nonzero boundary (N pass, N+1 fail), ignored WRONG counts, non-air blueprint targets with actual air/replaceable occupants (including water/grass), excluded colors, unread cells, pass numbers beyond three, persisted pass direction, legacy journals and resetting the pass for the next picture. Live acceptance must verify alternating reversals and supply attempts on every pass, no band-boundary all-canvas freezes, separate missing/wrong/unread counts, and map creation only after the missing threshold passes with no unread cells. Change the setting during a run and after a pause: it must take effect on the next check without Reset Batch, canvas cleanup or a configuration-signature mismatch; already-started map-material/activation/cartography/deposit stages must not roll back. Once still requires exact completion. Repair replenishment must return to the repair route, try each selected color at most once per pass, keep two map-material slots free and yield when supplies/space are unavailable. Placement/server confirmation, safe standing and required water/map waits are retained; no runtime test here simulates them.

Explicit directory validation now reinitializes the batch even for an identical directory/queue. Regression checks cover repeated archive retention, an invalid archive destination preserving the live checkpoint, and unconfirmed map-transaction reset guards. Live acceptance: validate the same directory twice after partially printing; each successful validation must leave the module stopped and the next enable must start from picture 1, while keeping locations/material registrations. Failed/empty checks and stale asynchronous callbacks must preserve progress. Ordinary disable/re-enable without validation must still resume. Reinitialization itself must not click the lever.

Restart coverage additionally checks opening each checkpoint with a fresh journal instance, remaining wait time, cumulative runtime, restocking markers, inspection cursor/direction/matrix, invalidation of unloaded saved observations, and compatibility with older journals/checkpoints. Live acceptance still requires closing/reopening Minecraft during a supply trip, midway through inspection, and midway through a lever wait. Verify no repeat cleanup edge or item click, no premature supply return, and no offline time added to batch runtime. Forced termination may resume from the latest periodic checkpoint rather than the exact last frame.

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/fullauto/BatchFiles.java src/main/java/com/buildertoolbox/fullauto/BatchPrintCheckpoint.java src/main/java/com/buildertoolbox/fullauto/BatchJournal.java src/main/java/com/buildertoolbox/fullauto/BatchWorkflowRules.java src/main/java/com/buildertoolbox/fullauto/MapArtBandGeometry.java src/main/java/com/buildertoolbox/fullauto/BatchInspection.java tests/java/com/buildertoolbox/fullauto/BatchWorkflowTest.java
java -cp build/planner-tests com.buildertoolbox.fullauto.BatchWorkflowTest
```

Covers natural/direct-folder scanning, fingerprints/path boundaries, native negative offsets, map alignment, configurable inclusive missing-count boundaries and WRONG exclusion, exactly two dispenser edges with 80s of lever waits plus a 10s post-clean buffer, full physical-inspection route coverage at widths 1-5 in both directions, unique missing/wrong cell counters, ambiguous lever recovery, distinct raw/locked map IDs, every checkpoint phase, deposit recovery and two-file commit ordering. It does not simulate Litematica decoding, Minecraft packet timing, actual movement, dispenser machinery or live cartography. In-game acceptance must additionally verify invalid/multilayer files cause no lever interaction, both cleanup pulses followed by a timed buffer (no post-clean residual scan), walking the complete repair/check route while filling reachable air/replaceable targets, reporting missing/wrong/unread totals and repeating until the current missing threshold is met, exact-ID material transfers from mixed boxes, center hold for 2s, locked result/changed ID, full product storage, background/Esc execution and restarting at every lever/lock/deposit boundary. Check the original worker/printer hashes before and after any full-auto change.

Run from the project directory with Java 21 on PATH:

Full-auto snake continuation is always enabled: the full route/cursor, fixed start, band width/direction, verification pass and work return position must survive a disk restart. Test restarting mid-band, at a turn and during a color-supply trip. Re-enabling the module must rescan actual blocks and continue without cleanup. No saved movement key, pending placement or container click should be replayed. The regression suite checks checkpoint serialization and corrupt-cursor rejection; actual restart/navigation still needs in-game acceptance.

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtSupplyPlanner.java tests/java/com/buildertoolbox/utils/MapArtSupplyPlannerTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtSupplyPlannerTest
```

These scenarios cover existing inventory, whole-stack space requirements, current-band priority, per-color slot reservation, unavailable supplies, and a full backpack. They do not simulate Minecraft movement, network responses, or block placement.

Band geometry checks (safe maximum width, continuous snake turns, negative coordinates, short final bands, restart corners and exact reverse traversal for odd/even/sparse bands):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtBandGeometry.java tests/java/com/buildertoolbox/utils/MapArtBandGeometryTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtBandGeometryTest
```

Route/stock integration check (restart at the far corner, partial completion, five-row demand, existing inventory and opposite first-band loading for reverse traversal):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtBandGeometry.java src/main/java/com/buildertoolbox/utils/MapArtSupplyPlanner.java tests/java/com/buildertoolbox/utils/MapArtSnakeSupplyTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtSnakeSupplyTest
```

Legacy inventory merge planner checks (the planner is no longer used by either map-art module):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/InventoryMergePlanner.java tests/java/com/buildertoolbox/utils/InventoryMergePlannerTest.java
java -cp build/planner-tests com.buildertoolbox.utils.InventoryMergePlannerTest
```

The legacy merge test checks only arithmetic. To guard the removal of automatic sorting from both map-art workers, run:

```powershell
javac -encoding UTF-8 -d build/planner-tests tests/java/com/buildertoolbox/utils/MapArtRestockSourceTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtRestockSourceTest
```

This source guard checks that compaction states, merge calls and cursor-pickup clicks are absent from both workers, while direct restock planning, actual-space checks and whole-stack retrieval remain. In-game acceptance still needs both modules restocking in the background, with partial stacks and a full inventory, without rearranging player inventory or leaving an item on the cursor. Any preexisting cursor item should be put back manually before testing.

Supply travel checks (diagonal shortcuts, full player footprint, corner obstacles, holes and carpet edges):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/GroundPathVisibility.java tests/java/com/buildertoolbox/utils/GroundPathVisibilityTest.java
java -cp build/planner-tests com.buildertoolbox.utils.GroundPathVisibilityTest
```

These use simulated terrain; actual steering and dynamic obstacle avoidance still require in-game testing.

Color exclusion checks (all 16 dye colors, independent light/dark colors, master switch and similarly named non-dyed blocks):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtColorFilter.java tests/java/com/buildertoolbox/utils/MapArtColorFilterTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtColorFilterTest
```

These verify block-ID filtering; the swatches, checkbox persistence and live task refresh also need an in-game UI check.

Automatic crafting accounting checks (bone expansion, nine-slot powder distribution, repeated batches, leftovers and rejected conversions):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/CraftingBatchPlan.java tests/java/com/buildertoolbox/utils/CraftingBatchPlanTest.java
java -cp build/planner-tests com.buildertoolbox.utils.CraftingBatchPlanTest
```

These check arithmetic, not server crafting. In-game checks: register a chest/barrel/shulker and a table; run 64 bones (21 blocks + 3 powder), then 192 bones (64 blocks). Verify multiple sources each receive their own results, split powder stacks are consumed, unrelated chest contents stay put, full storage pauses with products retained, and stopping with a populated crafting grid returns its ingredients normally. A storage inventory is not scanned before its initial full server inventory packet arrives. Keep four main-inventory slots free before fetching bones; move any preexisting bones, powder and blocks out before starting a new job.
