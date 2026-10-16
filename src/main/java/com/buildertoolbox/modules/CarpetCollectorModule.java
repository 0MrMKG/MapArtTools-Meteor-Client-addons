package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/** Picks up dropped carpets on one level and sorts them into labeled storage. */
public final class CarpetCollectorModule extends Module {
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final long SKIP_TICKS = 20L * 10L;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("Search Radius")
        .description("Searches loaded dropped carpets around the position where collection started.")
        .defaultValue(64).range(8, 256).sliderRange(16, 128).build());

    private final Setting<Boolean> selectStorage = sgStorage.add(new BoolSetting.Builder()
        .name("Select Storage Chests")
        .description("Right-click a chest or barrel with a nearby carpet-ID sign; press Esc to return to settings.")
        .defaultValue(false).onChanged(this::onSelectStorageChanged).build());

    private final Setting<List<String>> storageList = sgStorage.add(new StringListSetting.Builder()
        .name("Storage Chest List")
        .description("Saved carpet item ID -> chest position. The sign is checked again before every deposit.")
        .defaultValue(new String[0]).onChanged(this::restoreStorageList).build());

    private final Map<Item, List<BlockPos>> storage = new HashMap<>();
    private final Map<UUID, Long> skippedDrops = new HashMap<>();
    private final Set<BlockPos> rejectedChestStands = new HashSet<>();
    private final List<BlockPos> path = new ArrayList<>();
    private State state = State.COLLECTING;
    private State beforeRegistration = State.COLLECTING;
    private BlockPos origin;
    private int walkY;
    private double collectionY;
    private ItemEntity targetDrop;
    private long pickupApproachTick = -1;
    private long pickupWaitTick = -1;
    private BlockPos navigationGoal;
    private int pathIndex;
    private long lastProgressTick;
    private double bestStepDistance = Double.POSITIVE_INFINITY;
    private int routeFailures;
    private boolean ownsForward;
    private Item depositItem;
    private BlockPos depositChest;
    private BlockPos depositStand;
    private int chestIndex;
    private long chestOpenTick = -1;
    private long chestAimTick = -1;
    private int chestOpenAttempts;
    private int previousSyncId;
    private long transferTick = -1;
    private int transferBefore;
    private long tick;
    private long lastFoundDropTick;
    private boolean updatingStorageList;
    private String activity = "Idle";

    public CarpetCollectorModule() {
        super(BuilderToolboxAddon.CATEGORY, "Carpet Collector",
            "Collects dropped carpets within one block of the starting height and sorts them into sign-labeled chests.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (storage.isEmpty() && !storageList.get().isEmpty()) restoreStorageList(storageList.get());
        origin = mc.player.getBlockPos().toImmutable();
        walkY = origin.getY();
        collectionY = mc.player.getY();
        if (selectStorage.get()) {
            state = State.REGISTERING;
            activity = "Select chests: right-click, Esc to finish";
        } else if (storage.isEmpty()) {
            state = State.PAUSED;
            warning("Register carpet storage chests before collecting.");
        } else {
            state = State.COLLECTING;
            activity = "Searching for carpets";
        }
    }

    @Override
    public void onDeactivate() {
        releaseMovement();
        closeChest();
        if (selectStorage.get()) selectStorage.set(false);
        resetRuntime();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        tick++;
        if (state == State.REGISTERING || state == State.PAUSED) { releaseMovement(); return; }
        if (state != State.DEPOSITING && mc.currentScreen != null) {
            releaseMovement();
            activity = "Paused by screen";
            return;
        }
        if (state == State.DEPOSITING) tickDeposit();
        else tickCollect();
    }

    private void tickCollect() {
        if (inventoryIsFull()) { beginDeposit(); return; }
        if (!isEligibleDrop(targetDrop)) {
            targetDrop = null;
            pickupApproachTick = -1;
            pickupWaitTick = -1;
            resetNavigation();
        }
        if (targetDrop == null) targetDrop = nearestDrop();
        if (targetDrop == null) {
            releaseMovement();
            if (tick - lastFoundDropTick >= 100 && nextInventoryCarpet() != null) {
                beginDeposit();
                return;
            }
            activity = "Waiting for dropped carpets";
            return;
        }
        lastFoundDropTick = tick;
        if (!hasInventoryCapacity(targetDrop.getStack())) { beginDeposit(); return; }

        Vec3d dropPos = new Vec3d(targetDrop.getX(), targetDrop.getY(), targetDrop.getZ());
        double dx = mc.player.getX() - dropPos.x;
        double dz = mc.player.getZ() - dropPos.z;
        if (dx * dx + dz * dz <= 0.35 * 0.35) {
            releaseMovement();
            activity = "Picking up " + targetDrop.getStack().getName().getString();
            // A drop below a solid floor cannot be picked up from this level.
            if (pickupWaitTick < 0) pickupWaitTick = tick;
            if (tick - pickupWaitTick > 40) skipCurrentDrop();
            return;
        }
        pickupWaitTick = -1;
        if (pickupApproachTick >= 0) {
            BlockPos itemCell = new BlockPos(MathHelper.floor(dropPos.x), walkY, MathHelper.floor(dropPos.z));
            if (tick - pickupApproachTick > 40 || !isStandUsable(itemCell)) {
                skipCurrentDrop();
                return;
            }
            faceToward(dropPos);
            pressForward();
            activity = "Approaching carpet item";
            return;
        }
        BlockPos stand = findDropStand(targetDrop);
        if (stand == null) {
            skipCurrentDrop();
            return;
        }
        RouteResult route = navigateTo(stand);
        if (route == RouteResult.UNREACHABLE) { skipCurrentDrop(); return; }
        if (route == RouteResult.ARRIVED) {
            pickupApproachTick = tick;
            releaseMovement();
            return;
        }
        activity = "Walking to " + targetDrop.getStack().getName().getString();
    }

    private ItemEntity nearestDrop() {
        int radius = searchRadius.get();
        Box search = new Box(origin.getX() - radius, collectionY - 1,
            origin.getZ() - radius, origin.getX() + radius + 1,
            collectionY + 1, origin.getZ() + radius + 1);
        skippedDrops.entrySet().removeIf(entry -> entry.getValue() <= tick);
        return mc.world.getEntitiesByClass(ItemEntity.class, search, this::isEligibleDrop).stream()
            .min(Comparator.comparingDouble(drop -> drop.squaredDistanceTo(mc.player))).orElse(null);
    }

    private boolean isEligibleDrop(ItemEntity drop) {
        if (drop == null || !drop.isAlive() || skippedDrops.containsKey(drop.getUuid())) return false;
        ItemStack stack = drop.getStack();
        if (!isCarpet(stack) || !storage.containsKey(stack.getItem())) return false;
        if (Math.abs(drop.getY() - collectionY) > 1.0) return false;
        int radius = searchRadius.get();
        return Math.abs(drop.getX() - (origin.getX() + 0.5)) <= radius
            && Math.abs(drop.getZ() - (origin.getZ() + 0.5)) <= radius;
    }

    private void skipCurrentDrop() {
        if (targetDrop != null) skippedDrops.put(targetDrop.getUuid(), tick + SKIP_TICKS);
        targetDrop = null;
        pickupApproachTick = -1;
        pickupWaitTick = -1;
        resetNavigation();
        activity = "Skipping unreachable carpet temporarily";
    }

    private static boolean isCarpet(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof CarpetBlock;
    }

    private boolean inventoryIsFull() {
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return false;
        }
        return true;
    }

    private boolean hasInventoryCapacity(ItemStack incoming) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack held = mc.player.getInventory().getStack(slot);
            if (held.isEmpty() || held.isOf(incoming.getItem()) && held.getCount() < held.getMaxCount()) return true;
        }
        return false;
    }

    private int countInventory(Item item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    private void beginDeposit() {
        releaseMovement();
        resetNavigation();
        targetDrop = null;
        pickupApproachTick = -1;
        pickupWaitTick = -1;
        depositItem = null;
        depositChest = null;
        state = State.DEPOSITING;
        activity = "Inventory full: sorting carpets";
    }

    private Item nextInventoryCarpet() {
        Item best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!isCarpet(stack)) continue;
            List<BlockPos> options = storage.get(stack.getItem());
            if (options == null || options.isEmpty()) continue;
            double distance = options.stream().mapToDouble(this::squaredDistanceToPlayer)
                .min().orElse(Double.POSITIVE_INFINITY);
            if (distance < bestDistance) { best = stack.getItem(); bestDistance = distance; }
        }
        return best;
    }

    private void tickDeposit() {
        if (depositItem == null || countInventory(depositItem) == 0) {
            closeChest();
            depositItem = nextInventoryCarpet();
            chestIndex = 0;
            depositChest = null;
            if (depositItem == null) {
                if (inventoryIsFull()) {
                    state = State.PAUSED;
                    activity = "Inventory full of items without registered carpet storage";
                    warning("Only carpets are sorted. Free space or register storage for the carpet colors still held.");
                } else {
                    state = State.COLLECTING;
                    activity = "Storage complete; resuming collection";
                }
                return;
            }
        }

        List<BlockPos> options = storage.getOrDefault(depositItem, List.of());
        if (depositChest == null) {
            if (chestIndex >= options.size()) {
                state = State.PAUSED;
                activity = "No usable storage for " + depositItem.getName().getString();
                warning("No reachable space in registered storage for %s.", depositItem.getName().getString());
                return;
            }
            depositChest = options.get(chestIndex++);
            depositStand = null;
            rejectedChestStands.clear();
            chestOpenTick = -1;
            chestAimTick = -1;
            chestOpenAttempts = 0;
            transferTick = -1;
            if (!labelMatchesChest()) { nextChest(); return; }
        }

        GenericContainerScreenHandler opened = mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
            ? handler : null;
        if (opened != null && (chestOpenTick < 0 || opened.syncId == previousSyncId)) {
            mc.player.closeHandledScreen();
            opened = null;
        }
        if (opened == null && mc.currentScreen != null) {
            releaseMovement();
            activity = "Close the current screen to continue storage";
            return;
        }
        if (opened == null && chestOpenTick >= 0 && tick - chestOpenTick < 12) {
            releaseMovement();
            activity = "Waiting for storage chest";
            return;
        }

        BlockHitResult hit = findChestHit(depositChest, mc.player.getEyePos());
        if (opened == null && hit == null) {
            if (depositStand == null) depositStand = findChestStand(depositChest);
            if (depositStand == null) { nextChest(); return; }
            RouteResult route = navigateTo(depositStand);
            if (route == RouteResult.UNREACHABLE || route == RouteResult.ARRIVED) {
                rejectedChestStands.add(depositStand);
                depositStand = null;
                resetNavigation();
            }
            activity = "Walking to storage";
            return;
        }
        resetNavigation();
        if (opened == null) {
            if (chestOpenTick >= 0 && ++chestOpenAttempts >= 3) { nextChest(); return; }
            if (chestAimTick < 0 || tick - chestAimTick > 2) {
                faceToward(hit.getPos());
                chestAimTick = tick;
                activity = "Facing storage chest";
                return;
            }
            faceToward(hit.getPos());
            previousSyncId = mc.player.currentScreenHandler.syncId;
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
            chestOpenTick = tick;
            chestAimTick = -1;
            activity = "Opening storage chest";
            return;
        }
        if (!labelMatchesChest()) { nextChest(); return; }
        if (transferTick >= 0) {
            if (countInventory(depositItem) < transferBefore) {
                transferTick = -1;
                activity = "Stored " + depositItem.getName().getString();
                return;
            }
            if (tick - transferTick < 10) return;
            nextChest();
            return;
        }
        int containerSlots = opened.getRows() * 9;
        Identifier labelId = Registries.ITEM.getId(depositItem);
        for (int slot = containerSlots; slot < opened.slots.size(); slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (isCarpet(stack) && labelId.equals(Registries.ITEM.getId(stack.getItem()))) {
                transferBefore = countInventory(depositItem);
                InvUtils.shiftClick().slotId(slot);
                transferTick = tick;
                activity = "Storing " + depositItem.getName().getString();
                return;
            }
        }
        depositItem = null;
    }

    private void nextChest() {
        closeChest();
        depositChest = null;
        depositStand = null;
        chestOpenTick = -1;
        transferTick = -1;
        resetNavigation();
    }

    private void closeChest() {
        if (chestOpenTick >= 0 && mc.player != null
            && mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)
            mc.player.closeHandledScreen();
    }

    private boolean labelMatchesChest() {
        if (depositChest == null || depositItem == null) return false;
        Item label = findNearestSignCarpet(depositChest, 4);
        return label != null && Registries.ITEM.getId(label).equals(Registries.ITEM.getId(depositItem));
    }

    private boolean isContainer(BlockPos pos) {
        BlockState block = mc.world.getBlockState(pos);
        return block.getBlock() instanceof ChestBlock || block.getBlock() instanceof BarrelBlock;
    }

    private BlockHitResult findChestHit(BlockPos pos, Vec3d eye) {
        if (!isContainer(pos)) return null;
        for (Direction face : Direction.values()) {
            Vec3d point = Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.49,
                face.getOffsetY() * 0.49, face.getOffsetZ() * 0.49);
            if (eye.squaredDistanceTo(point) > 16.0) continue;
            HitResult trace = mc.world.raycast(new RaycastContext(eye, point,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (trace instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) return blockHit;
        }
        return null;
    }

    private BlockPos findChestStand(BlockPos chest) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = chest.getX() - 4; x <= chest.getX() + 4; x++) {
            for (int z = chest.getZ() - 4; z <= chest.getZ() + 4; z++) {
                BlockPos stand = new BlockPos(x, walkY, z);
                if (!isStandUsable(stand) || rejectedChestStands.contains(stand)) continue;
                Vec3d eye = new Vec3d(x + 0.5, walkY + 1.62, z + 0.5);
                if (findChestHit(chest, eye) != null) candidates.add(stand);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(this::squaredDistanceToPlayer)).orElse(null);
    }

    private BlockPos findDropStand(ItemEntity drop) {
        BlockPos nearest = null;
        double best = Double.POSITIVE_INFINITY;
        BlockPos center = new BlockPos(MathHelper.floor(drop.getX()), walkY, MathHelper.floor(drop.getZ()));
        for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
            for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
                BlockPos stand = new BlockPos(x, walkY, z);
                if (!isStandUsable(stand)) continue;
                double distance = (x + 0.5 - drop.getX()) * (x + 0.5 - drop.getX())
                    + (z + 0.5 - drop.getZ()) * (z + 0.5 - drop.getZ());
                double score = distance * 4 + squaredDistanceToPlayer(stand) * 0.01;
                if (score < best) { best = score; nearest = stand; }
            }
        }
        return nearest;
    }

    private RouteResult navigateTo(BlockPos goal) {
        if (hasReached(goal)) { resetNavigation(); return RouteResult.ARRIVED; }
        if (!isStandUsable(goal)) return RouteResult.UNREACHABLE;
        boolean changed = navigationGoal == null || !navigationGoal.equals(goal);
        boolean stuck = !path.isEmpty() && tick - lastProgressTick > 50;
        if (changed || path.isEmpty() || stuck) {
            if (changed) { resetNavigation(); navigationGoal = goal.toImmutable(); }
            else if (stuck) { releaseMovement(); path.clear(); }
            if (++routeFailures > 3 || !planPath(goal)) return RouteResult.UNREACHABLE;
        }
        while (pathIndex < path.size() && hasReached(path.get(pathIndex))) {
            pathIndex++;
            lastProgressTick = tick;
            bestStepDistance = Double.POSITIVE_INFINITY;
        }
        if (pathIndex >= path.size()) return hasReached(goal) ? RouteResult.ARRIVED : RouteResult.MOVING;
        BlockPos next = path.get(pathIndex);
        if (!isStandUsable(next)) { path.clear(); return RouteResult.MOVING; }
        double distance = Math.sqrt(horizontalDistanceSquared(next));
        if (distance + 0.04 < bestStepDistance) { bestStepDistance = distance; lastProgressTick = tick; }
        faceToward(Vec3d.ofCenter(next));
        pressForward();
        return RouteResult.MOVING;
    }

    private boolean planPath(BlockPos goal) {
        path.clear();
        pathIndex = 0;
        lastProgressTick = tick;
        bestStepDistance = Double.POSITIVE_INFINITY;
        // Water can lift the player within the permitted +/-1 collection
        // band. Keep routing on the original floor plane in that case.
        BlockPos start = new BlockPos(MathHelper.floor(mc.player.getX()), walkY,
            MathHelper.floor(mc.player.getZ()));
        int minX = Math.min(start.getX(), goal.getX()) - 4;
        int maxX = Math.max(start.getX(), goal.getX()) + 4;
        int minZ = Math.min(start.getZ(), goal.getZ()) - 4;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + 4;
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        open.add(start);
        parents.put(start, null);
        int examined = 0;
        while (!open.isEmpty() && examined++ < 30000) {
            BlockPos current = open.removeFirst();
            if (current.equals(goal)) {
                ArrayDeque<BlockPos> reverse = new ArrayDeque<>();
                for (BlockPos cursor = goal; cursor != null; cursor = parents.get(cursor)) reverse.addFirst(cursor);
                path.addAll(reverse);
                pathIndex = path.size() > 1 ? 1 : 0;
                return true;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.offset(direction).toImmutable();
                if (next.getX() < minX || next.getX() > maxX || next.getZ() < minZ || next.getZ() > maxZ
                    || next.getY() != walkY || parents.containsKey(next) || !isStandUsable(next)) continue;
                parents.put(next, current);
                open.addLast(next);
            }
        }
        return false;
    }

    private boolean isStandUsable(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        return (feet.getCollisionShape(mc.world, pos).isEmpty() || feet.getBlock() instanceof CarpetBlock)
            && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty()
            && !mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down()).isEmpty();
    }

    private boolean hasReached(BlockPos pos) {
        return horizontalDistanceSquared(pos) <= 0.20 * 0.20
            && Math.abs(mc.player.getY() - collectionY) <= 1.0;
    }

    private double horizontalDistanceSquared(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    private double squaredDistanceToPlayer(BlockPos pos) {
        return mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private void faceToward(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        mc.player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI - 90.0));
        mc.player.setPitch((float) (-MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    private void pressForward() {
        setKeyPressed(mc.options.forwardKey, true);
        ownsForward = true;
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private void releaseMovement() {
        if (!ownsForward || mc.options == null) return;
        setKeyPressed(mc.options.forwardKey, false);
        ownsForward = false;
    }

    private static void setKeyPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    private void resetNavigation() {
        releaseMovement();
        path.clear();
        navigationGoal = null;
        pathIndex = 0;
        routeFailures = 0;
        bestStepDistance = Double.POSITIVE_INFINITY;
    }

    private void resetRuntime() {
        releaseMovement();
        resetNavigation();
        skippedDrops.clear();
        rejectedChestStands.clear();
        targetDrop = null;
        pickupApproachTick = -1;
        pickupWaitTick = -1;
        depositItem = null;
        depositChest = null;
        depositStand = null;
        chestIndex = 0;
        chestOpenTick = -1;
        chestAimTick = -1;
        transferTick = -1;
        tick = 0;
        lastFoundDropTick = 0;
        activity = "Idle";
    }

    private void onSelectStorageChanged(boolean enabled) {
        if (!enabled) return;
        if (!isActive()) toggle();
        else if (state != State.REGISTERING) {
            releaseMovement();
            beforeRegistration = state;
            state = State.REGISTERING;
        }
        if (mc.player != null) mc.setScreen(null);
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive() || !selectStorage.get() || mc.player == null || mc.world == null
            || event.action != KeyAction.Press || event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        event.cancel();
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || !isContainer(hit.getBlockPos())) {
            warning("Right-click a chest or barrel with a nearby carpet sign.");
            return;
        }
        BlockPos chest = hit.getBlockPos().toImmutable();
        Item item = findNearestSignCarpet(chest, 4);
        if (item == null) { warning("No nearby sign names a carpet item ID."); return; }
        List<BlockPos> boxes = storage.computeIfAbsent(item, ignored -> new ArrayList<>());
        if (!boxes.contains(chest)) boxes.add(chest);
        saveStorageList();
        info("Carpet storage: %s -> %s", Registries.ITEM.getId(item), chest.toShortString());
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || !selectStorage.get() || event.action != KeyAction.Press
            || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        selectStorage.set(false);
        state = storage.isEmpty() ? State.PAUSED
            : beforeRegistration == State.PAUSED ? State.COLLECTING : beforeRegistration;
        mc.setScreen(GuiThemes.get().moduleScreen(this));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || !selectStorage.get() || mc.world == null) return;
        Color yellow = new Color(255, 220, 45, 255);
        Set<BlockPos> shown = new HashSet<>();
        for (List<BlockPos> boxes : storage.values()) {
            for (BlockPos pos : boxes) if (shown.add(pos)) event.renderer.box(pos, null, yellow, ShapeMode.Lines, 0);
        }
    }

    private Item findNearestSignCarpet(BlockPos chest, int radius) {
        Item nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int y = chest.getY() - radius; y <= chest.getY() + radius; y++) {
            for (int z = chest.getZ() - radius; z <= chest.getZ() + radius; z++) {
                for (int x = chest.getX() - radius; x <= chest.getX() + radius; x++) {
                    BlockPos signPos = new BlockPos(x, y, z);
                    BlockEntity entity = mc.world.getBlockEntity(signPos);
                    if (!(entity instanceof SignBlockEntity sign)) continue;
                    Item item = carpetOnSign(sign);
                    if (item == null) continue;
                    double distance = signPos.getSquaredDistance(chest);
                    if (distance < nearestDistance) { nearest = item; nearestDistance = distance; }
                }
            }
        }
        return nearest;
    }

    private Item carpetOnSign(SignBlockEntity sign) {
        for (boolean front : List.of(true, false)) {
            for (net.minecraft.text.Text line : sign.getText(front).getMessages(false)) {
                Identifier id = Identifier.tryParse(line.getString().trim());
                if (id == null) continue;
                Item item = Registries.ITEM.get(id);
                if (isCarpet(new ItemStack(item))) return item;
            }
        }
        return null;
    }

    private void saveStorageList() {
        List<String> entries = new ArrayList<>();
        storage.forEach((item, boxes) -> {
            for (BlockPos pos : boxes) entries.add(Registries.ITEM.getId(item) + " -> "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        });
        entries.sort(String::compareTo);
        updatingStorageList = true;
        storageList.set(entries);
        updatingStorageList = false;
    }

    private void restoreStorageList(List<String> entries) {
        if (updatingStorageList) return;
        storage.clear();
        for (String entry : entries) {
            String[] parts = entry.split(" -> ", 2);
            if (parts.length != 2) continue;
            Identifier id = Identifier.tryParse(parts[0]);
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR || !isCarpet(new ItemStack(item))) continue;
            String[] xyz = parts[1].split(",", 3);
            if (xyz.length != 3) continue;
            try {
                BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                List<BlockPos> boxes = storage.computeIfAbsent(item, ignored -> new ArrayList<>());
                if (!boxes.contains(pos)) boxes.add(pos);
            } catch (NumberFormatException ignored) { }
        }
    }

    @Override
    public String getInfoString() { return activity; }

    private enum State { COLLECTING, DEPOSITING, REGISTERING, PAUSED }
    private enum RouteResult { MOVING, ARRIVED, UNREACHABLE }
}
