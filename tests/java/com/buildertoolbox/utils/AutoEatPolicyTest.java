package com.buildertoolbox.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AutoEatPolicyTest {
    public static void main(String[] args) throws Exception {
        check(AutoEatPolicy.shouldEat(true,false,7,false),"start below four hunger icons");
        check(!AutoEatPolicy.shouldEat(true,false,8,false),"exactly four icons does not start a new session");
        check(AutoEatPolicy.shouldEat(true,false,8,true),"do not stop at the trigger threshold");
        check(AutoEatPolicy.shouldEat(true,false,19,true),"continue until full");
        check(!AutoEatPolicy.shouldEat(true,false,20,true),"full hunger releases use and resumes work");
        check(!AutoEatPolicy.shouldEat(false,false,0,true),"disabling the setting stops eating");
        check(!AutoEatPolicy.shouldEat(true,true,0,true),"creative mode does not consume food");
        for(int hunger=0;hunger<=20;hunger++)for(boolean enabled:new boolean[]{false,true})for(boolean eating:new boolean[]{false,true}) {
            boolean old=AutoEatPolicy.shouldEat(enabled,false,hunger,eating);
            boolean full=com.buildertoolbox.fullauto.AutoEatPolicy.shouldEat(enabled,false,hunger,eating);
            check(old==full,"independent workers have identical trigger/completion behavior");
        }
        check(AutoEatPolicy.timeoutTicks(1.6f)==100,"normal foods have a bounded five-second progress window");
        check(AutoEatPolicy.timeoutTicks(10f)==260,"custom slow foods get their consume duration plus margin");
        for(String name:List.of("LitematicaMapArtModule","FullAutoMapArtModule")) {
            String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/"+name+".java"),StandardCharsets.UTF_8);
            int setting=source.indexOf(".name(\"Auto Eat\")");
            check(setting>=0&&source.substring(setting,source.indexOf(".build()",setting)).contains(".defaultValue(true)"),name+" must default auto eat on");
            check(source.contains("autoEater.stop();"),name+" releases controls on shutdown/disable");
            check(source.contains("if (autoEater.eating()) event.target = null;"),name+" suppresses block-use crosshair while eating");
        }
        for(String namespace:List.of("utils","fullauto")) {
            String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/"+namespace+"/MapArtAutoEat.java"),StandardCharsets.UTF_8);
            check(source.contains("slot<36"),"food search covers hotbar and main inventory");
            check(source.contains("SlotActionType.SWAP")&&!source.contains("SlotActionType.PICKUP")&&!source.contains("InvUtils.move()"),"food transfer cannot reintroduce cursor-based inventory sorting");
            check(source.contains("Hand.MAIN_HAND"),"eating uses an explicit hand instead of clicking a block");
            check(source.contains("attempts>=3")&&source.contains("cooldownUntil=System.currentTimeMillis()+10000"),"failed eating must yield to the worker and retry later");
        }
        System.out.println("AutoEatPolicy: hunger boundaries, until-full behavior, independent default-on settings, cursor-free food switching and bounded retries passed.");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
