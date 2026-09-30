package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;

/** Draft-only appearance editor: Save is the only preference mutation. */
public final class AppearanceActivity extends Activity {
    private Appearance.Config draft;
    private EditText background,accent;
    private Switch glass;
    private SeekBar opacity;
    private TextView opacityLabel,fontLabel,sample,previewName;
    private LinearLayout preview;
    private boolean importing;
    static void open(Context c,int display){try{c.startActivity(new Intent(c,AppearanceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}catch(RuntimeException e){Ui.message(c,e.getMessage());}}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);draft=Appearance.read(this);
        if(state!=null){draft.panel=state.getInt("panel",draft.panel);draft.accent=state.getInt("accent",draft.accent);draft.opacity=state.getInt("opacity",draft.opacity);draft.glass=state.getBoolean("glass",draft.glass);draft.font=state.getString("font",draft.font);draft.file=state.getString("file",draft.file);draft.ttcIndex=state.getInt("ttcIndex",0);}
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.BG);
        LinearLayout root=Ui.column(this);root.setPadding(dp(24),dp(24),dp(24),dp(96));scroll.addView(root);
        Ui.heading(root,getString(R.string.appearance_title));Ui.note(root,getString(R.string.appearance_note));
        LinearLayout presets=new LinearLayout(this);
        String[] names={"Midnight","Frost","AMOLED"};int[] colors={0xff1c2636,0xffdce7f1,0xff000000};int[] accents={0xff6ee7c8,0xff005e87,0xffc5a3ff};
        for(int i=0;i<names.length;i++){final int n=i;presets.addView(Ui.button(this,names[i],()->{draft.panel=colors[n];draft.accent=accents[n];draft.glass=n==1;draft.opacity=n==1?85:100;fields();}),new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(presets);
        background=colorInput(root,R.string.appearance_background);accent=colorInput(root,R.string.appearance_accent);
        glass=new Switch(this);glass.setText(R.string.appearance_glass);glass.setTextColor(Ui.TEXT);root.addView(glass);
        opacityLabel=Ui.text(this,"",14,Ui.TEXT);root.addView(opacityLabel);
        opacity=new SeekBar(this);opacity.setMin(35);opacity.setMax(100);opacity.setContentDescription(getString(R.string.appearance_opacity));root.addView(opacity,new LinearLayout.LayoutParams(-1,dp(48)));
        glass.setOnCheckedChangeListener((v,on)->{draft.glass=on;opacity.setEnabled(on);preview();});
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int v,boolean user){draft.opacity=v;preview();}});
        fontLabel=Ui.text(this,"",15,Ui.TEXT);root.addView(fontLabel);
        root.addView(Ui.button(this,getString(R.string.appearance_font),()->{
            String[] labels={getString(R.string.appearance_font_default),"Sans Serif","Serif","Monospace","Condensed"};String[] values={"sans-serif","sans-serif-medium","serif","monospace","sans-serif-condensed"};
            new AlertDialog.Builder(this).setTitle(R.string.appearance_font).setItems(labels,(d,n)->{draft.font=values[n];draft.file="";draft.ttcIndex=0;preview();}).show();
        }));
        root.addView(Ui.button(this,getString(R.string.appearance_import),()->{if(importing)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,71);}catch(RuntimeException e){Ui.message(this,e.getMessage());}}));
        root.addView(Ui.button(this,getString(R.string.appearance_collection),()->{if("custom".equals(draft.font))selectFace(new File(getFilesDir(),draft.file));}));
        // Checker-like two-tone backing makes transparency visible without changing live surfaces.
        FrameLayout backing=new FrameLayout(this);backing.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xff627c94,0xff142b35}));
        preview=Ui.column(this);preview.setPadding(dp(20),dp(16),dp(20),dp(16));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-2);pp.setMargins(dp(12),dp(12),dp(12),dp(12));backing.addView(preview,pp);
        previewName=Ui.text(this,"StellaShell",20,Ui.TEXT);sample=Ui.text(this,"Start  ·  アプリ  ·  12:34",17,Ui.TEXT);preview.addView(previewName);preview.addView(sample);root.addView(backing,new LinearLayout.LayoutParams(-1,dp(130)));
        root.addView(Ui.button(this,getString(R.string.appearance_preview),()->{if(readColors())preview();}));
        root.addView(Ui.button(this,getString(R.string.appearance_reset),()->{draft=new Appearance.Config();fields();}));
        root.addView(Ui.button(this,getString(R.string.appearance_save),()->{if(importing){Ui.message(this,getString(R.string.appearance_importing));return;}if(!readColors())return;Launches.prefs(this).edit().putString(Appearance.KEY,draft.json()).apply();finish();}));
        root.addView(Ui.button(this,getString(R.string.ui_cancel),this::finish));setContentView(scroll);fields();
    }
    private EditText colorInput(LinearLayout root,int label){root.addView(Ui.text(this,getString(label)+" (#RRGGBB)",14,Ui.TEXT));EditText text=new EditText(this);text.setSingleLine();text.setTextColor(Ui.TEXT);text.setTypeface(Appearance.face);text.setContentDescription(getString(label));root.addView(text);return text;}
    private void fields(){background.setText(hex(draft.panel));accent.setText(hex(draft.accent));glass.setChecked(draft.glass);opacity.setProgress(draft.opacity);opacity.setEnabled(draft.glass);preview();}
    private static String hex(int value){return String.format(Locale.ROOT,"#%06X",value&0xffffff);}
    private boolean readColors(){try{String b=background.getText().toString().trim(),a=accent.getText().toString().trim();if(!b.matches("#[0-9a-fA-F]{6}")||!a.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException();draft.panel=Color.parseColor(b);draft.accent=Color.parseColor(a);return true;}catch(RuntimeException e){Ui.message(this,getString(R.string.appearance_invalid_color));return false;}}
    private void preview(){if(preview==null)return;opacityLabel.setText(getString(R.string.appearance_opacity)+" "+draft.opacity+"%");fontLabel.setText(getString(R.string.appearance_font)+": "+("custom".equals(draft.font)?getString(R.string.appearance_custom)+" #"+(draft.ttcIndex+1):draft.font));preview.setBackground(Appearance.surface(this,draft,16));sample.setTypeface(Appearance.typeface(this,draft));previewName.setTypeface(Appearance.typeface(this,draft));sample.setTextColor(Appearance.foreground(draft.panel));previewName.setTextColor(draft.accent);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=71||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();importing=true;Ui.message(this,getString(R.string.appearance_importing));
        new Thread(()->{File file=null;String error=null;try{file=File.createTempFile("font-",".bin",getFilesDir());try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){if(in==null)throw new IOException();byte[] bytes=new byte[8192];int total=0,n;while((n=in.read(bytes))!=-1){total+=n;if(total>128*1024*1024)throw new IOException("Font exceeds 128 MiB");out.write(bytes,0,n);}}FontCollection.count(file);if(new Typeface.Builder(file).setTtcIndex(0).build()==null)throw new IOException("Invalid font");}catch(Exception e){error=e.getMessage();if(file!=null)file.delete();file=null;}File selected=file;String failure=error;runOnUiThread(()->{importing=false;if(isDestroyed()||isFinishing()){if(selected!=null)selected.delete();return;}if(selected==null){Ui.message(this,getString(R.string.appearance_import_failed)+(failure==null?"":" "+failure));return;}selectFace(selected);});},"font-import").start();
    }
    private void selectFace(File file){
        try{
            int count=FontCollection.count(file);
            if(count==1){applyFace(file,0);return;}
            String[] labels=new String[count];for(int i=0;i<count;i++)labels[i]=getString(R.string.appearance_collection_face,i+1);
            new AlertDialog.Builder(this).setTitle(R.string.appearance_collection).setItems(labels,(d,n)->applyFace(file,n)).setNegativeButton(R.string.ui_cancel,(d,w)->{}).show();
        }catch(IOException e){Ui.message(this,getString(R.string.appearance_import_failed));}
    }
    private void applyFace(File file,int index){
        try{if(new Typeface.Builder(file).setTtcIndex(index).build()==null)throw new IllegalArgumentException();draft.font="custom";draft.file=file.getName();draft.ttcIndex=index;preview();}
        catch(RuntimeException e){Ui.message(this,getString(R.string.appearance_import_failed));}
    }
    @Override protected void onSaveInstanceState(Bundle state){if(background!=null){try{draft.panel=Color.parseColor(background.getText().toString());draft.accent=Color.parseColor(accent.getText().toString());}catch(RuntimeException ignored){}}state.putInt("panel",draft.panel);state.putInt("accent",draft.accent);state.putInt("opacity",draft.opacity);state.putBoolean("glass",draft.glass);state.putString("font",draft.font);state.putString("file",draft.file);state.putInt("ttcIndex",draft.ttcIndex);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){if(isFinishing()){String used=Appearance.read(this).file;File[] files=getFilesDir().listFiles((dir,name)->name.startsWith("font-"));if(files!=null)for(File f:files)if(!f.getName().equals(used))f.delete();}super.onDestroy();}
    private int dp(int n){return Ui.dp(this,n);}
}
