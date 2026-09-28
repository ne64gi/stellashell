package net.fuyumori.stellashell;

import java.util.*;

/** Pure routing/command policy, independently testable without Android. */
public final class Policy {
    private Policy() {}
    public static int selectDisplay(int preferred, Collection<Integer> available) {
        if (preferred > 0 && available.contains(preferred)) return preferred;
        return available.stream().filter(id -> id > 0).min(Integer::compareTo).orElse(-1);
    }
    public static void requireTarget(int displayId, Collection<Integer> available) {
        if (displayId <= 0 || !available.contains(displayId))
            throw new IllegalArgumentException("外部ディスプレイが切断されています");
    }
    public static void component(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z_.$][A-Za-z0-9_.$]*"))
            throw new IllegalArgumentException("起動するアプリの指定が不正です");
    }
    public static void setting(String value) {
        if (!Arrays.asList("0", "1", "null").contains(value))
            throw new IllegalArgumentException("未対応の設定値です");
    }
    public static String[] launchCommand(String component, int displayId, int mode) {
        component(component);
        if (displayId <= 0) throw new IllegalArgumentException("スマホ画面への転送は行いません");
        if (mode != 1 && mode != 5) throw new IllegalArgumentException("未対応のウィンドウモードです");
        return new String[]{"/system/bin/am", "start", "--user", "current", "--display", Integer.toString(displayId),
                "--windowingMode", Integer.toString(mode), "-a", "android.intent.action.MAIN", "-n", component,
                "-f", "0x10200000"};
    }
    public static List<String> recent(List<String> old, String component) {
        component(component);
        List<String> out = new ArrayList<>(); out.add(component);
        for (String item : old) if (!out.contains(item) && out.size() < 6) out.add(item);
        return out;
    }
}
