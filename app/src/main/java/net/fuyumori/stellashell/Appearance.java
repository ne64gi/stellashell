package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;

final class Appearance {
    static final String KEY="appearance_config";
    static final class Config {
        int panel=0xff1c2636,accent=0xff6ee7c8,opacity=100;
        int ttcIndex=0;
        boolean glass=false;
        String font="sans-serif",file="";
        String json(){try{return new JSONObject().put("panel",panel).put("accent",accent).put("opacity",opacity).put("glass",glass).put("font",font).put("file",file).put("ttcIndex",ttcIndex).toString();}catch(Exception e){throw new IllegalStateException(e);}}
    }
    static Config read(Context c){
        Config v=new Config();try{JSONObject j=new JSONObject(Launches.prefs(c).getString(KEY,"{}"));v.panel=j.optInt("panel",v.panel)|0xff000000;v.accent=j.optInt("accent",v.accent)|0xff000000;v.opacity=Math.max(35,Math.min(100,j.optInt("opacity",100)));v.glass=j.optBoolean("glass",false);v.font=j.optString("font","sans-serif");v.file=j.optString("file","");v.ttcIndex=Math.max(0,j.optInt("ttcIndex",0));}catch(Exception ignored){}return v;
    }
    static Typeface typeface(Context c,Config v){
        if("custom".equals(v.font)&&v.file.matches("font-[a-zA-Z0-9.-]+"))try{Typeface result=new Typeface.Builder(new File(c.getFilesDir(),v.file)).setTtcIndex(v.ttcIndex).build();if(result!=null)return result;}catch(RuntimeException ignored){}
        return Typeface.create("custom".equals(v.font)?"sans-serif":v.font,Typeface.NORMAL);
    }
    static int foreground(int color){double y=0.2126*linear(Color.red(color))+0.7152*linear(Color.green(color))+0.0722*linear(Color.blue(color));return y>0.179?0xff101725:0xffe7edf5;}
    private static double linear(int n){double x=n/255.0;return x<=0.04045?x/12.92:Math.pow((x+0.055)/1.055,2.4);}
    static int theme(){return foreground(current.panel)==0xff101725?R.style.AppThemeLight:R.style.AppTheme;}
    static Config current=new Config();
    static Typeface face=Typeface.DEFAULT;
    static void load(Context c){current=read(c);face=typeface(c,current);Ui.PANEL=current.panel;Ui.BG=current.panel==0xff1c2636?0xff101725:current.panel;Ui.ACCENT=current.accent;Ui.TEXT=foreground(current.panel);Ui.MUTED=Ui.TEXT==0xff101725?0xff46515e:0xffb7c2cf;}
    static GradientDrawable surface(Context c,int radius){return surface(c,current,radius);}
    static GradientDrawable surface(Context c,Config v,int radius){GradientDrawable d=new GradientDrawable();d.setColor((v.panel&0xffffff)|((v.glass?Math.round(v.opacity*2.55f):255)<<24));d.setCornerRadius(Ui.dp(c,radius));if(v.glass)d.setStroke(Ui.dp(c,1),0x55ffffff);return d;}
    static void fonts(View view){
        if(view instanceof android.appwidget.AppWidgetHostView)return;
        if(view instanceof TextView){TextView t=(TextView)view;t.setTypeface(face,t.getTypeface()==null?Typeface.NORMAL:t.getTypeface().getStyle());}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)fonts(group.getChildAt(i));}
    }
}
