# Map-art supply planner regression checks

Full-auto batch regression (no Minecraft runtime):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/fullauto/BatchFiles.java src/main/java/com/buildertoolbox/fullauto/BatchJournal.java src/main/java/com/buildertoolbox/fullauto/BatchWorkflowRules.java src/main/java/com/buildertoolbox/fullauto/MapArtBandGeometry.java src/main/java/com/buildertoolbox/fullauto/BatchInspection.java tests/java/com/buildertoolbox/fullauto/BatchWorkflowTest.java
java -cp build/planner-tests com.buildertoolbox.fullauto.BatchWorkflowTest
```

Covers natural/direct-folder scanning, fingerprints/path boundaries, native negative offsets, map alignment, 49/50 error boundary, exactly two dispenser edges with 80s of lever waits plus a 10s post-clean buffer, full physical-inspection route coverage at widths 1-5 in both directions, unique missing/wrong cell counters, ambiguous lever recovery, distinct raw/locked map IDs, every checkpoint phase, deposit recovery and two-file commit ordering. It does not simulate Litematica decoding, Minecraft packet timing, actual movement, dispenser machinery or live cartography. In-game acceptance must additionally verify invalid/multilayer files cause no lever interaction, both cleanup pulses followed by a timed buffer (no post-clean residual scan), walking the complete inspection route without placing and reporting missing/wrong totals, exact-ID material transfers from mixed boxes, center hold for 2s, locked result/changed ID, full product storage, background/Esc execution and restarting at every lever/lock/deposit boundary. Check the original worker/printer hashes before and after any full-auto change.

Run from the project directory with Java 21 on PATH:

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

Pre-restock inventory merge checks (overflow, component separation, stack limits, item conservation and 500 randomized inventories):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/InventoryMergePlanner.java tests/java/com/buildertoolbox/utils/InventoryMergePlannerTest.java
java -cp build/planner-tests com.buildertoolbox.utils.InventoryMergePlannerTest
```

The merge test checks planning only, not live container clicks or server synchronization. In-game checks should include merging `40 + 40 + 40` into `64 + 56`, a full inventory with partial stacks, Esc/background execution, and stopping the module during the synchronization wait.

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
