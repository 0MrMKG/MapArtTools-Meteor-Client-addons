package com.buildertoolbox.fullauto;

import java.util.HashSet;
import java.util.Set;

public final class CanvasOrientationTest {
    public static void main(String[] args) {
        check(CanvasOrientation.DEFAULT.signatureSuffix().isEmpty(),"default ++ preserves old batch signatures");
        check(CanvasAxisDirection.POSITIVE.next().next()==CanvasAxisDirection.POSITIVE,"buttons have exactly two states");
        check(CanvasAxisDirection.valueOf(CanvasAxisDirection.NEGATIVE.toString())==CanvasAxisDirection.NEGATIVE,
            "saved enum values can be loaded independently of button labels");
        check(CanvasAxisDirection.POSITIVE.buttonLabel("X").equals("X +")
            &&CanvasAxisDirection.NEGATIVE.buttonLabel("Z").equals("Z -"),"axis-specific button labels");
        Set<String> signatures=new HashSet<>();
        for(int sx:new int[]{1,-1})for(int sz:new int[]{1,-1}) {
            var orientation=new CanvasOrientation(sx,sz);
            signatures.add(orientation.signatureSuffix());
            int originX=sx>0?64:191,originZ=sz>0?-192:-65;
            int minX=orientation.minX(originX,0,127),minZ=orientation.minZ(originZ,0,127);
            check(minX==64&&minZ==-192&&BatchFiles.aligned(minX,minZ),"opposite corners cover the same aligned map");
            boolean[] visited=new boolean[16384];
            for(int z=0;z<128;z++)for(int x=0;x<128;x++) {
                int index=orientation.index(x,z);
                check(!visited[index],"orientation is a bijection, with no omitted or overwritten pixels");
                visited[index]=true;
                check(minX+index%128==originX+sx*x&&minZ+index/128==originZ+sz*z,
                    "normalized worker cells equal signed native schematic coordinates");
                if(sx==1&&sz==1)check(index==z*128+x,"default matrix is byte-for-byte in the original order");
            }
            for(boolean cell:visited)check(cell,"every one of the 16384 world cells is assigned once");
            for(int offset:new int[]{-200,-127,-15,0,31,128}) {
                // Sub-region negative sizes retain native origins before orientation is applied.
                int localMin=BatchFiles.regionMinimum(offset,-128),localMax=offset;
                int wx=orientation.minX(1000,localMin,localMax),wz=orientation.minZ(-1000,localMin,localMax);
                for(int local=localMin;local<=localMax;local++) {
                    int n=local-localMin,index=orientation.index(n,n);
                    check(wx+index%128==1000+sx*local&&wz+index/128==-1000+sz*local,
                        "nonzero offsets and negative native region sizes do not recenter the blueprint");
                }
            }
        }
        check(signatures.size()==4,"each axis combination has distinct checkpoint identity");
        check(!BatchFiles.aligned(CanvasOrientation.DEFAULT.minX(0,0,127),64),"misaligned origins remain rejected");
        try { new CanvasOrientation(0,1);throw new AssertionError("zero sign accepted"); }
        catch(IllegalArgumentException expected) { }
        try { new CanvasOrientation(-1,2);throw new AssertionError("invalid sign accepted"); }
        catch(IllegalArgumentException expected) { }
        try { new CanvasOrientation(1,1).minX(Integer.MAX_VALUE,1,128);throw new AssertionError("coordinate overflow accepted"); }
        catch(ArithmeticException expected) { }
        System.out.println("CanvasOrientation: four directions, 65536 unique pixel mappings, default compatibility, aligned corners, offsets, negative native sizes and config labels passed.");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
