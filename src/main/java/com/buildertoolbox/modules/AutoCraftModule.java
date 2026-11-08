package com.buildertoolbox.modules;

import com.buildertoolbox.utils.AutomationControl;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.utils.CraftingBatchPlan;
import com.buildertoolbox.utils.StorageBlockUtils;
import com.buildertoolbox.utils.WorkstationAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;

/** One source per batch: storage -> recipe chain at table -> original storage. */
public final class AutoCraftModule extends Module {
    // New chains can use the same storage, movement, cursor and transfer code.
    private static final RecipeChain RECIPE = new RecipeChain("Bone -> Bone Meal -> Bone Block", List.of(
        new CraftStep(Items.BONE, Items.BONE_MEAL, 3, List.of(1)),
        new CraftStep(Items.BONE_MEAL, Items.BONE_BLOCK, 1, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9))));
    private static final int SETTLE_TICKS = 6;
    private static final int ACTION_TIMEOUT = 60;

    private final SettingGroup locations = settings.createGroup("Locations");
    private final Setting<Boolean> selectStorage = locations.add(new BoolSetting.Builder()
        .name("Select Storage Blocks")
        .description("隐藏设置后右键登记箱子、木桶或潜影盒，按 Esc 返回，无须物品标签。")
        .defaultValue(false).onChanged(value -> { if (value) enterSelection(false); }).build());
    private final Setting<List<String>> storageList = locations.add(new StringListSetting.Builder()
        .name("Storage Block List")
        .description("保存原料容器的坐标。各容器分别加工，产物放回各自的原容器。")
        .defaultValue(new String[0]).build());
    private final Setting<Boolean> selectTable = locations.add(new BoolSetting.Builder()
        .name("Select Crafting Table")
        .description("隐藏设置后右键登记工作台坐标，按 Esc 返回。")
        .defaultValue(false).onChanged(value -> { if (value) enterSelection(true); }).build());
    private final Setting<List<String>> tablePosition = locations.add(new StringListSetting.Builder()
        .name("Crafting Table Position")
        .description("工作台的 X,Y,Z 坐标。合成骨块需要真实工作台的 3×3 合成格。")
        .defaultValue(new String[0]).build());
    private final Setting<String> workDimension = locations.add(new StringSetting.Builder()
        .name("Work Dimension").defaultValue("").visible(() -> false).build());
    private final Setting<Boolean> resume = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("Start / Resume")
        .description("登记位置或腾出空间后，开始任务或继续暂停的任务。暂停时已取出的材料仍归属原容器。")
        .defaultValue(false).onChanged(this::onResumeChanged).build());

    private final WorkstationAccess access = new WorkstationAccess(mc);
    private final Set<BlockPos> finishedSources = new HashSet<>();
    private State state = State.IDLE;
    private BlockPos source;
    private String jobDimension;
    private String jobWorld;
    private boolean sourceExhausted;
    private int boneBatchLimit;
    private boolean selectionOnly;
    private Pending pending;
    private long tick;
    private long resultWaitAt = -1;
    private long completedBlocks;
    private String activity = "空闲";

    public AutoCraftModule() {
        super(BuilderToolboxAddon.CATEGORY, "Auto Craft",
            "从登记容器取骨头，在指定工作台加工成骨粉和骨块，再将产物放回原容器。");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        return theme.label("合成配方：骨头 → 骨粉 → 骨块");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (!AutomationControl.acquire(this)) { disable(); return; }
        tick = 0;
        access.start(mc.player.getBlockPos().getY());
        pending = null;
        resultWaitAt = -1;
        if (selectStorage.get() || selectTable.get()) {
            selectionOnly = source == null;
            state = State.SELECTING;
            activity = "右键登记位置，Esc 返回设置";
        } else beginRun();
    }

    @Override
    public void onDeactivate() {
        access.release(tick);
        access.reset();
        pending = null;
        state = State.IDLE;
        if (selectStorage.get()) selectStorage.set(false);
        if (selectTable.get()) selectTable.set(false);
        if (source != null) warning("自动合成已停止；携带的配方材料仍属于 %s。再次开启可继续处理并回存原箱。", source.toShortString());
        AutomationControl.release(this);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof InventoryS2CPacket packet) access.inventoryReceived(packet.syncId());
    }

    private void onResumeChanged(boolean enabled) {
        if (!enabled) return;
        if (!isActive()) {
            toggle();
            if (!isActive()) { resume.set(false); return; }
        }
        else if (state != State.SELECTING) beginRun();
        resume.set(false);
    }

    private void beginRun() {
        if (mc.player == null || mc.world == null) return;
        if (selectStorage.get() || selectTable.get()) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            pause("请先把鼠标上的物品放回背包，再开始自动合成");
            return;
        }
        if (!workDimension.get().isEmpty() && !dimension().equals(workDimension.get())) {
            pause("登记位置不在当前维度，请回到登记的维度后继续");
            return;
        }
        if (table() == null || sources().isEmpty()) {
            pause("请先登记至少一个储藏方块和一个工作台，再点击 Start / Resume");
            return;
        }
        if (source == null) {
            if (hasRecipeItems()) {
                pause("新任务开始前，请先移走背包中的骨头、骨粉和骨块，以便产物只回存到本次取料的原箱");
                return;
            }
            finishedSources.clear();
            completedBlocks = 0;
            jobDimension = dimension();
            jobWorld = world();
            state = State.FETCH;
        } else {
            if (!dimension().equals(jobDimension) || !world().equals(jobWorld)) {
                pause("请返回原任务所在服务器/存档和维度，避免将材料存到别处");
                return;
            }
            state = sourceExhausted ? State.DEPOSIT : State.CRAFT;
        }
        access.start(mc.player.getBlockPos().getY());
        pending = null;
        resultWaitAt = -1;
        activity = "开始骨头合成任务";
        info("自动合成：%s；%d 个储藏方块，产物按批次回到各自原箱。", RECIPE.name(), sources().size());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        tick++;
        if (state == State.IDLE || state == State.PAUSED || state == State.SELECTING) return;
        if (!dimension().equals(jobDimension) || !world().equals(jobWorld)) {
            pause("服务器/存档或维度发生变化，已暂停；材料留在背包中");
            return;
        }
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            pause("检测到鼠标持有物品，已停止点击；请把物品放回背包后继续");
            return;
        }
        if (pending != null && waitForAction()) return;
        switch (state) {
            case FETCH -> tickFetch();
            case CRAFT -> tickCraft();
            case DEPOSIT -> tickDeposit();
            default -> { }
        }
        if (tick % 200 == 0) info("自动合成：%s；已合成 %d 个骨块。", activity, completedBlocks);
    }

    private void tickFetch() {
        if (source == null) {
            source = sources().stream().filter(pos -> !finishedSources.contains(pos))
                .min(Comparator.comparingDouble(pos -> mc.player.squaredDistanceTo(pos.getX() + 0.5,
                    pos.getY() + 0.5, pos.getZ() + 0.5))).orElse(null);
            if (source == null) {
                info("自动合成结束：已检查 %d 个储藏方块，合成 %d 个骨块；余料与产物已回存。", finishedSources.size(), completedBlocks);
                disable();
                return;
            }
            sourceExhausted = false;
            boneBatchLimit = 0;
        }
        ScreenHandler handler = open(source, false);
        if (handler == null) return;
        int storageSlots = StorageBlockUtils.storageSlots(handler);
        CraftStep unpack = RECIPE.steps().getFirst();
        CraftStep pack = RECIPE.steps().getLast();
        int bonesHeld = countBag(unpack.input());
        if (bonesHeld == 0 && countBag(pack.input()) >= pack.grid().size()) {
            changeState(State.CRAFT);
            return;
        }
        // Reserve room for expanded powder and one output stack. At most
        // three stacks per trip produce exactly one stack of bone blocks.
        if (boneBatchLimit == 0) boneBatchLimit = Math.min(3, Math.max(1, (emptySlots() - 1) / 3)) * 64;
        int boneSlot = -1;
        int powderSlot = -1;
        int powderInBox = 0;
        for (int slot = 0; slot < storageSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isOf(unpack.input()) && boneSlot < 0) boneSlot = slot;
            if (stack.isOf(pack.input())) { powderInBox += stack.getCount(); if (powderSlot < 0) powderSlot = slot; }
        }
        if (bonesHeld > 0 && (boneSlot < 0 || bonesHeld >= boneBatchLimit || emptySlots() < 4
            || bonesHeld + handler.getSlot(boneSlot).getStack().getCount() > boneBatchLimit)) {
            changeState(State.CRAFT);
            return;
        }
        int wanted = boneSlot >= 0 ? boneSlot
            : powderInBox + countBag(pack.input()) >= pack.grid().size() ? powderSlot : -1;
        if (wanted < 0) {
            sourceExhausted = true;
            changeState(State.DEPOSIT);
            return;
        }
        if (boneSlot >= 0 && emptySlots() < 4) {
            pause("背包空间不足：取骨头前需要至少 4 个空格，供骨粉展开和骨块产物使用");
            return;
        }
        ItemStack stack = handler.getSlot(wanted).getStack();
        if (boneSlot >= 0 && stack.getCount() > boneBatchLimit) {
            pause("这组骨头超出当前背包可处理的批量，请腾出更多空间或先拆成普通物品组");
            return;
        }
        if (capacity(stack) < stack.getCount()) { pause("没有空间取出整组原料，请先腾出背包"); return; }
        beginTransfer(handler, wanted, stack.getItem(), true);
    }

    private void tickCraft() {
        ScreenHandler opened = open(table(), true);
        if (!(opened instanceof CraftingScreenHandler handler)) return;
        CraftStep active = null;
        boolean gridEmpty = true;
        for (int slot = 1; slot <= 9; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) { gridEmpty = false; break; }
        }
        if (!gridEmpty) {
            for (CraftStep step : RECIPE.steps()) if (matchesGrid(handler, step)) { active = step; break; }
            if (active == null) {
                // Only clear our own chain ingredients, never an unexpected
                // user/third-party item or the server's output slot.
                for (int slot = 1; slot <= 9; slot++) {
                    ItemStack stack = handler.getSlot(slot).getStack();
                    if (stack.isEmpty()) continue;
                    if (!recipeItems().contains(stack.getItem())) { pause("工作台中出现非本配方物品，已暂停"); return; }
                    if (capacity(stack) < stack.getCount()) { pause("背包空间不足，无法收回工作台余料"); return; }
                    moveQuick(handler, slot);
                    return;
                }
            }
        } else {
            for (CraftStep step : RECIPE.steps()) {
                if (countBag(step.input()) >= step.grid().size()) { active = step; break; }
            }
            if (active == null) { changeState(State.DEPOSIT); return; }
            fillGrid(handler, active);
            return;
        }
        if (active == null) return;
        ItemStack output = handler.getSlot(0).getStack();
        if (!output.isOf(active.output()) || output.getCount() != active.outputCount()) {
            if (resultWaitAt < 0) resultWaitAt = tick;
            if (tick - resultWaitAt > ACTION_TIMEOUT) pause("服务器未给出预期合成结果，请检查该服务器的骨头/骨块配方");
            else activity = "等待服务器确认合成结果";
            return;
        }
        if (capacity(output) < output.getCount()) { pause("背包没有空间容纳合成结果，材料保留在工作台/背包中"); return; }
        Map<Item, Integer> before = counts(handler);
        click(handler, 0, 0, SlotActionType.QUICK_MOVE);
        pending = new Pending(Action.CRAFT, handler, tick, before, Map.of(), active, null, 0);
        resultWaitAt = -1;
        activity = "合成 " + active.output().getName().getString();
    }

    private boolean matchesGrid(CraftingScreenHandler handler, CraftStep step) {
        for (int slot = 1; slot <= 9; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (step.grid().contains(slot) ? !stack.isOf(step.input()) : !stack.isEmpty()) return false;
        }
        return true;
    }

    private void fillGrid(CraftingScreenHandler handler, CraftStep step) {
        Map<Item, Integer> before = counts(handler);
        int sourceSlot = -1;
        for (int slot = 10; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isOf(step.input()) && (sourceSlot < 0
                || stack.getCount() > handler.getSlot(sourceSlot).getStack().getCount())) sourceSlot = slot;
        }
        if (sourceSlot < 0) return;
        int count = handler.getSlot(sourceSlot).getStack().getCount();
        if (step.grid().size() == 1) {
            click(handler, sourceSlot, 0, SlotActionType.PICKUP);
            click(handler, step.grid().getFirst(), 0, SlotActionType.PICKUP);
            returnCursor(handler, sourceSlot);
        } else if (CraftingBatchPlan.distribute(count, step.grid().size()).perSlot() > 0) {
            // Vanilla left-drag distributes equal stacks into the entire
            // recipe. Start, all slots, finish and cursor return are atomic
            // within this tick; no cursor-held item becomes a task state.
            click(handler, sourceSlot, 0, SlotActionType.PICKUP);
            click(handler, -999, ScreenHandler.packQuickCraftData(0, 0), SlotActionType.QUICK_CRAFT);
            for (int slot : step.grid()) click(handler, slot, ScreenHandler.packQuickCraftData(1, 0), SlotActionType.QUICK_CRAFT);
            click(handler, -999, ScreenHandler.packQuickCraftData(2, 0), SlotActionType.QUICK_CRAFT);
            returnCursor(handler, sourceSlot);
        } else {
            // Nine ingredients may be split across several small/component-
            // distinct stacks. Fill one complete recipe without merging IDs.
            int next = 0;
            for (int slot = 10; slot < handler.slots.size() && next < step.grid().size(); slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (!stack.isOf(step.input())) continue;
                int amount = Math.min(stack.getCount(), step.grid().size() - next);
                click(handler, slot, 0, SlotActionType.PICKUP);
                for (int i = 0; i < amount; i++) click(handler, step.grid().get(next++), 1, SlotActionType.PICKUP);
                returnCursor(handler, slot);
                if (!handler.getCursorStack().isEmpty()) break;
            }
        }
        if (!handler.getCursorStack().isEmpty()) { pause("合成摆料未能收回鼠标物品，请手动放回背包后继续"); return; }
        pending = new Pending(Action.MOVE, handler, tick, before, layout(handler), null, null, 0);
        resultWaitAt = -1;
        activity = "摆放合成原料，等待同步";
    }

    private void tickDeposit() {
        ScreenHandler handler = open(source, false);
        if (handler == null) return;
        int storageSlots = StorageBlockUtils.storageSlots(handler);
        for (int slot = storageSlots; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !recipeItems().contains(stack.getItem())) continue;
            // Carry <9 powder between batches from the SAME original source,
            // avoiding a growing pile of leftovers and repeated useless trips.
            if (!sourceExhausted && stack.isOf(RECIPE.steps().getLast().input())
                && countBag(stack.getItem()) < RECIPE.steps().getLast().grid().size()) continue;
            if (containerCapacity(handler, storageSlots, stack) == 0) {
                pause("原箱已满，无法回存 " + stack.getName().getString() + "；产物留在背包中，腾空原箱后点击 Start / Resume");
                return;
            }
            beginTransfer(handler, slot, stack.getItem(), false);
            return;
        }
        access.reset();
        if (sourceExhausted) {
            finishedSources.add(source);
            info("原箱 %s 已处理并回存完成。", source.toShortString());
            source = null;
        }
        state = State.FETCH;
        boneBatchLimit = 0;
        activity = "继续检查原箱中的剩余原料";
    }

    private ScreenHandler open(BlockPos target, boolean crafting) {
        ScreenHandler handler = access.open(target, crafting, tick);
        if (access.failure() != null) pause(access.failure());
        else if (handler == null) activity = access.activity();
        return handler;
    }

    private void beginTransfer(ScreenHandler handler, int slot, Item item, boolean extracting) {
        Map<Item, Integer> before = counts(handler);
        int amount = handler.getSlot(slot).getStack().getCount();
        click(handler, slot, 0, SlotActionType.QUICK_MOVE);
        pending = new Pending(extracting ? Action.EXTRACT : Action.DEPOSIT, handler, tick,
            before, Map.of(), null, item, amount);
        activity = extracting ? "从原箱取出整组原料" : "向原箱回存产物";
    }

    private void moveQuick(ScreenHandler handler, int slot) {
        Map<Item, Integer> before = counts(handler);
        click(handler, slot, 0, SlotActionType.QUICK_MOVE);
        pending = new Pending(Action.MOVE, handler, tick, before, layout(handler), null, null, 0);
    }

    /** True means this tick is consumed, including a rejected/timed-out action. */
    private boolean waitForAction() {
        Pending action = pending;
        if (mc.player.currentScreenHandler != action.handler()) {
            pause("操作期间界面被关闭或更换，已暂停；请检查背包后点击 Start / Resume");
            return true;
        }
        if (tick - action.at() < SETTLE_TICKS) return true;
        Map<Item, Integer> now = counts(action.handler());
        boolean valid;
        if (action.type() == Action.CRAFT) {
            CraftStep step = action.step();
            valid = CraftingBatchPlan.validConversion(action.before().getOrDefault(step.input(), 0), now.getOrDefault(step.input(), 0),
                action.before().getOrDefault(step.output(), 0), now.getOrDefault(step.output(), 0), step.grid().size(), step.outputCount());
            for (Item item : recipeItems()) {
                if (item != step.input() && item != step.output()
                    && !action.before().getOrDefault(item, 0).equals(now.getOrDefault(item, 0))) valid = false;
            }
        } else if (action.type() == Action.MOVE) {
            valid = action.before().equals(now) && layoutMatches(action.handler(), action.expected());
        } else {
            int delta = now.getOrDefault(action.item(), 0) - action.before().getOrDefault(action.item(), 0);
            valid = action.type() == Action.EXTRACT ? delta == action.amount() : delta < 0 && -delta <= action.amount();
        }
        if (!valid) {
            if (tick - action.at() >= ACTION_TIMEOUT) pause("取放或合成数量未得到确认，已暂停以免重复点击；请检查界面、背包和原箱");
            return true;
        }
        if (action.type() == Action.CRAFT && action.step() == RECIPE.steps().getLast())
            completedBlocks += now.getOrDefault(action.step().output(), 0) - action.before().getOrDefault(action.step().output(), 0);
        pending = null;
        return true;
    }

    private Map<Item, Integer> counts(ScreenHandler handler) {
        Map<Item, Integer> result = new HashMap<>();
        for (Item item : recipeItems()) result.put(item, countBag(item));
        if (handler instanceof CraftingScreenHandler) {
            for (int slot = 1; slot <= 9; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (recipeItems().contains(stack.getItem())) result.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return result;
    }

    private Map<Integer, ItemStack> layout(ScreenHandler handler) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        for (int slot = 1; slot < handler.slots.size(); slot++) result.put(slot, handler.getSlot(slot).getStack().copy());
        return result;
    }

    private boolean layoutMatches(ScreenHandler handler, Map<Integer, ItemStack> expected) {
        for (var entry : expected.entrySet()) {
            if (!ItemStack.areEqual(entry.getValue(), handler.getSlot(entry.getKey()).getStack())) return false;
        }
        return true;
    }

    private void click(ScreenHandler handler, int slot, int button, SlotActionType type) {
        if (mc.player.currentScreenHandler == handler) mc.interactionManager.clickSlot(handler.syncId, slot, button, type, mc.player);
    }

    private void returnCursor(ScreenHandler handler, int slot) {
        ItemStack cursor = handler.getCursorStack();
        ItemStack original = handler.getSlot(slot).getStack();
        if (!cursor.isEmpty() && (original.isEmpty() || ItemStack.areItemsAndComponentsEqual(cursor, original))
            && cursor.getCount() + original.getCount() <= cursor.getMaxCount()) click(handler, slot, 0, SlotActionType.PICKUP);
    }

    private int countBag(Item item) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (mc.player.getInventory().getStack(slot).isOf(item))
            count += mc.player.getInventory().getStack(slot).getCount();
        return count;
    }

    private int emptySlots() {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (mc.player.getInventory().getStack(slot).isEmpty()) count++;
        return count;
    }

    private int capacity(ItemStack incoming) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) count += space(mc.player.getInventory().getStack(slot), incoming);
        return count;
    }

    private int containerCapacity(ScreenHandler handler, int slots, ItemStack incoming) {
        int count = 0;
        for (int slot = 0; slot < slots; slot++) if (handler.getSlot(slot).canInsert(incoming))
            count += space(handler.getSlot(slot).getStack(), incoming);
        return count;
    }

    private int space(ItemStack held, ItemStack incoming) {
        return held.isEmpty() ? incoming.getMaxCount() : ItemStack.areItemsAndComponentsEqual(held, incoming)
            ? Math.max(0, held.getMaxCount() - held.getCount()) : 0;
    }

    private Set<Item> recipeItems() {
        Set<Item> items = new HashSet<>();
        for (CraftStep step : RECIPE.steps()) { items.add(step.input()); items.add(step.output()); }
        return items;
    }

    private boolean hasRecipeItems() { return recipeItems().stream().anyMatch(item -> countBag(item) > 0); }

    private void changeState(State next) {
        access.reset();
        resultWaitAt = -1;
        state = next;
    }

    private void pause(String reason) {
        access.release(tick);
        // Keep an open crafting grid and cursor intact for inspection.
        pending = null;
        state = State.PAUSED;
        activity = reason;
        warning("%s", reason);
    }

    private void enterSelection(boolean crafting) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            if (crafting) selectTable.set(false); else selectStorage.set(false);
            warning("请先将鼠标上的物品放好，再选择位置。", new Object[0]);
            return;
        }
        if (crafting && selectStorage.get()) selectStorage.set(false);
        if (!crafting && selectTable.get()) selectTable.set(false);
        if (!isActive()) toggle();
        if (!isActive()) return;
        access.reset();
        pending = null;
        state = State.SELECTING;
        activity = crafting ? "右键选择工作台，Esc 退出" : "右键选择储藏方块，Esc 退出";
        mc.setScreen(null);
    }

    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (!isActive() || state != State.SELECTING || mc.currentScreen != null || mc.player == null || mc.world == null
            || event.action != KeyAction.Press || event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        event.cancel();
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        BlockPos pos = hit.getBlockPos().toImmutable();
        if (!workDimension.get().isEmpty() && !dimension().equals(workDimension.get())
            && (!sources().isEmpty() || table() != null)) {
            warning("已有位置在其他维度，请先清空位置列表后重新登记。", new Object[0]);
            return;
        }
        if (selectTable.get()) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) { warning("请右键点击工作台。", new Object[0]); return; }
            tablePosition.set(List.of(StorageBlockUtils.position(pos)));
            info("工作台已登记：%s", pos.toShortString());
        } else if (selectStorage.get()) {
            if (!StorageBlockUtils.isStorage(mc, pos)) { warning("请右键点击箱子、木桶或潜影盒。", new Object[0]); return; }
            List<String> entries = new ArrayList<>(storageList.get());
            if (!sources().contains(pos)) entries.add(StorageBlockUtils.position(pos));
            storageList.set(entries);
            info("储藏方块已登记：%s", pos.toShortString());
        }
        workDimension.set(dimension());
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || state != State.SELECTING || event.action != KeyAction.Press || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        selectStorage.set(false);
        selectTable.set(false);
        state = State.PAUSED;
        activity = "位置已记录，点击 Start / Resume 开始";
        if (selectionOnly && source == null) { selectionOnly = false; disable(); }
        mc.setScreen(GuiThemes.get().moduleScreen(this));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || state != State.SELECTING || mc.world == null) return;
        Color yellow = new Color(255, 220, 45, 255);
        for (BlockPos pos : sources()) event.renderer.box(pos, null, yellow, ShapeMode.Lines, 0);
        BlockPos table = table();
        if (table != null) event.renderer.box(table, null, new Color(60, 210, 255, 255), ShapeMode.Lines, 0);
    }

    private List<BlockPos> sources() {
        List<BlockPos> result = new ArrayList<>();
        for (String text : storageList.get()) {
            BlockPos pos = StorageBlockUtils.parsePosition(text);
            if (pos != null && !result.contains(pos)) result.add(pos);
        }
        return result;
    }

    private BlockPos table() { return tablePosition.get().isEmpty() ? null : StorageBlockUtils.parsePosition(tablePosition.get().getFirst()); }
    private String dimension() { return mc.world.getRegistryKey().getValue().toString(); }
    private String world() {
        if (mc.getServer() != null) return "local:" + mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath();
        return mc.getCurrentServerEntry() == null ? "unknown" : "server:" + mc.getCurrentServerEntry().address;
    }
    @Override public String getInfoString() { return activity + " [" + completedBlocks + " bone blocks]"; }

    private record CraftStep(Item input, Item output, int outputCount, List<Integer> grid) { }
    private record RecipeChain(String name, List<CraftStep> steps) { }
    private record Pending(Action type, ScreenHandler handler, long at, Map<Item, Integer> before,
                           Map<Integer, ItemStack> expected, CraftStep step, Item item, int amount) { }
    private enum Action { MOVE, CRAFT, EXTRACT, DEPOSIT }
    private enum State { IDLE, SELECTING, FETCH, CRAFT, DEPOSIT, PAUSED }
}
