package com.forgeide.core.settings;
import org.junit.*;import org.junit.rules.TemporaryFolder;import java.nio.file.*;import static org.junit.Assert.*;
public class SettingsTest{
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 @Test public void overridesAndRoundTrip()throws Exception{Settings s=new Settings();s.set("fontSize",18,false);s.set("fontSize",21,true);assertEquals(21,s.integer("fontSize"));Path p=tmp.newFile().toPath();s.save(p,true);Settings t=new Settings();t.load(p,true);assertEquals(21,t.integer("fontSize"));}
 @Test public void limitsValidated(){for(Object v:new Object[]{0,1000,"14"})try{new Settings().set("fontSize",v,false);fail();}catch(IllegalArgumentException expected){}}
 @Test public void malformedFilePreserved()throws Exception{Path p=tmp.newFile().toPath();Files.writeString(p,"not JSON");try{new Settings().load(p,false);fail();}catch(java.io.IOException expected){}assertEquals("not JSON",Files.readString(p));}
}
