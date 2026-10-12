package net.fuyumori.stellashell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Compiler boundaries plus guards against rejoining features to the app's global state. */
public final class ModuleArchitectureFitnessTest {
    private static final Pattern DEPENDENCY=Pattern.compile(
            "(?:api|implementation|compileOnly|runtimeOnly)\\s+project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern IMPORT=Pattern.compile("(?m)^import\\s+(?:static\\s+)?([^;]+);");

    @Test public void moduleDependenciesPointTowardsDomainNotBackToApplication()throws Exception {
        Path root=root();
        assertEquals(set(),dependencies(root.resolve("core/build.gradle")));
        assertEquals(set(":core"),dependencies(root.resolve("features/search/build.gradle")));
        assertEquals(set(":core"),dependencies(root.resolve("features/launch/build.gradle")));
        assertEquals(set(":core"),dependencies(root.resolve("platform/bridge/build.gradle")));
        assertEquals(set(":core",":feature-search",":feature-launch",":platform-bridge"),dependencies(root.resolve("app/build.gradle")));
        assertTrue(read(root.resolve("core/build.gradle")).contains("java-library"));
        assertFalse(read(root.resolve("core/build.gradle")).contains("com.android"));
    }
    @Test public void reusableSourcesCannotReachAndroidOrAppImplementation()throws Exception {
        Path root=root();List<String> violations=new ArrayList<>();
        inspect(root.resolve("core/src/main/java"),"net.fuyumori.stellashell.core",true,violations);
        inspect(root.resolve("features/search/src/main/java"),"net.fuyumori.stellashell.feature.search",false,violations);
        inspect(root.resolve("features/launch/src/main/java"),"net.fuyumori.stellashell.feature.launch",false,violations);
        // Privileged Java/AIDL retain their legacy package for wire and Shizuku entry compatibility.
        inspect(root.resolve("platform/bridge/src/main/java"),"net.fuyumori.stellashell",false,violations);
        assertTrue(String.join("\n",violations),violations.isEmpty());
        for(String port:Arrays.asList("SearchSettings.java","StartSearchSession.java")){
            String source=read(root.resolve("features/search/src/main/java/net/fuyumori/stellashell/feature/search/"+port));
            Matcher imports=IMPORT.matcher(source);
            while(imports.find())assertFalse("Search port depends on Android: "+port,imports.group(1).startsWith("android."));
            assertFalse("Search port reaches concrete storage",source.contains("WebSearchSettings"));
        }
    }
    @Test public void settingsKeysAreOwnedByDomainAdaptersAndUiUsesCommands()throws Exception {
        Path root=root();List<String> violations=new ArrayList<>();
        for(String module:Arrays.asList("app","core","features/search","features/launch","platform/bridge")){
            Path directory=root.resolve(module+"/src/main/java");
            try(Stream<Path> files=Files.walk(directory)){
                files.filter(p->p.toString().endsWith(".java")).forEach(path->{
                    String relative=root.relativize(path).toString().replace('\\','/');
                    String source;
                    try{source=read(path);}catch(Exception error){throw new IllegalStateException(error);}
                    String transport="platform/bridge/src/main/java/net/fuyumori/stellashell/platform/power/ShizukuConnection.java";
                    if(!relative.equals(transport)&&source.contains("rikka.shizuku"))
                        violations.add(relative+" bypasses optional Power transport");
                    if(!relative.equals("app/src/main/java/net/fuyumori/stellashell/PowerMode.java")
                            &&Pattern.compile("(?:contains|getBoolean|putBoolean)\\s*\\(\\s*\"power_(?:mode|cleanup_pending)\"").matcher(source).find())
                        violations.add(relative+" bypasses PowerMode stored intent owner");
                    if(module.equals("platform/bridge")&&(source.contains("getSharedPreferences(")||
                            Pattern.compile("\\bR\\s*\\.").matcher(source).find()))
                        violations.add(relative+" couples privileged operations to app preferences/resources");
                    for(String key:Arrays.asList("web_search","launch_profiles")){
                        String owner=key.equals("web_search")
                                ?"features/search/src/main/java/net/fuyumori/stellashell/feature/search/WebSearchSettings.java"
                                :"app/src/main/java/net/fuyumori/stellashell/LaunchProfilePreferencesStore.java";
                        if(!relative.equals(owner)&&Pattern.compile("getSharedPreferences\\s*\\(\\s*\""+key+"\"").matcher(source).find())
                            violations.add(relative+" bypasses "+key+" owner");
                    }
                    if(Pattern.compile("\\bProfiles\\s*\\.\\s*save\\s*\\(").matcher(source).find())
                        violations.add(relative+" writes a stale whole profile instead of a field command");
                    if(!module.equals("features/launch")&&!relative.endsWith("/WorkspaceProfile.java")
                            &&Pattern.compile("(?:getString|putString)\\s*\\(\\s*\"(?:recent|pinned|phone_pinned|phone_taskbar_pinned|dock_pinned|desktop_shortcuts|phone_desktop_shortcuts)\"").matcher(source).find())
                        violations.add(relative+" bypasses LaunchItems storage owner");
                });
            }
        }
        assertTrue(String.join("\n",violations),violations.isEmpty());
        assertFalse("Application depends directly on optional Shizuku SDK",read(root.resolve("app/build.gradle")).contains("dev.rikka.shizuku"));
        assertFalse("Application manifest registers optional SDK implementation",read(root.resolve("app/src/main/AndroidManifest.xml")).contains("rikka.shizuku"));
        assertTrue("Optional transport must own SDK dependencies",read(root.resolve("platform/bridge/build.gradle")).contains("dev.rikka.shizuku:api"));
        String manifest=read(root.resolve("platform/bridge/src/main/AndroidManifest.xml"));
        assertTrue("Optional transport must own provider registration",manifest.contains("android:name=\"rikka.shizuku.ShizukuProvider\""));
        assertTrue("Provider authority must remain application-specific",manifest.contains("android:authorities=\"${applicationId}.shizuku\""));
        for(String ordinary:Arrays.asList("SetupActivity.java","PrerequisitesActivity.java","HomeActivity.java")){
            String source=read(root.resolve("app/src/main/java/net/fuyumori/stellashell/"+ordinary));
            assertFalse("Ordinary shell reaches privileged facade: "+ordinary,Pattern.compile("\\bBridge\\b").matcher(source).find());
            assertFalse("Ordinary shell reaches SDK: "+ordinary,source.contains("rikka.shizuku"));
        }
        String facade=read(root.resolve("app/src/main/java/net/fuyumori/stellashell/Launches.java"));
        for(String global:Arrays.asList("appQueue","appLaunching","appHandler","appDone","queryIntentActivities"))
            assertFalse("Launch facade regained an owner/PackageManager implementation: "+global,facade.contains(global));
        for(String legacy:Arrays.asList("Policy","AppLaunchProfile","WindowGeometry","SearchEngine","WebSearchSettings",
                "DesktopBridgeService","TaskBackend","FrameworkTaskAccess","TaskPins","MouseRouting",
                "VirtualKeyboardPolicy","PrimaryScreenPower","DesktopCapture","DisplaySessions","ScreenScaling"))
            assertFalse("Relocated type duplicated in app: "+legacy,
                    Files.exists(root.resolve("app/src/main/java/net/fuyumori/stellashell/"+legacy+".java")));
    }
    private static void inspect(Path directory,String namespace,boolean jdkOnly,List<String> violations)throws Exception {
        try(Stream<Path> files=Files.walk(directory)){
            for(Path path:(Iterable<Path>)files.filter(p->p.toString().endsWith(".java"))::iterator){
                String source=read(path);
                Matcher declaration=Pattern.compile("(?m)^package\\s+([^;]+);").matcher(source);
                if(!declaration.find()||!declaration.group(1).startsWith(namespace+"." )&&!declaration.group(1).equals(namespace))
                    violations.add(path+" escaped its module namespace");
                Matcher imports=IMPORT.matcher(source);
                while(imports.find()){
                    String dependency=imports.group(1);
                    boolean allowed=dependency.startsWith("java.")||dependency.startsWith("javax.")
                            ||dependency.startsWith("net.fuyumori.stellashell.core.")
                            ||!jdkOnly&&(dependency.startsWith("android.")||dependency.startsWith(namespace+".")
                            ||namespace.equals("net.fuyumori.stellashell")&&dependency.startsWith("org.json.")
                            ||path.toString().replace('\\','/').endsWith("/platform/power/ShizukuConnection.java")
                                &&dependency.equals("rikka.shizuku.Shizuku"));
                    if(!allowed)violations.add(path+" depends on "+dependency);
                }
            }
        }
    }
    private static Set<String> dependencies(Path file)throws Exception {
        Set<String> result=new HashSet<>();Matcher matcher=DEPENDENCY.matcher(read(file));
        while(matcher.find())result.add(matcher.group(1));return result;
    }
    private static Set<String> set(String...items){return new HashSet<>(Arrays.asList(items));}
    private static String read(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static Path root(){
        for(Path path=Paths.get("").toAbsolutePath();path!=null;path=path.getParent())
            if(Files.isRegularFile(path.resolve("settings.gradle"))&&Files.isDirectory(path.resolve("core")))return path;
        throw new IllegalStateException("Cannot locate StellaShell modules");
    }
}
