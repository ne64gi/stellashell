package net.fuyumori.stellashell;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Display-only snapshot of public notification extras. Never executes RemoteViews or keeps history. */
final class NotificationContent {
    final String title,body,sender;
    final long timestamp;
    private static final long FUTURE_TOLERANCE=5*60_000L;
    private NotificationContent(String title,String body,String sender,long timestamp){
        this.title=title;this.body=body;this.sender=sender;this.timestamp=timestamp;
    }
    static NotificationContent from(StatusBarNotification item){
        if(item==null)return new NotificationContent("","","",0);
        Notification notification=item.getNotification();
        if(notification==null)return new NotificationContent("","","",validTime(item.getPostTime(),System.currentTimeMillis()));
        Bundle extras=notification.extras;
        String title=text(extras,Notification.EXTRA_TITLE),body=text(extras,Notification.EXTRA_BIG_TEXT),sender="";
        String conversation=text(extras,Notification.EXTRA_CONVERSATION_TITLE);
        if(!conversation.isEmpty())title=conversation;
        if(body.isEmpty())body=inbox(extras);
        if(body.isEmpty())body=text(extras,Notification.EXTRA_TEXT);
        List<Notification.MessagingStyle.Message> messages=messages(notification);
        if(!messages.isEmpty()){
            Person user=person(extras,Notification.EXTRA_MESSAGING_PERSON);
            String userName=user==null?text(extras,Notification.EXTRA_SELF_DISPLAY_NAME):clean(user.getName());
            ArrayList<Notification.MessagingStyle.Message> visible=new ArrayList<>();
            for(int i=messages.size()-1;i>=0&&visible.size()<3;i--){
                Notification.MessagingStyle.Message message=messages.get(i);
                if(!clean(message.getText()).isEmpty())visible.add(0,message);
            }
            if(!visible.isEmpty()){
                Notification.MessagingStyle.Message latest=visible.get(visible.size()-1);
                if(!isSelf(latest,user,userName))sender=messageSender(latest);
                if(title.isEmpty())title=sender;
                Set<String> authors=new HashSet<>();
                for(Notification.MessagingStyle.Message message:visible)authors.add(isSelf(message,user,userName)?"self":"sender:"+messageSender(message));
                boolean prefix=authors.size()>1;
                StringBuilder joined=new StringBuilder();
                for(Notification.MessagingStyle.Message message:visible){
                    if(joined.length()>0)joined.append('\n');
                    String name=isSelf(message,user,userName)?userName:messageSender(message);
                    if(prefix&&!name.isEmpty())joined.append(name).append(": ");
                    joined.append(clean(message.getText()));
                }
                body=joined.toString();
            }
        }
        long now=System.currentTimeMillis();
        long timestamp=validTime(notification.when,now);
        if(timestamp==0)timestamp=validTime(item.getPostTime(),now);
        return new NotificationContent(title,body,sender,timestamp);
    }
    String formatTime(Context context){return formatTime(context,System.currentTimeMillis());}
    String formatTime(Context context,long now){
        if(timestamp<=0)return "";
        long elapsed=now-timestamp;
        if(elapsed>=0&&elapsed<60_000)return context.getString(R.string.notification_time_now);
        if(elapsed>=60_000&&elapsed<60*60_000){
            int minutes=(int)(elapsed/60_000);
            return context.getResources().getQuantityString(R.plurals.notification_time_minutes,minutes,minutes);
        }
        Locale locale=context.getResources().getConfiguration().getLocales().get(0);
        String clock=DateFormat.getBestDateTimePattern(locale,DateFormat.is24HourFormat(context)?"Hm":"hm");
        String time=new SimpleDateFormat(clock,locale).format(new Date(timestamp));
        Calendar today=Calendar.getInstance(locale);today.setTimeInMillis(now);
        Calendar date=Calendar.getInstance(locale);date.setTimeInMillis(timestamp);
        if(sameDay(today,date))return time;
        today.add(Calendar.DAY_OF_YEAR,-1);
        if(sameDay(today,date))return context.getString(R.string.notification_time_yesterday,time);
        Calendar reference=Calendar.getInstance(locale);reference.setTimeInMillis(now);
        String skeleton=date.get(Calendar.YEAR)==reference.get(Calendar.YEAR)?"MMMd":"yMMMd";
        String day=new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale,skeleton),locale).format(new Date(timestamp));
        return context.getString(R.string.notification_time_date,day,time);
    }
    private static boolean sameDay(Calendar a,Calendar b){return a.get(Calendar.ERA)==b.get(Calendar.ERA)&&a.get(Calendar.YEAR)==b.get(Calendar.YEAR)&&a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);}
    private static long validTime(long time,long now){return time>0&&time<=now+FUTURE_TOLERANCE?time:0;}
    private static Object value(Bundle extras,String key){
        if(extras==null)return null;
        try{return extras.get(key);}catch(RuntimeException ignored){return null;}
    }
    private static String text(Bundle extras,String key){Object value=value(extras,key);return value instanceof CharSequence?clean((CharSequence)value):"";}
    private static Person person(Bundle extras,String key){Object value=value(extras,key);return value instanceof Person?(Person)value:null;}
    private static String clean(CharSequence value){return value==null?"":value.toString().trim();}
    private static String inbox(Bundle extras){
        Object value=value(extras,Notification.EXTRA_TEXT_LINES);
        if(!(value instanceof CharSequence[]))return "";
        CharSequence[] lines=(CharSequence[])value;ArrayList<String> visible=new ArrayList<>();
        for(int i=lines.length-1;i>=0&&visible.size()<5;i--){String line=clean(lines[i]);if(!line.isEmpty())visible.add(0,line);}
        return String.join("\n",visible);
    }
    private static List<Notification.MessagingStyle.Message> messages(Notification notification){
        ArrayList<Notification.MessagingStyle.Message> messages=new ArrayList<>();
        Bundle extras=notification.extras;
        Object value=value(extras,Notification.EXTRA_MESSAGES);
        if(!(value instanceof Parcelable[]))return messages;
        Parcelable[] bundles=(Parcelable[])value;
        if(bundles.length<=64){
            try{
                messages.addAll(Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles));return messages;
            }catch(RuntimeException ignored){}
        }
        // Decode separately: a malformed adjacent entry must not discard all usable messages.
        for(int i=Math.max(0,bundles.length-64);i<bundles.length;i++){
            if(!(bundles[i] instanceof Bundle))continue;
            try{
                messages.addAll(Notification.MessagingStyle.Message.getMessagesFromBundleArray(new Parcelable[]{bundles[i]}));
            }
            catch(RuntimeException ignored){}
        }
        return messages;
    }
    @SuppressWarnings("deprecation")
    private static String messageSender(Notification.MessagingStyle.Message message){
        Person person=message.getSenderPerson();return person==null?clean(message.getSender()):clean(person.getName());
    }
    private static boolean isSelf(Notification.MessagingStyle.Message message,Person user,String userName){
        Person sender=message.getSenderPerson();String name=messageSender(message);
        if(sender==null&&name.isEmpty())return true;
        if(user!=null&&sender!=null){
            if(user.getKey()!=null&&sender.getKey()!=null)return user.getKey().equals(sender.getKey());
            if(user.getUri()!=null&&sender.getUri()!=null)return user.getUri().equals(sender.getUri());
        }
        return !userName.isEmpty()&&userName.equals(name);
    }
}
