package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.util.Xml;
import java.io.*;

/** Isolated preferences + a tiny installed pack: no personal icons/settings are overwritten. */
final class IconThemeChecks {
    static void run(Instrumentation test)throws Exception {
        Context context=new ContextWrapper(test.getTargetContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("instrumentation_icons_"+name,mode);}
        };
        String pkg=test.getContext().getPackageName(),app="example.app/example.app.Main",second="example.app/example.app.Other";
        ColorDrawable original=new ColorDrawable(Color.GREEN);File imported=null,input=null;
        try{
            Launches.prefs(context).edit().clear().commit();
            require(IconTheme.installed(context).stream().anyMatch(p->p.pkg.equals(pkg)),"pack discovery/package visibility");
            IconTheme.Pack pack=IconTheme.pack(context,pkg);require(pack!=null,"pack resource lookup");
            require("fixture_red".equals(pack.mappings.get(app)),"short component mapping from assets");
            require(pack.icons.contains("fixture_blue"),"drawable.xml manual picker index");
            IconTheme.selectPack(context,pkg);
            require(pixel(IconTheme.resolve(context,app,original))==Color.RED,"global pack mapping");
            require(pixel(IconTheme.resolve(context,second,original))==Color.GREEN,"unmapped activity fallback (not package-wide)");
            IconTheme.override(context,app,"pack:"+pkg+":fixture_blue");
            require(pixel(IconTheme.resolve(context,app,original))==Color.BLUE,"manual pack override precedence");
            IconTheme.selectPack(context,"");require(pixel(IconTheme.resolve(context,app,original))==Color.BLUE,"override survives global change");
            IconTheme.selectPack(context,pkg);IconTheme.override(context,app,"default");require(pixel(IconTheme.resolve(context,app,original))==Color.GREEN,"explicit original bypasses global pack");
            IconTheme.override(context,app,"");require(pixel(IconTheme.resolve(context,app,original))==Color.RED,"reset resumes global pack");
            IconTheme.override(context,app,"pack:missing.icon.pack:gone");require(pixel(IconTheme.resolve(context,app,original))==Color.RED,"uninstalled individual pack fallback");
            IconTheme.selectPack(context,"missing.icon.pack");require(pixel(IconTheme.resolve(context,app,original))==Color.GREEN,"uninstalled global pack fallback");
            IconTheme.selectPack(context,pkg);IconTheme.override(context,app,"pack:"+pkg+":missing_resource");require(pixel(IconTheme.resolve(context,app,original))==Color.RED,"missing drawable fallback");
            android.content.res.Resources resources=test.getContext().getResources();
            IconTheme.Pack parsed=new IconTheme.Pack(pkg,resources);org.xmlpull.v1.XmlPullParser parser=Xml.newPullParser();
            parser.setInput(new StringReader("<resources><item component='ComponentInfo{one.pkg/.Main}' drawable='fixture_red'/><item component='bad' drawable='fixture_blue'/><item component='two.pkg/.Main' drawable='../unsafe'/></resources>"));IconTheme.parse(parser,parsed);
            require(parsed.mappings.size()==1&&parsed.mappings.containsKey("one.pkg/one.pkg.Main"),"component canonicalization and invalid entry handling");
            Bitmap image=Bitmap.createBitmap(1024,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();paint.setColor(Color.MAGENTA);canvas.drawRect(300,100,700,400,paint);
            input=File.createTempFile("icon-source-",".png",context.getCacheDir());try(OutputStream out=new FileOutputStream(input)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            imported=IconTheme.importImage(context,Uri.fromFile(input));Bitmap decoded=BitmapFactory.decodeFile(imported.getPath());
            require(decoded.getWidth()==256&&decoded.getHeight()==128,"bounded image decoding/aspect ratio");require(Color.alpha(decoded.getPixel(0,0))==0,"image alpha preserved");decoded.recycle();
            IconTheme.override(context,app,"file:"+imported.getName());require(pixel(IconTheme.resolve(context,app,original))==Color.MAGENTA,"imported image precedence");
            IconTheme.override(context,app,"");require(!imported.exists(),"replaced image cleanup");
            Drawable a=IconTheme.resolve(context,app,original),b=IconTheme.resolve(context,app,original);a.setBounds(0,0,64,64);b.setBounds(0,0,8,8);require(a.getBounds().width()==64,"independent drawable instances");
        }finally{
            Launches.prefs(context).edit().clear().commit();IconTheme.invalidate();if(imported!=null)imported.delete();if(input!=null)input.delete();
        }
    }
    static void ui(Instrumentation test,int display)throws Exception {
        Context context=test.getTargetContext();String component=test.getContext().getPackageName()+"/net.fuyumori.stellashell.IconPackFixtureActivity";
        SharedPreferences prefs=Launches.prefs(context);String key=IconTheme.key(component),old=prefs.getString(key,null);android.app.Activity[] screen={null};
        try{
            Intent intent=new Intent(context,IconSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("component",component);
            screen[0]=test.startActivitySync(intent,android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
            test.runOnMainSync(()->{android.view.View button=text(screen[0].getWindow().getDecorView(),context.getString(R.string.icons_choose_from_pack));require(button!=null,"individual editor controls");button.performClick();});
            await(test,()->type(screen[0].getWindow().getDecorView(),android.widget.ListView.class)!=null);
            test.runOnMainSync(()->{
                android.widget.ListView list=(android.widget.ListView)type(screen[0].getWindow().getDecorView(),android.widget.ListView.class);
                int found=-1;for(int i=0;i<list.getAdapter().getCount();i++)if("StellaShell icon test fixture".equals(list.getAdapter().getItem(i)))found=i;
                require(found>=0,"fixture pack choice");list.performItemClick(null,found,found);
            });
            await(test,()->type(screen[0].getWindow().getDecorView(),android.widget.GridView.class)!=null);
            test.runOnMainSync(()->{
                android.widget.GridView grid=(android.widget.GridView)type(screen[0].getWindow().getDecorView(),android.widget.GridView.class);
                int found=-1;for(int i=0;i<grid.getAdapter().getCount();i++)if("fixture_blue".equals(grid.getAdapter().getItem(i)))found=i;
                require(found>=0,"manual picker grid");grid.performItemClick(null,found,found);
                require(prefs.getString(key,"").endsWith(":fixture_blue"),"manual picker applies selection");
                text(screen[0].getWindow().getDecorView(),context.getString(R.string.icons_original)).performClick();require("default".equals(prefs.getString(key,"")),"original button");
                text(screen[0].getWindow().getDecorView(),context.getString(R.string.icons_follow_pack)).performClick();require(!prefs.contains(key),"clear override button");
            });
        }finally{
            test.runOnMainSync(()->{if(screen[0]!=null)screen[0].finishAndRemoveTask();IconTheme.override(context,component,old==null?"":old);});
        }
    }
    private static android.view.View text(android.view.View view,String label){
        if(view instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)view).getText()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View found=text(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private static android.view.View type(android.view.View view,Class<?> wanted){
        if(wanted.isInstance(view))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View found=type(group.getChildAt(i),wanted);if(found!=null)return found;}}return null;
    }
    private static void await(Instrumentation test,java.util.function.BooleanSupplier ready)throws Exception {
        for(int i=0;i<100;i++){boolean[] result={false};test.runOnMainSync(()->result[0]=ready.getAsBoolean());if(result[0])return;Thread.sleep(50);}throw new AssertionError("picker load timeout");
    }
    private static int pixel(Drawable drawable){Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,64,64);drawable.draw(new Canvas(bitmap));int color=bitmap.getPixel(32,32);bitmap.recycle();return color;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
