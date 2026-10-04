package net.fuyumori.stellashell.core.assets;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class FontCollectionTest {
    private File file(int... words)throws Exception{File f=File.createTempFile("font-test", ".ttc");try(DataOutputStream out=new DataOutputStream(new FileOutputStream(f))){for(int w:words)out.writeInt(w);}return f;}
    @Test public void collectionDirectory()throws Exception{File f=file(0x74746366,0x10000,2,20,32,0x10000,0,0,0x10000,0,0);try{assertEquals(2,FontCollection.count(f));}finally{f.delete();}}
    @Test public void rejectsTruncatedCollection()throws Exception{File f=file(0x74746366,0x10000,200);try{try{FontCollection.count(f);fail();}catch(IOException expected){}}finally{f.delete();}}
    @Test public void rejectsOutOfBoundsFace()throws Exception{File f=file(0x74746366,0x10000,1,-1,0,0,0);try{try{FontCollection.count(f);fail();}catch(IOException expected){}}finally{f.delete();}}
    @Test public void singleFont()throws Exception{File f=file(0x4f54544f,0,0);try{assertEquals(1,FontCollection.count(f));}finally{f.delete();}}
}
