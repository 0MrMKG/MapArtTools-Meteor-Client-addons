package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.buildertoolbox.fullauto.BatchJournal.Phase;

/** Queue, native offsets, lever intents, commit recovery and disk restart tests. */
public final class BatchWorkflowTest {
    public static void main(String[] args) throws Exception {
        check(BatchFiles.acceptableErrors(49),"49 errors finish");
        check(!BatchFiles.acceptableErrors(50),"50 errors must not finish");
        check(!BatchFiles.acceptableErrors(-1),"unknown error count is not success");
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

            BatchJournal journal=new BatchJournal(temp.resolve("config/batch.properties"));
            BatchJournal.State s=new BatchJournal.State();s.world="test-server";s.dimension="minecraft:overworld";
            s.configuration="locations+filter";s.folder=pictures.toRealPath().toString();s.queue=new ArrayList<>(entries.subList(0,2));
            s.locations="canvas=-64,64,-64;lever=-65,64,-64;mapSupply=-66,64,-64;table=-67,64,-64;product=-68,64,-64";
            s.rawMap=12;s.lockedMap=37;s.mapSlot=8;s.glassSlot=20;s.privateMaps="1,2,3";s.initialGlass=15;
            s.initialMinX=-64;s.initialMinZ=-192;s.initialY=64;s.materialInventoryBefore="inventory";
            s.leverIssued=s.mapIssued=s.glassIssued=s.activationIssued=s.lockIssued=s.depositIssued=s.lockConfirmed=true;
            for(Phase phase:Phase.values()) {
                s.phase=phase;s.index=phase==Phase.DONE?s.queue.size():0;s.deadline=123456789;s.pause="暂停原因";
                journal.write(s);var restored=journal.read();
                equal(phase,restored.phase,"every phase survives restart");equal(12,restored.rawMap,"raw map ID");equal(37,restored.lockedMap,"lock may create a NEW map ID");
                equal(s.queue,restored.queue,"fixed queue/fingerprints");
                equal(s.locations,restored.locations,"all five registered locations are checkpointed");
                equal(s.deadline,restored.deadline,"wait checkpoint");equal(s.pause,restored.pause,"Unicode status");
                check(restored.leverIssued&&restored.depositIssued&&restored.lockConfirmed,"operation intent/confirmation flags");
            }
            s.index=0;s.phase=Phase.STORE_PENDING;
            boolean advanced=false;try{s.nextPainting();advanced=true;}catch(IllegalStateException expected){}
            check(!advanced,"never advance before confirmed product commit");
            s.phase=Phase.NEXT;s.nextPainting();journal.write(s);
            var second=journal.read();equal(1,second.index,"resume second picture after first product commit");equal(Phase.LOAD,second.phase,"new file starts with validation");
            equal(-1,second.rawMap,"reset per-picture map ownership");check(!second.depositIssued,"no previous-product intent leaks");
            second.phase=Phase.NEXT;second.nextPainting();journal.write(second);equal(Phase.DONE,journal.read().phase,"stop after final product commit");
            journal.archive();check(!journal.exists(),"reset archives rather than overwrites the old batch");
            check(Files.exists(temp.resolve("config/batch.properties.previous")),"recoverable previous checkpoint");
        } finally {
            try(var files=Files.walk(temp)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("BatchWorkflow: directory/queue, two cleanup pulses + 10s buffer, physical inspection coverage/counters, 49/50 threshold, all-phase restart and two-picture commits passed.");
    }

    private static void inspectionChecks() {
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
            check(BatchFiles.acceptableErrors(inspection.errors()),"49 actual missing cells pass after the complete walk");
            inspection.observe(50,0,BatchInspection.Cell.WRONG);
            equal(1,inspection.wrong(),"wrong block/color is separate from missing");
            check(!BatchFiles.acceptableErrors(inspection.errors()),"49 missing plus 1 wrong is 50 and fails");
            inspection.observe(0,0,BatchInspection.Cell.CORRECT);
            equal(48,inspection.missing(),"updated actual observations replace previous results");
            inspection.observe(50,0,BatchInspection.Cell.CORRECT);
            equal(0,inspection.wrong(),"excluded/correct cells do not count as errors");
        }
    }

    private static void inspectAt(BatchInspection inspection,int playerX,int playerZ) {
        for(int z=Math.max(0,playerZ-BatchInspection.RADIUS);z<=Math.min(127,playerZ+BatchInspection.RADIUS);z++)
            for(int x=Math.max(0,playerX-BatchInspection.RADIUS);x<=Math.min(127,playerX+BatchInspection.RADIUS);x++)
                inspection.observe(x,z,BatchInspection.Cell.CORRECT);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
}
