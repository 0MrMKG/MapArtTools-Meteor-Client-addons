package com.buildertoolbox.fullauto;

import fi.dy.masa.litematica.data.DataManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.buildertoolbox.client.utils.player.InvUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapPostProcessingComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.FilledMapItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import static com.buildertoolbox.fullauto.BatchJournal.Phase;

/** Full-auto workflow. Its only worker dependency is its private Host interface. */
public final class MapArtBatchController {
    public record Configuration(String folder, BlockPos origin, BlockPos lever, BlockPos mapSupply,
                                BlockPos table, BlockPos product, String filter) {
        public String signature() { return BatchFiles.hash(folder+"|"+origin+"|"+lever+"|"+mapSupply+"|"+table+"|"+product+"|"+filter); }
        public String locations() { return "canvas="+origin+";lever="+lever+";mapSupply="+mapSupply+";table="+table+";product="+product; }
        public void validate() throws IOException {
            if(folder.isBlank()||origin==null||lever==null||mapSupply==null||table==null||product==null)
                throw new IOException("请填写蓝图目录，并登记画布原点、拉杆、地图原料箱、制图台和成品箱");
            if(mapSupply.equals(product))throw new IOException("地图原料箱与成品箱请使用不同位置");
        }
    }
    public interface Host {
        Configuration batchConfiguration();
        boolean batchExcluded(BlockState state);
        void batchPrepare(BatchPainting painting);
        String batchPreflight();
        void batchStartDrawing();
        BatchPrintCheckpoint batchDrawingCheckpoint();
        void batchResumeDrawing(BatchPrintCheckpoint checkpoint);
        void batchRestoreReviewContext(BatchPrintCheckpoint checkpoint);
        void batchStopWorker();
        boolean batchPlacementPending();
        boolean batchWorkSweepFinished();
        void batchHoldForReview();
        boolean batchOwnsContainer();
        boolean batchCursorStorageAllowed(ItemStack stack);
        boolean batchFreeCursorSpace();
        void batchCursorRecovered();
        boolean batchReturnAfterCursorRecovery();
        void batchBeginReviewRepairs(int pass);
        boolean batchRepairNearby();
        boolean batchRepairEligible(BlockPos pos);
        boolean batchRepairTarget(BlockPos pos);
        void batchObserveBlock(BlockPos pos);
        int batchReviewWidth();
        boolean batchReviewReverse();
        int batchReviewOriginX();
        int batchReviewOriginZ();
        int batchAllowedMissingBlocks();
        int batchLane();
        boolean batchWorkerPaused();
        void batchPauseWorker(String reason);
        void batchStatus(String text);
        void batchMessage(String text);
        void batchFinish();
    }
    public record Scan(int errors,int unknown,int water,int missingSupport) { }
    private record LeverAck(long version,boolean powered) { }

    private final Host host;
    private final MinecraftClient mc;
    private final BatchWorkstationAccess access;
    private final BatchJournal journal;
    private final CursorRecovery cursorRecovery;
    private final AtomicLong blockVersion = new AtomicLong();
    private final ConcurrentHashMap<BlockPos,LeverAck> leverAcks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Boolean> lockedMaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos,BlockState> serverBlocks = new ConcurrentHashMap<>();
    private final AtomicLong canvasRevision = new AtomicLong();
    private volatile BlockPos watchedLever;
    private BatchJournal.State job;
    private Configuration config;
    private volatile BatchPainting painting;
    private CompletableFuture<List<BatchFiles.Entry>> discovery;
    private Path folder;
    private boolean running;
    private boolean resuming;
    private boolean runtimeRestored;
    private boolean restoringLever;
    private long tick, actionAt=-1, actionAckBefore, waitStarted=-1, captureStarted=-1;
    private int errors=-1;
    private int fetchAttempts, lockInputAttempts, lockAttempts, depositAttempts, mapSwapAttempts;
    private BatchInspection inspection;
    private int inspectionWaypoint;
    private BlockPos inspectionResume;
    private BlockPos inspectionRepairTarget;
    private final Set<BlockPos> inspectionDeferred = new HashSet<>();
    private int canvasScanIndex;
    private long scanStartRevision, completedScanRevision = -1;
    private long clockNanos = -1, lastSavedNanos;
    private long drawingSavedAt;
    private IOException stopSaveFailure;
    private String status="尚未开始";

    public MapArtBatchController(Host host,MinecraftClient mc) {
        this.host=host;this.mc=mc;access=new BatchWorkstationAccess(mc);cursorRecovery=new CursorRecovery(mc);
        journal=new BatchJournal(FabricLoader.getInstance().getConfigDir().resolve("builder_toolbox/fullauto-batch.properties"));
    }
    public boolean running() { return running; }
    public BlockState observedBlock(BlockPos pos) { return serverBlocks.getOrDefault(pos, mc.world.getBlockState(pos)); }
    public void watchPlacement(BlockPos pos) {
        serverBlocks.putIfAbsent(pos.toImmutable(), mc.world.getBlockState(pos));
        canvasRevision.incrementAndGet();
    }
    public void rememberCursorOrigin(ScreenHandler handler,int slot) { cursorRecovery.rememberOrigin(handler,slot); }
    public boolean printing() { return running&&job!=null&&job.phase==Phase.PRINT; }
    public int reservedSlots() { return running&&job!=null&&(job.phase==Phase.PRINT||job.phase==Phase.REVIEW)?2:0; }
    public String status() { return prefix()+status; }
    public String stageStatus() { return status; }
    public String queueDescription() { return prefix().trim(); }
    public String elapsedStatus() {
        long millis=job==null?0:job.elapsedMillis;
        if(running&&clockNanos>=0)millis+=Math.max(0,System.nanoTime()-clockNanos)/1_000_000L;
        return "批次累计运行 " + MapArtProgressTimer.duration(millis/1000);
    }
    public String queueProgress() {
        if(job==null||job.queue.isEmpty())return "地图画队列尚未读取";
        return "地图画 第 " + Math.min(job.index+1,job.queue.size()) + " / " + job.queue.size() + " 张";
    }
    public boolean hidesScreen(Screen screen) { return access.hidesScreen(screen); }
    public boolean closingMenu() { return access.closingMenu(); }
    public boolean allowsAutoEat() {
        if(!running||job==null||resuming||discovery!=null||access.busyForAutoEat())return false;
        return switch(job.phase) {
            case LOAD,PRINT,REVIEW,WAIT_ON_1,WAIT_OFF_1,WAIT_ON_2,WAIT_OFF_2,CLEAR_BUFFER,CHECK_CLEAR -> true;
            case CLEAR_ON_1,CLEAR_OFF_1,CLEAR_ON_2,CLEAR_OFF_2 -> !job.leverIssued;
            default -> false; // Do not interrupt map activation, cartography or product transactions.
        };
    }
    public void holdForAutoEat() { access.release(tick); }

    public void startOrResume() {
        stop();
        fetchAttempts=lockInputAttempts=lockAttempts=depositAttempts=mapSwapAttempts=0;
        if(mc.player==null||mc.world==null)return;
        try {
            config=host.batchConfiguration();config.validate();
            folder=BatchFiles.directory(DataManager.getSchematicsBaseDirectory(),config.folder());
            watchedLever=config.lever();
            if(journal.exists()) {
                job=journal.read();
                if(!job.world.equals(world())||!job.dimension.equals(dimension()))throw new IOException("批次属于其他服务器/存档或维度");
                if(!job.configuration.equals(config.signature())||!job.folder.equals(folder.toString()))throw new IOException("批次配置发生变化，请恢复原配置或使用 Reset Batch 重建");
                if(job.phase==Phase.DONE) {status="队列已完成；Reset Batch 后可开始新批次";host.batchPauseWorker(status);return;}
                Path selected=folder;
                List<BatchFiles.Entry> snapshot=List.copyOf(job.queue);
                resuming=true;
                discovery=CompletableFuture.supplyAsync(()->{
                    try{BatchFiles.validateQueue(selected,snapshot);return snapshot;}
                    catch(IOException exception){throw new java.util.concurrent.CompletionException(exception);}
                });
                running=true;host.batchStopWorker();
            } else {
                job=null;painting=null;
                Path selected=folder;
                discovery=CompletableFuture.supplyAsync(()->{
                    try{return BatchFiles.scan(selected);}catch(IOException exception){throw new java.util.concurrent.CompletionException(exception);}
                });
                running=true;host.batchStopWorker();status="读取目录与文件指纹";
            }
            actionAt=-1;waitStarted=-1;
            status=job==null?"读取队列":"恢复阶段："+job.phase;
            host.batchMessage("全自动批次开始/恢复："+config.folder());
        } catch(Exception exception) {pause(message(exception));}
    }

    public void stop() {
        if(running&&job!=null&&!resuming)try{captureDrawing();save();}catch(IOException exception){stopSaveFailure=exception;host.batchMessage("保存进度失败："+exception.getMessage());}
        stopRuntime();
    }

    private void stopRuntime() {
        if(discovery!=null){discovery.cancel(true);discovery=null;}
        running=false;resuming=false;access.reset();actionAt=-1;waitStarted=-1;
        runtimeRestored=false;captureStarted=-1;
        clockNanos=-1;
        serverBlocks.clear();leverAcks.clear();lockedMaps.clear();
        inspection=null;inspectionWaypoint=0;inspectionResume=null;
        inspectionRepairTarget=null;inspectionDeferred.clear();canvasScanIndex=0;completedScanRevision=-1;
        cursorRecovery.reset();
    }

    public boolean reset(Path selectedFolder, List<BatchFiles.Entry> files, int firstMap) {
        BatchJournal.State existing=null;
        try {
            // Validate the new start before touching the current checkpoint.
            BatchJournal.State replacement=BatchJournal.startingAt(files,firstMap);
            if(mc.player==null||mc.world==null)throw new IOException("请进入游戏后重建批次，以保存服务器/存档与维度");
            Configuration selectedConfig=host.batchConfiguration();selectedConfig.validate();
            if(!selectedFolder.equals(BatchFiles.directory(DataManager.getSchematicsBaseDirectory(),selectedConfig.folder())))
                throw new IOException("蓝图目录已变化，请重新检测");
            replacement.world=world();replacement.dimension=dimension();replacement.folder=selectedFolder.toString();
            replacement.configuration=selectedConfig.signature();replacement.locations=selectedConfig.locations();
            // Unlike ordinary stop(), initialization must fail if saving the
            // latest authoritative phase fails; never archive an older phase.
            if(stopSaveFailure!=null)throw new IOException("最近一次停止时保存失败，尚不能安全初始化；请先恢复并成功保存当前批次",stopSaveFailure);
            if(running&&runtimeRestored&&job!=null&&!resuming&&discovery==null){captureDrawing();save();}
            existing=journal.exists()?journal.read():job;
            String before=resetOwnershipProblem(existing);
            stopRuntime();host.batchStopWorker();
            if(before!=null)throw new IOException(before);
            // Closing our cartography/container screen may return its inputs.
            // Check again after releasing it before archiving ownership data.
            String after=resetOwnershipProblem(existing);
            if(after!=null)throw new IOException(after);
            journal.replace(replacement);job=replacement;painting=null;config=selectedConfig;folder=selectedFolder;
            status="批次已重新初始化：第 "+firstMap+" / "+files.size()+" 张（含）："+files.get(firstMap-1).name()
                +"；起点已保存，旧记录已归档；开启后重新清理并制作所选地图";
            host.batchPauseWorker(status);
            host.batchMessage(status);
            return true;
        }catch(Exception exception){
            stopRuntime();host.batchStopWorker();
            if(existing!=null)job=existing;
            status="初始化未执行，原进度已保留："+message(exception);
            host.batchPauseWorker(status);host.batchMessage(status);
            return false;
        }
    }

    private String resetOwnershipProblem(BatchJournal.State existing) {
        String transaction=BatchJournal.resetTransactionProblem(existing);
        if(transaction!=null||existing==null||existing.phase==Phase.NEXT||existing.phase==Phase.DONE)return transaction;
        if(existing.rawMap<0&&existing.lockedMap<0)return null;
        if(mc.player==null||mc.world==null)return "请进入原批次所在世界核对本轮地图，再重新初始化";
        // Map IDs are world-local: another save/dimension can have the same ID.
        if(!existing.world.equals(world())||!existing.dimension.equals(dimension()))return null;
        for(int slot=0;slot<36;slot++)if(ownsMap(existing,mc.player.getInventory().getStack(slot)))
            return "本轮地图仍在背包，请先恢复完成锁定/入箱，再重新初始化";
        if(ownsMap(existing,mc.player.getOffHandStack())||ownsMap(existing,mc.player.currentScreenHandler.getCursorStack())
            ||ownsMap(existing,mc.player.playerScreenHandler.getCursorStack()))
            return "本轮地图仍在副手或鼠标上，请先恢复完成入箱，再重新初始化";
        for(var slot:mc.player.currentScreenHandler.slots)if(ownsMap(existing,slot.getStack()))
            return "当前容器或制图台仍有本轮地图，请先恢复确认产物入箱，再重新初始化";
        return null;
    }

    private boolean ownsMap(BatchJournal.State existing,ItemStack stack) {
        int id=mapId(stack);
        return id>=0&&(id==existing.rawMap||id==existing.lockedMap);
    }

    /** Returns true when the workflow owns this tick; false lets the snake worker tick. */
    public boolean tick() {
        tick++;
        if(!running)return true;
        try {
            if(!config.signature().equals(host.batchConfiguration().signature()))throw new IOException("位置、目录或颜色筛选已改变，已暂停；恢复原配置后可续作");
            if(job!=null&&(!job.world.equals(world())||!job.dimension.equals(dimension())))throw new IOException("服务器/存档或维度发生变化");
            access.claimPendingHandler(tick);checkAccess();
            if(cursorRecovery.tick(mc.player.currentScreenHandler==mc.player.playerScreenHandler
                ||access.owns(mc.player.currentScreenHandler)||host.batchOwnsContainer(),this::cursorStorageAllowed,host::batchCursorStorageAllowed)) {
                access.release(tick);host.batchHoldForReview();report(cursorRecovery.status());return true;
            }
            if(cursorRecovery.needsSpace()&&!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                access.release(tick);host.batchHoldForReview();
                boolean freeing=job!=null&&(job.phase==Phase.PRINT||job.phase==Phase.REVIEW)
                    &&mc.player.currentScreenHandler==mc.player.playerScreenHandler&&host.batchFreeCursorSpace();
                report(freeing?"正在安全铺设已有材料，为鼠标物品腾出空间"
                    :"附近暂无可安全腾出的槽位：请清出一个背包格，腾出后自动继续");return true;
            }
            if(cursorRecovery.consumeRecovered()) {
                host.batchCursorRecovered();
                access.resetTravel();
                host.batchMessage("已安全收回鼠标物品，继续当前步骤");
                if(job!=null&&(job.phase==Phase.FETCH_MAP||job.phase==Phase.FETCH_GLASS
                    ||job.phase==Phase.STORE||job.phase==Phase.STORE_PENDING))access.reset();
            }
            boolean resumeTable=job!=null&&(job.phase==Phase.LOCK||job.phase==Phase.TAKE_LOCKED)
                &&access.recoveryMatches(config.table(),true);
            if(!resumeTable&&!access.closeRecoveryHandlerIfSafe()) {report("先前自有界面仍有需要保留的物品，等待安全续作");return true;}
            if(host.batchReturnAfterCursorRecovery()) {access.release(tick);report("鼠标物品已收回，返回原工作位置");return true;}
            if(discovery!=null) {
                if(!discovery.isDone()){report("读取目录与文件指纹");return true;}
                List<BatchFiles.Entry> files=discovery.join();discovery=null;
                if(resuming) {
                    resuming=false;loadPainting();job.pause="";
                    restoringLever=job.leverIssued;
                    if(BatchWorkflowRules.leverWait(job.phase))
                        job.deadline=System.currentTimeMillis()+(job.waitRemainingMillis>=0?job.waitRemainingMillis:20000);
                    if(job.phase==Phase.CLEAR_BUFFER||job.phase==Phase.CHECK_CLEAR) {
                        job.phase=Phase.CLEAR_BUFFER;
                        job.deadline=System.currentTimeMillis()+(job.waitRemainingMillis>=0
                            ?Math.min(job.waitRemainingMillis,BatchWorkflowRules.CLEAR_BUFFER_MILLIS):BatchWorkflowRules.CLEAR_BUFFER_MILLIS);
                    }
                    if(job.phase==Phase.CAPTURE)captureStarted=System.currentTimeMillis()
                        -(2000-(job.waitRemainingMillis>=0?Math.min(2000,job.waitRemainingMillis):2000));
                    if(job.phase==Phase.PRINT)host.batchResumeDrawing(job.drawing);
                    if(job.phase==Phase.REVIEW) {
                        host.batchRestoreReviewContext(job.drawing);
                        host.batchBeginReviewRepairs(job.repairPass);
                        if(job.inspection!=null) {
                            // Old narrow checkpoints migrate to broad perception. Never trust saved observations.
                            inspection=new BatchInspection(BatchInspection.SCAN_WIDTH,host.batchReviewReverse(),
                                host.batchReviewOriginX(),host.batchReviewOriginZ());
                            inspectionWaypoint=0;
                            inspectionResume=new BlockPos(job.inspection.resumeX(),painting.y(),job.inspection.resumeZ());
                            host.batchMessage("恢复宽范围复查：重新读取画布，只接近漏格和未加载区域");
                        }
                    }
                    host.batchMessage("已从磁盘恢复："+job.phase+"；"+elapsedStatus()+"；不会重新清理已进入绘制的画布");
                } else {
                    job=new BatchJournal.State();job.world=world();job.dimension=dimension();job.folder=folder.toString();
                    job.configuration=config.signature();job.locations=config.locations();job.queue=new ArrayList<>(files);
                }
                runtimeRestored=true;
                clockNanos=System.nanoTime();
                save();
            }
            if(job==null)throw new IOException("批次状态未初始化");
            if(System.nanoTime()-lastSavedNanos>=2_000_000_000L) {
                captureDrawing();
                save();
            }
            switch(job.phase) {
                case LOAD -> {
                    if(painting==null)loadPainting();
                    String preflight=host.batchPreflight();
                    if(preflight!=null)throw new IOException(preflight);
                    Scan initial=scan(true);
                    if(initial.unknown()>0){loadCanvas("清理前范围检查");return true;}
                    if(initial.missingSupport()>0)throw new IOException("画布缺少底板 "+initial.missingSupport()+" 格，尚未触发清理");
                    if(initial.water()>0)throw new IOException("画布已有水，需先人工确认清理装置状态，尚未触发拉杆");
                    fetchAttempts=lockInputAttempts=lockAttempts=depositAttempts=mapSwapAttempts=0;
                    job.privateMaps=mapIds();job.initialGlass=count(Items.GLASS_PANE);save();
                    transition(Phase.CLEAR_ON_1);
                }
                case CLEAR_ON_1,CLEAR_OFF_1,CLEAR_ON_2,CLEAR_OFF_2 -> lever(BatchWorkflowRules.leverPowered(job.phase),BatchWorkflowRules.nextLeverPhase(job.phase));
                case WAIT_ON_1,WAIT_OFF_1,WAIT_ON_2,WAIT_OFF_2 -> waitLever(BatchWorkflowRules.leverPowered(job.phase),BatchWorkflowRules.nextLeverPhase(job.phase));
                // Legacy checkpoints migrate without inspecting the canvas.
                case CHECK_CLEAR -> {job.deadline=System.currentTimeMillis()+BatchWorkflowRules.CLEAR_BUFFER_MILLIS;transition(Phase.CLEAR_BUFFER);}
                case CLEAR_BUFFER -> waitClearBuffer();
                case PRINT -> {
                    checkpointDrawing();
                    if(host.batchWorkerPaused()){pause("绘制已暂停，请检查材料或放置状态，再重新开启本模块自动恢复");return true;}
                    if(host.batchWorkSweepFinished()) {
                        host.batchHoldForReview();
                        if(host.batchPlacementPending()){report("首轮完成，等待在途放置确认后开始修补");return true;}
                        beginInspection();return true;
                    }
                    return false;
                }
                case REVIEW -> inspectDrawing();
                case FETCH_MAP -> fetch(Items.MAP,true);
                case FETCH_GLASS -> fetch(Items.GLASS_PANE,false);
                case ACTIVATE_MAP -> activateMap();
                case CAPTURE -> capture();
                case LOCK -> lock();
                case TAKE_LOCKED -> takeLocked();
                case CONFIRM_LOCK -> confirmLocked();
                case STORE,STORE_PENDING -> store();
                case NEXT -> {job.nextPainting();painting=null;save();}
                case DONE -> {report("全部文件已完成并确认入箱");running=false;host.batchMessage(status());host.batchFinish();}
            }
        }catch(Exception exception){pause(message(exception));}
        return true;
    }

    public void verifyDrawing() {
        if(!printing())return;
        try {
            if(host.batchPlacementPending()){report("等待放置回包后复查");return;}
            beginInspection();
        }catch(Exception exception){pause(message(exception));}
    }

    private void loadPainting() throws IOException {
        var entry=job.queue.get(job.index);
        painting=BatchPaintingLoader.load(folder,entry,config.origin());
        if(painting.y()<=mc.world.getBottomY()||painting.y()>mc.world.getTopYInclusive())throw new IOException("画布高度超出当前世界可放置范围，或没有底板空间");
        int maxX=Math.addExact(painting.minX(),127),maxZ=Math.addExact(painting.minZ(),127);
        if(!mc.world.getWorldBorder().contains(new BlockPos(painting.minX(),painting.y(),painting.minZ()))
            ||!mc.world.getWorldBorder().contains(new BlockPos(maxX,painting.y(),maxZ)))
            throw new IOException("画布范围超出当前世界边界，尚未触发清理");
        if(job.initialMinX!=Integer.MIN_VALUE&&(painting.minX()!=job.initialMinX||painting.minZ()!=job.initialMinZ||painting.y()!=job.initialY))
            throw new IOException("本文件按Litematica原点换算后的范围与本批画布不同，已停止以免清理其他位置");
        job.initialMinX=painting.minX();job.initialMinZ=painting.minZ();job.initialY=painting.y();
        host.batchPrepare(painting);access.start(painting.y());errors=-1;
        inspection=null;inspectionWaypoint=0;inspectionResume=null;
    }

    private void lever(boolean target,Phase waiting) throws IOException {
        var hit=access.approachLever(config.lever(),tick);checkAccess();
        if(hit==null){report("前往清理拉杆（水中允许沿底板走动）");return;}
        BlockState state=mc.world.getBlockState(config.lever());
        if(!state.isOf(Blocks.LEVER))throw new IOException("登记位置已不是拉杆");
        boolean powered=state.get(LeverBlock.POWERED);
        if(restoringLever) {
            restoringLever=false;
            if(BatchWorkflowRules.leverRecovery(true,target,powered)!=BatchWorkflowRules.LeverRecovery.CONFIRMED)throw new IOException("拉杆待执行记录与实际通断不一致，无法确认是否已触发；请检查现场，不会盲目再切换");
            leverCommitted(waiting);return;
        }
        if(!job.leverIssued) {
            if(BatchWorkflowRules.leverRecovery(false,target,powered)!=BatchWorkflowRules.LeverRecovery.EXECUTE)throw new IOException("拉杆不在预期的起始通断状态，已暂停；开始清理前应关闭拉杆");
            if(mc.player.isSneaking())throw new IOException("请松开潜行键后继续操作拉杆");
            if(job.phase==Phase.CLEAR_ON_1) {
                Scan initial=scan(true);
                if(initial.water()>0)throw new IOException("清理前画布已有水，不能确定投射器状态，请先人工确认出水/收水状态");
            }
            actionAckBefore=blockVersion.get();job.leverIssued=true;save();actionAt=tick;
            mc.interactionManager.interactBlock(mc.player,Hand.MAIN_HAND,hit);
            return;
        }
        LeverAck ack=leverAcks.get(config.lever());
        if(actionAt>=0&&tick-actionAt>=2&&ack!=null&&ack.version()>actionAckBefore&&ack.powered()==target&&powered==target) {
            leverCommitted(waiting);return;
        }
        if(actionAt<0||tick-actionAt>100)throw new IOException("拉杆操作未获服务器确认；请检查现场后继续，不会自动重复触发");
        report("等待拉杆通断确认");
    }
    private void leverCommitted(Phase waiting) throws IOException {
        job.leverIssued=false;job.deadline=System.currentTimeMillis()+20000;transition(waiting);
    }
    private void waitLever(boolean expected,Phase next) throws IOException {
        access.release(tick);
        if(!mc.world.isChunkLoaded(config.lever().getX()>>4,config.lever().getZ()>>4))throw new IOException("等待期间拉杆区块未加载");
        BlockState state=mc.world.getBlockState(config.lever());
        if(!state.isOf(Blocks.LEVER)||state.get(LeverBlock.POWERED)!=expected)throw new IOException("等待期间拉杆被改变，已暂停以避免多触发");
        long left=job.deadline-System.currentTimeMillis();
        if(left>0){report((job.phase==Phase.WAIT_ON_1||job.phase==Phase.WAIT_OFF_1?"第一次触发后等待":"第二次触发后等待")+" "+((left+999)/1000)+"秒");return;}
        if(next==Phase.CLEAR_BUFFER)job.deadline=System.currentTimeMillis()+BatchWorkflowRules.CLEAR_BUFFER_MILLIS;
        transition(next);
    }

    private void waitClearBuffer() throws IOException {
        access.release(tick);
        if(job.deadline<=0){job.deadline=System.currentTimeMillis()+BatchWorkflowRules.CLEAR_BUFFER_MILLIS;save();}
        long left=job.deadline-System.currentTimeMillis();
        if(left>0){report("清理后缓冲 "+((left+999)/1000)+"秒（不检查清空结果）");return;}
        host.batchPrepare(painting);transition(Phase.PRINT);host.batchStartDrawing();
        captureDrawing();save();
    }

    private void captureDrawing() {
        if(job==null||job.phase!=Phase.PRINT||resuming||discovery!=null)return;
        BatchPrintCheckpoint checkpoint=host.batchDrawingCheckpoint();
        if(checkpoint!=null)job.drawing=checkpoint;
    }

    private void checkpointDrawing() throws IOException {
        BatchPrintCheckpoint previous=job.drawing;
        captureDrawing();
        if(job.drawing==null)return;
        if(previous==null||previous.cursor()!=job.drawing.cursor()||!previous.route().equals(job.drawing.route())
            ||previous.openingCleanup()!=job.drawing.openingCleanup()
            ||previous.restockTripsSinceCleanup()!=job.drawing.restockTripsSinceCleanup()
            ||System.currentTimeMillis()-drawingSavedAt>=2000) {
            save();drawingSavedAt=System.currentTimeMillis();
        }
    }

    private void beginInspection() throws IOException {
        captureDrawing();
        host.batchStopWorker();
        job.repairPass=1;
        inspection=null;
        beginRepairPass();
    }

    private void beginRepairPass() throws IOException {
        host.batchBeginReviewRepairs(job.repairPass);
        // Keep the first coverage map. Failed/missing cells get another targeted
        // attempt, not a newly blank matrix and another complete physical lap.
        if(inspection==null) {
            inspectionWaypoint=0;job.inspection=null;canvasScanIndex=0;completedScanRevision=-1;
        } else if(inspection.unknown()>0) inspectionWaypoint=0;
        inspectionResume=null;inspectionRepairTarget=null;inspectionDeferred.clear();
        transition(Phase.REVIEW);
        host.batchMessage(inspection==null
            ? "开始一次全图检查并补漏：分帧读取画布，仅接近缺口；允许漏放 ≤ "+host.batchAllowedMissingBlocks()
            : "保留已检查区域，继续定点补剩余漏格 "+inspection.missing()+"；不重新遍历整张画布");
    }

    private void inspectDrawing() throws IOException {
        if(inspection==null) {
            inspection=new BatchInspection(BatchInspection.SCAN_WIDTH,host.batchReviewReverse(),
                host.batchReviewOriginX(),host.batchReviewOriginZ());
            inspectionWaypoint=0;
        }
        inspectionResume=null; // Perception does not need to retrace a previous physical scan cursor.
        observeNearby();
        if(host.batchRepairNearby()) {
            access.release(tick);
            report("检查并补漏：已检查 "+inspection.observed()+"/16384，漏放 "+inspection.missing()+"（允许 ≤ "+host.batchAllowedMissingBlocks()+"），错占 "+inspection.wrong()+"（不计门槛）");
            return;
        }
        errors=inspection.errors();
        // Finish one fresh, bounded scan after the last server change/placement before accepting.
        int unknown=inspection.unknown(),allowed=host.batchAllowedMissingBlocks();
        if(inspection.canAccept(completedScanRevision,canvasRevision.get(),host.batchPlacementPending(),allowed)) {
            drawingAccepted(allowed);return;
        }
        if(inspectionRepairTarget!=null) {
            BlockPos target=inspectionRepairTarget;
            if(!mc.world.isChunkLoaded(target.getX()>>4,target.getZ()>>4)) {
                int approach=access.walk(target,tick);
                if(approach==0){report("靠近已发现的漏格，加载对应区块");return;}
                access.reset();
                if(approach<0){inspectionDeferred.add(target);inspectionRepairTarget=null;return;}
            }
            access.release(tick);
            if(host.batchRepairTarget(target)){report("定点补漏 "+target.toShortString()+"，剩余漏放 "+inspection.missing());return;}
            inspectionDeferred.add(target);inspectionRepairTarget=null;access.resetTravel();
            observeCell(target.getX()-painting.minX(),target.getZ()-painting.minZ());
        }
        var missing=inspection.nextMissing(mc.player.getBlockX()-painting.minX(),mc.player.getBlockZ()-painting.minZ(),
            point->{BlockPos pos=painting.position(point.x(),point.z());return !inspectionDeferred.contains(pos)&&host.batchRepairEligible(pos);});
        if(missing!=null){inspectionRepairTarget=painting.position(missing.x(),missing.z());access.release(tick);return;}
        // Let the initial scan finish before walking to an area that is already loaded.
        if(completedScanRevision<0){access.release(tick);report("分帧读取画布："+inspection.observed()+"/16384");return;}
        while(inspectionWaypoint<inspection.route().size()&&!inspection.needsVisit(inspection.route().get(inspectionWaypoint)))inspectionWaypoint++;
        if(inspectionWaypoint<inspection.route().size()) {
            var point=inspection.route().get(inspectionWaypoint);
            int route=access.walk(painting.position(point.x(),point.z()),tick);
            checkAccess();
            if(route<0) {
                host.batchMessage("复查路点暂不可达，跳过本点继续走；未读取区域不会算作通过："+painting.position(point.x(),point.z()).toShortString());
                access.reset();inspectionWaypoint++;save();
                return;
            }
            if(route==1){inspectionWaypoint++;save();}
            report("加载未检查区域 "+inspectionWaypoint+"/"+inspection.route().size()+"，已检查 "+inspection.observed()+"/16384，漏放 "+inspection.missing()+"（允许 ≤ "+host.batchAllowedMissingBlocks()+"）");
            return;
        }
        if(host.batchPlacementPending()){access.release(tick);report("等待本轮最后的放置确认");return;}
        // Fresh observations remain valid when the player walks far enough to
        // unload a chunk. Offline checkpoints are invalidated separately on resume.
        if(completedScanRevision!=canvasRevision.get()){access.release(tick);report("正在确认最后一次放置后的实际画布");return;}
        access.reset();errors=inspection.errors();
        unknown=inspection.unknown();allowed=host.batchAllowedMissingBlocks();
        host.batchMessage("本批定点补漏结束：漏放 "+inspection.missing()+"（允许 ≤ "+allowed+"），错占 "+inspection.wrong()+"（仅标记，不计门槛），未读取 "+unknown);
        if(BatchWorkflowRules.reviewAction(job.repairPass,inspection.missing(),unknown,allowed)==BatchWorkflowRules.ReviewAction.ACCEPT)
            drawingAccepted(allowed);
        else {job.repairPass=BatchWorkflowRules.nextRepairPass(job.repairPass);beginRepairPass();}
    }

    private void observeNearby() {
        long started=System.nanoTime();
        for(int count=0;count<BatchInspection.MAX_SCAN_CELLS;count++) {
            if(canvasScanIndex==0)scanStartRevision=canvasRevision.get();
            observeCell(canvasScanIndex%128,canvasScanIndex/128);
            if(++canvasScanIndex==16384){canvasScanIndex=0;completedScanRevision=scanStartRevision;break;}
            if((count&31)==31&&System.nanoTime()-started>=BatchInspection.MAX_SCAN_NANOS)break;
        }
    }

    private void observeCell(int x,int z) {
        BlockPos pos=painting.position(x,z);
        if(!mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))return;
        BlockState actual=observedBlock(pos),expected=painting.at(x,z);
        inspection.observe(x,z,BatchInspection.classify(host.batchExcluded(expected),expected.isAir(),
            actual.equals(expected),actual.isAir(),actual.isReplaceable()));
        host.batchObserveBlock(pos);
    }

    private Scan scan(boolean clear) {
        int incorrect=0,unknown=0,water=0,missing=0;
        for(int z=0;z<128;z++)for(int x=0;x<128;x++) {
            BlockPos pos=painting.position(x,z);
            if(!mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4)){unknown++;continue;}
            BlockState actual=serverBlocks.getOrDefault(pos,mc.world.getBlockState(pos)),expected=painting.at(x,z);
            if(!actual.getFluidState().isEmpty())water++;
            if(clear){if(!actual.isAir()&&actual.getFluidState().isEmpty())incorrect++;
                if(mc.world.getBlockState(pos.down()).getCollisionShape(mc.world,pos.down()).isEmpty())missing++;
            } else if(!host.batchExcluded(expected)&&!actual.equals(expected))incorrect++;
        }
        return new Scan(incorrect,unknown,water,missing);
    }

    private void loadCanvas(String purpose) throws IOException {
        if(waitStarted<0)waitStarted=tick;
        int route=access.walk(painting.center(),tick);
        if(route<0)throw new IOException(purpose+"时无法靠近画布中央以加载全图");
        if(route==1&&tick-waitStarted>200)throw new IOException("画布仍有未加载区块，请提高视距后继续，不能把未加载区域当成完成");
        report(purpose+"：靠近中央加载全图");
    }

    private void drawingAccepted(int allowedMissing) throws IOException {
        if(inspection!=null&&!inspection.canAccept(completedScanRevision,canvasRevision.get(),host.batchPlacementPending(),allowedMissing))return;
        if(inspection==null||BatchWorkflowRules.reviewAction(job.repairPass,inspection.missing(),inspection.unknown(),allowedMissing)
            !=BatchWorkflowRules.ReviewAction.ACCEPT)throw new IOException("漏放检查尚未达到当前允许值，不能开始制图");
        host.batchStopWorker();access.reset();
        host.batchMessage("漏放检查达标："+inspection.missing()+" ≤ "+allowedMissing+" 格；错占 "+inspection.wrong()+" 格不计门槛，进入地图制作");
        job.privateMaps=mapIds();job.initialGlass=count(Items.GLASS_PANE);
        transition(Phase.FETCH_MAP);
    }

    private void fetch(Item wanted,boolean map) throws IOException {
        ScreenHandler handler=open(config.mapSupply(),false);if(handler==null)return;
        int reserved=map?job.mapSlot:job.glassSlot;
        boolean issued=map?job.mapIssued:job.glassIssued;
        if(issued&&reserved>=0) {
            ItemStack owned=mc.player.getInventory().getStack(reserved);
            if(owned.isOf(wanted)&&owned.getCount()==1) {
                fetchAttempts=0;
                transition(map?Phase.FETCH_GLASS:Phase.ACTIVATE_MAP);return;
            }
            if(!owned.isEmpty()||!inventoryFingerprint().equals(job.materialInventoryBefore))
                throw new IOException("领取原料后的背包与记录不符，请将该原料放回登记的背包槽，或检查后重建批次");
            // Same authoritative pre-operation inventory: a rejected/not-sent
            // transfer may be retried without taking a second owned item.
            if(actionAt>=0&&tick-actionAt<40){report("等待原料同步");return;}
        }
        if(reserved<0){reserved=emptySlot();if(reserved<0)throw new IOException("背包没有预留的空槽，无法领取地图原料");}
        int slots=StorageBlockUtils.storageSlots(handler),source=-1;
        for(int i=0;i<slots;i++)if(handler.getSlot(i).getStack().isOf(wanted)){source=i;break;}
        if(source<0)throw new IOException("地图原料箱缺少 "+wanted.getName().getString());
        int destination=handler.getSlotIndex(mc.player.getInventory(),reserved).orElseThrow(()->new IOException("未找到预留背包槽"));
        if(!handler.getSlot(destination).getStack().isEmpty())throw new IOException("地图原料预留槽被占用");
        if(++fetchAttempts>3)throw new IOException("领取原料连续未获服务器确认，已暂停；检查后重新开启本模块自动恢复");
        if(map){job.mapSlot=reserved;job.mapIssued=true;}else{job.glassSlot=reserved;job.glassIssued=true;}
        job.materialInventoryBefore=inventoryFingerprint();save();
        actionAt=tick;if(!transferOne(handler,source,destination))return;
        // Reopen to obtain authoritative contents rather than treating local
        // click prediction as a receipt (also works behind the Esc menu).
        access.reset();report("领取1个 "+wanted.getName().getString()+"，重新确认服务器背包");
    }

    private void activateMap() throws IOException {
        if(job.rawMap>=0) { if(findMap(job.rawMap)<0)throw new IOException("本轮已生成的地图不在背包，已暂停以免重复生成");transition(Phase.CAPTURE);return; }
        if(!job.activationIssued&&!prepareActivationMap())return;
        int route=access.walk(painting.center(),tick);
        if(route<0)throw new IOException("无法走到画布中央");
        if(route==0){report("前往画布中央生成地图");return;}
        access.release(tick);
        Set<Integer> candidates=inventoryMapIds();candidates.removeAll(privateMapIds());
        if(job.activationIssued&&candidates.size()==1) {
            job.rawMap=candidates.iterator().next();save();
            host.batchMessage("空地图已激活，收到本轮地图ID=" + job.rawMap + "；中央持图约2秒后前往制图台");
            captureStarted=-1;transition(Phase.CAPTURE);return;
        }
        if(!candidates.isEmpty())throw new IOException("背包出现未能归属本次激活操作的新地图，已暂停");
        if(job.activationIssued){if(actionAt<0||tick-actionAt>100)throw new IOException("空地图对空气右键后未确认生成结果，已暂停；不会重复消耗另一张地图");report("已对空气使用空地图，等待服务器返回新地图ID");return;}
        if(job.mapSlot<0||!mc.player.getInventory().getStack(job.mapSlot).isOf(Items.MAP))throw new IOException("本轮空地图不在预留槽");
        if(!holdInventorySlot(job.mapSlot,Items.MAP,-1))return;
        useEmptyMapInAir();
    }

    /** Swap beside the supply box and reopen it for an authoritative inventory receipt. */
    private boolean prepareActivationMap() throws IOException {
        if(job.mapSwapSource<0&&job.mapSlot>=0&&job.mapSlot<9) {
            ItemStack owned=mc.player.getInventory().getStack(job.mapSlot);
            if(!owned.isOf(Items.MAP)||owned.getCount()!=1)throw new IOException("本轮单张空地图不在预留快捷栏槽位");
            return true;
        }
        ScreenHandler handler=open(config.mapSupply(),false);if(handler==null)return false;
        if(job.mapSwapSource>=0) {
            ItemStack source=mc.player.getInventory().getStack(job.mapSwapSource);
            ItemStack target=mc.player.getInventory().getStack(job.mapSwapTarget);
            var recovery=BatchJournal.mapSwapRecovery(source.isOf(Items.MAP)?source.getCount():0,
                target.isOf(Items.MAP)?target.getCount():0);
            if(recovery==BatchJournal.MapSwapRecovery.CONFIRMED_DESTINATION) {
                if(job.glassSlot==job.mapSwapTarget)job.glassSlot=job.mapSwapSource;
                job.mapSlot=job.mapSwapTarget;job.mapSwapSource=job.mapSwapTarget=-1;
                save();access.reset();report("服务器已确认空地图换手，前往画布中央");return false;
            }
            if(recovery!=BatchJournal.MapSwapRecovery.RETRY_SOURCE)
                throw new IOException("空地图换手的两个槽位无法唯一确认本轮地图；保留当前阶段，不会重新领取地图");
            job.mapSlot=job.mapSwapSource;
            job.mapSwapSource=job.mapSwapTarget=-1;save();
        }
        if(job.mapSlot<9||job.mapSlot>=36)throw new IOException("本轮空地图的背包槽位无效");
        ItemStack source=mc.player.getInventory().getStack(job.mapSlot);
        if(!source.isOf(Items.MAP)||source.getCount()!=1)throw new IOException("本轮单张空地图不在登记的背包槽位，不会重复领取");
        int target=mc.player.getInventory().getSelectedSlot();
        if(mc.player.getInventory().getStack(target).isOf(Items.MAP)) {
            target=-1;
            for(int slot=0;slot<9;slot++)if(!mc.player.getInventory().getStack(slot).isOf(Items.MAP)){target=slot;break;}
        }
        if(target<0)throw new IOException("快捷栏均为空地图，无法唯一确认本轮地图的换手结果");
        int sourceSlot=handler.getSlotIndex(mc.player.getInventory(),job.mapSlot)
            .orElseThrow(()->new IOException("地图原料箱未提供本轮地图的背包槽位"));
        if(++mapSwapAttempts>3)throw new IOException("空地图换手连续未获服务器确认，保留本轮地图并暂停");
        // Keep mapSlot pointing at the old confirmed slot until the fresh
        // container inventory proves which side of this atomic swap survived.
        job.mapSwapSource=job.mapSlot;job.mapSwapTarget=target;save();
        click(handler,sourceSlot,target,SlotActionType.SWAP);
        access.reset();report("空地图换到快捷栏，重新开箱确认服务器背包");return false;
    }

    private void useEmptyMapInAir() throws IOException {
        if(mc.player.currentScreenHandler!=mc.player.playerScreenHandler)
            throw new IOException("请关闭容器后再激活空地图");
        ItemStack held=mc.player.getMainHandStack();
        if(mc.player.getInventory().getSelectedSlot()!=job.mapSlot||!held.isOf(Items.MAP)||held.getCount()!=1)
            throw new IOException("主手不是本轮领取的单张空地图，已暂停激活");
        // Write the intent first: an interrupted response must not cause a second use.
        job.activationIssued=true;save();actionAt=tick;
        // Direct item use is the air-right-click path, regardless of the crosshair.
        // Never use interactBlock or hold the use key: either can open a dispenser.
        mc.interactionManager.interactItem(mc.player,Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        report("已在画布中央对空气右键激活空地图，等待新地图ID");
        host.batchMessage("已在画布中央对空气使用本轮空地图；确认激活前不会进入制图台步骤");
    }

    private void capture() throws IOException {
        if(!holdMap(job.rawMap))return;
        if(captureStarted<0)captureStarted=System.currentTimeMillis();
        long left=2000-(System.currentTimeMillis()-captureStarted);
        if(left>0){report("中央持图约2秒："+((left+999)/1000)+"秒");return;}
        transition(Phase.LOCK);
    }

    private void lock() throws IOException {
        ScreenHandler opened=open(config.table(),true);if(!(opened instanceof CartographyTableScreenHandler handler))return;
        if(actionAt>=0&&tick-actionAt<6){report("等待制图台槽位同步");return;}
        ItemStack input=handler.getSlot(0).getStack(),pane=handler.getSlot(1).getStack();
        if(!input.isEmpty()&&mapId(input)!=job.rawMap)throw new IOException("制图台里出现其他地图，已暂停");
        if(!pane.isEmpty()&&(!pane.isOf(Items.GLASS_PANE)||pane.getCount()!=1))throw new IOException("制图台材料槽不是本轮的一个玻璃板");
        if(input.isEmpty()) {
            int source=findHandlerMap(handler,job.rawMap);
            if(source<0)throw new IOException("本轮地图不在背包或制图台，无法继续锁定");
            if(++lockInputAttempts>6)throw new IOException("制图台连续拒绝输入材料，已暂停，请检查服务器限制后继续");
            save();actionAt=tick;transferOne(handler,source,0);return;
        }
        if(pane.isEmpty()) {
            if(count(Items.GLASS_PANE)<=job.initialGlass)throw new IOException("本轮玻璃板缺失");
            int source=findHandlerItem(handler,Items.GLASS_PANE);
            if(source<0)throw new IOException("背包中没有玻璃板");
            if(++lockInputAttempts>6)throw new IOException("制图台连续拒绝输入材料，已暂停，请检查服务器限制后继续");
            save();actionAt=tick;transferOne(handler,source,1);return;
        }
        ItemStack result=handler.getSlot(2).getStack();
        if(!result.isOf(Items.FILLED_MAP)||result.get(DataComponentTypes.MAP_POST_PROCESSING)!=MapPostProcessingComponent.LOCK) {
            if(waitStarted<0)waitStarted=tick;
            if(tick-waitStarted>100)throw new IOException("制图台没有给出玻璃板锁定结果");
            report("等待制图台锁定配方结果");return;
        }
        if(++lockAttempts>3)throw new IOException("制图台锁定连续未获确认，保留本轮地图并暂停");
        job.lockIssued=true;job.phase=Phase.TAKE_LOCKED;save();
        click(handler,2,0,SlotActionType.QUICK_MOVE);actionAt=tick;
        access.reset();
    }

    private void takeLocked() throws IOException {
        // A fresh full container packet settles the post-processing map ID.
        ScreenHandler handler=open(config.table(),true);if(handler==null)return;
        if(job.lockedMap>=0){transition(Phase.CONFIRM_LOCK);return;}
        Set<Integer> ids=inventoryMapIds();ids.removeAll(privateMapIds());ids.remove(job.rawMap);
        if(ids.size()==1) {job.lockedMap=ids.iterator().next();save();transition(Phase.CONFIRM_LOCK);return;}
        if(ids.size()>1)throw new IOException("出现多张新地图，无法确认锁定产物");
        if(findMap(job.rawMap)>=0&&Boolean.TRUE.equals(lockedMaps.get(job.rawMap))) {
            job.lockedMap=job.rawMap;save();transition(Phase.CONFIRM_LOCK);return;
        }
        if(findMap(job.rawMap)>=0&&count(Items.GLASS_PANE)==job.initialGlass) {
            job.lockedMap=job.rawMap;save();transition(Phase.CONFIRM_LOCK);return;
        }
        if(findMap(job.rawMap)>=0&&count(Items.GLASS_PANE)>job.initialGlass) {
            // Closing an unacknowledged cartography screen returns both
            // inputs. The same map may be retried; never create a new map.
            job.lockIssued=false;transition(Phase.LOCK);return;
        }
        throw new IOException("锁定产物的地图ID未能确认，请检查背包和制图台后继续");
    }

    private void confirmLocked() throws IOException {
        access.reset();
        if(!holdMap(job.lockedMap))return;
        var state=FilledMapItem.getMapState(mc.player.getMainHandStack(),mc.world);
        if(Boolean.TRUE.equals(lockedMaps.get(job.lockedMap))||(state!=null&&state.locked)) {
            job.lockConfirmed=true;save();transition(Phase.STORE);return;
        }
        if(waitStarted<0)waitStarted=tick;
        if(tick-waitStarted>200)throw new IOException("未收到本轮地图的服务器锁定状态，不会把未确认地图当成成品");
        report("持图等待服务器确认锁定状态");
    }

    private void store() throws IOException {
        if(!job.lockConfirmed||job.lockedMap<0)throw new IOException("本轮成品尚未确认锁定");
        ScreenHandler handler=open(config.product(),false);if(handler==null)return;
        int slots=StorageBlockUtils.storageSlots(handler),inBox=0;
        for(int i=0;i<slots;i++)if(mapId(handler.getSlot(i).getStack())==job.lockedMap)inBox+=handler.getSlot(i).getStack().getCount();
        var recovery=BatchWorkflowRules.productRecovery(findMap(job.lockedMap)>=0?1:0,inBox);
        if(recovery==BatchWorkflowRules.ProductRecovery.AMBIGUOUS)throw new IOException("成品箱与背包同时存在本轮地图，请核对数量，避免重复存入");
        if(recovery==BatchWorkflowRules.ProductRecovery.CONFIRMED) {
            host.batchMessage(painting.name()+" 已确认入箱，地图ID="+job.lockedMap);transition(Phase.NEXT);return;
        }
        int source=findHandlerMap(handler,job.lockedMap);
        if(source<0)throw new IOException("本轮锁定地图既不在背包，也不在成品箱，不会清理画布或跳到下一张");
        ItemStack stack=handler.getSlot(source).getStack();int destination=-1;
        for(int i=0;i<slots;i++) {
            ItemStack existing=handler.getSlot(i).getStack();
            if(handler.getSlot(i).canInsert(stack)&&(existing.isEmpty()||ItemStack.areItemsAndComponentsEqual(existing,stack)&&existing.getCount()<existing.getMaxCount())){destination=i;break;}
        }
        if(destination<0)throw new IOException("成品箱已满，本轮锁定地图保留在背包");
        if(++depositAttempts>3)throw new IOException("成品转移连续未获服务器确认，已暂停，不推进下一张");
        // Journal the intent without transition(): that helper closes the
        // owned screen, which must stay open until the click is dispatched.
        job.depositIssued=true;job.phase=Phase.STORE_PENDING;save();
        if(!transferOne(handler,source,destination))return;
        access.reset();report("存入本轮地图，重新开箱确认");
    }

    private ScreenHandler open(BlockPos pos,boolean cartography) throws IOException {
        ScreenHandler handler=access.open(pos,cartography,tick);checkAccess();
        if(handler==null)report(access.activity());
        return handler;
    }
    private void checkAccess() throws IOException {if(access.failure()!=null)throw new IOException(access.failure());}
    private boolean cursorStorageAllowed(ItemStack stack) {
        if(host.batchCursorStorageAllowed(stack))return true;
        ScreenHandler handler=mc.player.currentScreenHandler;
        if(job==null||!access.owns(handler)||StorageBlockUtils.storageSlots(handler)<=0)return false;
        BlockPos position=access.ownedPosition(handler);
        if(config.mapSupply().equals(position))return stack.isOf(Items.MAP)||stack.isOf(Items.GLASS_PANE);
        return config.product().equals(position)&&job.lockedMap>=0&&mapId(stack)==job.lockedMap;
    }
    private void transition(Phase next) throws IOException {
        access.reset();job.phase=next;job.pause="";actionAt=-1;waitStarted=-1;save();report(next.name());
    }
    private void save() throws IOException {
        if(job==null||!runtimeRestored||resuming||discovery!=null)return;
        long now=System.nanoTime();
        if(clockNanos>=0&&running)job.elapsedMillis+=Math.max(0,now-clockNanos)/1_000_000L;
        clockNanos=running?now:-1;
        if(BatchWorkflowRules.leverWait(job.phase)||job.phase==Phase.CLEAR_BUFFER)
            job.waitRemainingMillis=Math.max(0,Math.min(20000,job.deadline-System.currentTimeMillis()));
        else if(job.phase==Phase.CAPTURE)
            job.waitRemainingMillis=captureStarted<0?2000:Math.max(0,2000-(System.currentTimeMillis()-captureStarted));
        else job.waitRemainingMillis=-1;
        if(job.phase==Phase.REVIEW&&inspection!=null&&mc.player!=null) {
            BlockPos resume=inspectionResume!=null?inspectionResume:mc.player.getBlockPos();
            job.inspection=inspection.checkpoint(inspectionWaypoint,resume.getX(),resume.getZ());
        }
        try { journal.write(job); }
        catch(IOException exception){stopSaveFailure=exception;com.buildertoolbox.BuilderToolboxAddon.LOG.error("Could not persist full-auto batch checkpoint",exception);throw exception;}
        stopSaveFailure=null;
        lastSavedNanos=now;
    }
    private void pause(String reason) {
        captureDrawing();status=reason;
        if(job!=null){job.pause=reason;try{save();}catch(IOException exception){status+="；保存失败："+exception.getMessage();}}
        running=false;clockNanos=-1;host.batchStopWorker();access.release(tick);
        host.batchPauseWorker(status());host.batchMessage("全自动已暂停："+status());
    }
    private void report(String text) {status=text;host.batchStatus(status());}
    private String prefix() {
        if(job==null||job.queue.isEmpty())return "[全自动] ";
        String file=job.index<job.queue.size()?job.queue.get(job.index).name():"完成";
        return "["+queueProgress()+"："+file+(inspection!=null?" 漏放="+inspection.missing()+"/"+host.batchAllowedMissingBlocks()
            +" 错占="+inspection.wrong()+"（不计门槛）":errors>=0?" 观察差异="+errors:"")+"] ";
    }
    private static String message(Throwable exception) {while((exception instanceof java.util.concurrent.CompletionException || exception instanceof java.util.concurrent.ExecutionException) && exception.getCause()!=null)exception=exception.getCause();return exception.getMessage()==null?exception.toString():exception.getMessage();}

    public void receive(Packet<?> packet) {
        cursorRecovery.onPacket(packet);
        if(packet instanceof InventoryS2CPacket inventory)access.inventoryReceived(inventory.syncId());
        if(packet instanceof MapUpdateS2CPacket map)lockedMaps.put(map.mapId().id(),map.locked());
        if(packet instanceof BlockUpdateS2CPacket block)observeBlock(block.getPos(),block.getState());
        if(packet instanceof ChunkDeltaUpdateS2CPacket delta)delta.visitUpdates(this::observeBlock);
        if(packet instanceof ChunkDataS2CPacket chunk) {
            serverBlocks.keySet().removeIf(pos->(pos.getX()>>4)==chunk.getChunkX()&&(pos.getZ()>>4)==chunk.getChunkZ());
            BatchPainting current=painting;
            if(current!=null&&chunk.getChunkX()>=(current.minX()>>4)&&chunk.getChunkX()<=((current.minX()+127)>>4)
                &&chunk.getChunkZ()>=(current.minZ()>>4)&&chunk.getChunkZ()<=((current.minZ()+127)>>4))canvasRevision.incrementAndGet();
        }
    }
    private void observeBlock(BlockPos pos,BlockState state) {
        if(pos.equals(watchedLever)&&state.isOf(Blocks.LEVER))leverAcks.put(pos.toImmutable(),new LeverAck(blockVersion.incrementAndGet(),state.get(LeverBlock.POWERED)));
        BatchPainting current=painting;
        if(current!=null&&pos.getY()==current.y()&&pos.getX()>=current.minX()&&pos.getX()<current.minX()+128
            &&pos.getZ()>=current.minZ()&&pos.getZ()<current.minZ()+128) {
            serverBlocks.put(pos.toImmutable(),state);canvasRevision.incrementAndGet();
        }
    }
    private int emptySlot() {for(int i=0;i<36;i++)if(mc.player.getInventory().getStack(i).isEmpty())return i;return -1;}
    private int count(Item item) {int count=0;for(int i=0;i<36;i++)if(mc.player.getInventory().getStack(i).isOf(item))count+=mc.player.getInventory().getStack(i).getCount();return count;}
    private int mapId(ItemStack stack) {var id=stack.get(DataComponentTypes.MAP_ID);return stack.isOf(Items.FILLED_MAP)&&id!=null?id.id():-1;}
    private int findMap(int id) {if(id<0)return -1;for(int i=0;i<36;i++)if(mapId(mc.player.getInventory().getStack(i))==id)return i;return -1;}
    private Set<Integer> inventoryMapIds() {Set<Integer> ids=new HashSet<>();for(int i=0;i<36;i++){int id=mapId(mc.player.getInventory().getStack(i));if(id>=0)ids.add(id);}return ids;}
    private String mapIds() {return inventoryMapIds().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
    private Set<Integer> privateMapIds() {Set<Integer> ids=new HashSet<>();if(!job.privateMaps.isBlank())for(String id:job.privateMaps.split(","))ids.add(Integer.parseInt(id));return ids;}
    private int findHandlerMap(ScreenHandler h,int id) {for(int i=0;i<h.slots.size();i++)if(h.getSlot(i).inventory==mc.player.getInventory()&&mapId(h.getSlot(i).getStack())==id)return i;return -1;}
    private int findHandlerItem(ScreenHandler h,Item item) {for(int i=0;i<h.slots.size();i++)if(h.getSlot(i).inventory==mc.player.getInventory()&&h.getSlot(i).getStack().isOf(item))return i;return -1;}
    private void click(ScreenHandler h,int slot,int button,SlotActionType type) throws IOException {
        if(mc.player.currentScreenHandler!=h)throw new IOException("容器在操作中发生变化");
        mc.interactionManager.clickSlot(h.syncId,slot,button,type,mc.player);
    }
    private boolean transferOne(ScreenHandler h,int from,int to) throws IOException {
        if(!h.getCursorStack().isEmpty()){report("自动收回鼠标物品后继续取放");return false;}
        ItemStack source=h.getSlot(from).getStack().copy();
        if(source.isEmpty())throw new IOException("来源槽已空");
        cursorRecovery.rememberOrigin(h,from);
        click(h,from,0,SlotActionType.PICKUP);
        try {click(h,to,1,SlotActionType.PICKUP);}
        finally {
            var cursor=h.getCursorStack();var original=h.getSlot(from).getStack();
            if(!cursor.isEmpty()&&(original.isEmpty()||ItemStack.areItemsAndComponentsEqual(cursor,original))
                &&cursor.getCount()+original.getCount()<=cursor.getMaxCount())click(h,from,0,SlotActionType.PICKUP);
        }
        if(!h.getCursorStack().isEmpty()){report("取放中断，自动收回鼠标物品后继续");return false;}
        return true;
    }
    private boolean holdMap(int id) throws IOException {
        int slot=findMap(id);if(slot<0)throw new IOException("本轮地图ID="+id+" 不在背包");
        return holdInventorySlot(slot,Items.FILLED_MAP,id);
    }
    private boolean holdInventorySlot(int slot,Item item,int map) {
        ItemStack held=mc.player.getMainHandStack();
        if(mc.player.getInventory().getSelectedSlot()==slot&&held.isOf(item)&&(map<0||mapId(held)==map))return true;
        if(slot<9)InvUtils.swap(slot,false);
        else if(mc.player.currentScreenHandler==mc.player.playerScreenHandler
            &&mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            int source=mc.player.playerScreenHandler.getSlotIndex(mc.player.getInventory(),slot).orElse(-1);
            if(source>=0)mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,source,
                mc.player.getInventory().getSelectedSlot(),SlotActionType.SWAP,mc.player);
        }
        return false;
    }
    private String inventoryFingerprint() {
        StringBuilder text=new StringBuilder();for(int i=0;i<36;i++){
            ItemStack stack=mc.player.getInventory().getStack(i);text.append(i).append(':').append(stack.getItem()).append(':').append(stack.getCount()).append(':').append(stack.getComponents()).append(';');
        }return BatchFiles.hash(text.toString());
    }
    private String world() {return mc.getServer()!=null?"local:"+mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath():mc.getCurrentServerEntry()==null?"unknown":"server:"+mc.getCurrentServerEntry().address;}
    private String dimension() {return mc.world.getRegistryKey().getValue().toString();}
}
