package com.forgeide.core.git;
import org.junit.Test;import static org.junit.Assert.*;
public class GitSupportTest{
 @Test public void credentialUrlsAndLocalProtocolsRejected(){for(String u:new String[]{"file:///data/secret","ssh://host/x","https://token@github.com/a/b","https://github.com/a?token=x","-upload-pack=sh"})try{RemotePolicy.https(u);fail(u);}catch(IllegalArgumentException expected){}assertEquals("github.com",RemotePolicy.https("https://github.com/a/b.git").getHost());}
 @Test public void conflictsResolveOnlyChosenBlock(){String text="before\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\nafter\n";java.util.List<ConflictParser.Conflict> conflicts=ConflictParser.parse(text);assertEquals(1,conflicts.size());assertEquals("before\nours\nafter\n",ConflictParser.resolve(text,conflicts.get(0),true,false));assertEquals("before\nours\ntheirs\nafter\n",ConflictParser.resolve(text,conflicts.get(0),true,true));}
}
