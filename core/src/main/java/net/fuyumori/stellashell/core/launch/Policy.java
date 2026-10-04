package net.fuyumori.stellashell.core.launch;

import java.util.*;

/** Pure routing/command policy, independently testable without Android. */
public final class Policy {
    private Policy() {}
    public static int selectDisplay(int preferred, Collection<Integer> available) {
        if (preferred > 0 && available.contains(preferred)) return preferred;
        return available.stream().filter(id -> id > 0).min(Integer::compareTo).orElse(-1);
    }
    public static void requireTarget(int displayId, Collection<Integer> available) {
        requireTarget(displayId,available,false);
    }
    public static void requireTarget(int displayId, Collection<Integer> available, boolean primary) {
        if (displayId < 0 || (displayId == 0 && !primary) || !available.contains(displayId))
            throw new IllegalArgumentException("The external display is disconnected");
    }
    public static int selectDisplay(int preferred,Collection<Integer> available,boolean primary) {
        return primary ? (available.contains(0)?0:-1) : selectDisplay(preferred,available);
    }
    public static void component(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z_.$][A-Za-z0-9_.$]*"))
            throw new IllegalArgumentException("Invalid app component");
    }
    public static void setting(String value) {
        if (!Arrays.asList("0", "1", "null").contains(value))
            throw new IllegalArgumentException("Unsupported setting value");
    }
    public static String[] launchCommand(String component, int displayId, int mode) {
        return launchCommand(component,displayId,mode,false);
    }
    public static String[] launchCommand(String component,int displayId,int mode,boolean primary) {
        component(component);
        if (displayId < 0 || (displayId == 0 && !primary)) throw new IllegalArgumentException("Will not redirect to the phone display");
        if (mode != 1 && mode != 5) throw new IllegalArgumentException("Unsupported windowing mode");
        return new String[]{"/system/bin/am", "start", "--user", "current", "--display", Integer.toString(displayId),
                "--windowingMode", Integer.toString(mode), "-a", "android.intent.action.MAIN", "-n", component,
                "-f", "0x10200000"};
    }
    public static String[] backCommand(int displayId,Collection<Integer> available){
        return backCommand(displayId,available,false);
    }
    public static String[] backCommand(int displayId,Collection<Integer> available,boolean primary){
        requireTarget(displayId,available,primary);
        return new String[]{"/system/bin/input","-d",Integer.toString(displayId),"keyevent","4"};
    }
    public static List<String> recent(List<String> old, String component) {
        component(component);
        List<String> out = new ArrayList<>(); out.add(component);
        for (String item : old) if (!out.contains(item) && out.size() < 6) out.add(item);
        return out;
    }
}
