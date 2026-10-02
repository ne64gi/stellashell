package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Real view dispatch, isolated preferences/host, and no personal widget allocation. */
final class WidgetEditorChecks {
    private final android.app.Instrumentation test;
    private WidgetEditorFixtureActivity screen;
    private WidgetEditFrame first;
    private int taps,cancels,moves;
    private long down;
    WidgetEditorChecks(android.app.Instrumentation test){this.test=test;}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});test.waitForIdleSync();if(error[0]!=null)throw new AssertionError(error[0]);}
    private void await(java.util.function.BooleanSupplier condition)throws Exception {for(int n=0;n<100;n++){boolean[] ready={false};main(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(40);}throw new AssertionError("fixture wait timeout at "+new Exception().getStackTrace()[1]+" frame="+first.getWidth()+"x"+first.getHeight()+" child="+first.getChildAt(0).getWidth()+"x"+first.getChildAt(0).getHeight()+" canvas="+screen.canvas.getWidth()+"x"+screen.canvas.getHeight());}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void event(int action,float x,float y){if(action==MotionEvent.ACTION_DOWN)down=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);first.dispatchTouchEvent(e);e.recycle();}
    private View desc(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=desc(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    private View text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return root;if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=text(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    private void clickDescription(int id){View v=desc(screen.canvas,screen.getString(id));require(v!=null,"missing control "+id);v.performClick();}
    private JSONObject saved() {try{return new JSONArray(screen.getSharedPreferences("widget_editor_fixture",0).getString("items","[]")).getJSONObject(0);}catch(Exception e){throw new AssertionError(e);}}
    private Object field(Object target,String name){try{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private void inputs(View view,java.util.List<EditText> result){if(view instanceof EditText)result.add((EditText)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)inputs(((ViewGroup)view).getChildAt(i),result);}
    private View type(View view,Class<?> type){if(type.isInstance(view))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=type(((ViewGroup)view).getChildAt(i),type);if(child!=null)return child;}return null;}
    private JSONArray items(){try{return new JSONArray(screen.getSharedPreferences("widget_editor_fixture",0).getString("items","[]"));}catch(JSONException error){throw new AssertionError(error);}}
    private void customItems()throws Exception{
        java.io.File source=new java.io.File(screen.getCacheDir(),"widget-content-fixture.png");String[] imageName={null};
        try{
            main(screen.widgets::addText);
            main(()->{
                AlertDialog dialog=(AlertDialog)field(screen.widgets,"contentDialog");java.util.List<EditText> fields=new java.util.ArrayList<>();inputs(dialog.getWindow().getDecorView(),fields);
                fields.get(0).setText("Stella\nCustom text");fields.get(1).setText("26");fields.get(2).setText("#123456");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                require(items().length()==3,"text item did not save");View frame=screen.canvas.getChildAt(2);require(text(frame,"Stella\nCustom text")!=null,"text item missing");
            });
            main(()->text(screen.canvas,screen.getString(R.string.widget_item_background)).performClick());
            main(()->{
                View frame=screen.canvas.getChildAt(2);AlertDialog dialog=(AlertDialog)field(screen.widgets,"contentDialog");java.util.List<EditText> fields=new java.util.ArrayList<>();inputs(dialog.getWindow().getDecorView(),fields);fields.get(0).setText("#234567");((SeekBar)type(dialog.getWindow().getDecorView(),SeekBar.class)).setProgress(75);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                android.graphics.drawable.GradientDrawable background=(android.graphics.drawable.GradientDrawable)frame.getBackground();require(android.graphics.Color.alpha(background.getColor().getDefaultColor())==191,"item background alpha wrong");require(frame.getAlpha()==1&&((ViewGroup)frame).getChildAt(0).getAlpha()==1,"background faded text content");
            });
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(3200,200,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.TRANSPARENT);bitmap.setPixel(1600,100,android.graphics.Color.CYAN);
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(source)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
            main(()->require(screen.widgets.result(7103,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(source))),"image result not handled"));
            await(()->items().length()==4);
            main(()->{
                try{JSONObject image=items().getJSONObject(3);require("image".equals(image.getString("kind")),"image became provider widget");imageName[0]=image.getString("image");}catch(JSONException error){throw new AssertionError(error);}
                require(screen.canvas.getChildAt(3) instanceof WidgetEditFrame&&type(screen.canvas.getChildAt(3),ImageView.class)!=null,"image view missing");
                android.graphics.BitmapFactory.Options info=new android.graphics.BitmapFactory.Options();info.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(WidgetImages.file(screen,imageName[0]).getPath(),info);require(info.outWidth==1600&&info.outHeight==100,"image import aspect/memory bound wrong");
                require(WidgetImages.file(screen,"../../private.png")==null,"unsafe private image path accepted");
                screen.widgets.destroy();screen.widgets=new DesktopWidgets(screen,screen.canvas,"widget_editor_fixture",0x535458);
                require(text(screen.canvas,"Stella\nCustom text")!=null&&type(screen.canvas.getChildAt(3),ImageView.class)!=null,"custom items not restored");
                android.graphics.drawable.GradientDrawable bg=(android.graphics.drawable.GradientDrawable)screen.canvas.getChildAt(2).getBackground();require(android.graphics.Color.alpha(bg.getColor().getDefaultColor())==191,"background not restored");
                require(new android.appwidget.AppWidgetHost(screen,0x535458).getAppWidgetIds().length==0,"custom items allocated provider IDs");
            });
        }finally{source.delete();if(imageName[0]!=null)WidgetImages.remove(screen,imageName[0]);}
    }
    void run(int display)throws Exception {
        try{
            screen=(WidgetEditorFixtureActivity)test.startActivitySync(new Intent(test.getTargetContext(),WidgetEditorFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
            main(()->{
                first=(WidgetEditFrame)screen.canvas.getChildAt(0);
                first.removeAllViews();Button provider=new Button(screen);provider.setText("Widget preview");provider.setOnClickListener(v->taps++);
                provider.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels++;if(e.getActionMasked()==MotionEvent.ACTION_MOVE)moves++;return false;});first.addView(provider,new FrameLayout.LayoutParams(-1,-1));
            });
            await(()->first.getWidth()>0&&first.getChildAt(0).getWidth()==first.getWidth()&&first.getChildAt(0).getHeight()==first.getHeight());
            main(()->{event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_UP,30,30);});await(()->taps==1);
            main(()->{event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_MOVE,100,30);});
            Thread.sleep(ViewConfiguration.getLongPressTimeout()+150);
            main(()->{require(!screen.widgets.isEditing()&&moves>0,"provider scroll triggered editing");event(MotionEvent.ACTION_CANCEL,100,30);event(MotionEvent.ACTION_DOWN,30,30);});
            Thread.sleep(ViewConfiguration.getLongPressTimeout()+150);
            main(()->{
                require(!screen.widgets.isEditing(),"hold entered edit mode");require(cancels==1,"hold cancelled provider input");event(MotionEvent.ACTION_CANCEL,30,30);require(taps==1,"hold accidentally clicked provider");
                screen.widgets.setEditing(true);event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_CANCEL,30,30);
                FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)first.getLayoutParams();require(p.topMargin==0&&p.width==Ui.dp(screen,180)&&p.height==Ui.dp(screen,120),"edit chrome shifted/resized provider");
                require(first.getChildCount()==1,"old bars or resize handles remain");
                clickDescription(R.string.widget_edit_hide);require(desc(screen.canvas,screen.getString(R.string.widget_edit_open))!=null,"collapse lost reopen control");
                event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_MOVE,90,90);event(MotionEvent.ACTION_UP,90,90);
                require(saved().optInt("x")>16&&saved().optInt("y")>0,"whole-body drag did not save");
            });
            main(()->{
                int x=saved().optInt("x"),y=saved().optInt("y"),w=saved().optInt("w"),h=saved().optInt("h");float right=first.getWidth()-4,bottom=first.getHeight()-4;
                event(MotionEvent.ACTION_DOWN,right,bottom);event(MotionEvent.ACTION_MOVE,right+40,bottom+30);event(MotionEvent.ACTION_UP,right+40,bottom+30);
                require(saved().optInt("w")>w&&saved().optInt("h")>h,"corner drag did not resize both axes");require(saved().optInt("x")==x&&saved().optInt("y")==y,"corner resize moved widget origin");
            });
            main(()->{
                int w=saved().optInt("w"),h=saved().optInt("h");float right=first.getWidth()-4,bottom=first.getHeight()-4;
                event(MotionEvent.ACTION_DOWN,right,bottom);event(MotionEvent.ACTION_MOVE,right+60,bottom+60);event(MotionEvent.ACTION_CANCEL,right+60,bottom+60);
                require(first.getLayoutParams().width==Ui.dp(screen,w)&&first.getLayoutParams().height==Ui.dp(screen,h),"cancel left partial corner resize");
            });
            int[] before={0,0};main(()->{before[0]=saved().optInt("x");before[1]=saved().optInt("y");event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_MOVE,150,150);event(MotionEvent.ACTION_CANCEL,150,150);require(saved().optInt("x")==before[0],"cancel persisted drag");clickDescription(R.string.widget_edit_open);clickDescription(R.string.widget_edit_side);});
            main(()->{
                View scroll=desc(screen.canvas,screen.getString(R.string.widget_edit_hide)).getParent() instanceof View?((View)desc(screen.canvas,screen.getString(R.string.widget_edit_hide)).getParent()):null;
                require(scroll!=null,"side controls missing");
                String plus=screen.getString(R.string.widget_edit_width)+" +";View button=desc(screen.canvas,plus);int width=saved().optInt("w");button.performClick();require(saved().optInt("w")>width,"panel width adjustment failed");
                int x=saved().optInt("x"),y=saved().optInt("y");screen.widgets.setEditing(false);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)first.getLayoutParams();require(p.leftMargin==Ui.dp(screen,x)&&p.topMargin==Ui.dp(screen,y),"finish moved content");
                require(desc(screen.canvas,screen.getString(R.string.widget_edit_hide))==null&&first.getForeground()==null,"editor chrome remains after Done");
                event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_UP,30,30);
                screen.canvas.performLongClick();require(!screen.widgets.isEditing(),"blank long press entered editing");screen.widgets.setEditing(true);event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_MOVE,130,130);screen.onBackPressed();event(MotionEvent.ACTION_MOVE,150,150);event(MotionEvent.ACTION_UP,150,150);require(saved().optInt("x")==x&&saved().optInt("y")==y,"Back during drag changed saved placement");require(!screen.widgets.isEditing()&&!screen.isFinishing(),"Back closed screen before editor");
            });
            main(()->require(taps==2,"provider input not restored"));
            main(()->{first.removeAllViews();first.addView(new TextView(screen));event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_MOVE,100,30);});
            Thread.sleep(ViewConfiguration.getLongPressTimeout()+150);
            main(()->{require(!screen.widgets.isEditing(),"empty provider area has a second long-press timer");event(MotionEvent.ACTION_CANCEL,100,30);});
            // Recreate the host with the same isolated preferences, preserving saved placement.
            main(()->{int x=saved().optInt("x"),width=saved().optInt("w");screen.widgets.destroy();screen.widgets=new DesktopWidgets(screen,screen.canvas,"widget_editor_fixture",0x535458);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)screen.canvas.getChildAt(0).getLayoutParams();require(p.leftMargin==Ui.dp(screen,x)&&p.width==Ui.dp(screen,width),"saved geometry not restored");});
            main(()->{
                try{
                    java.lang.reflect.Field field=DesktopWidgets.class.getDeclaredField("entries");field.setAccessible(true);Object entry=((java.util.List<?>)field.get(screen.widgets)).get(0);
                    java.lang.reflect.Field infoField=entry.getClass().getDeclaredField("info");infoField.setAccessible(true);
                    android.appwidget.AppWidgetProviderInfo info=android.appwidget.AppWidgetManager.getInstance(screen).getInstalledProviders().get(0).clone();info.resizeMode=android.appwidget.AppWidgetProviderInfo.RESIZE_NONE;info.minWidth=Ui.dp(screen,160);info.minHeight=Ui.dp(screen,120);infoField.set(entry,info);
                    screen.widgets.setEditing(true);first=(WidgetEditFrame)screen.canvas.getChildAt(0);event(MotionEvent.ACTION_DOWN,30,30);event(MotionEvent.ACTION_CANCEL,30,30);
                    View plus=desc(screen.canvas,screen.getString(R.string.widget_edit_width)+" +");require(!plus.isEnabled(),"normal mode ignored fixed provider size");
                    text(screen.canvas,screen.getString(R.string.widget_edit_force)).performClick();require(desc(screen.canvas,screen.getString(R.string.widget_edit_width)+" +").isEnabled(),"force mode remains fixed");
                    text(screen.canvas,screen.getString(R.string.widget_edit_scale)).performClick();require(text(screen.canvas,screen.getString(R.string.ui_render_size))!=null,"scale lost render-size setting");
                    ViewGroup.LayoutParams p=screen.canvas.getLayoutParams();p.width=Ui.dp(screen,320);p.height=Ui.dp(screen,480);screen.canvas.setLayoutParams(p);
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
            await(()->screen.canvas.getWidth()==Ui.dp(screen,320)&&screen.canvas.getChildAt(screen.canvas.getChildCount()-1).getHeight()==Ui.dp(screen,480));
            main(()->{
                View editor=screen.canvas.getChildAt(screen.canvas.getChildCount()-1);require(editor instanceof ScrollView&&editor.getWidth()<=screen.canvas.getWidth(),"sidebar escaped narrow canvas");
                clickDescription(R.string.widget_edit_side);editor=screen.canvas.getChildAt(screen.canvas.getChildCount()-1);require((((FrameLayout.LayoutParams)editor.getLayoutParams()).gravity&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.LEFT,"panel side did not change");
            });
            await(()->screen.canvas.getChildAt(screen.canvas.getChildCount()-1).getWidth()>0);
            main(()->{
                View editor=screen.canvas.getChildAt(screen.canvas.getChildCount()-1);
                // Render only the synthetic fixture view, never the physical display or personal content.
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(screen.canvas.getWidth(),screen.canvas.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);screen.canvas.draw(new android.graphics.Canvas(image));
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(screen.getCacheDir(),"widget-editor-preview.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(java.io.IOException e){throw new AssertionError(e);}finally{image.recycle();}
                ScrollView scroll=(ScrollView)editor;
                for(int swipe=0;swipe<4;swipe++){
                    long start=SystemClock.uptimeMillis();float x=scroll.getWidth()/2f,from=scroll.getHeight()*.85f,to=scroll.getHeight()*.15f;
                    MotionEvent downEvent=MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,from,0);scroll.dispatchTouchEvent(downEvent);downEvent.recycle();
                    for(int n=1;n<=10;n++){MotionEvent moveEvent=MotionEvent.obtain(start,start+n*16,MotionEvent.ACTION_MOVE,x,from+(to-from)*n/10,0);scroll.dispatchTouchEvent(moveEvent);moveEvent.recycle();}
                    MotionEvent endEvent=MotionEvent.obtain(start,start+176,MotionEvent.ACTION_CANCEL,x,to,0);scroll.dispatchTouchEvent(endEvent);endEvent.recycle();
                }
            });
            Thread.sleep(250);
            main(()->{View add=text(screen.canvas,screen.getString(R.string.ui_add_widget));android.graphics.Rect rect=new android.graphics.Rect();require(add!=null&&add.getGlobalVisibleRect(rect)&&rect.height()>0,"narrow panel cannot reach bottom controls; scroll="+((ScrollView)screen.canvas.getChildAt(screen.canvas.getChildCount()-1)).getScrollY()+" add="+(add==null?"missing":add.getTop()));});

            customItems();
            // A display can change density without changing this canvas's pixel
            // bounds. Simulate only fixture Resources; never change device settings.
            main(()->{
                android.content.res.Resources resources=screen.getResources();
                android.util.DisplayMetrics metrics=resources.getDisplayMetrics();
                float density=metrics.density;int dpi=metrics.densityDpi;
                int canvasWidth=screen.canvas.getWidth();
                try{
                    screen.widgets.setEditing(false);metrics.density=density*.75f;metrics.densityDpi=Math.round(dpi*.75f);
                    screen.widgets.configurationChanged();
                    require(screen.canvas.getWidth()==canvasWidth,"Density fixture changed canvas pixels");
                    require(screen.canvas.getChildAt(0).getLayoutParams().width==Ui.dp(screen,Math.min(saved().optInt("w"),Math.round(canvasWidth/metrics.density))),"Density-only update kept old widget pixels");
                }finally{metrics.density=density;metrics.densityDpi=dpi;screen.widgets.configurationChanged();}
            });
        }finally{if(screen!=null)main(()->screen.finishAndRemoveTask());}
    }
}
