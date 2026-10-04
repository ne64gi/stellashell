package net.fuyumori.stellashell;


import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.junit.Assert.assertTrue;

/** Guards production call sites from bypassing the process-owned state facades. */
public final class StateOwnershipFitnessTest {
    private static final String[] SETTINGS_KEYS = {
            "phone_sidebar", "phone_sidebar_over_apps", "phone_sidebar_side", "sidebar_height",
            "phone_dock_scale", "desktop_dock", "dock_edge", "dock_x", "dock_y",
            "desktop_dock_scale", "phone_taskbar", "phone_taskbar_scale",
            "desktop_taskbar_scale", "shell_layout", "compact_workspace", "workspace_auto",
            "primary_mode", "preferred_display"
    };
    private static final String[] ACCESSORS = {
            "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet",
            "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet",
            "contains", "remove"
    };
    private static final Pattern TASK_SESSION = Pattern.compile("\\bTaskSession\\b");
    private static final Pattern WORKSPACE_CALL = Pattern.compile("\\bWorkspace\\s*\\.\\s*[A-Za-z_$][\\w$]*\\s*\\(");
    private static final Pattern DOCK_SERVICE_CALL = Pattern.compile(
            "\\bDockService\\s*\\.\\s*(?!class\\b)[A-Za-z_$][\\w$]*\\s*\\(");
    private static final Pattern PREFERENCE_RECEIVER = Pattern.compile(
            "(?:Launches\\s*\\.\\s*prefs\\s*\\([^)]*\\)|\\b(?:prefs|preferences|sharedPreferences|p|editor|e)\\b)"
                    + "(?:\\s*\\.\\s*edit\\s*\\(\\s*\\))?\\s*\\.\\s*");

    @Test public void productionConsumersUseRuntimeSettingsAndTaskOwners() throws Exception {
        Path sourceRoot = productionSourceRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                String source;
                try { source = stripComments(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)); }
                catch (IOException error) { throw new IllegalStateException("Cannot read " + path, error); }

                checkTaskSessionOwnership(relative, source, violations);
                checkOwnerFacadeCalls(relative, source, violations);
                checkOwnedPreferenceKeys(relative, source, violations);
                if(!relative.equals("WebSearchSettings.java")&&Pattern.compile("getSharedPreferences\\s*\\(\\s*\"web_search\"").matcher(source).find())
                    violations.add(relative+" opens search preferences outside WebSearchSettings");
            });
        }
        assertTrue("Production state ownership bypasses:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    private static void checkTaskSessionOwnership(String relative, String source,
            List<String> violations) {
        if (relative.equals("TaskState.java") || relative.equals("TaskSession.java")) return;
        Matcher matcher = TASK_SESSION.matcher(source);
        while (matcher.find()) violations.add(location(relative, source, matcher.start())
                + " references internal TaskSession outside TaskState/TaskSession");
    }

    private static void checkOwnerFacadeCalls(String relative, String source,
            List<String> violations) {
        if (!relative.equals("Workspace.java") && !relative.equals("TaskState.java")) {
            Matcher workspace = WORKSPACE_CALL.matcher(source);
            while (workspace.find()) violations.add(location(relative, source, workspace.start())
                    + " calls Workspace state directly; use TaskState.of(context)");
        }
        if (!relative.equals("DockService.java") && !relative.equals("ShellRuntime.java")) {
            Matcher service = DOCK_SERVICE_CALL.matcher(source);
            while (service.find()) violations.add(location(relative, source, service.start())
                    + " calls DockService directly; use ShellRuntime");
        }
    }

    private static void checkOwnedPreferenceKeys(String relative, String source,
            List<String> violations) {
        for (String key : SETTINGS_KEYS) {
            if (relative.equals("ShellSettings.java")) continue;
            checkPreferenceKey(relative, source, key, violations);
        }
        if (!relative.equals("ShellRuntime.java")) {
            checkPreferenceKey(relative, source, "enabled", violations);
            checkPreferenceKey(relative, source, "active_display", violations);
        }
        if (!relative.equals("TaskState.java") && !relative.equals("Workspace.java")) {
            checkPreferenceKey(relative, source, "workspace_display", violations);
        }
    }

    private static void checkPreferenceKey(String relative, String source, String key,
            List<String> violations) {
        String literal = Pattern.quote(key);
        String accessorNames = String.join("|", ACCESSORS);
        Pattern accessor = Pattern.compile(PREFERENCE_RECEIVER.pattern() + "(?:"
                + accessorNames + ")\\s*\\(\\s*\"" + literal + "\"\\s*[,)]");
        Matcher use = accessor.matcher(source);
        while (use.find()) {
            violations.add(location(relative, source, use.start())
                    + " accesses owner key '" + key + "' outside its owner");
        }

        Pattern keyObserver = Pattern.compile("\"" + literal
                + "\"\\s*\\.\\s*equals\\s*\\(\\s*key\\s*\\)|\\bkey\\s*\\.\\s*equals\\s*\\(\\s*\""
                + literal + "\"\\s*\\)");
        Matcher observed = keyObserver.matcher(source);
        while (observed.find()) violations.add(location(relative, source, observed.start())
                + " observes owner key '" + key + "' by raw SharedPreferences key");
    }

    private static Path productionSourceRoot() {
        Path working = Paths.get("").toAbsolutePath().normalize();
        for (Path current = working; current != null; current = current.getParent()) {
            Path appModule = current.resolve("app/src/main/java/net/fuyumori/stellashell");
            if (Files.isDirectory(appModule)) return appModule;
            Path module = current.resolve("src/main/java/net/fuyumori/stellashell");
            if (Files.isDirectory(module)) return module;
        }
        throw new IllegalStateException("Could not locate production Java sources from " + working);
    }

    private static String location(String relative, String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) if (source.charAt(index) == '\n') line++;
        return relative + ":" + line;
    }

    private static String stripComments(String source) {
        StringBuilder clean = new StringBuilder(source.length());
        boolean string = false, character = false, lineComment = false, blockComment = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n') { lineComment = false; clean.append('\n'); }
                else clean.append(' ');
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') { clean.append("  "); i++; blockComment = false; }
                else clean.append(current == '\n' ? '\n' : ' ');
                continue;
            }
            if (string || character) {
                clean.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if ((string && current == '"') || (character && current == '\'')) {
                    string = false;
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') { clean.append("  "); i++; lineComment = true; }
            else if (current == '/' && next == '*') { clean.append("  "); i++; blockComment = true; }
            else {
                clean.append(current);
                if (current == '"') string = true;
                else if (current == '\'') character = true;
            }
        }
        return clean.toString();
    }
}
