package net.fuyumori.stellashell.core.layout;

/** Reusable parser for bounded, unlabelled getevent -t lines. No input lines are retained. */
public final class GetEventParser {
    private int type,code,value;
    private long millis;

    /** Fields are valid only after true. Ordinary parsing allocates no per-event objects. */
    public boolean parse(String line) {
        if(line==null||line.length()>160||line.isEmpty()||line.charAt(0)!='[')return false;
        int length=line.length(),at=space(line,1),start=at;
        long seconds=0;
        boolean overflow=false;
        while(at<length&&decimal(line.charAt(at))){
            int digit=line.charAt(at++)-'0';
            if(seconds>(Long.MAX_VALUE-digit)/10)overflow=true;
            else if(!overflow)seconds=seconds*10+digit;
        }
        if(at==start||at>=length||line.charAt(at++)!='.')return false;
        int micros=0;
        for(int i=0;i<6;i++){
            if(at>=length||!decimal(line.charAt(at)))return false;
            micros=micros*10+line.charAt(at++)-'0';
        }
        if(at>=length||line.charAt(at++)!=']')return false;
        start=at;at=space(line,at);if(at==start)return false;
        if(at<length&&line.charAt(at)=='/'){
            String prefix="/dev/input/event";
            if(!line.startsWith(prefix,at))return false;
            at+=prefix.length();start=at;
            while(at<length&&decimal(line.charAt(at)))at++;
            if(at==start||at>=length||line.charAt(at++)!=':')return false;
            start=at;at=space(line,at);if(at==start)return false;
        }
        long parsedType=hex(line,at,4);if(parsedType<0)return false;at+=4;
        start=at;at=space(line,at);if(at==start)return false;
        long parsedCode=hex(line,at,4);if(parsedCode<0)return false;at+=4;
        start=at;at=space(line,at);if(at==start)return false;
        long parsedValue=hex(line,at,8);if(parsedValue<0)return false;at+=8;
        if(space(line,at)!=length)return false;
        // Preserve the old numeric parser's failure after a fully matched overflowing timestamp.
        // The message is constant: no raw input/coordinates/key content are retained or logged.
        if(overflow)throw new NumberFormatException("getevent seconds out of range");
        type=(int)parsedType;code=(int)parsedCode;value=(int)parsedValue;
        millis=seconds*1000+micros/1000;
        return true;
    }
    public int type(){return type;}
    public int code(){return code;}
    public int value(){return value;}
    public long millis(){return millis;}

    private static boolean decimal(char c){return c>='0'&&c<='9';}
    private static int space(String line,int at){
        while(at<line.length()){
            char c=line.charAt(at);
            if(c!=' '&&c!='\t'&&c!='\n'&&c!='\u000b'&&c!='\f'&&c!='\r')break;
            at++;
        }
        return at;
    }
    private static long hex(String line,int at,int count){
        if(at+count>line.length())return -1;
        long result=0;
        for(int i=0;i<count;i++){
            char c=line.charAt(at+i);
            int digit=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;
            if(digit<0)return -1;
            result=(result<<4)|digit;
        }
        return result;
    }
}
