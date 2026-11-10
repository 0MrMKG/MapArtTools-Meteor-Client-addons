/*

 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).

 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10.

 */



package com.buildertoolbox.client.gui.widgets;



import java.util.ArrayList;

import java.util.List;



public abstract class WMultiLabel extends WLabel {

    protected List<String> lines = new ArrayList<>(2);



    protected double maxWidth;



    public WMultiLabel(String text, boolean title, double maxWidth) {

        super(text, title);



        this.maxWidth = maxWidth;

    }



    @Override
    protected void onCalculateSize() {
        lines.clear(); StringBuilder line=new StringBuilder(); double lineWidth=0; width=0;
        double limit=maxWidth>0?theme.scale(maxWidth):Double.MAX_VALUE;
        for(int codePoint:text.codePoints().toArray()){
            String glyph=new String(Character.toChars(codePoint));double glyphWidth=theme.textWidth(glyph);
            if(codePoint=='\n'||lineWidth+glyphWidth>limit){lines.add(line.toString());width=Math.max(width,lineWidth);line.setLength(0);lineWidth=0;}
            if(codePoint!='\n'){line.append(glyph);lineWidth+=glyphWidth;}
        }
        lines.add(line.toString());width=Math.max(width,lineWidth);height=lines.size()*theme.textHeight(title);
    }
}
