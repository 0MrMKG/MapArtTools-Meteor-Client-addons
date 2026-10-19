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
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
        void batchStopWorker();
        boolean batchPlacementPending();
        boolean batchReadyForReview();
        void batchHoldForReview();
        int batchReviewWidth();
        boolean batchReviewReverse();
        int batchRemaining();
        int batchLane();
        boolean batchWorkerPaused();
        void batchRetryDrawing(int errors);
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
    private final AtomicLong blockVersion = new AtomicLong();
    private final ConcurrentHashMap<BlockPos,LeverAck> leverAcks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Boolean> lockedMaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos,BlockState> serverBlocks = new ConcurrentHashMap<>();
    private volatile BlockPos watchedLever;
    private BatchJournal.State job;
    private Configuration config;
    private volatile BatchPainting painting;
    private CompletableFuture<List<BatchFiles.Entry>> discovery;
    private Path folder;
    private boolean running;
    private boolean resuming;
    private boolean restoringLever;
    private long tick, actionAt=-1, actionAckBefore, waitStarted=-1, lastVerification=-100, captureStarted=-1;
    private int errors=-1, lastLane=Integer.MIN_VALUE;
    private int fetchAttempts, lockInputAttempts, lockAttempts, depositAttempts;
    private BatchInspection inspection;
    private int inspectionWaypoint;
    private String status="尚未开始";

    public MapArtBatchController(Host host,MinecraftClient mc) {
        this.host=host;this.mc=mc;access=new BatchWorkstationAccess(mc);
        journal=new BatchJournal(FabricLoader.getInstance().getConfigDir().resolve("builder_toolbox/fullauto-batch.properties"));
    }
    public boolean running() { return running; }
    public boolean printing() { return running&&job!=null&&job.phase==Phase.PRINT; }
    public int reservedSlots() { return printing()?2:0; }
    public String status() { return prefix()+status; }
    public boolean hidesScreen(Screen screen) { return access.hidesScreen(screen); }
    public boolean closingMenu() { return access.closingMenu(); }

    public void startOrResume() {
        stop();
        fetchAttempts=lockInputAttempts=lockAttempts=depositAttempts=0;
        if(mc.player==null||mc.world==null)return;
        try {
            config=host.batchConfiguration();config.validate();
            if(!mc.player.currentScreenHandler.getCursorStack().isEmpty())throw new IOException("请先放回鼠标持有的物品");
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
            actionAt=-1;waitStarted=-1;lastLane=Integer.MIN_VALUE;
            status=job==null?"读取队列":"恢复阶段："+job.phase;
            host.batchMessage("全自动批次开始/恢复："+config.folder());
        } catch(Exception exception) {pause(message(exception));}
    }

    public void stop() {
        if(discovery!=null){discovery.cancel(true);discovery=null;}
        if(running&&job!=null)try{journal.write(job);}catch(IOException exception){host.batchMessage("保存进度失败："+exception.getMessage());}
        running=false;resuming=false;access.reset();actionAt=-1;waitStarted=-1;
        serverBlocks.clear();leverAcks.clear();lockedMaps.clear();
        inspection=null;inspectionWaypoint=0;
    }

    public void reset() {
        stop();
        try {
            if(job!=null&&mc.player!=null&&(findMap(job.rawMap)>=0||findMap(job.lockedMap)>=0))
                throw new IOException("本轮地图仍在背包，请先继续锁定/入箱后再重置，避免遗失归属");
            journal.archive();job=null;painting=null;status="批次已重置，旧记录保留为 .previous";
            host.batchPauseWorker(status);
        }catch(IOException exception){pause(exception.getMessage());}
    }

    /** Returns true when the workflow owns this tick; false lets the snake worker tick. */
    public boolean tick() {
        tick++;
        if(!running)return true;
        try {
            if(!config.signature().equals(host.batchConfiguration().signature()))throw new IOException("位置、目录或颜色筛选已改变，已暂停；恢复原配置后可续作");
            if(job!=null&&(!job.world.equals(world())||!job.dimension.equals(dimension())))throw new IOException("服务器/存档或维度发生变化");
            if(!mc.player.currentScreenHandler.getCursorStack().isEmpty())throw new IOException("鼠标持有物品，已暂停；请放回物品后继续");
            if(discovery!=null) {
                if(!discovery.isDone()){report("读取目录与文件指纹");return true;}
                List<BatchFiles.Entry> files=discovery.join();discovery=null;
                if(resuming) {
                    resuming=false;loadPainting();job.pause="";
                    restoringLever=job.leverIssued;
                    if(BatchWorkflowRules.leverWait(job.phase))job.deadline=System.currentTimeMillis()+20000;
                    if(job.phase==Phase.CLEAR_BUFFER||job.phase==Phase.CHECK_CLEAR) {
                        job.phase=Phase.CLEAR_BUFFER;
                        job.deadline=System.currentTimeMillis()+BatchWorkflowRules.CLEAR_BUFFER_MILLIS;
                    }
                    if(job.phase==Phase.CAPTURE)captureStarted=-1;
                    if(job.phase==Phase.PRINT)host.batchStartDrawing();
                } else {
                    job=new BatchJournal.State();job.world=world();job.dimension=dimension();job.folder=folder.toString();
                    job.configuration=config.signature();job.locations=config.locations();job.queue=new ArrayList<>(files);
                }
                save();
            }
            if(job==null)throw new IOException("批次状态未初始化");
            switch(job.phase) {
                case LOAD -> {
                    if(painting==null)loadPainting();
                    String preflight=host.batchPreflight();
                    if(preflight!=null)throw new IOException(preflight);
                    Scan initial=scan(true);
                    if(initial.unknown()>0){loadCanvas("清理前范围检查");return true;}
                    if(initial.missingSupport()>0)throw new IOException("画布缺少底板 "+initial.missingSupport()+" 格，尚未触发清理");
                    if(initial.water()>0)throw new IOException("画布已有水，需先人工确认清理装置状态，尚未触发拉杆");
                    fetchAttempts=lockInputAttempts=lockAttempts=depositAttempts=0;
                    job.privateMaps=mapIds();job.initialGlass=count(Items.GLASS_PANE);save();
                    transition(Phase.CLEAR_ON_1);
                }
                case CLEAR_ON_1,CLEAR_OFF_1,CLEAR_ON_2,CLEAR_OFF_2 -> lever(BatchWorkflowRules.leverPowered(job.phase),BatchWorkflowRules.nextLeverPhase(job.phase));
                case WAIT_ON_1,WAIT_OFF_1,WAIT_ON_2,WAIT_OFF_2 -> waitLever(BatchWorkflowRules.leverPowered(job.phase),BatchWorkflowRules.nextLeverPhase(job.phase));
                // Legacy checkpoints migrate without inspecting the canvas.
                case CHECK_CLEAR -> {job.deadline=System.currentTimeMillis()+BatchWorkflowRules.CLEAR_BUFFER_MILLIS;transition(Phase.CLEAR_BUFFER);}
                case CLEAR_BUFFER -> waitClearBuffer();
                case PRINT -> {
                    if(host.batchWorkerPaused()){pause("绘制已暂停，请检查材料或放置状态，再点击 Start / Resume");return true;}
                    int lane=host.batchLane();
                    if(host.batchReadyForReview()&&tick-lastVerification>=20&&(host.batchRemaining()<50||lane!=lastLane)) {
                        // Freeze new work at the band boundary, but allow an
                        // already queued rotation/placement to finish first.
                        host.batchHoldForReview();
                        if(host.batchPlacementPending()){report("条带复查：等待在途放置结束");return true;}
                        lastLane=lane;lastVerification=tick;
                        Scan scan=scan(false);errors=scan.errors();
                        if(scan.unknown()==0&&BatchFiles.acceptableErrors(errors)){beginInspection();return true;}
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
    }

    private void lever(boolean target,Phase waiting) throws IOException {
        var hit=access.approach(config.lever(),tick);checkAccess();
        if(hit==null){report("前往清理拉杆");return;}
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
    }

    private void beginInspection() throws IOException {
        host.batchStopWorker();
        inspection=null;inspectionWaypoint=0;
        transition(Phase.REVIEW);
        host.batchMessage("开始实际走位复查：蛇形从起点走到终点，途中不放置，分别统计漏放和错放");
    }

    private void inspectDrawing() throws IOException {
        if(inspection==null) {
            inspection=new BatchInspection(Math.max(1,Math.min(5,host.batchReviewWidth())),host.batchReviewReverse());
            inspectionWaypoint=0;
        }
        if(tick%5==0||inspectionWaypoint>=inspection.route().size())observeNearby();
        errors=inspection.errors();
        if(inspectionWaypoint<inspection.route().size()) {
            var point=inspection.route().get(inspectionWaypoint);
            int route=access.walk(painting.position(point.x(),point.z()),tick);
            checkAccess();
            if(route<0)throw new IOException("实际复查路点不可达："+painting.position(point.x(),point.z()).toShortString());
            if(route==1){observeNearby();inspectionWaypoint++;}
            report("走位复查 "+inspectionWaypoint+"/"+inspection.route().size()+"，已检查 "+inspection.observed()+"/16384，漏放 "+inspection.missing()+"，错放 "+inspection.wrong());
            return;
        }
        access.reset();errors=inspection.errors();
        if(!inspection.complete())throw new IOException("走位复查仍有 "+inspection.unknown()+" 格未实际读取，不能当成完成，请检查道路或区块加载后继续");
        host.batchMessage("整图走位复查结束：漏放 "+inspection.missing()+"，错放 "+inspection.wrong()+"，合计 "+errors);
        if(BatchFiles.acceptableErrors(errors))drawingAccepted();
        else {transition(Phase.PRINT);host.batchRetryDrawing(errors);}
    }

    private void observeNearby() {
        int playerX=mc.player.getBlockX()-painting.minX(),playerZ=mc.player.getBlockZ()-painting.minZ();
        for(int z=Math.max(0,playerZ-BatchInspection.RADIUS);z<=Math.min(127,playerZ+BatchInspection.RADIUS);z++)
            for(int x=Math.max(0,playerX-BatchInspection.RADIUS);x<=Math.min(127,playerX+BatchInspection.RADIUS);x++) {
                BlockPos pos=painting.position(x,z);
                if(!mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))continue;
                BlockState actual=serverBlocks.getOrDefault(pos,mc.world.getBlockState(pos)),expected=painting.at(x,z);
                BatchInspection.Cell result=host.batchExcluded(expected)||actual.equals(expected)?BatchInspection.Cell.CORRECT
                    :!expected.isAir()&&actual.isAir()?BatchInspection.Cell.MISSING:BatchInspection.Cell.WRONG;
                inspection.observe(x,z,result);
            }
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

    private void drawingAccepted() throws IOException {
        host.batchStopWorker();access.reset();
        host.batchMessage("实际世界复查："+errors+" 格不正确（小于50），进入地图制作");
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
        if(++fetchAttempts>3)throw new IOException("领取原料连续未获服务器确认，已暂停；检查后点击 Start / Resume 重试");
        if(map){job.mapSlot=reserved;job.mapIssued=true;}else{job.glassSlot=reserved;job.glassIssued=true;}
        job.materialInventoryBefore=inventoryFingerprint();save();
        transferOne(handler,source,destination);actionAt=tick;
        // Reopen to obtain authoritative contents rather than treating local
        // click prediction as a receipt (also works behind the Esc menu).
        access.reset();report("领取1个 "+wanted.getName().getString()+"，重新确认服务器背包");
    }

    private void activateMap() throws IOException {
        if(job.rawMap>=0) { if(findMap(job.rawMap)<0)throw new IOException("本轮已生成的地图不在背包，已暂停以免重复生成");transition(Phase.CAPTURE);return; }
        int route=access.walk(painting.center(),tick);
        if(route<0)throw new IOException("无法走到画布中央");
        if(route==0){report("前往画布中央生成地图");return;}
        access.release(tick);
        Set<Integer> candidates=inventoryMapIds();candidates.removeAll(privateMapIds());
        if(job.activationIssued&&candidates.size()==1) {
            job.rawMap=candidates.iterator().next();save();captureStarted=-1;transition(Phase.CAPTURE);return;
        }
        if(!candidates.isEmpty())throw new IOException("背包出现未能归属本次激活操作的新地图，已暂停");
        if(job.activationIssued){if(actionAt<0||tick-actionAt>100)throw new IOException("空地图使用结果不确定，已暂停；不会再拿一张重做");report("等待新地图ID");return;}
        if(job.mapSlot<0||!mc.player.getInventory().getStack(job.mapSlot).isOf(Items.MAP))throw new IOException("本轮空地图不在预留槽");
        if(!holdInventorySlot(job.mapSlot,Items.MAP,-1)) {
            if(job.mapSlot>=9){job.mapSlot=mc.player.getInventory().getSelectedSlot();save();}
            return;
        }
        job.activationIssued=true;save();actionAt=tick;
        mc.interactionManager.interactItem(mc.player,Hand.MAIN_HAND);report("在中央使用本轮空地图");
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
            save();transferOne(handler,source,0);actionAt=tick;return;
        }
        if(pane.isEmpty()) {
            if(count(Items.GLASS_PANE)<=job.initialGlass)throw new IOException("本轮玻璃板缺失");
            int source=findHandlerItem(handler,Items.GLASS_PANE);
            if(source<0)throw new IOException("背包中没有玻璃板");
            if(++lockInputAttempts>6)throw new IOException("制图台连续拒绝输入材料，已暂停，请检查服务器限制后继续");
            save();transferOne(handler,source,1);actionAt=tick;return;
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
        transferOne(handler,source,destination);access.reset();report("存入本轮地图，重新开箱确认");
    }

    private ScreenHandler open(BlockPos pos,boolean cartography) throws IOException {
        ScreenHandler handler=access.open(pos,cartography,tick);checkAccess();
        if(handler==null)report(access.activity());
        return handler;
    }
    private void checkAccess() throws IOException {if(access.failure()!=null)throw new IOException(access.failure());}
    private void transition(Phase next) throws IOException {
        access.reset();job.phase=next;job.pause="";actionAt=-1;waitStarted=-1;save();report(next.name());
    }
    private void save() throws IOException {if(job!=null)journal.write(job);}
    private void pause(String reason) {
        running=false;status=reason;host.batchStopWorker();access.release(tick);
        if(job!=null){job.pause=reason;try{journal.write(job);}catch(IOException exception){status+="；保存失败："+exception.getMessage();}}
        host.batchPauseWorker(status());host.batchMessage("全自动已暂停："+status());
    }
    private void report(String text) {status=text;host.batchStatus(status());}
    private String prefix() {
        if(job==null||job.queue.isEmpty())return "[全自动] ";
        String file=job.index<job.queue.size()?job.queue.get(job.index).name():"完成";
        return "["+Math.min(job.index+1,job.queue.size())+"/"+job.queue.size()+" "+file+(errors>=0?" 错误="+errors:"")+"] ";
    }
    private static String message(Throwable exception) {while(exception.getCause()!=null)exception=exception.getCause();return exception.getMessage()==null?exception.toString():exception.getMessage();}

    public void receive(Packet<?> packet) {
        if(packet instanceof InventoryS2CPacket inventory)access.inventoryReceived(inventory.syncId());
        if(packet instanceof MapUpdateS2CPacket map)lockedMaps.put(map.mapId().id(),map.locked());
        if(packet instanceof BlockUpdateS2CPacket block)observeBlock(block.getPos(),block.getState());
        if(packet instanceof ChunkDeltaUpdateS2CPacket delta)delta.visitUpdates(this::observeBlock);
        if(packet instanceof ChunkDataS2CPacket chunk)serverBlocks.keySet().removeIf(pos->(pos.getX()>>4)==chunk.getChunkX()&&(pos.getZ()>>4)==chunk.getChunkZ());
    }
    private void observeBlock(BlockPos pos,BlockState state) {
        if(pos.equals(watchedLever)&&state.isOf(Blocks.LEVER))leverAcks.put(pos.toImmutable(),new LeverAck(blockVersion.incrementAndGet(),state.get(LeverBlock.POWERED)));
        BatchPainting current=painting;
        if(current!=null&&pos.getY()==current.y()&&pos.getX()>=current.minX()&&pos.getX()<current.minX()+128
            &&pos.getZ()>=current.minZ()&&pos.getZ()<current.minZ()+128)serverBlocks.put(pos.toImmutable(),state);
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
    private void transferOne(ScreenHandler h,int from,int to) throws IOException {
        if(!h.getCursorStack().isEmpty())throw new IOException("鼠标已有物品，拒绝取放");
        ItemStack source=h.getSlot(from).getStack().copy();
        if(source.isEmpty())throw new IOException("来源槽已空");
        click(h,from,0,SlotActionType.PICKUP);
        try {click(h,to,1,SlotActionType.PICKUP);}
        finally {
            var cursor=h.getCursorStack();var original=h.getSlot(from).getStack();
            if(!cursor.isEmpty()&&(original.isEmpty()||ItemStack.areItemsAndComponentsEqual(cursor,original))
                &&cursor.getCount()+original.getCount()<=cursor.getMaxCount())click(h,from,0,SlotActionType.PICKUP);
        }
        if(!h.getCursorStack().isEmpty())throw new IOException("取放后鼠标仍有物品，请手动放回后继续");
    }
    private boolean holdMap(int id) throws IOException {
        int slot=findMap(id);if(slot<0)throw new IOException("本轮地图ID="+id+" 不在背包");
        return holdInventorySlot(slot,Items.FILLED_MAP,id);
    }
    private boolean holdInventorySlot(int slot,Item item,int map) {
        ItemStack held=mc.player.getMainHandStack();
        if(mc.player.getInventory().getSelectedSlot()==slot&&held.isOf(item)&&(map<0||mapId(held)==map))return true;
        if(slot<9)InvUtils.swap(slot,false);
        else InvUtils.move().from(slot).toHotbar(mc.player.getInventory().getSelectedSlot());
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
