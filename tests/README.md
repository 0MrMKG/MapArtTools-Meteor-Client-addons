# Map-art supply planner regression checks

Run from the project directory with Java 21 on PATH:

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtSupplyPlanner.java tests/java/com/buildertoolbox/utils/MapArtSupplyPlannerTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtSupplyPlannerTest
```

These scenarios cover existing inventory, whole-stack space requirements, current-band priority, per-color slot reservation, unavailable supplies, and a full backpack. They do not simulate Minecraft movement, network responses, or block placement.

Band geometry checks (safe maximum width, continuous snake turns, negative coordinates, short final bands and restart corners):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtBandGeometry.java tests/java/com/buildertoolbox/utils/MapArtBandGeometryTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtBandGeometryTest
```

Route/stock integration check (restart at the far corner, partial completion, five-row demand and existing inventory):

```powershell
javac -encoding UTF-8 -d build/planner-tests src/main/java/com/buildertoolbox/utils/MapArtBandGeometry.java src/main/java/com/buildertoolbox/utils/MapArtSupplyPlanner.java tests/java/com/buildertoolbox/utils/MapArtSnakeSupplyTest.java
java -cp build/planner-tests com.buildertoolbox.utils.MapArtSnakeSupplyTest
```
