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
        assertEquals(set(":core"),dependencies(root.resolve("platform/bridge/build.gradle")));
        assertEquals(set(":core",":feature-search",":platform-bridge"),dependencies(root.resolve("app/build.gradle")));
        assertTrue(read(root.resolve("core/build.gradle")).contains("java-library"));
        assertFalse(read(root.resolve("core/build.gradle")).contains("com.android"));
    }
    @Test public void reusableSourcesCannotReachAndroidOrAppImplementation()throws Exception {
        Path root=root();List<String> violations=new ArrayList<>();
        inspect(root.resolve("core/src/main/java"),"net.fuyumori.stellashell.core",true,violations);
        inspect(root.resolve("features/search/src/main/java"),"net.fuyumori.stellashell.feature.search",false,violations);
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
        for(String module:Arrays.asList("app","core","features/search","platform/bridge")){
            Path directory=root.resolve(module+"/src/main/java");
            try(Stream<Path> files=Files.walk(directory)){
                files.filter(p->p.toString().endsWith(".java")).forEach(path->{
                    String relative=root.relativize(path).toString().replace('\\','/');
                    String source;
                    try{source=read(path);}catch(Exception error){throw new IllegalStateException(error);}
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
                });
            }
        }
        assertTrue(String.join("\n",violations),violations.isEmpty());
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
                            ||namespace.equals("net.fuyumori.stellashell")&&dependency.startsWith("org.json."));
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
