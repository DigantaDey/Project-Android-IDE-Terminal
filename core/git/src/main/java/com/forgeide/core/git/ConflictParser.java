package com.forgeide.core.git;

import java.util.*;

/** Parses real Git conflict blocks and resolves one block without touching unrelated text. */
public final class ConflictParser {
    public static final class Conflict { public final int start,end;public final String ours,theirs;Conflict(int start,int end,String ours,String theirs){this.start=start;this.end=end;this.ours=ours;this.theirs=theirs;} }
    public static List<Conflict> parse(String text){
        List<Conflict> out=new ArrayList<>();String[] lines=text.split("(?<=\n)",-1);int offset=0,start=-1,oursStart=0,divider=-1,theirsStart=0;
        for(String line:lines){if(line.startsWith("<<<<<<< ")){start=offset;oursStart=offset+line.length();divider=-1;}else if(start>=0&&line.stripTrailing().equals("=======")){divider=offset;theirsStart=offset+line.length();}else if(start>=0&&divider>=0&&line.startsWith(">>>>>>> ")){out.add(new Conflict(start,offset+line.length(),text.substring(oursStart,divider),text.substring(theirsStart,offset)));start=-1;}offset+=line.length();}return out;
    }
    public static String resolve(String text,Conflict conflict,boolean ours,boolean both){String choice=both?conflict.ours+conflict.theirs:ours?conflict.ours:conflict.theirs;return text.substring(0,conflict.start)+choice+text.substring(conflict.end);}
}
