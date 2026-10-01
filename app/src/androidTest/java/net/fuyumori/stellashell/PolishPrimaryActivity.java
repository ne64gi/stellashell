package net.fuyumori.stellashell;
public class PolishPrimaryActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);android.widget.TextView text=new android.widget.TextView(this);text.setText("PRIMARY · Window test");text.setTextSize(24);text.setGravity(android.view.Gravity.CENTER);text.setBackgroundColor(0xffd6e9ef);setContentView(text);}
}
