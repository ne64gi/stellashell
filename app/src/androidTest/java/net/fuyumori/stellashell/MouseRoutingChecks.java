package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Reversible reset/reroute of an existing owned physical mouse; no task/key/display operations. */
final class MouseRoutingChecks {
    private final Instrumentation test;private final Context context;
    private Object inputService;private Method deviceIds,deviceInfo,associated;
    private boolean resetPointerObserved,restoredPointerObserved;
    private static final Pattern DIAGNOSTIC=Pattern.compile("display=(\\d+), mice=(\\d+), preserved=(\\d+)");
    MouseRoutingChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private void probeFreshRead(){
        // Optional fresh Binder reads. Hidden API denial must not veto the
        // authoritative native read, and this fixture never changes exemptions.
        try{
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"input");
            inputService=Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            Class<?> api=Class.forName("android.hardware.input.IInputManager");
            deviceIds=api.getMethod("getInputDeviceIds");deviceInfo=api.getMethod("getInputDevice",int.class);
            associated=InputDevice.class.getMethod("getAssociatedDisplayId");
        }catch(ReflectiveOperationException|RuntimeException denied){inputService=null;associated=null;}
    }
    private List<InputDevice> devices()throws Exception{
        List<InputDevice> result=new ArrayList<>();
        if(inputService!=null){
            try{
                for(int id:(int[])deviceIds.invoke(inputService)){InputDevice device=(InputDevice)deviceInfo.invoke(inputService,id);if(device!=null)result.add(device);}
                return result;
            }catch(ReflectiveOperationException|RuntimeException denied){inputService=null;associated=null;result.clear();}
        }
        InputManager manager=context.getSystemService(InputManager.class);
        for(int id:manager.getInputDeviceIds()){InputDevice device=manager.getInputDevice(id);if(device!=null)result.add(device);}
        return result;
    }
    private Integer associated(InputDevice device){
        if(associated==null)return null;
        try{return (Integer)associated.invoke(device);}catch(ReflectiveOperationException|RuntimeException denied){associated=null;return null;}
    }
    private static boolean mouse(InputDevice device){
        return device.isExternal()&&!device.isVirtual()&&device.supportsSource(InputDevice.SOURCE_MOUSE)
            &&device.getKeyboardType()!=InputDevice.KEYBOARD_TYPE_ALPHABETIC&&!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)&&!device.supportsSource(InputDevice.SOURCE_TOUCHPAD);
    }
    private static boolean protectedDevice(InputDevice device){
        return !device.isVirtual()&&(device.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC||device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)||device.supportsSource(InputDevice.SOURCE_TOUCHPAD));
    }
    private Map<String,String> protectedAssociations(NativeState nativeState)throws Exception{
        Map<String,String> result=new HashMap<>();
        for(InputDevice device:devices())if(protectedDevice(device)){
            String identity=nativeState.protectedIdentity(device);
            String signature=nativeState.associations.get(device.getId());
            check(signature!=null&&!signature.isEmpty(),"Native protected-device association metadata absent");
            // Native metadata includes descriptor/port bindings and numeric
            // mapper viewport IDs; do not depend on cached public device info.
            check(result.put(device.getDescriptor(),identity+"\n"+signature)==null,"Protected logical descriptors are not unique; refusing incomplete snapshot");
        }
        return result;
    }
    private static final class NativeIdentity {
        String name,sources;Integer generation,keyboardType;Boolean external;
    }
    private static final class NativeState {
        int pointer=-2;boolean mouseControllersAbsent;
        final Map<Integer,String> descriptorUniqueIds=new HashMap<>(),associations=new HashMap<>(),memberships=new HashMap<>();
        final Map<Integer,Integer> cursorDisplays=new HashMap<>();
        final Map<Integer,Set<String>> descriptors=new HashMap<>();
        final Map<Integer,NativeIdentity> identities=new HashMap<>();
        boolean matches(InputDevice device){Set<String> values=descriptors.get(device.getId());return values!=null&&values.contains(device.getDescriptor());}
        String protectedIdentity(InputDevice device){
            NativeIdentity identity=identities.get(device.getId());
            String membership=memberships.get(device.getId());
            // The logical identifier can outlive its first EventHub subdevice.
            // Bind the fresh public logical record to the native logical record
            // instead of assuming its descriptor belongs to a surviving member.
            // Public toString exposes generation without hidden API access.
            Matcher generation=Pattern.compile("(?m)^\\s*Generation:\\s*(\\d+)\\s*$").matcher(device.toString());
            check(identity!=null&&membership!=null&&generation.find(),"Protected logical identity metadata incomplete: device="+device.getId());
            boolean sameGeneration=Objects.equals(identity.generation,Integer.parseInt(generation.group(1))),
                sameName=Objects.equals(identity.name,device.getName()),sameKeyboard=Objects.equals(identity.keyboardType,device.getKeyboardType()),
                sameExternal=Objects.equals(identity.external,device.isExternal()),sameSources=sourcesMatch(identity.sources,device.getSources());
            check(sameGeneration&&sameName&&sameKeyboard&&sameExternal&&sameSources,
                "Fresh public/native protected logical identity disagrees: device="+device.getId()
                    +" generation="+(sameGeneration?1:0)+" name="+(sameName?1:0)+" keyboard="+(sameKeyboard?1:0)
                    +" external="+(sameExternal?1:0)+" sources="+(sameSources?1:0));
            // Snapshot keys also retain the public logical descriptor. Complete
            // native membership IDs/descriptors must stay identical afterwards;
            // ID-only or name-only matching is never sufficient.
            return "logical="+device.getId()+";name="+identity.name+";sources="+identity.sources
                +";keyboard="+identity.keyboardType+";external="+identity.external+";members="+membership;
        }
        private static boolean sourcesMatch(String nativeSources,int publicSources){
            if(nativeSources==null)return false;
            if(nativeSources.matches("0x[0-9a-fA-F]+"))return Long.parseLong(nativeSources.substring(2),16)==Integer.toUnsignedLong(publicSources);
            if(publicSources==0)return nativeSources.equals("UNKNOWN");
            if(publicSources==-256)return nativeSources.equals("ANY");
            // AOSP inputEventSourceToString's complete source masks. Compare
            // sets because native formatting order is not an identity property.
            int[] masks={0x101,0x201,0x401,0x1002,0x2002,0x4002,0x8002,0x10004,0x20004,0x100008,0x200000,0x1000010,0x2000001,0x4000000,0x400000};
            String[] names={"KEYBOARD","DPAD","GAMEPAD","TOUCHSCREEN","MOUSE","STYLUS","BLUETOOTH_STYLUS","TRACKBALL","MOUSE_RELATIVE","TOUCHPAD","TOUCH_NAVIGATION","JOYSTICK","HDMI","SENSOR","ROTARY_ENCODER"};
            Set<String> expected=new HashSet<>();for(int i=0;i<masks.length;i++)if((publicSources&masks[i])==masks[i])expected.add(names[i]);
            Set<String> actual=new HashSet<>();for(String part:nativeSources.split("\\|"))actual.add(part.trim());
            return !expected.isEmpty()&&expected.equals(actual);
        }
    }
    private NativeState nativeState()throws Exception{
        ParcelFileDescriptor pipe=test.getUiAutomation().executeShellCommand("dumpsys input");
        FutureTask<String> read=new FutureTask<>(()->{
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe)){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1){if(bytes.size()+count>1024*1024)throw new IOException("Input dump exceeded bound");bytes.write(buffer,0,count);}
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        });
        Thread reader=new Thread(read,"StellaMouseCheckRead");reader.setDaemon(true);reader.start();String dump;
        try{dump=read.get(2,TimeUnit.SECONDS);}finally{read.cancel(true);try{pipe.close();}catch(IOException ignored){}}
        NativeState state=new NativeState();Map<Integer,String> hubDescriptors=new HashMap<>();Map<Integer,List<Integer>> hubs=new HashMap<>();
        int device=-1,hub=-1,deviceIndent=-1;boolean inHub=false,cursorMapper=false;
        Pattern deviceLine=Pattern.compile("Device (\\d+): (.*)"),hubLine=Pattern.compile("(\\d+):.*"),
            pointerLine=Pattern.compile("Pointer Display ID:\\s*(-?\\d+)"),cursorLine=Pattern.compile("DisplayId:\\s*(-?\\d+)"),
            viewportLine=Pattern.compile("displayId\\s*[:=]\\s*(-?\\d+)");
        for(String line:dump.split("\\r?\\n")){
            String value=line.trim();int indent=line.length()-line.replaceFirst("^\\s+", "").length();
            if(value.equals("Event Hub State:")){inHub=true;device=-1;}
            if(value.startsWith("Input Reader State")&&value.endsWith(":")){inHub=false;hub=-1;}
            if(inHub){
                Matcher head=hubLine.matcher(value);if(head.matches())hub=Integer.parseInt(head.group(1));
                if(hub>=0&&value.startsWith("Descriptor:"))hubDescriptors.put(hub,value.substring("Descriptor:".length()).trim());
                continue;
            }
            Matcher head=deviceLine.matcher(value);
            if(head.matches()){
                device=Integer.parseInt(head.group(1));deviceIndent=indent;cursorMapper=false;
                NativeIdentity identity=new NativeIdentity();identity.name=head.group(2);state.identities.put(device,identity);
            }
            else if(!value.isEmpty()&&device>=0&&indent<=deviceIndent)device=-1;
            if(device<0)continue;
            NativeIdentity identity=state.identities.get(device);
            if(value.startsWith("Generation:"))identity.generation=Integer.parseInt(value.substring("Generation:".length()).trim());
            if(value.startsWith("IsExternal:")){
                String external=value.substring("IsExternal:".length()).trim();
                if(external.equals("true")||external.equals("false"))identity.external=Boolean.parseBoolean(external);
            }
            if(value.startsWith("KeyboardType:"))identity.keyboardType=Integer.parseInt(value.substring("KeyboardType:".length()).trim());
            if(value.startsWith("Sources:"))identity.sources=value.substring("Sources:".length()).trim();
            if(value.startsWith("EventHub Devices:")){
                List<Integer> ids=new ArrayList<>();Matcher number=Pattern.compile("\\d+").matcher(value);while(number.find())ids.add(Integer.parseInt(number.group()));hubs.put(device,ids);
            }
            if(value.endsWith("InputMapper:"))cursorMapper=value.equals("Cursor Input Mapper:")||value.equals("CursorInputMapper:");
            if(value.startsWith("AssociatedDisplay")){
                state.associations.merge(device,value+"\n",String::concat);
                if(value.startsWith("AssociatedDisplayUniqueIdByDescriptor:"))state.descriptorUniqueIds.put(device,value.substring("AssociatedDisplayUniqueIdByDescriptor:".length()).trim());
            }
            Matcher cursor=cursorLine.matcher(value);if(cursor.matches()){
                state.associations.merge(device,"display="+cursor.group(1)+"\n",String::concat);
                // DisplayId is emitted by CursorInputMapper on supported builds.
                if(cursorMapper||!state.cursorDisplays.containsKey(device))state.cursorDisplays.put(device,Integer.parseInt(cursor.group(1)));
            }
            Matcher viewport=viewportLine.matcher(value);while(viewport.find())state.associations.merge(device,"viewport="+viewport.group(1)+"\n",String::concat);
        }
        for(Map.Entry<Integer,List<Integer>> entry:hubs.entrySet()){
            Set<String> values=new HashSet<>();SortedMap<Integer,String> members=new TreeMap<>();boolean complete=!entry.getValue().isEmpty();
            for(int id:entry.getValue()){
                String descriptor=hubDescriptors.get(id);
                if(descriptor==null||descriptor.isEmpty())complete=false;
                else{values.add(descriptor);members.put(id,descriptor);}
            }
            state.descriptors.put(entry.getKey(),values);
            if(complete&&members.size()==entry.getValue().size())state.memberships.put(entry.getKey(),members.toString());
        }
        int controllers=dump.indexOf("MousePointerControllers:"),touch=dump.indexOf("TouchPointerControllers:",controllers);
        check(controllers>=0&&touch>controllers,"Native mouse-controller section unknown; refusing ambiguous parser");
        String mouseControllers=dump.substring(controllers+"MousePointerControllers:".length(),touch);
        state.mouseControllersAbsent=mouseControllers.trim().isEmpty();
        // Only mouse controllers can prove physical cursor placement. Touch and
        // stylus controller displays are independent and must not stand in for it.
        state.pointer=-2;Matcher observed=pointerLine.matcher(mouseControllers);
        while(observed.find()){
            int id=Integer.parseInt(observed.group(1));
            check(state.pointer==-2||state.pointer==id,"Mouse controllers disagree; refusing ambiguous routing check");state.pointer=id;
        }
        check(state.pointer>=0||state.mouseControllersAbsent,"Nonempty mouse-controller section has no understood display");return state;
    }
    private Map<String,Object> prefsSnapshot(){
        SharedPreferences prefs=Launches.prefs(context);Map<String,Object> snapshot=new HashMap<>();
        for(String key:new String[]{"enabled","primary_mode","active_display","preferred_display","workspace_display","workspace_auto","hide_virtual_ime","desktop_taskbar","phone_taskbar"})
            snapshot.put(key,prefs.contains(key)?prefs.getAll().get(key):null);
        return snapshot;
    }
    private boolean samePrefs(Map<String,Object> before){return before.equals(prefsSnapshot());}
    private static String unique(Display display)throws Exception{return (String)Display.class.getMethod("getUniqueId").invoke(display);}
    private boolean sameTarget(int target,String id,Map<String,Object> prefs)throws Exception{
        Display display=context.getSystemService(DisplayManager.class).getDisplay(target);
        return display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0&&id.equals(unique(display))&&samePrefs(prefs);
    }
    private String[] globalSettings(){return new String[]{Settings.Global.getString(context.getContentResolver(),"force_desktop_mode_on_external_displays"),Settings.Global.getString(context.getContentResolver(),"enable_freeform_support")};}
    private void reset()throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] reply=new String[2];
        main(()->Bridge.get(context).resetMouseRouting((result,error)->{reply[0]=result;reply[1]=error;done.countDown();}));
        check(done.await(10,TimeUnit.SECONDS),"Mouse reset timed out");check(reply[1]==null&&"OK".equals(reply[0]),"Owned mouse cleanup did not complete");
    }
    private void awaitRouting(Set<String> descriptors,int expected,String targetUnique,Map<String,String> protectedBefore,long deadline)throws Exception{
        while(SystemClock.uptimeMillis()<deadline){
            Map<String,InputDevice> current=new HashMap<>();for(InputDevice device:devices())current.put(device.getDescriptor(),device);
            NativeState nativeState=nativeState();
            check(protectedBefore.equals(protectedAssociations(nativeState)),"Keyboard/touchscreen association changed during mouse check");
            boolean ready=nativeState.pointer==expected||nativeState.mouseControllersAbsent;
            for(String descriptor:descriptors){
                InputDevice device=current.get(descriptor);check(device!=null&&mouse(device),"Owned physical mouse disappeared or changed type");
                check(nativeState.matches(device),"Owned mouse native descriptor changed; refusing stale device ID");
                Integer association=associated(device);
                ready&=(association==null||association==(expected==0?-1:expected));
                Integer mapper=nativeState.cursorDisplays.get(device.getId());
                // An unassociated pointer mapper uses INVALID on Android 16;
                // PointerChoreographer decides the physical cursor display later.
                ready&=expected==0?(Objects.equals(mapper,-1)||Objects.equals(mapper,0)):Objects.equals(mapper,expected);
                String value=nativeState.descriptorUniqueIds.get(device.getId());
                ready&=expected==0?value!=null&&(value.isEmpty()||"<none>".equals(value)):targetUnique.equals(value);
            }
            if(ready){if(expected==0)resetPointerObserved=nativeState.pointer==0;else restoredPointerObserved=nativeState.pointer==expected;return;}Thread.sleep(80);
        }
        throw new AssertionError("Owned mouse association/controller did not settle for expected output "+expected);
    }
    void run()throws Exception{
        long finalDeadline=SystemClock.uptimeMillis()+30000,deadline=finalDeadline-10000;
        SharedPreferences prefs=Launches.prefs(context);Map<String,Object> before=prefsSnapshot();String[] globals=globalSettings();
        int target=prefs.getInt("active_display",-1);Display display=context.getSystemService(DisplayManager.class).getDisplay(target);
        check(DockService.running()&&prefs.getBoolean("enabled",false)&&!prefs.getBoolean("primary_mode",true),"Requires an existing enabled external session; fixture never starts/stops it");
        check(target>0&&display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0,"Requires current public external target");
        String targetUnique=unique(display);Matcher diagnostic=DIAGNOSTIC.matcher(prefs.getString("mouse_diagnostics",""));
        check(diagnostic.matches()&&Integer.parseInt(diagnostic.group(1))==target&&Integer.parseInt(diagnostic.group(2))>0,"Requires nonempty existing owned-mouse routing diagnostic");
        boolean[] ready={false};main(()->ready[0]=Bridge.get(context).ready()&&Bridge.get(context).authorized());check(ready[0],"Requires already-ready authorized Bridge");
        // A ready Binder alone does not prove that the recreated app has
        // dispatched its first input lease sync. Wait for that serialized call.
        CountDownLatch synced=new CountDownLatch(1);String[] syncError={null};
        main(()->Bridge.get(context).call(service->"OK",(result,error)->{syncError[0]=error;synced.countDown();}));
        check(synced.await(10,TimeUnit.SECONDS)&&syncError[0]==null,"Initial input lease did not sync");
        probeFreshRead();NativeState initial=nativeState();check(initial.pointer==target||initial.mouseControllersAbsent,"Initial observed mouse controller disagrees with selected output");
        Set<String> owned=new HashSet<>();int candidates=0,identities=0,mappers=0,uniqueIds=0,associations=0;
        for(InputDevice device:devices())if(mouse(device)){
            candidates++;boolean identity=initial.matches(device),mapper=Objects.equals(initial.cursorDisplays.get(device.getId()),target),unique=targetUnique.equals(initial.descriptorUniqueIds.get(device.getId()));
            Integer association=associated(device);boolean associated=association==null||association==target;
            if(identity)identities++;if(mapper)mappers++;if(unique)uniqueIds++;if(associated)associations++;
            if(identity&&mapper&&unique&&associated)owned.add(device.getDescriptor());
        }
        check(!owned.isEmpty()&&owned.size()==Integer.parseInt(diagnostic.group(2)),"Cannot prove lease; refusing reset: candidates="+candidates+" identity="+identities+" mapper="+mappers+" uniqueId="+uniqueIds+" association="+associations+" owned="+owned.size());
        Map<String,String> protectedBefore=protectedAssociations(initial);boolean resetAttempted=false,restored=false;
        try{
            check(sameTarget(target,targetUnique,before)&&Arrays.equals(globals,globalSettings()),"Target or settings changed before mouse mutation");
            resetAttempted=true;reset();awaitRouting(owned,0,targetUnique,protectedBefore,deadline);
            check(sameTarget(target,targetUnique,before)&&Arrays.equals(globals,globalSettings()),"Target/settings changed; refusing inappropriate reroute");
            main(()->Bridge.get(context).mouseDisplay(target));awaitRouting(owned,target,targetUnique,protectedBefore,deadline);restored=true;
            android.util.Log.i("StellaMouseCheck","PASS target="+target+" owned="+owned.size()+" resetControllerObserved="+(resetPointerObserved?1:0)+" restoredControllerObserved="+(restoredPointerObserved?1:0)+" configuredOutput="+target+" protectedDevices="+protectedBefore.size());
        }finally{
            if(resetAttempted&&!restored&&sameTarget(target,targetUnique,before)&&Arrays.equals(globals,globalSettings())){
                main(()->Bridge.get(context).mouseDisplay(target));awaitRouting(owned,target,targetUnique,protectedBefore,finalDeadline);
            }
            check(samePrefs(before),"Mouse fixture changed protected preference values/presence");check(Arrays.equals(globals,globalSettings()),"Mouse fixture changed global display settings");
            check(protectedBefore.equals(protectedAssociations(nativeState())),"Keyboard/touchscreen association not preserved");
        }
    }
}
