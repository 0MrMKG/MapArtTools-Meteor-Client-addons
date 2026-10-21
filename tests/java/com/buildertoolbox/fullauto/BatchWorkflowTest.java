package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.buildertoolbox.fullauto.BatchJournal.Phase;

/** Queue, native offsets, lever intents, commit recovery and disk restart tests. */
public final class BatchWorkflowTest {
    public static void main(String[] args) throws Exception {
        equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(1,0,0,0),"zero missing satisfies the default strict limit");
        equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(1,1,0,0),"one missing cell cannot satisfy the default zero limit");
        equal(-127,BatchFiles.regionMinimum(0,-128),"negative native region size keeps its origin");
        equal(0,BatchFiles.regionMinimum(0,128),"positive native region size");
        equal(-96,BatchFiles.regionMinimum(-96,128),"nonzero native region offset");
        check(BatchFiles.aligned(-64,-64)&&BatchFiles.aligned(64,-192),"positive and negative map grids");
        check(!BatchFiles.aligned(0,0)&&!BatchFiles.aligned(-63,-64),"do not silently shift a misaligned anchor");

        int risingEdges=0,actions=0,waits=0;boolean powered=false;
        for(Phase phase=Phase.CLEAR_ON_1;phase!=Phase.CLEAR_BUFFER;phase=BatchWorkflowRules.nextLeverPhase(phase)) {
            boolean desired=BatchWorkflowRules.leverPowered(phase);
            if(BatchWorkflowRules.leverWait(phase)){waits++;continue;}
            equal(BatchWorkflowRules.LeverRecovery.EXECUTE,BatchWorkflowRules.leverRecovery(false,desired,powered),"fresh action");
            // A crash after the click but before saving the confirmation must
            // acknowledge the already-changed block, not click a second time.
            equal(BatchWorkflowRules.LeverRecovery.CONFIRMED,BatchWorkflowRules.leverRecovery(true,desired,desired),"restart after confirmed physical edge");
            equal(BatchWorkflowRules.LeverRecovery.AMBIGUOUS,BatchWorkflowRules.leverRecovery(true,desired,!desired),"unconfirmed crash pauses");
            if(!powered&&desired)risingEdges++;
            powered=desired;actions++;
        }
        equal(2,risingEdges,"one dispense pulse and one collection pulse only");
        equal(4,actions,"exactly two on/off pairs");
        equal(80,waits*20,"four twenty-second waits");
        equal(10000L,BatchWorkflowRules.CLEAR_BUFFER_MILLIS,"ten-second post-clean buffer without a canvas check");
        equal(Phase.CLEAR_BUFFER,BatchWorkflowRules.nextLeverPhase(Phase.WAIT_OFF_2),"cleanup enters the buffer, not canvas validation");
        check(!powered,"leave the lever off");
        equal(BatchWorkflowRules.ProductRecovery.CONFIRMED,BatchWorkflowRules.productRecovery(0,1),"crash after deposit recognizes product");
        equal(BatchWorkflowRules.ProductRecovery.TRANSFER,BatchWorkflowRules.productRecovery(1,0),"resume deposit from bag");
        equal(BatchWorkflowRules.ProductRecovery.MISSING,BatchWorkflowRules.productRecovery(0,0),"lost map cannot advance");
        equal(BatchWorkflowRules.ProductRecovery.AMBIGUOUS,BatchWorkflowRules.productRecovery(1,1),"duplicate map pauses");
        inspectionChecks();
        inspectionClassificationChecks();
        inspectionOriginChecks();
        mapSwapChecks();
        resetTransactionChecks();
        repairPassChecks();
        drawingCheckpointChecks();

        Path base=Path.of("build");Files.createDirectories(base);
        Path temp=Files.createTempDirectory(base,"fullauto-regression-");
        try {
            Path folder=Files.createDirectory(temp.resolve("schematics"));
            Path pictures=Files.createDirectory(folder.resolve("pictures"));
            equal(List.of(),BatchFiles.inspectDirectory(pictures),"validation reports zero files without starting a batch");
            boolean emptyRejected=false;try{BatchFiles.scan(pictures);}catch(java.io.IOException expected){emptyRejected=true;}
            check(emptyRejected,"an empty directory still cannot start a batch");
            for(String name:List.of("map10.litematic","map2.litematic","map1.litematic","说明.litematic"))Files.writeString(pictures.resolve(name),name);
            Files.writeString(pictures.resolve("not-a-map.txt"),"ignore");
            Files.createDirectory(pictures.resolve("nested"));Files.writeString(pictures.resolve("nested/extra.litematic"),"ignore nested");
            var entries=BatchFiles.scan(BatchFiles.directory(folder,"pictures"));
            equal(List.of("map1.litematic","map2.litematic","map10.litematic","说明.litematic"),entries.stream().map(BatchFiles.Entry::name).toList(),"natural/direct-only queue");
            BatchFiles.validateQueue(pictures,entries);
            Files.writeString(pictures.resolve("map99.litematic"),"a later batch");
            BatchFiles.validateQueue(pictures,entries);
            String old=entries.getFirst().sha256();Files.writeString(pictures.resolve("map1.litematic"),"changed");
            check(!old.equals(BatchFiles.fingerprint(pictures.resolve("map1.litematic"))),"detect a changed blueprint before clearing");
            boolean changed=false;try{BatchFiles.validateQueue(pictures,entries);}catch(java.io.IOException expected){changed=true;}
            check(changed,"resuming validates every queued fingerprint before any interaction");
            boolean escaped=false;try{BatchFiles.directory(folder,"..");}catch(java.io.IOException expected){escaped=true;}
            check(escaped,"reject a path outside schematics");

            Path chineseFolder=Files.createDirectories(folder.resolve("地图画/风景 合集"));
            for(String name:List.of("风景10.litematic","风景2.litematic"))Files.writeString(chineseFolder.resolve(name),"中文蓝图测试："+name);
            Path chineseResolved=BatchFiles.directory(folder,"地图画/风景 合集");
            equal(chineseFolder.toRealPath(),chineseResolved,"Chinese nested paths with spaces are relative to schematics");
            equal(chineseResolved,BatchFiles.directory(folder,"地图画"+java.io.File.separator+"风景 合集"),"native path separators preserve Unicode");
            var chineseEntries=BatchFiles.scan(chineseResolved);
            equal(List.of("风景2.litematic","风景10.litematic"),chineseEntries.stream().map(BatchFiles.Entry::name).toList(),"Chinese filenames retain natural numeric order");
            BatchFiles.validateQueue(chineseResolved,chineseEntries);

            BatchJournal journal=new BatchJournal(temp.resolve("config/batch.properties"));
            BatchJournal.State s=new BatchJournal.State();s.world="test-server";s.dimension="minecraft:overworld";
            s.configuration="locations+filter";s.folder=chineseResolved.toString();s.queue=new ArrayList<>(chineseEntries);
            s.locations="canvas=-64,64,-64;lever=-65,64,-64;mapSupply=-66,64,-64;table=-67,64,-64;product=-68,64,-64";
            s.rawMap=12;s.lockedMap=37;s.mapSlot=8;s.glassSlot=20;s.privateMaps="1,2,3";s.initialGlass=15;
            s.mapSwapSource=23;s.mapSwapTarget=4;
            s.initialMinX=-64;s.initialMinZ=-192;s.initialY=64;s.materialInventoryBefore="inventory";
            s.drawing=drawingCheckpoint();
            BatchInspection savedInspection=new BatchInspection(4,true);
            savedInspection.observe(1,2,BatchInspection.Cell.MISSING);
            savedInspection.observe(2,2,BatchInspection.Cell.WRONG);
            s.inspection=savedInspection.checkpoint(7,-60,-180);
            s.elapsedMillis=1234567;s.waitRemainingMillis=7500;
            s.leverIssued=s.mapIssued=s.glassIssued=s.activationIssued=s.lockIssued=s.depositIssued=s.lockConfirmed=true;
            for(Phase phase:Phase.values()) {
                s.phase=phase;s.index=phase==Phase.DONE?s.queue.size():0;s.deadline=123456789;s.pause="暂停原因";
                s.repairPass=phase==Phase.REVIEW?37:0;
                journal.write(s);var restored=new BatchJournal(temp.resolve("config/batch.properties")).read();
                equal(phase,restored.phase,"every phase survives restart");equal(12,restored.rawMap,"raw map ID");equal(37,restored.lockedMap,"lock may create a NEW map ID");
                equal(s.repairPass,restored.repairPass,"many repair sweeps survive restart without resetting their count");
                equal(23,restored.mapSwapSource,"unconfirmed empty-map swap source survives a crash");
                equal(4,restored.mapSwapTarget,"unconfirmed empty-map swap target survives a crash");
                equal(s.queue,restored.queue,"fixed queue/fingerprints");
                equal(s.folder,restored.folder,"Chinese directory survives checkpoint restart");
                equal(s.drawing,restored.drawing,"snake cursor, fixed origin, route and work return survive a full disk restart");
                equal(s.inspection,restored.inspection,"inspection matrix, direction, waypoint and return position survive a fresh journal read");
                equal(1234567L,restored.elapsedMillis,"elapsed work time survives closing the client");
                equal(7500L,restored.waitRemainingMillis,"remaining wait does not restart at twenty seconds");
                equal(s.locations,restored.locations,"all five registered locations are checkpointed");
                equal(s.deadline,restored.deadline,"wait checkpoint");equal(s.pause,restored.pause,"Unicode status");
                check(restored.leverIssued&&restored.depositIssued&&restored.lockConfirmed,"operation intent/confirmation flags");
            }
            var legacyProperties=new java.util.Properties();
            try(var input=Files.newInputStream(temp.resolve("config/batch.properties"))){legacyProperties.load(input);}
            legacyProperties.remove("inspection");legacyProperties.remove("elapsedMillis");legacyProperties.remove("waitRemainingMillis");
            legacyProperties.remove("mapSwapSource");legacyProperties.remove("mapSwapTarget");
            legacyProperties.remove("repairPass");legacyProperties.setProperty("phase","REVIEW");legacyProperties.setProperty("index","0");
            try(var output=Files.newOutputStream(temp.resolve("config/batch.properties"))){legacyProperties.store(output,"legacy checkpoint");}
            var legacyState=journal.read();
            check(legacyState.inspection==null,"older journals have no inspection cursor");
            equal(0L,legacyState.elapsedMillis,"older journals start with unknown elapsed time at zero");
            equal(-1L,legacyState.waitRemainingMillis,"older journals use conservative wait fallback");
            equal(-1,legacyState.mapSwapSource,"older journals have no outstanding map swap");
            equal(1,legacyState.repairPass,"legacy review checkpoints resume as the second overall sweep");
            s.index=0;s.phase=Phase.STORE_PENDING;
            boolean advanced=false;try{s.nextPainting();advanced=true;}catch(IllegalStateException expected){}
            check(!advanced,"never advance before confirmed product commit");
            s.phase=Phase.NEXT;s.nextPainting();journal.write(s);
            var second=journal.read();equal(1,second.index,"resume second picture after first product commit");equal(Phase.LOAD,second.phase,"new file starts with validation");
            equal(-1,second.rawMap,"reset per-picture map ownership");check(!second.depositIssued,"no previous-product intent leaks");
            equal(-1,second.mapSwapSource,"new pictures cannot inherit a previous map swap intent");
            equal(-1,second.mapSwapTarget,"new pictures clear the old map swap destination");
            equal(0,second.repairPass,"a new picture starts with no completed repair sweeps");
            check(second.drawing==null,"a new painting cannot inherit the previous snake cursor");
            check(second.inspection==null,"a new painting cannot inherit previous inspection observations");
            equal(1234567L,second.elapsedMillis,"cumulative batch time survives the next painting");
            equal(-1L,second.waitRemainingMillis,"next painting clears the old waiting stage");
            second.phase=Phase.NEXT;second.nextPainting();journal.write(second);equal(Phase.DONE,journal.read().phase,"stop after final product commit");
            journal.archive();check(!journal.exists(),"reset archives rather than overwrites the old batch");
            check(Files.exists(temp.resolve("config/batch.properties.previous")),"recoverable previous checkpoint");
            archiveChecks(temp.resolve("archive-regression"),chineseEntries);
        } finally {
            try(var files=Files.walk(temp)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("BatchWorkflow: directory/queue, two cleanup pulses + 10s buffer, physical inspection/missing-only limits, repeat-until-complete, all-phase restart and two-picture commits passed.");
    }

    private static BatchPrintCheckpoint drawingCheckpoint() {
        return new BatchPrintCheckpoint(5,false,3,-63,-62,23,-57,1,List.of(
            new BatchPrintCheckpoint.Step(-64,-62,0,true),new BatchPrintCheckpoint.Step(63,-62,0,false),
            new BatchPrintCheckpoint.Step(63,-57,1,true),new BatchPrintCheckpoint.Step(-64,-57,1,false),
            new BatchPrintCheckpoint.Step(-65,-57,1,false)));
    }

    private static void drawingCheckpointChecks() {
        var checkpoint=drawingCheckpoint();
        equal(checkpoint,BatchPrintCheckpoint.decode(checkpoint.encode()),"mid-band and supply-return checkpoint round trip");
        var reverse=new BatchPrintCheckpoint(5,true,checkpoint.route().size(),-63,-62,23,-57,0,checkpoint.route());
        equal(reverse,BatchPrintCheckpoint.decode(reverse.encode()),"reverse and final endpoint cursor survive serialization");
        var repeated=new BatchPrintCheckpoint(5,false,3,-63,-62,23,-57,37,checkpoint.route());
        equal(repeated,BatchPrintCheckpoint.decode(repeated.encode()),"many repair sweeps survive restart without a one-pass cap");
        var restocking=new BatchPrintCheckpoint(5,false,3,-63,-62,23,-57,37,checkpoint.route(),true);
        equal(restocking,BatchPrintCheckpoint.decode(restocking.encode()),"supply phase survives a restart instead of prematurely returning to print");
        String legacy=checkpoint.encode().replaceFirst("2;", "1;");
        legacy=legacy.substring(0,legacy.lastIndexOf(';'));
        equal(checkpoint,BatchPrintCheckpoint.decode(legacy),"older route-only checkpoints remain readable");
        boolean invalid=false;
        try{BatchPrintCheckpoint.decode(checkpoint.encode().replace("2;5;0;3;","2;5;0;999;"));}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"corrupt cursors must not silently restart or walk arbitrary routes");
    }

    private static void inspectionChecks() {
        BatchInspection original=new BatchInspection(4,true);
        original.observe(1,2,BatchInspection.Cell.MISSING);
        original.observe(2,2,BatchInspection.Cell.WRONG);
        original.observe(3,2,BatchInspection.Cell.CORRECT);
        var saved=original.checkpoint(7,-60,-180);
        equal(saved,BatchInspection.Checkpoint.decode(saved.encode()),"inspection checkpoint round trip");
        String legacyInspection="1;"+saved.width()+";"+(saved.reverse()?1:0)+";"+saved.waypoint()+";"+saved.resumeX()+";"+saved.resumeZ()+";"+saved.cells();
        equal(saved,BatchInspection.Checkpoint.decode(legacyInspection),"legacy inspection checkpoints still restore with canonical route order");
        String version2="2;"+saved.width()+";"+(saved.reverse()?1:0)+";"+saved.waypoint()+";"+saved.resumeX()+";"+saved.resumeZ()+";"+saved.cells()+";"+saved.routeStart();
        equal(saved,BatchInspection.Checkpoint.decode(version2),"version-two inspection checkpoints use the legacy zero origin");
        var recovered=BatchInspection.restore(BatchInspection.Checkpoint.decode(saved.encode()));
        equal(original.route(),recovered.route(),"inspection preserves width and direction");
        equal(3,recovered.observed(),"restored observations");
        equal(2,recovered.errors(),"restored missing/wrong counts");
        recovered.invalidate(1,2);recovered.invalidate(1,2);
        equal(2,recovered.observed(),"unloaded saved cell becomes unknown only once");
        equal(0,recovered.missing(),"invalidated saved missing count is removed");
        recovered.observe(2,2,BatchInspection.Cell.CORRECT);
        equal(0,recovered.errors(),"live world observations override pre-restart errors");
        check(!recovered.complete(),"saved partial matrix cannot prove completion");
        boolean invalid=false;
        try{new BatchInspection.Checkpoint(4,true,999,0,0,saved.cells());}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"corrupt inspection cursor rejected");
        for(int width=1;width<=5;width++)for(boolean reverse:new boolean[]{false,true}) {
            BatchInspection inspection=new BatchInspection(width,reverse);
            var points=inspection.route();
            var forward=new BatchInspection(width,false).route();
            if(reverse)for(int i=0;i<points.size();i++)equal(forward.get(forward.size()-1-i),points.get(i),"reverse follows the exact opposite full route");
            check(!inspection.complete(),"an unread matrix is never complete");
            var previous=points.getFirst();
            inspectAt(inspection,previous.x(),previous.z());
            for(var point:points) {
                check(previous.x()==point.x()||previous.z()==point.z(),"continuous axis-aligned snake segments");
                int x=previous.x(),z=previous.z();
                while(x!=point.x()||z!=point.z()) {
                    x+=Integer.compare(point.x(),x);z+=Integer.compare(point.z(),z);
                    inspectAt(inspection,x,z);
                }
                previous=point;
            }
            equal(16384,inspection.observed(),"physical path covers the entire 128x128 matrix at width "+width);
            check(inspection.complete(),"all cells read along the route");
            for(int i=0;i<49;i++)inspection.observe(i,0,BatchInspection.Cell.MISSING);
            equal(49,inspection.missing(),"missing is counted by unique matrix cells");
            inspection.observe(0,0,BatchInspection.Cell.MISSING);
            equal(49,inspection.errors(),"revisiting a cell does not double-count it");
            equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(1,inspection.missing(),inspection.unknown(),49),"an explicitly configured inclusive limit accepts 49 missing cells");
            inspection.observe(50,0,BatchInspection.Cell.WRONG);
            equal(1,inspection.wrong(),"wrong block/color is separate from missing");
            equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(1,inspection.missing(),inspection.unknown(),49),"a wrong occupied cell does not increase the missing-only threshold");
            equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(1,inspection.missing(),inspection.unknown(),0),"the same matrix still fails the default zero missing limit");
            inspection.observe(0,0,BatchInspection.Cell.CORRECT);
            equal(48,inspection.missing(),"updated actual observations replace previous results");
            inspection.observe(50,0,BatchInspection.Cell.CORRECT);
            equal(0,inspection.wrong(),"excluded/correct cells do not count as errors");
            // At low view distance earlier chunks unload behind the player;
            // observations from this same lap still constitute full coverage.
            check(inspection.complete(),"same-session observations remain valid after the route moves away");
            var disk=inspection.checkpoint(3,45,67);
            BatchInspection offline=BatchInspection.restartAfterOffline(disk);
            equal(0,offline.observed(),"offline observations must all be checked again");
            check(!offline.complete(),"a complete saved matrix cannot pass after restarting the game");
            equal(inspection.route().get(3),offline.route().getFirst(),"restart continues toward the saved next waypoint");
            equal(offline.route().getFirst(),offline.route().getLast(),"rotated inspection closes the unfinished band at the end");
            equal(offline.checkpoint(1,45,67),BatchInspection.Checkpoint.decode(offline.checkpoint(1,45,67).encode()),"rotated route survives another disk checkpoint");
            var cursor=offline.route().getFirst();
            inspectAt(offline,cursor.x(),cursor.z());
            for(var next:offline.route()) {
                int x=cursor.x(),z=cursor.z();
                while(x!=next.x()||z!=next.z()) {
                    x+=Integer.compare(next.x(),x);z+=Integer.compare(next.z(),z);
                    inspectAt(offline,x,z);
                }
                cursor=next;
            }
            check(offline.complete(),"continued remainder plus wraparound rechecks all cells after restart");
        }
    }

    private static void mapSwapChecks() {
        equal(BatchJournal.MapSwapRecovery.RETRY_SOURCE,BatchJournal.mapSwapRecovery(1,0),"crash before a swap retries the same source map");
        equal(BatchJournal.MapSwapRecovery.CONFIRMED_DESTINATION,BatchJournal.mapSwapRecovery(0,1),"crash after a swap commits the server-confirmed target");
        equal(BatchJournal.MapSwapRecovery.MISSING,BatchJournal.mapSwapRecovery(0,0),"missing owned map does not fetch a replacement automatically");
        equal(BatchJournal.MapSwapRecovery.AMBIGUOUS,BatchJournal.mapSwapRecovery(1,1),"two empty maps cannot be assigned to one round");
        equal(BatchJournal.MapSwapRecovery.AMBIGUOUS,BatchJournal.mapSwapRecovery(0,2),"a stack of maps is not the single owned map");
    }

    private static void inspectionOriginChecks() {
        for(int width=1;width<=5;width++)for(int originX:List.of(0,127))for(int originZ:List.of(0,127)) {
            var normal=new BatchInspection(width,false,originX,originZ);
            equal(originX,normal.route().getFirst().x(),"inspection enters from the same X corner as the worker");
            int edgeLane=originZ==0?0:(127/width);
            int edgeZ=MapArtBandGeometry.centerZ(0,127,width,edgeLane);
            equal(edgeZ,normal.route().getFirst().z(),"inspection enters from the same Z band as the worker");
            for(boolean reverse:new boolean[]{false,true}) {
                var first=new BatchInspection(width,reverse,originX,originZ);
                var second=new BatchInspection(width,!reverse,originX,originZ);
                var third=new BatchInspection(width,reverse,originX,originZ);
                var backward=new ArrayList<>(first.route());java.util.Collections.reverse(backward);
                equal(backward,second.route(),"second sweep exactly retraces the complete first sweep from its far end");
                equal(first.route().getLast(),second.route().getFirst(),"second sweep does not return across the canvas before beginning work");
                equal(first.route(),third.route(),"third sweep restores the original forward traversal");
                equal(second.route().getLast(),third.route().getFirst(),"third sweep starts at the second sweep's endpoint");
                var saved=second.checkpoint(3,45,67);
                var decoded=BatchInspection.Checkpoint.decode(saved.encode());
                equal(saved,decoded,"reference corner survives a checkpoint round trip");
                equal(originX,decoded.originX(),"checkpoint saves the relative work origin X");
                equal(originZ,decoded.originZ(),"checkpoint saves the relative work origin Z");
                equal(second.route(),BatchInspection.restore(decoded).route(),"restored inspection uses its original reference corner");
                var offline=BatchInspection.restartAfterOffline(decoded);
                equal(second.route().get(3),offline.route().getFirst(),"offline resume preserves the reference corner while continuing its route");
                var resumed=offline.checkpoint(1,45,67);
                equal(originX,resumed.originX(),"offline wraparound keeps X reference");
                equal(originZ,resumed.originZ(),"offline wraparound keeps Z reference");
            }
        }
    }

    private static void resetTransactionChecks() {
        check(BatchJournal.resetTransactionProblem(null)==null,"a missing batch has no pending map transaction");
        var state=new BatchJournal.State();state.phase=Phase.ACTIVATE_MAP;state.activationIssued=true;
        check(BatchJournal.resetTransactionProblem(state)!=null,"reset refuses an activation whose map ID is not confirmed");
        state.rawMap=12;
        check(BatchJournal.resetTransactionProblem(state)==null,"a known map proceeds to physical ownership checks");
        state.phase=Phase.TAKE_LOCKED;state.lockIssued=true;
        check(BatchJournal.resetTransactionProblem(state)!=null,"reset refuses an unknown locked output ID");
        state.lockedMap=37;
        check(BatchJournal.resetTransactionProblem(state)==null,"a known locked map proceeds to physical ownership checks");
        state.phase=Phase.STORE_PENDING;state.lockConfirmed=true;state.depositIssued=true;
        check(BatchJournal.resetTransactionProblem(state)!=null,"a sent deposit is not a committed deposit");
        for(Phase committed:List.of(Phase.NEXT,Phase.DONE)) {
            state.phase=committed;state.rawMap=state.lockedMap=-1;
            check(BatchJournal.resetTransactionProblem(state)==null,"committed products may be archived despite historical intent flags");
        }
    }

    private static void repairPassChecks() {
        for(int pass:List.of(1,2,3,37,500,Integer.MAX_VALUE)) {
            equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(pass,0,0,0),"any round may finish with no missing cells");
            equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(pass,50,0,50),"a configured missing threshold is inclusive");
            equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(pass,51,0,50),"one cell above the configured threshold continues repairing");
            equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(pass,1,0,0),"no late round forces map creation while one required cell is missing");
            equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(pass,0,1,0),"unread cells never count as verified complete");
        }
        equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(4,16384,0,16384),"the configured upper bound can accept the full known canvas");
        equal(BatchWorkflowRules.ReviewAction.REPAIR_AGAIN,BatchWorkflowRules.reviewAction(37,5,0,0),"current settings can require more repair on the same persisted pass");
        equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(37,5,0,5),"adjusting the live threshold does not require changing the batch or round");
        equal(38,BatchWorkflowRules.nextRepairPass(37),"unmet demand advances to the next persisted repair sweep");
        boolean invalid=false;try{BatchWorkflowRules.reviewAction(0,0,0,0);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"the initial printing stage is not a repair sweep");
        invalid=false;try{BatchWorkflowRules.reviewAction(1,0,0,-1);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"negative missing limits are invalid");
        invalid=false;try{BatchWorkflowRules.reviewAction(1,0,0,16385);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"missing limits cannot exceed the canvas size");
        invalid=false;try{BatchWorkflowRules.nextRepairPass(Integer.MAX_VALUE);}catch(ArithmeticException expected){invalid=true;}
        check(invalid,"sweep numbers cannot silently overflow into invalid checkpoint values");
    }

    private static void inspectionClassificationChecks() {
        equal(BatchInspection.Cell.MISSING,BatchInspection.classify(false,false,false,true,true),"air at a required block is missing");
        equal(BatchInspection.Cell.MISSING,BatchInspection.classify(false,false,false,false,true),"replaceable water or vegetation at a required block is missing");
        equal(BatchInspection.Cell.WRONG,BatchInspection.classify(false,false,false,false,false),"a solid wrong color/block is tracked separately and ignored by the completion gate");
        equal(BatchInspection.Cell.CORRECT,BatchInspection.classify(false,false,true,false,true),"an exact matching block wins over its replaceable flag");
        equal(BatchInspection.Cell.CORRECT,BatchInspection.classify(true,false,false,true,true),"excluded colors do not create missing demand");
        equal(BatchInspection.Cell.WRONG,BatchInspection.classify(false,true,false,false,true),"projection air never creates a missing required block");
        equal(BatchInspection.Cell.CORRECT,BatchInspection.classify(false,true,true,true,true),"matching projection air remains correct");
        var wrongOnly=new BatchInspection(4,false);
        for(int z=0;z<128;z++)for(int x=0;x<128;x++)wrongOnly.observe(x,z,BatchInspection.Cell.WRONG);
        equal(16384,wrongOnly.wrong(),"wrong occupied cells remain visible in inspection statistics");
        equal(BatchWorkflowRules.ReviewAction.ACCEPT,BatchWorkflowRules.reviewAction(1,wrongOnly.missing(),wrongOnly.unknown(),0),"even many wrong cells do not inflate the missing-only limit");
    }

    private static void archiveChecks(Path folder,List<BatchFiles.Entry> queue) throws Exception {
        Path checkpoint=folder.resolve("batch.properties");
        BatchJournal journal=new BatchJournal(checkpoint);
        var state=new BatchJournal.State();state.world="archive-server";state.dimension="minecraft:overworld";
        state.configuration="unchanged";state.folder="unchanged-folder";state.queue=new ArrayList<>(queue);
        state.phase=Phase.PRINT;journal.write(state);journal.archive();
        state.phase=Phase.LOAD;journal.write(state);journal.archive();
        check(!journal.exists(),"reinitializing an unchanged directory removes only the live checkpoint");
        Path previous=folder.resolve("batch.properties.previous");
        equal(Phase.LOAD,new BatchJournal(previous).read().phase,"latest checkpoint remains available as .previous");
        equal(queue,new BatchJournal(previous).read().queue,"same filenames and queue do not suppress reinitialization");
        List<Path> older;
        try(var files=Files.list(folder)){older=files.filter(path->path.getFileName().toString().endsWith(".bak")).toList();}
        equal(1,older.size(),"overwriting .previous first creates a unique older backup");
        equal(Phase.PRINT,new BatchJournal(older.getFirst()).read().phase,"earlier unfinished batch is preserved exactly");
        state.phase=Phase.REVIEW;state.repairPass=2;journal.write(state);journal.archive();
        try(var files=Files.list(folder)){older=files.filter(path->path.getFileName().toString().endsWith(".bak")).toList();}
        equal(2,older.size(),"repeated resets never overwrite earlier unique backups");
        equal(Phase.REVIEW,new BatchJournal(previous).read().phase,"newest archive remains compatible with .previous");
        var phases=new java.util.HashSet<Phase>();
        for(Path backup:older)phases.add(new BatchJournal(backup).read().phase);
        equal(java.util.Set.of(Phase.PRINT,Phase.LOAD),phases,"all earlier batch phases remain recoverable");

        Path failed=folder.resolve("failed.properties");
        BatchJournal failureJournal=new BatchJournal(failed);state.phase=Phase.PRINT;failureJournal.write(state);
        Path protectedArchive=Files.createDirectory(folder.resolve("failed.properties.previous"));
        Files.writeString(protectedArchive.resolve("keep.txt"),"unrelated material");
        boolean rejected=false;try{failureJournal.archive();}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"an invalid archive destination reports initialization failure");
        check(failureJournal.exists(),"archive failure keeps the live checkpoint");
        equal(Phase.PRINT,failureJournal.read().phase,"failed initialization leaves the original state readable");
        equal("unrelated material",Files.readString(protectedArchive.resolve("keep.txt")),"archive failure cannot overwrite unrelated destination contents");
    }

    private static void inspectAt(BatchInspection inspection,int playerX,int playerZ) {
        for(int z=Math.max(0,playerZ-BatchInspection.RADIUS);z<=Math.min(127,playerZ+BatchInspection.RADIUS);z++)
            for(int x=Math.max(0,playerX-BatchInspection.RADIUS);x<=Math.min(127,playerX+BatchInspection.RADIUS);x++)
                inspection.observe(x,z,BatchInspection.Cell.CORRECT);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
}
