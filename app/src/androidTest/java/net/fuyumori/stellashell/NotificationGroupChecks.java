package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** In-memory notifications only. No listener permission, OS posting, or personal notification access. */
final class NotificationGroupChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(Instrumentation test)throws Exception{
        Context base=test.getTargetContext();model(base);
        for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.JAPANESE}){
            Configuration config=new Configuration(base.getResources().getConfiguration());config.setLocale(locale);
            Context context=new ContextThemeWrapper(base.createConfigurationContext(config),R.style.AppTheme);
            rendering(context,Locale.JAPANESE.equals(locale));
            stableReading(context);
        }
    }
    private static StatusBarNotification item(Context context,String pkg,int user,int id,long time,String group,boolean summary){
        Notification.Builder builder=new Notification.Builder(context,"synthetic-only").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("合成通知 "+id).setContentText("グループ表示の動作確認です");
        if(group!=null)builder.setGroup(group);if(summary)builder.setGroupSummary(true);
        int uid=10000+user*100000;
        return new StatusBarNotification(pkg,pkg,id,null,uid,0,0,builder.build(),UserHandle.getUserHandleForUid(uid),time);
    }
    private static void model(Context context){
        StatusBarNotification chatOld=item(context,"fixture.chat",0,1,10,"conversation-a",false);
        StatusBarNotification mail=item(context,"fixture.mail",0,2,30,null,false);
        StatusBarNotification chatNew=item(context,"fixture.chat",0,3,40,"conversation-b",false);
        StatusBarNotification work=item(context,"fixture.chat",10,4,20,"conversation-a",false);
        List<StatusBarNotification> input=Arrays.asList(chatOld,mail,work,chatNew);
        List<NotificationGroups.Group> groups=NotificationGroups.collect(input);
        check(groups.size()==3,"package/user partition");
        check(groups.get(0).items.equals(Arrays.asList(chatNew,chatOld)),"interleaved app/conversation notifications not combined newest first");
        check(groups.get(1).items.equals(Collections.singletonList(mail))&&groups.get(2).items.equals(Collections.singletonList(work)),"section order or profile separation");
        check(input.get(0)==chatOld,"snapshot was mutated");
        StatusBarNotification summary=item(context,"fixture.chat",0,5,100,"conversation-a",true);
        groups=NotificationGroups.collect(Arrays.asList(summary,chatOld,mail));
        check(groups.size()==2&&groups.get(0).items.get(0)==mail&&groups.get(1).items.equals(Collections.singletonList(chatOld)),"duplicated summary or hidden summary reordered groups");
        check(NotificationGroups.collect(Collections.singletonList(summary)).get(0).items.get(0)==summary,"standalone summary lost");
        check(NotificationGroups.collect(Arrays.asList(summary,chatNew)).get(0).items.size()==2,"summary with no matching children lost");
        check(NotificationGroups.collect(Arrays.asList(summary,work)).size()==2,"other profile hid summary");
        StatusBarNotification otherApp=item(context,"fixture.mail",0,6,20,"conversation-a",false);
        check(NotificationGroups.collect(Arrays.asList(summary,otherApp)).get(0).items.get(0)==summary,"other app hid summary");
        List<NotificationGroups.Group> tied=NotificationGroups.collect(Arrays.asList(mail,item(context,"fixture.chat",0,7,30,null,false)));
        check(tied.get(0).packageName.equals("fixture.chat"),"equal timestamp ordering is unstable");
        check(NotificationGroups.collect(Collections.emptyList()).isEmpty(),"empty grouping");
    }
    private static void rendering(Context context,boolean preview)throws Exception{
        List<StatusBarNotification> opened=new ArrayList<>(),dismissed=new ArrayList<>();
        LinearLayout rows=Ui.column(context);NotificationList list=new NotificationList(rows,opened::add,dismissed::add);
        StatusBarNotification first=item(context,"LINE",0,1,50,"a",false),second=item(context,"LINE",0,2,20,"b",false),mail=item(context,"Mail",0,3,30,null,false);
        PendingIntent pending=PendingIntent.getActivity(context,97531,new Intent(context,HomeActivity.class).setAction("notification-group-fixture"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        try{
            first.getNotification().contentIntent=pending;
            second.getNotification().flags|=Notification.FLAG_NO_CLEAR;
            list.render(Arrays.asList(first,mail,second));
            check(rows.getChildCount()==2,"not one view section per app");
            ViewGroup section=(ViewGroup)rows.getChildAt(0);View header=section.getChildAt(0);ViewGroup cards=(ViewGroup)section.getChildAt(1);
            check(cards.getChildCount()==1&&cards.getVisibility()==View.VISIBLE,"default must show latest one, not all or none");
            View latest=cards.getChildAt(0);ViewGroup mailSection=(ViewGroup)rows.getChildAt(1);
            Button more=(Button)section.getChildAt(2);
            check(more.getVisibility()==View.VISIBLE&&more.getText().toString().equals(context.getResources().getQuantityString(R.plurals.notification_list_more,1,1)),"N more affordance missing/localization stale");
            check(header.getContentDescription().toString().contains(context.getResources().getQuantityString(R.plurals.hub_notification_count,2,2)),"group count missing");
            check(latest.performClick()&&opened.equals(Collections.singletonList(first)),"open callback lost notification identity");
            Button close=findButton(latest);check(close!=null&&close.performClick()&&dismissed.equals(Collections.singletonList(first)),"dismiss callback lost identity");
            check(header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND,null)&&cards.getChildCount()==2,"accessible expand failed");
            check(cards.getChildAt(0)==latest,"expansion recreated latest card");
            check(findButton(cards.getChildAt(1))==null&&!cards.getChildAt(1).isClickable(),"nonclearable or nonopenable notification gained action");
            ArrayList<String> saved=list.expandedGroups();check(saved.size()==1,"explicit expansion not saved");
            StatusBarNotification third=item(context,"LINE",0,4,60,null,false);
            list.render(Arrays.asList(first,second,third,mail));
            check(rows.getChildAt(0)==section&&rows.getChildAt(1)==mailSection&&section.getChildAt(0)==header,"live update recreated unchanged sections/header");
            check(cards.getVisibility()==View.VISIBLE&&cards.getChildCount()==3&&cards.getChildAt(1)==latest,"update reset expansion or recreated surviving card");
            check(header.getContentDescription().toString().contains(context.getResources().getQuantityString(R.plurals.hub_notification_count,3,3)),"updated count stale");
            check(header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE,null)&&cards.getChildCount()==1&&cards.getVisibility()==View.VISIBLE,"collapse must retain latest one");
            check(cards.getChildAt(0).getContentDescription().toString().contains("合成通知 4"),"collapse did not choose newest");
            check(list.expandedGroups().isEmpty(),"collapse did not clear explicit expansion");
            check(more.performClick()&&cards.getChildCount()==3,"N more button cannot expand");
            latest=cards.getChildAt(1);close=findButton(latest);
            LinearLayout recreatedRows=Ui.column(context);
            NotificationList recreated=new NotificationList(recreatedRows,opened::add,dismissed::add);
            recreated.restoreExpandedGroups(saved);recreated.render(Arrays.asList(first,second,third,mail));
            check(((ViewGroup)((ViewGroup)recreatedRows.getChildAt(0)).getChildAt(1)).getChildCount()==3,"Activity saved expansion not restored");
            recreated.clear();check(recreatedRows.getChildCount()==0&&recreated.expandedGroups().equals(saved),"privacy clear lost identifier state or retained views");
            recreated.render(Arrays.asList(first,second,mail));
            check(((ViewGroup)((ViewGroup)recreatedRows.getChildAt(0)).getChildAt(1)).getChildCount()==2,"lock/access clear did not restore explicit expansion");
            recreated.render(Collections.singletonList(mail));recreated.render(Arrays.asList(first,second,mail));
            check(((ViewGroup)((ViewGroup)recreatedRows.getChildAt(0)).getChildAt(1)).getChildCount()==1&&recreated.expandedGroups().isEmpty(),"removed group retained stale expansion");
            // Same key, new content/object: reuse the View but replace both individual callbacks.
            StatusBarNotification updated=item(context,"LINE",0,1,80,null,false);updated.getNotification().contentIntent=pending;
            updated.getNotification().extras.putCharSequence(Notification.EXTRA_TITLE,"Updated synthetic title");
            list.render(Arrays.asList(updated,second,mail));
            check(rows.getChildAt(0)==section&&cards.getChildAt(0)==latest,"same-key update replaced section/card");
            check(latest.getContentDescription().toString().contains("Updated synthetic title"),"same-key content stayed stale");
            latest.performClick();findButton(latest).performClick();
            check(opened.get(opened.size()-1)==updated&&dismissed.get(dismissed.size()-1)==updated,"same-key update callback retained stale object");
            View deleted=cards.getChildAt(1);list.render(Arrays.asList(updated,mail));
            check(cards.getChildCount()==1&&cards.getChildAt(0)==latest&&deleted.getContentDescription()==null,"removed card not erased or survivor replaced");
            list.clear();check(rows.getChildCount()==0&&latest.getContentDescription()==null&&allTextEmpty(latest),"clear retained rendered personal content");
            int actions=opened.size()+dismissed.size();latest.performClick();close.performClick();
            check(opened.size()+dismissed.size()==actions,"detached cleared card still runs notification callbacks");
            first.getNotification().extras=null;list.render(Collections.singletonList(first));
            check(rows.getChildCount()==1,"missing extras crashed card");
            list.render(Collections.emptyList());check(rows.getChildCount()==1&&findButton(rows)==null&&list.expandedGroups().isEmpty(),"empty view/pruning");
            android.app.Person self=new android.app.Person.Builder().setName("Synthetic self").setKey("self").build();
            android.app.Person alice=new android.app.Person.Builder().setName("Synthetic Alice").setKey("alice").build();
            long messageTime=System.currentTimeMillis()-60000;
            Notification message=new Notification.Builder(context,"synthetic-only").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(new Notification.MessagingStyle(self).setConversationTitle("Synthetic room").addMessage("Synthetic message body",messageTime,alice)).build();
            StatusBarNotification conversation=new StatusBarNotification("fixture.messages","fixture.messages",90,null,10000,0,0,message,UserHandle.getUserHandleForUid(10000),messageTime);
            list.render(Collections.singletonList(conversation));
            View metadataCard=((ViewGroup)((ViewGroup)rows.getChildAt(0)).getChildAt(1)).getChildAt(0);
            String shownTime=NotificationContent.from(conversation).formatTime(context);
            check(hasText(metadataCard,"Synthetic Alice")&&hasText(metadataCard,shownTime),"sender/time metadata is not visibly bound");
            check(metadataCard.getContentDescription().toString().contains(context.getString(R.string.notification_list_sender,"Synthetic Alice"))&&metadataCard.getContentDescription().toString().contains(shownTime),"sender/time accessibility lost");
            View metadataSection=rows.getChildAt(0);long later=System.currentTimeMillis()+120000;
            String refreshedTime=NotificationContent.from(conversation).formatTime(context,later);
            check(!refreshedTime.equals(shownTime),"synthetic time refresh setup");
            list.refreshTimes(later);
            check(rows.getChildAt(0)==metadataSection&&((ViewGroup)((ViewGroup)metadataSection).getChildAt(1)).getChildAt(0)==metadataCard,"time tick replaced section/card");
            check(hasText(metadataCard,refreshedTime)&&metadataCard.getContentDescription().toString().contains(refreshedTime),"time-only refresh missed text/accessibility");
            // Installed-package icon path as well as missing-package fallback.
            list.render(Collections.singletonList(item(context,context.getPackageName(),0,5,1,null,false)));
            check(rows.getChildCount()==1,"installed application rendering");
            if(preview){
                list.render(Arrays.asList(item(context,"LINE",0,1,50,null,false),item(context,"Mail",0,3,30,null,false),second));
                int width=Ui.dp(context,320);rows.setPadding(Ui.dp(context,8),0,Ui.dp(context,8),Ui.dp(context,10));
                rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));rows.layout(0,0,width,rows.getMeasuredHeight());
                check(rows.getMeasuredHeight()>0,"narrow layout absent");
                Bitmap bitmap=Bitmap.createBitmap(width,rows.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Ui.BG);rows.draw(canvas);
                try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"notification-groups-synthetic.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
            }
        }finally{pending.cancel();}
    }
    /** Real ScrollView measuring/layout, but only in-memory synthetic cards, no OS notification posting. */
    private static void stableReading(Context context){
        ScrollView scroll=new ScrollView(context);LinearLayout rows=Ui.column(context);scroll.addView(rows);
        NotificationList list=new NotificationList(rows,item->{},item->{});
        List<StatusBarNotification> snapshot=new ArrayList<>();
        for(int i=0;i<8;i++)snapshot.add(item(context,"fixture.app"+i,0,i,100-i,null,false));
        list.render(snapshot);layout(scroll,context);
        ViewGroup anchorSection=(ViewGroup)rows.getChildAt(3);View anchor=anchorSection.getChildAt(0);
        anchor.setFocusableInTouchMode(true);check(anchor.requestFocus(),"synthetic focus setup");
        scroll.scrollTo(0,topInScroll(anchor,scroll)+Ui.dp(context,6));
        int offset=topInScroll(anchor,scroll)-scroll.getScrollY(),before=scroll.getScrollY();
        ViewGroup unaffected=(ViewGroup)rows.getChildAt(6);View unaffectedCard=((ViewGroup)unaffected.getChildAt(1)).getChildAt(0);
        list.render(snapshot);check(rows.isLayoutRequested(),"identical render did not guarantee anchor-clearing traversal");layout(scroll,context);
        check(rows.getChildAt(3)==anchorSection&&rows.getChildAt(6)==unaffected&&((ViewGroup)unaffected.getChildAt(1)).getChildAt(0)==unaffectedCard,"identical update replaced stable views");
        check(anchor.hasFocus()&&scroll.getScrollY()==before,"identical update lost focus or reset scroll");
        // A user scroll after an identical render must become the next update's
        // anchor, not the stale pre-scroll position captured by that render.
        scroll.scrollTo(0,topInScroll(anchor,scroll)+Ui.dp(context,18));
        offset=topInScroll(anchor,scroll)-scroll.getScrollY();
        StatusBarNotification inserted=item(context,"fixture.new",0,99,200,null,false);
        snapshot.add(inserted);list.render(snapshot);layout(scroll,context);
        check(rows.getChildAt(4)==anchorSection&&anchor.hasFocus(),"insert replaced section or lost focus");
        check(topInScroll(anchor,scroll)-scroll.getScrollY()==offset,"insert above reading edge jumped scroll");
        // Same-key update changes card height in an earlier section without changing its View.
        StatusBarNotification taller=item(context,"fixture.app1",0,1,99,null,false);
        taller.getNotification().extras.putCharSequence(Notification.EXTRA_BIG_TEXT,"Synthetic line one\nSynthetic line two\nSynthetic line three\nSynthetic line four\nSynthetic line five");
        snapshot.set(1,taller);list.render(snapshot);layout(scroll,context);
        check(topInScroll(anchor,scroll)-scroll.getScrollY()==offset&&anchor.hasFocus(),"height change above anchor jumped reading/focus");
        // Promote the same package/key to the front: section/card identity and focused header survive detach/move.
        StatusBarNotification promoted=item(context,"fixture.app3",0,3,300,null,false);
        snapshot.set(3,promoted);list.render(snapshot);layout(scroll,context);
        check(rows.getChildAt(0)==anchorSection&&anchor.hasFocus(),"group reorder lost view/focus");
        check(topInScroll(anchor,scroll)-scroll.getScrollY()==offset,"group reorder jumped reading anchor");
        // Snapshot captures the old header focus; a user selects another header before
        // the deferred layout callback. The newer selection must win.
        list.render(snapshot);
        View newerFocus=((ViewGroup)rows.getChildAt(2)).getChildAt(0);
        newerFocus.setFocusableInTouchMode(true);check(newerFocus.requestFocus(),"newer focus setup");
        layout(scroll,context);
        check(newerFocus.hasFocus()&&!anchor.hasFocus(),"delayed renderer stole newer keyboard focus");
        // Reset to a middle section so deletion tests do not hit the unavoidable top scroll clamp.
        ViewGroup middle=(ViewGroup)rows.getChildAt(4);View reading=middle.getChildAt(0);
        scroll.scrollTo(0,topInScroll(reading,scroll)+Ui.dp(context,4));
        int readingOffset=topInScroll(reading,scroll)-scroll.getScrollY();
        snapshot.remove(inserted);list.render(snapshot);layout(scroll,context);
        check(topInScroll(reading,scroll)-scroll.getScrollY()==readingOffset,"remove above reading edge jumped scroll");
        // Removing the actual anchored section preserves the next surviving item/card's screen position.
        ViewGroup readingCards=(ViewGroup)middle.getChildAt(1);
        ViewGroup next=(ViewGroup)rows.getChildAt(rows.indexOfChild(middle)+1);View fallback=next.getChildAt(0);
        int fallbackOffset=topInScroll(fallback,scroll)-scroll.getScrollY();
        String removedPackage=middle.getChildAt(0).getContentDescription().toString().split(",")[0];
        snapshot.removeIf(notification->notification.getPackageName().equals(removedPackage));
        list.render(snapshot);layout(scroll,context);
        check(readingCards.getChildCount()==0&&topInScroll(fallback,scroll)-scroll.getScrollY()==fallbackOffset,"removed anchor not erased or next survivor jumped");
        // Multiple updates before one layout must retain the pre-update coordinates, not stale intermediate positions.
        scroll.scrollTo(0,topInScroll(fallback,scroll)+Ui.dp(context,3));fallbackOffset=topInScroll(fallback,scroll)-scroll.getScrollY();
        snapshot.add(inserted);list.render(snapshot);
        snapshot.add(item(context,"fixture.newer",0,100,400,null,false));list.render(snapshot);layout(scroll,context);
        check(topInScroll(fallback,scroll)-scroll.getScrollY()==fallbackOffset,"coalesced updates lost initial reading coordinates");
        list.clear();check(rows.getChildCount()==0,"scroll renderer clear failed");
    }
    private static void layout(ScrollView scroll,Context context){
        int width=Ui.dp(context,320),height=Ui.dp(context,240);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        scroll.layout(0,0,width,height);scroll.getChildAt(0).getViewTreeObserver().dispatchOnGlobalLayout();
    }
    private static int topInScroll(View view,ScrollView scroll){
        int top=view.getTop();ViewParent parent=view.getParent();
        while(parent instanceof View&&parent!=scroll){View ancestor=(View)parent;top+=ancestor.getTop()-ancestor.getScrollY();parent=parent.getParent();}return top;
    }
    private static boolean hasText(View view,String expected){
        if(view instanceof TextView&&view.getVisibility()==View.VISIBLE&&((TextView)view).getText().toString().equals(expected))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),expected))return true;}
        return false;
    }
    private static boolean allTextEmpty(View view){
        if(view instanceof TextView&&!(view instanceof Button)&&((TextView)view).getText().length()!=0)return false;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(!allTextEmpty(group.getChildAt(i)))return false;}
        return true;
    }
    private static Button findButton(View root){
        if(root instanceof Button)return (Button)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){Button result=findButton(group.getChildAt(i));if(result!=null)return result;}}
        return null;
    }
}
