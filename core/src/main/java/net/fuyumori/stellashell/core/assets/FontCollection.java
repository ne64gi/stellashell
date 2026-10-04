package net.fuyumori.stellashell.core.assets;

import java.io.*;

/** Reads only the bounded collection directory; font parsing remains Android-owned. */
public final class FontCollection {
    private FontCollection(){}
    public static int count(File file)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            if(in.length()<12)throw new IOException("Invalid font header");
            int magic=in.readInt();
            if(magic!=0x74746366){if(magic==0x00010000||magic==0x4f54544f||magic==0x74727565)return 1;throw new IOException("Unsupported font format");}
            int version=in.readInt(),count=in.readInt();
            if((version!=0x00010000&&version!=0x00020000)||count<1||count>256||12L+count*4L>in.length())throw new IOException("Invalid TTC directory");
            for(int i=0;i<count;i++){long offset=Integer.toUnsignedLong(in.readInt());if(offset<12L+count*4L||offset>in.length()-12)throw new IOException("Invalid TTC font offset");}
            return count;
        }
    }
}
