package com.buildertoolbox.fullauto;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

public record BatchPainting(String name, int minX, int y, int minZ, BlockState[] blocks) {
    public BatchPainting { if(blocks.length!=128*128)throw new IllegalArgumentException("画布必须为128×128"); blocks=blocks.clone(); }
    @Override public BlockState[] blocks() { return blocks.clone(); }
    public BlockState at(int x,int z) { return blocks[z*128+x]; }
    public BlockPos position(int x,int z) { return new BlockPos(minX+x,y,minZ+z); }
    public BlockPos center() { return position(64,64); }
}
