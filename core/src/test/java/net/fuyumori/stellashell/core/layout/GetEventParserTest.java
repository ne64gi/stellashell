package net.fuyumori.stellashell.core.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class GetEventParserTest {
    // Exact previous DockGestureMonitor grammar and numeric conversions, kept as a test oracle.
    private static final Pattern OLD=Pattern.compile("\\[\\s*(\\d+)\\.(\\d{6})\\]\\s+(?:/dev/input/event\\d+:\\s+)?([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*");
    private static long[] old(String line){
        if(line.length()>160)return null;
        Matcher m=OLD.matcher(line);if(!m.matches())return null;
        long time=Long.parseLong(m.group(1))*1000+Integer.parseInt(m.group(2))/1000;
        return new long[]{Integer.parseInt(m.group(3),16),Integer.parseInt(m.group(4),16),(int)Long.parseLong(m.group(5),16),time};
    }
    private static void equivalent(String line){
        GetEventParser parser=new GetEventParser();
        long[] expected;
        try{expected=old(line);}
        catch(NumberFormatException overflow){
            assertThrows(NumberFormatException.class,()->parser.parse(line));return;
        }
        assertEquals(expected!=null,parser.parse(line));
        if(expected!=null)assertArrayEquals(expected,new long[]{parser.type(),parser.code(),parser.value(),parser.millis()});
    }
    private static String line(long seconds,int micros,int type,int code,int value,boolean prefix,String whitespace){
        return String.format(Locale.ROOT,"[ %d.%06d]%s%s%04x%s%04x%s%08x%s",seconds,micros,whitespace,
                prefix?"/dev/input/event12:"+whitespace:"",type,whitespace,code,whitespace,value,whitespace);
    }
    @Test public void rawTimestampAndOptionalDevicePrefixMatchPreviousParser(){
        String[] lines={"[   1234.000999] 0003 0035 000000ff", "[0.001000] /dev/input/event0: 0003 0039 ffffffff ",
                "[000012.999999]\t/dev/input/event0012:\t0003\t0036\tABCDEF01\r", "[\t12.100001]\u000b0000\f0000\r00000000\n"};
        for(String line:lines)equivalent(line);
        GetEventParser parser=new GetEventParser();
        assertTrue(parser.parse(lines[0]));assertEquals(1234000,parser.millis());
        assertTrue(parser.parse(lines[2]));assertEquals(12999,parser.millis());assertEquals(0xabcdef01,parser.value());
    }
    @Test public void fullUnsignedHexRangeRetainsSignedTrackingAndAxisValues(){
        GetEventParser parser=new GetEventParser();
        for(int value:new int[]{0,1,Integer.MAX_VALUE,Integer.MIN_VALUE,-1,-1024}){
            String line=line(7,999999,0xffff,0xffff,value,false," ");equivalent(line);
            assertTrue(parser.parse(line));assertEquals(value,parser.value());
            assertEquals(0xffff,parser.type());assertEquals(0xffff,parser.code());
        }
    }
    @Test public void labelsSignsBadWidthsAndTrailingContentAreRejected(){
        String[] invalid={"", " [1.000000] 0003 0035 00000001", "[+1.000000] 0003 0035 00000001",
                "[-1.000000] 0003 0035 00000001", "[1.00000] 0003 0035 00000001", "[1.0000000] 0003 0035 00000001",
                "[1.000000 ] 0003 0035 00000001", "[1.000000]0003 0035 00000001", "[1.000000] 003 0035 00000001",
                "[1.000000] 00003 0035 00000001", "[1.000000] 0003 0035 fffffff", "[1.000000] 0003 0035 fffffffff",
                "[1.000000] 0003 0035 -0000001", "[1.000000] 0003 0035 +0000001", "[1.000000] 0003 0035 0x000001",
                "[1.000000] 0003 0035 0000000g", "[1.000000] EV_ABS ABS_MT_POSITION_X 00000001",
                "[1.000000] /dev/input/event: 0003 0035 00000001", "[1.000000] /dev/input/event-1: 0003 0035 00000001",
                "[1.000000] /dev/input/event1:0003 0035 00000001", "[1.000000] /dev/input/event1 0003 0035 00000001",
                "[1.000000] /dev/input/event1: 0003 0035 00000001extra", "[1.000000]\u00a00003 0035 00000001",
                "[\u0661.000000] 0003 0035 00000001"};
        GetEventParser parser=new GetEventParser();assertFalse(parser.parse(null));
        for(String line:invalid){equivalent(line);assertFalse(parser.parse(line));}
        String complete="[123.000456] /dev/input/event12: 0003 0035 00000100";
        for(int end=0;end<complete.length();end++)equivalent(complete.substring(0,end));
    }
    @Test public void lineBudgetAndOverflowBehaviorMatchPreviousReader(){
        String base="[1.000000] 0000 0000 00000000";
        String boundary=base+" ".repeat(160-base.length());
        GetEventParser parser=new GetEventParser();
        assertTrue(parser.parse(boundary));assertFalse(parser.parse(boundary+" "));
        equivalent(boundary);equivalent(boundary+" ");
        for(String seconds:new String[]{"9223372036854775807","9223372036854775808","9".repeat(100),"0".repeat(90)+"1"}){
            equivalent("["+seconds+".999999] 0000 0000 00000000");
            equivalent("["+seconds+".999999] 0000 0000 0000000z");
        }
    }
    @Test public void rejectionDoesNotPublishPartOfAnEvent(){
        GetEventParser parser=new GetEventParser();
        assertTrue(parser.parse("[1.010000] 0003 0039 ffffffff"));
        assertFalse(parser.parse("[2.020000] 0000 0000 0000000z"));
        assertArrayEquals(new long[]{3,0x39,-1,1010},new long[]{parser.type(),parser.code(),parser.value(),parser.millis()});
        assertTrue(parser.parse("[2.020000] 0000 0000 00000000"));assertEquals(2020,parser.millis());
    }
    @Test public void deterministicValidAndMutatedCorpusMatchesPreviousParser(){
        Random random=new Random(731921);
        for(int i=0;i<5000;i++){
            String whitespace=i%3==0?"\t":i%3==1?" ":" \t";
            String line=line(random.nextInt(10000000),random.nextInt(1000000),random.nextInt(65536),
                    random.nextInt(65536),random.nextInt(),random.nextBoolean(),whitespace);
            if(i%2==0)line=line.toUpperCase(Locale.ROOT).replace("/DEV/INPUT/EVENT","/dev/input/event");
            equivalent(line);
            int at=random.nextInt(line.length());
            equivalent(line.substring(0,at)+"x"+line.substring(at+1));
        }
    }
    @Test public void multitouchSynDropRecoveryAndRotationReachIdenticalFrames(){
        // Synthetic Type-B stream: single touch, second finger (including same-frame removal),
        // dropped frame + BTN_TOUCH all-up recovery, slot reuse, negative/out-of-range axis and slot.
        int[][] events={{3,0x39,1},{3,0x35,250},{3,0x36,600},{0,0,0},{3,0x35,350},{0,0,0},
                {3,0x39,-1},{0,0,0},{3,0x39,2},{0,0,0},{3,0x2f,1},{3,0x39,3},{3,0x39,-1},{0,0,0},
                {3,0x2f,0},{3,0x39,-1},{0,0,0},{3,0x39,4},{0,0,0},{0,3,0},{3,0x39,-1},{0,0,0},
                {1,0x14a,0},{0,0,0},{3,0x39,5},{3,0x35,500},{3,0x36,1000},{0,0,0},
                {3,0x35,-1},{0,0,0},{3,0x39,-1},{0,0,0},{3,0x2f,32},{0,0,0},
                {3,0x2f,0},{3,0x39,6},{3,0x35,500},{3,0x36,1000},{0,0,0},{3,0x39,-1},{0,0,0}};
        for(int rotation=0;rotation<4;rotation++){
            List<String> expected=trace(events,rotation,false),actual=trace(events,rotation,true);
            assertEquals(expected,actual);
            assertTrue(actual.stream().anyMatch(e->e.startsWith("down")));
            assertTrue(actual.stream().anyMatch(e->e.startsWith("move")));
            assertTrue(actual.stream().anyMatch(e->e.startsWith("up")));
            assertTrue(actual.contains("cancel"));
        }
    }
    private static List<String> trace(int[][] events,int rotation,boolean optimized){
        List<String> result=new ArrayList<>();
        TouchFrames frames=new TouchFrames(0,1000,0,2000,rotation,400,800,new TouchFrames.Listener(){
            public void down(float x,float y,long t){result.add("down "+x+" "+y+" "+t);}
            public void move(float x,float y,long t){result.add("move "+x+" "+y+" "+t);}
            public void up(float x,float y,long t){result.add("up "+x+" "+y+" "+t);}
            public void cancel(){result.add("cancel");}
        });
        GetEventParser parser=new GetEventParser();
        for(int i=0;i<events.length;i++){
            int[] event=events[i];String line=line(1,i*1000,event[0],event[1],event[2],i%2==0," ");
            if(optimized){assertTrue(parser.parse(line));frames.event(parser.type(),parser.code(),parser.value(),parser.millis());}
            else{long[] parsed=old(line);frames.event((int)parsed[0],(int)parsed[1],(int)parsed[2],parsed[3]);}
        }
        return result;
    }
}
