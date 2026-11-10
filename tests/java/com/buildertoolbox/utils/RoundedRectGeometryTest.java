package com.buildertoolbox.utils;

import java.util.Random;

public final class RoundedRectGeometryTest {
    public static void main(String[] args) {
        check(RoundedRectGeometry.spans(0,20,4,true,true).isEmpty(),"zero width has no draws");
        check(RoundedRectGeometry.spans(20,-1,4,true,true).isEmpty(),"collapsed animation never draws negative heights");
        Random random=new Random(391911);
        for(int run=0;run<2000;run++) {
            int width=1+random.nextInt(120),height=1+random.nextInt(100),radius=random.nextInt(80)-5;
            boolean top=random.nextBoolean(),bottom=random.nextBoolean();
            var spans=RoundedRectGeometry.spans(width,height,radius,top,bottom);
            int[] inset=new int[height];
            boolean[] covered=new boolean[height];
            int clamped=Math.max(0,Math.min(radius,Math.min(width,height)/2));
            check(spans.size()<=2*clamped+1,"draw calls depend on corner radius, not window height");
            for(var span:spans) {
                check(span.inset()>=0&&span.width()>0&&span.inset()+span.width()<=width,"all horizontal spans remain within their widget");
                check(span.y()>=0&&span.height()>0&&span.y()+span.height()<=height,"all vertical spans remain within their widget");
                for(int y=span.y();y<span.y()+span.height();y++) {
                    check(!covered[y],"transparent backgrounds must not double-blend overlapping spans");
                    covered[y]=true;inset[y]=span.inset();
                }
            }
            for(boolean row:covered)check(row,"every row is rendered exactly once");
            if(top==bottom)for(int y=0;y<height;y++)check(inset[y]==inset[height-1-y],"rounded windows are vertically symmetric");
            if(!top)check(inset[0]==0,"joined header bottom/body top can remain square");
            if(!bottom)check(inset[height-1]==0,"joined surfaces do not create corner gaps");
        }
        check(RoundedRectGeometry.spans(800,10000,6,true,true).size()<=13,"tall scrolling content has a bounded draw budget");
        System.out.println("RoundedRectGeometry: collapsed/tiny/large widgets, joined corners, 2000 randomized bounds and non-overlapping alpha checks passed.");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
