package com.buildertoolbox.utils;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.CarpetBlock;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.screen.slot.SlotActionType;

/** One restock-only discard pass. Real rotations and vanilla whole-stack THROW clicks. */
public final class RestockSurplusController {
    private final MinecraftClient mc;
    private Map<Item,Integer> keep=Map.of();
    private Set<Item> eligible=Set.of();
    private final Map<Integer,Integer> attempts=new HashMap<>();
    private boolean active;
    private float originalYaw,originalPitch,throwYaw;
    private long readyAt,startedAt,lastThrow=-1;
    private int dropped;
    private String status="";
    public RestockSurplusController(MinecraftClient mc){this.mc=mc;}
    public boolean active(){return active;}
    public String status(){return status;}

    public boolean begin(List<List<Item>> bands,Set<Item> materials,int reservedSlots,BlockPos destination,long tick){
        if(active||mc.player==null||mc.world==null||mc.interactionManager==null
            ||mc.player.currentScreenHandler!=mc.player.playerScreenHandler
            ||!mc.player.currentScreenHandler.getCursorStack().isEmpty())return false;
        Set<Item> allowed=new HashSet<>(materials);
        for(int i=0;i<36;i++){var item=mc.player.getInventory().getStack(i).getItem();if(item instanceof BlockItem b&&b.getBlock() instanceof CarpetBlock)allowed.add(item);}
        eligible=Set.copyOf(allowed);
        int personal=0;Map<Item,Integer> sizes=new HashMap<>();
        for(int i=0;i<36;i++){var stack=mc.player.getInventory().getStack(i);if(!stack.isEmpty()&&!eligible.contains(stack.getItem()))personal++;}
        for(var band:bands)for(var item:band)sizes.put(item,new ItemStack(item).getMaxCount());
        keep=RestockSurplusPlanner.keepTargets(bands,sizes,Math.max(0,36-personal-reservedSlots));
        if(surplus().isEmpty())return false;
        double dx=destination==null?Math.cos(Math.toRadians(mc.player.getYaw())):destination.getX()+0.5-mc.player.getX();
        double dz=destination==null?Math.sin(Math.toRadians(mc.player.getYaw())):destination.getZ()+0.5-mc.player.getZ();
        double base=Math.atan2(dz,dx);Vec3d eye=mc.player.getEyePos();boolean clear=false;
        // Throw across the route, never straight along its outward/return path.
        for(double side:new double[]{Math.PI/2,-Math.PI/2,Math.PI}){
            double angle=base+side;Vec3d end=eye.add(Math.cos(angle)*3.5,0.7,Math.sin(angle)*3.5);
            if(mc.world.raycast(new RaycastContext(eye,end,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,mc.player)).getType()!=HitResult.Type.MISS)continue;
            throwYaw=(float)(Math.toDegrees(angle)-90);clear=true;break;
        }
        if(!clear){status="丢弃方向受阻，保留材料，继续寻找补货空间";return false;}
        originalYaw=mc.player.getYaw();originalPitch=mc.player.getPitch();attempts.clear();dropped=0;lastThrow=-1;
        active=true;startedAt=tick;readyAt=tick+2;face();status="补货腾位：朝路线侧面丢出无关颜色";return true;
    }

    public boolean tick(long tick){
        if(!active)return false;
        if(mc.player==null||mc.world==null){stop();return false;}
        if(tick-startedAt>200){status="本次腾位超时，保留剩余材料并继续补货";stop();return false;}
        if (Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(mc.player.getYaw()-throwYaw)) > 1
            || Math.abs(mc.player.getPitch()+25) > 1) readyAt=tick+2;
        face();
        if(tick<readyAt)return true;
        if(mc.player.currentScreenHandler!=mc.player.playerScreenHandler||!mc.player.currentScreenHandler.getCursorStack().isEmpty()){
            if(tick-readyAt>60){status="腾位界面未就绪，保留剩余物品";stop();return false;}
            status="等待背包就绪后丢弃余料";return true;
        }
        for(int slot:surplus()){
            if(attempts.getOrDefault(slot,0)>=(lastThrow>=0&&tick-lastThrow>=6?2:1))continue;
            int handlerSlot=mc.player.playerScreenHandler.getSlotIndex(mc.player.getInventory(),slot).orElse(-1);
            if(handlerSlot<0)continue;
            // Recompute surplus before every click: a rollback or a new pickup
            // must never turn this into a blind replay against a reused slot.
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,handlerSlot,1,SlotActionType.THROW,mc.player);
            attempts.merge(slot,1,Integer::sum);lastThrow=tick;dropped++;
            status="补货腾位：已发出 "+dropped+" 次整组丢弃，继续保留当前条带材料";return true;
        }
        if(lastThrow>=0&&tick-lastThrow<6){status="等待背包同步后按新容量备货";return true;}
        status="补货腾位结束，重新计算条带备货";stop();return false;
    }

    private void face(){mc.player.setYaw(throwYaw);mc.player.setPitch(-25);}
    private List<Integer> surplus(){
        List<RestockSurplusPlanner.Stack<Item>> inventory=new ArrayList<>();
        for(int i=0;i<36;i++){var s=mc.player.getInventory().getStack(i);if(!s.isEmpty())inventory.add(new RestockSurplusPlanner.Stack<>(i,s.getItem(),s.getCount()));}
        return RestockSurplusPlanner.surplusSlots(inventory,keep,eligible);
    }
    public void stop(){if(active&&mc.player!=null){mc.player.setYaw(originalYaw);mc.player.setPitch(originalPitch);}active=false;}
    public static Set<Item> carpetMaterials(){
        Set<Item> result=new HashSet<>();for(String color:MapArtColorFilter.COLORS)result.add(Registries.ITEM.get(Identifier.of("minecraft",color+"_carpet")));return result;
    }
}
