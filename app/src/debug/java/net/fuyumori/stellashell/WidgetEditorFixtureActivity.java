package net.fuyumori.stellashell;

/** Debug-only isolated editor surface. Never reads a real widget host or user layout. */
public final class WidgetEditorFixtureActivity extends android.app.Activity {
    DesktopWidgets widgets;android.widget.FrameLayout canvas;
    @Override public void onCreate(android.os.Bundle state){
        super.onCreate(state);
        getSharedPreferences("widget_editor_fixture",0).edit().putString("items","[{\"id\":-7101,\"x\":16,\"y\":0,\"w\":180,\"h\":120},{\"id\":-7102,\"x\":36,\"y\":160,\"w\":200,\"h\":140}]").commit();
        canvas=new android.widget.FrameLayout(this);canvas.setBackgroundColor(0xff172535);
        setContentView(canvas);widgets=new DesktopWidgets(this,canvas,"widget_editor_fixture",0x535458);
    }
    @Override public void onBackPressed(){if(!widgets.finishEditing())super.onBackPressed();}
    @Override protected void onDestroy(){widgets.destroy();getSharedPreferences("widget_editor_fixture",0).edit().clear().commit();super.onDestroy();}
}
