package net.fuyumori.stellashell.core.display;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure density parsing and fail-closed, single-display scale transaction. */
public final class ScreenScalePolicy {
    private ScreenScalePolicy(){}
    public static final int MIN=50,MAX=200,DEFAULT=100;
    private static final Pattern LINE=Pattern.compile("^(Physical|Override) density:\\s*([0-9]+)$");
    public static final class Density {
        public final int physical,override;
        public Density(int physical,int override){
            validDensity(physical);if(override!=0)validDensity(override);this.physical=physical;this.override=override;
        }
        public int current(){return override==0?physical:override;}
        public int percent(){return (int)((current()*100L+physical/2L)/physical);}
        @Override public boolean equals(Object other){return other instanceof Density&&physical==((Density)other).physical&&override==((Density)other).override;}
        @Override public int hashCode(){return Objects.hash(physical,override);}
    }
    public static final class State {
        public final String identity;public final Density density;
        public State(String identity,Density density){
            if(identity==null||identity.isEmpty()||density==null)throw new IllegalArgumentException("Screen identity unavailable");
            this.identity=identity;this.density=density;
        }
    }
    public interface Store {
        State read(int display)throws Exception;
        /** Override 0 means reset; implementations must retain the explicit target. */
        void write(int display,int override)throws Exception;
    }
    private static void validDensity(int density){if(density<72||density>10000)throw new IllegalArgumentException("Unsupported screen density");}
    static int percent(int value){if(value<MIN||value>MAX)throw new IllegalArgumentException("Screen scale must be 50–200 percent");return value;}
    public static int targetDensity(int physical,int percent){
        validDensity(physical);percent(percent);int target=(int)((physical*(long)percent+50)/100);validDensity(target);return target;
    }
    static Density parse(String output){
        if(output==null)throw new IllegalArgumentException("Screen density unavailable");Integer physical=null,override=null;
        for(String raw:output.split("\\r?\\n")){
            String line=raw.trim();if(line.isEmpty())continue;Matcher match=LINE.matcher(line);
            if(!match.matches())throw new IllegalArgumentException("Unrecognized screen density response");
            int value;try{value=Integer.parseInt(match.group(2));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid screen density",e);}
            if("Physical".equals(match.group(1))){if(physical!=null)throw new IllegalArgumentException("Duplicate physical density");physical=value;}
            else{if(override!=null)throw new IllegalArgumentException("Duplicate override density");override=value;}
        }
        if(physical==null)throw new IllegalArgumentException("Physical density unavailable");return new Density(physical,override==null?0:override);
    }
    public static String[] command(int display,int override){
        if(display<0)throw new IllegalArgumentException("Invalid screen");if(override!=0)validDensity(override);
        return new String[]{"/system/bin/wm","density",override==0?"reset":Integer.toString(override),"-d",Integer.toString(display)};
    }
    private static void sameTarget(State expected,State actual){if(!expected.identity.equals(actual.identity))throw new IllegalStateException("Screen changed or disconnected");}
    public static State apply(Store store,int display,State before,int percent)throws Exception{
        if(display<0||before==null)throw new IllegalArgumentException("Invalid screen snapshot");
        int target=targetDensity(before.density.physical,percent),override=percent==DEFAULT?0:target;
        State fresh=store.read(display);sameTarget(before,fresh);
        if(!before.density.equals(fresh.density))throw new IllegalStateException("Screen density changed; refresh before applying");
        try{
            store.write(display,override);State after=store.read(display);sameTarget(before,after);
            if(after.density.physical!=before.density.physical||after.density.current()!=target||after.density.override!=override)
                throw new IllegalStateException("Screen density change could not be verified");
            return after;
        }catch(Exception failure){
            try{
                // Never restore a disconnected/replaced target, and never fall back to display 0.
                State current=store.read(display);sameTarget(before,current);
                store.write(display,before.density.override);State restored=store.read(display);sameTarget(before,restored);
                if(!before.density.equals(restored.density))throw new IllegalStateException("Previous screen density could not be verified");
            }catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
}
