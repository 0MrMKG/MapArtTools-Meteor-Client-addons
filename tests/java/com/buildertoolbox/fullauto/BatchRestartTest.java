package com.buildertoolbox.fullauto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.buildertoolbox.fullauto.BatchJournal.Phase;

/** Inclusive restart selection, durable pre-start checkpoint and recoverable replacement. */
public final class BatchRestartTest {
    public static void main(String[] args) throws Exception {
        var queue=List.of(new BatchFiles.Entry("地图1.litematic","one"),
            new BatchFiles.Entry("地图2.litematic","two"),new BatchFiles.Entry("地图10.litematic","ten"));
        for(int number=1;number<=queue.size();number++) {
            var state=BatchJournal.startingAt(queue,number);
            check(state.index==number-1&&state.queue.equals(queue),"inclusive index preserves original total and ordering");
            check(state.phase==Phase.LOAD&&state.drawing==null&&state.rawMap==-1&&state.lockedMap==-1,
                "explicit reset starts selected picture fresh, with no previous drawing or map ownership");
        }
        for(int invalid:List.of(-1,0,4,Integer.MAX_VALUE)) {
            boolean failed=false;
            try{BatchJournal.startingAt(queue,invalid);}catch(IOException expected){failed=true;}
            check(failed,"out of range is rejected rather than clamped or silently restarting picture one");
        }
        boolean empty=false;try{BatchJournal.startingAt(List.of(),1);}catch(IOException expected){empty=true;}
        check(empty,"empty directory cannot reset a batch");

        Path temp=Files.createTempDirectory(Path.of("build"),"batch-restart-");
        try {
            Path path=temp.resolve("batch.properties");
            var journal=new BatchJournal(path);
            var old=BatchJournal.startingAt(queue,1);old.phase=Phase.PRINT;old.elapsedMillis=12345;
            journal.write(old);
            var selected=BatchJournal.startingAt(queue,2);selected.world="server";selected.dimension="overworld";
            selected.configuration="config";selected.folder="地图合集";
            journal.replace(selected);
            var rebooted=new BatchJournal(path).read();
            check(rebooted.index==1&&rebooted.phase==Phase.LOAD&&rebooted.queue.get(rebooted.index).name().equals("地图2.litematic"),
                "game restart before enabling preserves selected second picture");
            check(new BatchJournal(path.resolveSibling("batch.properties.previous")).read().phase==Phase.PRINT,
                "old partial drawing remains archived rather than destroyed");
            rebooted.phase=Phase.NEXT;rebooted.nextPainting();journal.write(rebooted);
            check(journal.read().index==2&&journal.read().phase==Phase.LOAD,"after selected product commit, advance normally");
            var last=BatchJournal.startingAt(queue,3);journal.replace(last);
            last=new BatchJournal(path).read();last.phase=Phase.NEXT;last.nextPainting();
            check(last.phase==Phase.DONE&&last.index==3,"restart from last picture finishes after exactly one product");
            // Failure after archiving but before the replacement is serialized
            // must still leave a valid current record, not just a .previous file.
            var invalid=BatchJournal.startingAt(queue,1);invalid.world=null;
            boolean failed=false;try{journal.replace(invalid);}catch(NullPointerException expected){failed=true;}
            check(failed&&new BatchJournal(path).read().index==2,"failed replacement preserves the live checkpoint");
            Path guarded=temp.resolve("guarded.properties");var guardedJournal=new BatchJournal(guarded);
            guardedJournal.write(old);Files.createDirectory(temp.resolve("guarded.properties.previous"));
            failed=false;try{guardedJournal.replace(selected);}catch(IOException expected){failed=true;}
            check(failed&&guardedJournal.read().phase==Phase.PRINT,"archive failure cannot erase old progress");
        } finally {
            try(var paths=Files.walk(temp)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }

        String module=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        String controller=Files.readString(Path.of("src/main/java/com/buildertoolbox/fullauto/MapArtBatchController.java"));
        check(module.contains(".name(\"Reset Start Map\")")&&module.contains(".defaultValue(1).range(1, 100000)")
            &&module.contains("firstMap != resetStartMap.get()"),"one-based setting and stale async selection guard");
        check(module.contains("batch.reset(result.folder(), result.files(), firstMap)")
            &&module.contains("validateBatchDirectory(null)")&&!module.contains("从第 1 张开始"),
            "both reset entrances use selected start and do not promise picture one");
        String reset=section(controller,"public boolean reset(","private String resetOwnershipProblem(");
        check(reset.indexOf("BatchJournal.startingAt(files,firstMap)")<reset.indexOf("journal.replace(replacement)")
            &&reset.indexOf("resetOwnershipProblem(existing)")<reset.indexOf("journal.replace(replacement)")
            &&reset.contains("job=replacement"),"range and existing map ownership guard precede durable replacement");
        check(!reset.contains("lever(")&&!reset.contains("nextPainting()"),"reset does not trigger hardware or fake earlier product commits");
        System.out.println("BatchRestart: inclusive first/middle/last, invalid selection, pre-start reboot, queue advancement and failed replacement preservation passed.");
    }
    private static String section(String code,String from,String to){int a=code.indexOf(from),b=code.indexOf(to,a+from.length());check(a>=0&&b>a,"source section");return code.substring(a,b);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
