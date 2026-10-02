package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

/** Device checks against real Android drawing and inverse input transforms; no desktop changes. */
public final class WidgetRenderInstrumentation extends Instrumentation {
    private int homeExternalDisplay=-1;private int widgetEditDisplay=-1;private int phoneProfileDisplay=-1;private int phoneTaskDisplay=-1;
    private boolean organizationOnly,polishPauseShell,homeChecks,workspaceChecks,notificationChecks,phoneSidebarChecks,taskCloseChecks;private int polishDisplay=-1;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);taskCloseChecks=arguments!=null&&"true".equals(arguments.getString("task_close_checks"));phoneSidebarChecks=arguments!=null&&"true".equals(arguments.getString("phone_sidebar_checks"));notificationChecks=arguments!=null&&"true".equals(arguments.getString("notification_group_checks"));workspaceChecks=arguments!=null&&"true".equals(arguments.getString("workspace_transfer_checks"));if(arguments!=null&&arguments.containsKey("home_external_display"))homeExternalDisplay=Integer.parseInt(arguments.getString("home_external_display"));if(arguments!=null&&arguments.containsKey("phone_task_display"))phoneTaskDisplay=Integer.parseInt(arguments.getString("phone_task_display"));if(arguments!=null&&arguments.containsKey("phone_profile_display"))phoneProfileDisplay=Integer.parseInt(arguments.getString("phone_profile_display"));if(arguments!=null&&arguments.containsKey("widget_edit_display"))widgetEditDisplay=Integer.parseInt(arguments.getString("widget_edit_display"));pinChecks=arguments!=null&&"true".equals(arguments.getString("pin_checks"));if(arguments!=null&&arguments.containsKey("icon_ui_display"))iconUiDisplay=Integer.parseInt(arguments.getString("icon_ui_display"));iconChecks=arguments!=null&&"true".equals(arguments.getString("icon_checks"));homeChecks=arguments!=null&&"true".equals(arguments.getString("home_checks"));polishPauseShell=arguments!=null&&"true".equals(arguments.getString("polish_pause_shell"));if(arguments!=null&&arguments.containsKey("polish_display"))polishDisplay=Integer.parseInt(arguments.getString("polish_display"));organizationOnly=arguments!=null&&"true".equals(arguments.getString("organization_only"));start();}
    private boolean pinChecks;private boolean iconChecks;private int iconUiDisplay=-1;
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            if(notificationChecks){
                Throwable[] failure={null};
                runOnMainSync(()->{try{NotificationGroupChecks.run(this);NotificationContentChecks.run(this);NotificationIndicatorChecks.run(this);}catch(Throwable e){failure[0]=e;}});
                if(failure[0]!=null)throw failure[0];
                result.putString("stream","Notification polish: app/user groups + compact expansion + stable updates + sender/time content + presence indicator + individual actions + EN/JA rendering passed (synthetic only)\n");
                finish(-1,result);return;
            }
            if(taskCloseChecks){new TaskCloseChecks(this).run();result.putString("stream","Phone task navigation + strict identity + same-task floating/fullscreen round trip + target task removal + unrelated task preservation passed (disposable fixtures only)\n");finish(-1,result);return;}
            if(phoneSidebarChecks){
                Throwable[] failure={null};
                runOnMainSync(()->{try{PhoneSidebarChecks.run(this);}catch(Throwable e){failure[0]=e;}});
                if(failure[0]!=null)throw failure[0];
                result.putString("stream","Phone sidebar: Start/pins/active ordering + long-press/right-click task menu + float/fullscreen/close identity + bounded lifecycle feed + stale-reply cleanup passed (synthetic only)\n");
                finish(-1,result);return;
            }
            if(workspaceChecks){new WorkspaceTransferChecks(this).run();result.putString("stream","Workspace queued handoff + rollback-all + post-move error + stranded identity recovery passed (synthetic backend)\n");finish(-1,result);return;}
            if(phoneTaskDisplay>0){new PhoneTaskChecks(this).run(phoneTaskDisplay);result.putString("stream","Phone ordinary fullscreen + explicit floating + owned-only external handoff/return passed\n");finish(-1,result);return;}
            if(phoneProfileDisplay>0){Throwable[] failure={null};runOnMainSync(()->{try{PhoneProfileChecks.run(this,phoneProfileDisplay);}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw failure[0];result.putString("stream","Phone profile migration/isolation + standard Android launch/HOME passed\n");finish(-1,result);return;}
            if(widgetEditDisplay>=0){new WidgetEditorChecks(this).run(widgetEditDisplay);result.putString("stream","Widget editor: explicit edit only, hold/scroll input + text/image/background persistence + body drag + cancel + sidebar adjustment/collapse/side + unchanged content bounds + persistence + Back passed\n");finish(-1,result);return;}
            if(pinChecks){TaskPinChecks.run();result.putString("stream","Window pin ownership + minimize/restore + multiple pins + fullscreen + stale task identity + cleanup/retry checks passed\n");finish(-1,result);return;}
            if(iconChecks){IconThemeChecks.run(this);if(iconUiDisplay>=0)IconThemeChecks.ui(this,iconUiDisplay);result.putString("stream","Icon pack discovery + asset mappings + manual index + override precedence + fallback + bounded alpha-preserving image import checks passed\n");finish(-1,result);return;}
            if(homeChecks){new HomeLauncherChecks(this).run(homeExternalDisplay);result.putString("stream","HOME candidate + disconnected basic home + app drawer + extension stop checks passed (default unchanged)\n");finish(-1,result);return;}
            if(polishDisplay>=0){new DesktopPolishChecks(this,polishDisplay,polishPauseShell).run();result.putString("stream","Desktop polish fixture checks passed on display "+polishDisplay+"\n");finish(-1,result);return;}
            Throwable[] failure={null};
            runOnMainSync(()->{try{workAreas();transparentIcons();if(organizationOnly){organization();widgetRouting();appearance();return;}check(400,300,200,100);check(100,120,200,100);check(200,100,200,100);iconPreview();locales();organization();widgetRouting();appearance();}catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw failure[0];
            if(!organizationOnly)wallpaperDecode();
            result.putString("stream",organizationOnly?"App organization + RemoteViews routing + appearance passed\n":"Adaptive icon transparency + WidgetViewport: 3 rendering/input scenarios + bounded wallpaper decoding + locale + app organization checks passed\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream","FAILED: "+android.util.Log.getStackTraceString(error)+"\n");finish(0,result);}
    }
    private void transparentIcons(){
        android.graphics.drawable.Drawable foreground=new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.ColorDrawable(Color.RED),.4f);
        android.graphics.drawable.Drawable source=new android.graphics.drawable.AdaptiveIconDrawable(new android.graphics.drawable.ColorDrawable(Color.BLUE),foreground);
        android.graphics.drawable.Drawable icon=AppIcons.display(source);Bitmap bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        icon.setBounds(0,0,100,100);icon.draw(new Canvas(bitmap));
        require(Color.alpha(bitmap.getPixel(5,5))==0,"adaptive background still opaque");
        require(bitmap.getPixel(50,50)==Color.RED,"adaptive foreground lost");
        android.graphics.drawable.Drawable second=AppIcons.display(source);second.setBounds(0,0,30,30);second.draw(new Canvas(bitmap));
        bitmap.eraseColor(Color.TRANSPARENT);icon.draw(new Canvas(bitmap));require(bitmap.getPixel(50,50)==Color.RED,"icon instances share bounds");
        bitmap.eraseColor(Color.TRANSPARENT);android.graphics.drawable.Drawable legacy=AppIcons.display(new android.graphics.drawable.ColorDrawable(Color.WHITE));legacy.setBounds(0,0,100,100);legacy.draw(new Canvas(bitmap));
        require(bitmap.getPixel(5,5)==Color.WHITE,"legacy icon pixels were destructively removed");bitmap.recycle();
    }
    private void workAreas(){
        android.graphics.Rect display=new android.graphics.Rect(0,0,1920,1080);
        WorkArea desktop=new WorkArea(display,display,false,32,60);
        require(desktop.content.equals(new android.graphics.Rect(0,32,1920,1020)),"external desktop geometry regressed");
        WorkArea local=new WorkArea(display,new android.graphics.Rect(30,80,1890,1000),false,32,60);
        require(local.content.equals(new android.graphics.Rect(30,112,1890,940)),"system insets and caption not composed");
        require(local.clamp(new android.graphics.Rect(0,0,3000,2000)).equals(local.content),"oversized restore escaped work area");
        WorkArea ime=new WorkArea(display,new android.graphics.Rect(30,80,1890,600),true,32,0);
        require(ime.content.bottom==600&&ime.content.top==112,"compact IME work area reserves a dock");
        android.graphics.Rect moved=ime.clamp(new android.graphics.Rect(1700,500,1900,900));
        require(moved.equals(new android.graphics.Rect(1690,200,1890,600)),"clamp must preserve fitting size and offsets");
        require(ime.maximized(ime.content)&&!ime.maximized(moved),"maximize detection wrong");
    }
    private void appearance(){
        try{
            java.io.File collection=new java.io.File("/system/fonts/NotoSansCJK-Regular.ttc");
            if(collection.exists()){
                int count=FontCollection.count(collection);require(count>1,"system TTC is not a collection");
                require(new android.graphics.Typeface.Builder(collection).setTtcIndex(0).build()!=null,"first TTC face failed");
                require(new android.graphics.Typeface.Builder(collection).setTtcIndex(count-1).build()!=null,"last TTC face failed");
                Appearance.Config selection=new Appearance.Config();selection.ttcIndex=count-1;
                require(new org.json.JSONObject(selection.json()).getInt("ttcIndex")==count-1,"TTC index not serialized");
            }
        }catch(Exception e){throw new AssertionError(e);}

        android.content.Context c=new android.content.ContextWrapper(getTargetContext()){
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("instrumentation_appearance",mode);}
        };
        try{
            Launches.prefs(c).edit().clear().commit();Appearance.Config defaults=Appearance.read(c);
            require(defaults.opacity==100&&!defaults.glass,"appearance defaults changed");
            Appearance.Config draft=new Appearance.Config();draft.panel=0xffdce7f1;draft.accent=0xff005e87;draft.glass=true;draft.opacity=65;draft.font="serif";
            require(!Appearance.read(c).glass,"draft leaked to preferences");
            Launches.prefs(c).edit().putString(Appearance.KEY,draft.json()).commit();Appearance.Config restored=Appearance.read(c);
            require(restored.panel==draft.panel&&restored.opacity==65&&restored.glass&&"serif".equals(restored.font),"appearance persistence failed");
            require(Appearance.foreground(0xffffffff)==0xff101725&&Appearance.foreground(0xff000000)==0xffe7edf5,"foreground contrast wrong");
            android.graphics.drawable.GradientDrawable surface=Appearance.surface(c,restored,0);
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(10,10,android.graphics.Bitmap.Config.ARGB_8888);surface.setBounds(0,0,10,10);surface.draw(new android.graphics.Canvas(image));require(android.graphics.Color.alpha(image.getPixel(5,5))>=164&&android.graphics.Color.alpha(image.getPixel(5,5))<=167,"glass opacity incorrect");image.recycle();
            Launches.prefs(c).edit().putString(Appearance.KEY,"{bad").commit();require(Appearance.read(c).panel==defaults.panel,"corrupt preferences did not recover");
            android.widget.FrameLayout tree=new android.widget.FrameLayout(c);android.appwidget.AppWidgetHostView widget=new android.appwidget.AppWidgetHostView(c);android.widget.TextView providerText=new android.widget.TextView(c);providerText.setTypeface(android.graphics.Typeface.MONOSPACE);widget.addView(providerText);tree.addView(widget);Appearance.fonts(tree);require(providerText.getTypeface()==android.graphics.Typeface.MONOSPACE,"provider font was modified");
        }finally{Launches.prefs(c).edit().clear().commit();}
    }
    private void widgetRouting(){
        final Bundle[] sent={null};
        android.content.Context base=new android.content.ContextWrapper(getTargetContext()){
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("instrumentation_widget_routing",mode);}
            @Override public android.view.Display getDisplay(){return getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);}
            @Override public void startIntentSender(android.content.IntentSender sender,android.content.Intent fill,int mask,int values,int extra,Bundle options){sent[0]=options;}
        };
        try{
            Launches.prefs(base).edit().clear().commit();
            android.widget.RemoteViews remote=new android.widget.RemoteViews("android",android.R.layout.simple_list_item_1);
            android.app.PendingIntent pending=android.app.PendingIntent.getActivity(getTargetContext(),981,new android.content.Intent(getTargetContext(),SetupActivity.class),android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            remote.setOnClickPendingIntent(android.R.id.text1,pending);
            View view=remote.apply(new WidgetLaunchContext(base),new android.widget.FrameLayout(base));
            require(view.findViewById(android.R.id.text1).performClick(),"RemoteViews click missing");
            require(sent[0]!=null&&sent[0].getInt("android.activity.launchDisplayId",-1)==0,"RemoteViews did not route to primary");
            sent[0]=null;Launches.prefs(base).edit().putBoolean(WidgetLaunchContext.PRIMARY,false).commit();
            view.findViewById(android.R.id.text1).performClick();
            require(sent[0]!=null&&sent[0].getInt("android.activity.launchDisplayId",-1)==0,"current-display route failed");
            pending.cancel();
        }finally{Launches.prefs(base).edit().clear().commit();}
    }
    private void check(int width,int height,int logicalWidth,int logicalHeight){
        WidgetViewport viewport=new WidgetViewport(getTargetContext());
        View child=new View(getTargetContext());child.setBackgroundColor(Color.CYAN);
        float[] touch={-1,-1};child.setOnTouchListener((v,event)->{touch[0]=event.getX();touch[1]=event.getY();return true;});
        viewport.addView(child);viewport.contentSize(logicalWidth,logicalHeight);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));viewport.layout(0,0,width,height);
        require(child.getWidth()==logicalWidth&&child.getHeight()==logicalHeight,"logical content was squeezed");
        float scale=WidgetGeometry.scale(width,height,logicalWidth,logicalHeight);
        require(child.getScaleX()==child.getScaleY(),"nonuniform scale");
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);viewport.draw(new Canvas(bitmap));
        require(bitmap.getPixel(width/2,height/2)==Color.CYAN,"content missing");
        if(height>logicalHeight*scale)require(bitmap.getPixel(width/2,0)==Color.TRANSPARENT,"letterbox was stretched");
        bitmap.recycle();
        float x=(width-logicalWidth*scale)/2f+logicalWidth*.75f*scale;
        float y=(height-logicalHeight*scale)/2f+logicalHeight*.25f*scale;
        MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0);viewport.dispatchTouchEvent(event);event.recycle();
        require(Math.abs(touch[0]-logicalWidth*.75f)<.01f&&Math.abs(touch[1]-logicalHeight*.25f)<.01f,"input coordinate mismatch");
        event=MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,x,y,0);viewport.dispatchTouchEvent(event);event.recycle();
    }
    private void organization(){
        android.content.Context isolated=new android.content.ContextWrapper(getTargetContext()){
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("instrumentation_app_organization",mode);}
        };
        try{
            isolated.getSharedPreferences("app_organization",0).edit().clear().commit();
            AppOrganization.addGroup(isolated,"Tools");AppOrganization.assign(isolated,"test.package/.Main","Tools");AppOrganization.hide(isolated,"test.package/.Main",true);
            require(AppOrganization.hidden(isolated,"test.package/.Main"),"hidden flag not saved");
            AppOrganization.renameGroup(isolated,"Tools","Work");
            require("Work".equals(AppOrganization.group(isolated,"test.package/.Main")),"rename lost membership");
            AppOrganization.renameGroup(isolated,"Work","");
            require(AppOrganization.group(isolated,"test.package/.Main").isEmpty(),"deleted group retained membership");
            require(AppOrganization.hidden(isolated,"test.package/.Main"),"group edit changed visibility");
            AppOrganization.hide(isolated,"test.package/.Main",false);require(!AppOrganization.hidden(isolated,"test.package/.Main"),"unhide failed");
            AppOrganization.addGroup(isolated,"First");AppOrganization.addGroup(isolated,"Second");
            AppOrganization.assign(isolated,"test.one/.Main","First");AppOrganization.assign(isolated,"test.two/.Main","Second");AppOrganization.assign(isolated,"test.untouched/.Main","Second");
            AppOrganization.hide(isolated,"test.one/.Main",true);AppOrganization.hide(isolated,"test.uninstalled/.Main",true);
            java.util.Map<String,Boolean> changes=new java.util.HashMap<>();changes.put("test.one/.Main",true);changes.put("test.two/.Main",false);
            require(AppOrganization.applySelection(isolated,null,changes),"visibility save failed");
            require(!AppOrganization.hidden(isolated,"test.one/.Main")&&AppOrganization.hidden(isolated,"test.two/.Main"),"visibility edits not applied");
            require(AppOrganization.hidden(isolated,"test.uninstalled/.Main"),"unknown hidden app lost");
            require("First".equals(AppOrganization.group(isolated,"test.one/.Main")),"visibility modified group");
            changes.clear();changes.put("test.one/.Main",false);changes.put("test.two/.Main",true);
            require(AppOrganization.applySelection(isolated,"First",changes),"membership save failed");
            require(AppOrganization.group(isolated,"test.one/.Main").isEmpty(),"unchecked member retained");
            require("First".equals(AppOrganization.group(isolated,"test.two/.Main")),"checked app not moved between groups");
            require("Second".equals(AppOrganization.group(isolated,"test.untouched/.Main")),"unedited membership changed");
            require(AppOrganization.hidden(isolated,"test.two/.Main"),"membership unhid app");
            AppOrganization.assign(isolated,"test.two/.Main","Second");changes.clear();changes.put("test.two/.Main",false);
            AppOrganization.applySelection(isolated,"First",changes);
            require("Second".equals(AppOrganization.group(isolated,"test.two/.Main")),"stale uncheck removed other group");
            require(!AppOrganization.applySelection(isolated,"Deleted",changes),"missing group resurrected");
        }finally{getTargetContext().deleteSharedPreferences("instrumentation_app_organization");}
    }
    private void locales(){
        android.content.res.Configuration configuration=new android.content.res.Configuration(getTargetContext().getResources().getConfiguration());
        configuration.setLocales(android.os.LocaleList.forLanguageTags("en"));
        android.content.Context en=getTargetContext().createConfigurationContext(configuration);
        configuration.setLocales(android.os.LocaleList.forLanguageTags("ja"));
        android.content.Context ja=getTargetContext().createConfigurationContext(configuration);
        configuration.setLocales(android.os.LocaleList.forLanguageTags("fr"));
        android.content.Context fallback=getTargetContext().createConfigurationContext(configuration);
        require("Wallpaper".equals(en.getString(R.string.ui_wallpaper)),"English locale");
        require("壁紙".equals(ja.getString(R.string.ui_wallpaper)),"Japanese locale");
        require("Wallpaper".equals(fallback.getString(R.string.ui_wallpaper)),"fallback locale");
        require("1 app".equals(en.getResources().getQuantityString(R.plurals.app_count,1,1)),"singular");
        require("2 apps".equals(en.getResources().getQuantityString(R.plurals.app_count,2,2)),"plural");
        require("Resize Clock".equals(en.getString(R.string.ui_resize,"Clock")),"English format");
        require("Clock のサイズを変更".equals(ja.getString(R.string.ui_resize,"Clock")),"Japanese format");
        require("外部ディスプレイが切断されています".equals(ErrorText.localize(ja,"ERROR: The external display is disconnected")),"backend error translation");
    }
    private void wallpaperDecode()throws Exception{
        java.io.File image=new java.io.File(getTargetContext().getCacheDir(),"wallpaper-test.png");
        try{
            Bitmap source=Bitmap.createBitmap(4096,128,Bitmap.Config.ARGB_8888);source.eraseColor(Color.MAGENTA);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){source.compress(Bitmap.CompressFormat.PNG,100,out);}source.recycle();
            java.lang.reflect.Method decode=DesktopWallpaper.class.getDeclaredMethod("decode",android.graphics.ImageDecoder.Source.class);decode.setAccessible(true);
            Bitmap decoded=(Bitmap)decode.invoke(null,android.graphics.ImageDecoder.createSource(image));
            require(decoded.getWidth()==2048&&decoded.getHeight()==64,"wallpaper aspect or memory bound");decoded.recycle();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){out.write(new byte[]{1,2,3});}
            boolean rejected=false;try{decode.invoke(null,android.graphics.ImageDecoder.createSource(image));}catch(java.lang.reflect.InvocationTargetException expected){rejected=true;}
            require(rejected,"invalid image was accepted");
        }finally{image.delete();}
    }
    private void iconPreview(){
        Bitmap bitmap=Bitmap.createBitmap(432,432,Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable icon=getTargetContext().getDrawable(net.fuyumori.stellashell.R.drawable.ic_desktop);
        icon.setBounds(0,0,432,432);icon.draw(new Canvas(bitmap));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"icon-preview.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(java.io.IOException e){throw new AssertionError(e);}finally{bitmap.recycle();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
