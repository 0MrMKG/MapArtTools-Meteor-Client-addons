package com.buildertoolbox.fullauto;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;

/** Reads privately; never creates/changes a global Litematica placement. */
public final class BatchPaintingLoader {
    private BatchPaintingLoader() { }
    public static BatchPainting load(Path folder,BatchFiles.Entry file,BlockPos origin) throws IOException {
        Path path=folder.resolve(file.name()).normalize();
        if(!path.startsWith(folder)||!path.toRealPath().startsWith(folder.toRealPath()))throw new IOException("蓝图路径越界");
        if(!BatchFiles.fingerprint(path).equals(file.sha256()))throw new IOException("蓝图已变化，请确认后重建批次："+file.name());
        LitematicaSchematic schematic=LitematicaSchematic.createFromFile(folder,file.name());
        if(schematic==null)throw new IOException("无法读取蓝图："+file.name());
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var region:schematic.getAreaPositions().entrySet()) {
            BlockPos size=schematic.getAreaSize(region.getKey()),pos=region.getValue();
            if(size==null||size.getX()==0||size.getY()==0||size.getZ()==0)throw new IOException("蓝图区域尺寸无效");
            int x=BatchFiles.regionMinimum(pos.getX(),size.getX()),y=BatchFiles.regionMinimum(pos.getY(),size.getY()),z=BatchFiles.regionMinimum(pos.getZ(),size.getZ());
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,Math.addExact(x,Math.abs(size.getX())-1));
            maxY=Math.max(maxY,Math.addExact(y,Math.abs(size.getY())-1));
            maxZ=Math.max(maxZ,Math.addExact(z,Math.abs(size.getZ())-1));
        }
        if((long)maxX-minX+1!=128||(long)maxZ-minZ+1!=128||minY!=maxY)
            throw new IOException("只支持单层128×128地图画，文件区域范围不符："+file.name());
        if(minY!=0)throw new IOException("地图画层相对蓝图原点的Y不是0；请修正文件原点，程序不会擅自改层高");
        int worldX=Math.addExact(origin.getX(),minX),worldZ=Math.addExact(origin.getZ(),minZ);
        if(!BatchFiles.aligned(worldX,worldZ))throw new IOException("画布未对齐原版128格地图网格：最小X/Z都应为128×整数-64；当前 "+worldX+","+worldZ);
        BlockState[] blocks=new BlockState[16384]; Arrays.fill(blocks,Blocks.AIR.getDefaultState());
        boolean[] assigned=new boolean[16384];int nonAir=0;
        for(var region:schematic.getAreaPositions().entrySet()) {
            String name=region.getKey();BlockPos pos=region.getValue(),size=schematic.getAreaSize(name);
            var container=schematic.getSubRegionContainer(name);
            if(container==null)throw new IOException("缺少区域方块数据："+name);
            int x0=BatchFiles.regionMinimum(pos.getX(),size.getX())-minX,z0=BatchFiles.regionMinimum(pos.getZ(),size.getZ())-minZ;
            for(int z=0;z<Math.abs(size.getZ());z++)for(int x=0;x<Math.abs(size.getX());x++) {
                BlockState state=container.get(x,0,z);int index=(z0+z)*128+x0+x;
                if(!state.isAir()&&(!(state.getBlock().asItem() instanceof BlockItem)||!state.getFluidState().isEmpty()))throw new IOException("画面包含无法普通放置的方块："+state);
                if(assigned[index]&&!blocks[index].equals(state))throw new IOException("蓝图子区域重叠且内容冲突");
                blocks[index]=state;assigned[index]=true;
            }
        }
        for(BlockState state:blocks)if(!state.isAir())nonAir++;
        if(nonAir==0)throw new IOException("地图画为空");
        return new BatchPainting(file.name(),worldX,origin.getY(),worldZ,blocks);
    }
}
