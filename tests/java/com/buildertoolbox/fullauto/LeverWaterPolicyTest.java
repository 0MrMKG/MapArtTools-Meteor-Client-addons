package com.buildertoolbox.fullauto;

public final class LeverWaterPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(LeverWaterPolicy.allowsFluid(false,true,false),"ordinary dry floor remains valid");
        check(!LeverWaterPolicy.allowsFluid(false,false,true),"ordinary container travel still avoids water");
        check(LeverWaterPolicy.allowsFluid(true,false,true),"lever route may cross water");
        check(!LeverWaterPolicy.allowsFluid(true,false,false),"lava and other fluids remain excluded");
        check(LeverWaterPolicy.allowsDrift(true,true),"currents must not indefinitely delay lever interaction");
        check(!LeverWaterPolicy.allowsDrift(false,true),"water exception is exclusive to lever operations");
        check(!LeverWaterPolicy.allowsDrift(true,false),"dry movement still needs to settle");
        check(LeverWaterPolicy.reached(0.5,0,0.3,true,true),"slightly buoyant water arrival near a supported floor");
        check(!LeverWaterPolicy.reached(0.5,0,0.3,true,false),"intermediate corners keep the precise radius");
        check(!LeverWaterPolicy.reached(0,0,0,false,true),"water without a floor is not a stand");
        check(!LeverWaterPolicy.reached(0,0,Double.NaN,true,true),"unknown floor is not a stand");
        check(!LeverWaterPolicy.reached(0,0,1.5,true,true),"deep swimming is not mistaken for arrival on the bottom");
        check(route(true,false,false),"flooded floor is traversable for the lever");
        check(!route(false,false,false),"the same flooded floor is excluded in ordinary mode");
        check(!route(true,true,false),"a flooded hole is still impassable");
        check(!route(true,false,true),"lava is still impassable");
        System.out.println("LeverWaterPolicy: water-scoped travel, drift, supported arrival, holes and lava checks passed.");
    }

    private static boolean route(boolean lever, boolean hole, boolean lava) {
        return GroundPathVisibility.canWalk(0.5,0.5,6.5,0.5,0.3,0.6,4096,(x,z)-> {
            boolean flooded=x>=2&&x<=4;
            if(hole&&x==3)return Double.NaN;
            return LeverWaterPolicy.allowsFluid(lever,!flooded,flooded&&!lava)?64:Double.NaN;
        });
    }
}
