package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import java.util.Calendar;
import java.util.Locale;

/** Pure in-memory content fixtures; does not post notifications or inspect a notification listener. */
final class NotificationContentChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(Instrumentation test){
        Context context=test.getTargetContext();standard(context);messaging(context);malformed(context);timestamps(context);
        for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.JAPANESE}){
            Configuration config=new Configuration(context.getResources().getConfiguration());config.setLocale(locale);
            localizedTime(context.createConfigurationContext(config),Locale.JAPANESE.equals(locale));
        }
    }
    private static Notification.Builder builder(Context context){
        return new Notification.Builder(context,"synthetic-content-only").setSmallIcon(android.R.drawable.ic_dialog_info);
    }
    private static StatusBarNotification item(Notification notification,long posted){
        return new StatusBarNotification("fixture.content","fixture.content",97123,null,10000,0,0,notification,UserHandle.getUserHandleForUid(10000),posted);
    }
    private static NotificationContent content(Notification notification){return NotificationContent.from(item(notification,System.currentTimeMillis()));}
    private static Person person(String name,String key){return new Person.Builder().setName(name).setKey(key).build();}
    private static void standard(Context context){
        Notification notification=builder(context).setContentTitle("  Fixture title  ").setContentText("Brief body")
                .setStyle(new Notification.BigTextStyle().bigText("Expanded body\nSecond line")).build();
        NotificationContent display=content(notification);
        check(display.title.equals("Fixture title")&&display.body.equals("Expanded body\nSecond line")&&display.sender.isEmpty(),"standard big text extraction");
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT," \n ");
        check(content(notification).body.equals("Brief body"),"blank big text blocked useful ordinary text");
        Notification inbox=builder(context).setContentTitle("Inbox").setContentText("Summary")
                .setStyle(new Notification.InboxStyle().addLine("First useful line").addLine("Second useful line")).build();
        check(content(inbox).body.equals("First useful line\nSecond useful line"),"InboxStyle useful lines replaced by summary");
        inbox.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,new CharSequence[]{null," ","Useful"});
        check(content(inbox).body.equals("Useful"),"inbox empty line filtering");
        inbox.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,new CharSequence[]{"0","1","2","3","4","5"});
        check(content(inbox).body.equals("1\n2\n3\n4\n5"),"inbox latest-line bound");
        inbox.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,new CharSequence[]{null," "});
        check(content(inbox).body.equals("Summary"),"empty inbox lost ordinary fallback");
    }
    private static void messaging(Context context){
        Person me=person("Fixture me","self"),alice=person("Alice","alice"),bob=person("Bob","bob");
        long now=System.currentTimeMillis();
        Notification.MessagingStyle style=new Notification.MessagingStyle(me).setConversationTitle("Fixture room").setGroupConversation(true)
                .addMessage("First body",now-3_000,alice).addMessage("Second body",now-2_000,bob).addMessage("Latest body",now-1_000,alice);
        Notification notification=builder(context).setStyle(style).build();
        NotificationContent display=content(notification);
        check(display.title.equals("Fixture room")&&display.sender.equals("Alice"),"conversation title or sender extraction");
        check(display.body.equals("Alice: First body\nBob: Second body\nAlice: Latest body"),"multiple message bodies/attribution lost");
        style.addMessage("Own reply",now,(Person)null);notification=builder(context).setStyle(style).build();display=content(notification);
        check(display.sender.isEmpty(),"sending user confused with incoming sender");
        check(display.body.equals("Bob: Second body\nAlice: Latest body\nFixture me: Own reply"),"own reply dropped or latest-three window incorrect");
        style.addMessage("Named own reply",now,me);display=content(builder(context).setStyle(style).build());
        check(display.sender.isEmpty()&&display.body.contains("Named own reply"),"Person identity confused local user with sender");
        Notification.MessagingStyle direct=new Notification.MessagingStyle(me).addMessage("Direct body",now,alice);
        notification=builder(context).setStyle(direct).build();notification.extras.remove(Notification.EXTRA_TITLE);display=content(notification);
        check(display.title.equals("Alice")&&display.sender.equals("Alice")&&display.body.equals("Direct body"),"single message without title fallback");
        direct.addMessage("  ",now,bob);display=content(builder(context).setStyle(direct).build());
        check(display.sender.equals("Alice")&&display.body.equals("Direct body"),"blank message discarded previous useful body");
        Notification.MessagingStyle empty=new Notification.MessagingStyle(me).setConversationTitle("Empty room");
        notification=builder(context).setContentText("Body fallback").setStyle(empty).build();
        // A provider may retain fallback extras despite an empty current-message array.
        notification.extras.putCharSequence(Notification.EXTRA_TEXT,"Body fallback");
        notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES,new Parcelable[0]);display=content(notification);
        check(display.title.equals("Empty room")&&display.body.equals("Body fallback")&&display.sender.isEmpty(),"empty messaging style lost fallback");
        direct.addHistoricMessage(new Notification.MessagingStyle.Message("Historical only",now-10_000,bob));
        display=content(builder(context).setStyle(direct).build());
        check(!display.body.contains("Historical only"),"historical message presented as current content");
        // Explicit keys disambiguate people with the same displayed name.
        Person other=person("Fixture me","different-person");
        display=content(builder(context).setStyle(new Notification.MessagingStyle(me).addMessage("Other body",now,other)).build());
        check(display.sender.equals("Fixture me"),"same-name different person treated as self");
    }
    private static void malformed(Context context){
        Notification notification=builder(context).build();notification.extras=null;
        NotificationContent display=content(notification);
        check(display.title.isEmpty()&&display.body.isEmpty()&&display.sender.isEmpty(),"missing extras not empty/safe");
        notification.extras=new Bundle();notification.extras.putInt(Notification.EXTRA_TITLE,123);
        notification.extras.putParcelable(Notification.EXTRA_BIG_TEXT,new Intent());notification.extras.putString(Notification.EXTRA_TEXT_LINES,"wrong type");
        notification.extras.putCharSequence(Notification.EXTRA_TEXT,"Surviving fallback");
        notification.extras.putString(Notification.EXTRA_MESSAGES,"wrong array");display=content(notification);
        check(display.title.isEmpty()&&display.body.equals("Surviving fallback"),"malformed extras crashed or suppressed valid text");
        Notification.MessagingStyle style=new Notification.MessagingStyle(person("Me","self")).addMessage("Surviving message",1,person("Sender","sender"));
        notification=builder(context).setStyle(style).build();
        Parcelable[] valid=notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        Bundle invalid=new Bundle();invalid.putInt("text",12);invalid.putString("time","invalid");
        notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES,new Parcelable[]{invalid,new Intent(),valid[0],null});
        display=content(notification);
        check(display.body.equals("Surviving message")&&display.sender.equals("Sender"),"adjacent malformed message dropped useful entry");
        check(NotificationContent.from(null).timestamp==0&&NotificationContent.from(null).body.isEmpty(),"null snapshot not safe");
    }
    private static void timestamps(Context context){
        long now=System.currentTimeMillis(),posted=now-120_000,when=now-180_000;
        Notification notification=builder(context).setWhen(when).build();
        check(NotificationContent.from(item(notification,posted)).timestamp==when,"meaningful notification time ignored");
        for(long invalid:new long[]{0,-1,now+86_400_000}){
            notification.when=invalid;check(NotificationContent.from(item(notification,posted)).timestamp==posted,"invalid when did not fall back to postTime");
        }
        notification.when=0;
        check(NotificationContent.from(item(notification,0)).timestamp==0&&NotificationContent.from(item(notification,0)).formatTime(context,now).isEmpty(),"missing time manufactured a timestamp");
        check(NotificationContent.from(item(notification,now+86_400_000)).timestamp==0,"implausible future postTime accepted");
    }
    private static void localizedTime(Context context,boolean japanese){
        Calendar clock=Calendar.getInstance();clock.clear();clock.set(2020,Calendar.JANUARY,4,12,30);
        long now=clock.getTimeInMillis();Notification notification=builder(context).setWhen(now-30_000).build();
        check(NotificationContent.from(item(notification,now)).formatTime(context,now).equals(japanese?"たった今":"Just now"),"localized just-now string");
        notification.when=now-5*60_000;String value=NotificationContent.from(item(notification,now)).formatTime(context,now);
        check(value.equals(japanese?"5分前":"5 min ago"),"localized relative minutes");
        notification.when=now-2*60*60_000;value=NotificationContent.from(item(notification,now)).formatTime(context,now);
        check(!value.isEmpty()&&!value.contains("Jan")&&!value.contains("昨日")&&!value.contains("Yesterday"),"today time should not contain date");
        clock.add(Calendar.DAY_OF_YEAR,-1);notification.when=clock.getTimeInMillis();value=NotificationContent.from(item(notification,now)).formatTime(context,now);
        check(value.startsWith(japanese?"昨日 ":"Yesterday, "),"localized yesterday label");
        clock.add(Calendar.DAY_OF_YEAR,-1);notification.when=clock.getTimeInMillis();value=NotificationContent.from(item(notification,now)).formatTime(context,now);
        check(value.contains(japanese?"1月":"Jan"),"date format ignored context locale");
        clock.set(Calendar.YEAR,2019);notification.when=clock.getTimeInMillis();
        check(NotificationContent.from(item(notification,now)).formatTime(context,now).contains("2019"),"prior-year timestamp omitted year");
    }
}
