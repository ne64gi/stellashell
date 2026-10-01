package net.fuyumori.stellashell;
public class PolishSecondaryActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);android.widget.TextView text=new android.widget.TextView(this);text.setText("SECONDARY · Window test");text.setTextSize(20);text.setGravity(android.view.Gravity.CENTER);text.setBackgroundColor(0xfff1e4d0);setContentView(text);}
}
