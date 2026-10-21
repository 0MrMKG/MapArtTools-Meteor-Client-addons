package com.buildertoolbox.fullauto;

public final class AutoEatPolicy {
    private AutoEatPolicy() { }
    public static boolean shouldEat(boolean enabled, boolean creative, int hunger, boolean eating) {
        return enabled && !creative && hunger < (eating ? 20 : 8);
    }
    public static long timeoutTicks(float consumeSeconds) {
        return Math.max(100L, (long) Math.ceil(Math.max(0, consumeSeconds) * 20) + 60L);
    }
}
