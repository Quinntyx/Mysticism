package io.github.mysticism.landmark;

import java.io.*;
import java.util.*;
import org.objectweb.asm.*;

/** Startup-order integration contract: actual column surveys arrive before the source
 * session exists, then SERVER_STARTED consumes them without any unload/reload or player.
 * The overflow companion additionally prepares/publishes/cold-reloads this event path.
 * Inspect the compiled Fabric registration adapters too: merely testing a queue would
 * miss a null-Session guard placed before the capture, which was the reported defect. */
public final class GenerationSurveyStartupTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private record Server(String name){}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static GenerationSurvey.ColumnView columns(int[] height){
        return new GenerationSurvey.ColumnView(){
            public int height(int x,int z){return height[0];}
            public String biome(int x,int z,int y){return x<8?"minecraft:forest":"minecraft:desert";}
        };
    }
    private static void spawnLoadsBeforeSessionAreRetained(){
        var lifecycle=new GenerationSurveyLifecycle<Server>();var server=new Server("integrated");
        int[] height={64};var actual=columns(height);
        lifecycle.loaded(server,DIM,0,0,-64,320,actual);
        lifecycle.loaded(server,DIM,1,0,-64,320,actual);
        check(lifecycle.servers()==1,"early chunk loads retain server-scoped detached work without a Session");
        height[0]=120; // prove the live chunk view is consumed during CHUNK_LOAD, not retained for startup
        var sessionQueue=lifecycle.started(server);
        check(sessionQueue.chunks()==2&&sessionQueue.pending()==4,"SERVER_STARTED inherits both biome components in both already-loaded spawn chunks");
        check(sessionQueue.next(false).isEmpty(),"startup must not dispatch before model/index readiness");
        check(lifecycle.started(server)==sessionQueue,"session initialization never replaces the early generation queue");
        Set<Integer> chunks=new HashSet<>(),components=new HashSet<>();int attempts=0;
        while(sessionQueue.pending()>0){
            var a=sessionQueue.next(true).orElseThrow();
            check(a.position().getY()==65,"retained probe belongs to actual early source columns, not a later synthetic resurvey");
            chunks.add(a.chunk().x());components.add(Math.floorMod(a.position().getX(),16)<8?0:1);
            sessionQueue.completed(a,true);check(++attempts<=4,"startup debt drains without new CHUNK_LOAD or player hints");
        }
        check(chunks.equals(Set.of(0,1))&&components.equals(Set.of(0,1)),"both center and non-center spawn terrain components dispatch");
        lifecycle.loaded(server,DIM,2,0,-64,320,actual);
        check(sessionQueue.pending()==2,"post-start chunk loads feed the same active queue");
        lifecycle.stopped(server);
        check(sessionQueue.pending()==0&&lifecycle.servers()==0,"stop clears active queue and server identity");
    }
    private static void earlyUnloadAndFailedStartupDoNotLeak(){
        var lifecycle=new GenerationSurveyLifecycle<Server>();Server server=new Server("same"),other=new Server("same");
        lifecycle.unloaded(server,DIM,0,0);lifecycle.unloaded(server,DIM);lifecycle.stopped(server);
        check(lifecycle.servers()==0,"unload/stop before any load cannot create a retained server");
        var poison=new GenerationSurvey.ColumnView(){
            public int height(int x,int z){throw new AssertionError("projected terrain must never be surveyed");}
            public String biome(int x,int z,int y){throw new AssertionError();}
        };
        lifecycle.loaded(server,"mysticism:spirit",0,0,-64,320,poison);
        check(lifecycle.servers()==0,"destination projection does not create early generation work");
        var actual=columns(new int[]{64});
        lifecycle.loaded(server,DIM,0,0,-64,320,actual);
        lifecycle.loaded(server,DIM,1,0,-64,320,actual);
        lifecycle.loaded(server,"minecraft:the_nether",0,0,0,256,actual);
        lifecycle.loaded(other,DIM,0,0,-64,320,actual);
        lifecycle.unloaded(server,DIM,0,0);lifecycle.unloaded(server,"minecraft:the_nether");
        var inherited=lifecycle.started(server);
        check(inherited.pending()==2&&inherited.chunks()==1,"pre-start chunk/dimension unload removes only no-longer-resident probes");
        check(inherited.next(true).orElseThrow().chunk().x()==1,"already-unloaded spawn chunk cannot become startup work");
        check(lifecycle.started(other).pending()==2&&lifecycle.servers()==2,"distinct server identities cannot share work even with equal keys");
        lifecycle.stopped(server);lifecycle.stopped(server);
        check(inherited.pending()==0&&lifecycle.servers()==1,"stop is idempotent and does not clear another server");
        lifecycle.stopped(other);
        lifecycle.loaded(server,DIM,0,0,-64,320,actual); // setupServer fails, no SERVER_STARTED at all
        lifecycle.stopped(server);
        var restart=lifecycle.started(server);
        check(restart!=inherited&&restart.pending()==0,"failed-startup cleanup leaves no generation debt for restart");
        lifecycle.stopped(server);check(lifecycle.servers()==0,"all server references released");
    }
    private static void compiledFabricAdaptersCaptureBeforeSessionGuards()throws IOException{
        Map<String,List<String>> methods=new HashMap<>();Map<String,String> bindings=new HashMap<>();
        String source="io/github/mysticism/landmark/SourceLandmarks";
        try(InputStream in=SourceLandmarks.class.getResourceAsStream("SourceLandmarks.class")){
            if(in==null)throw new AssertionError("missing compiled production event adapter");
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    List<String> calls=new ArrayList<>();methods.put(name,calls);
                    return new MethodVisitor(Opcodes.ASM9){
                        String event;
                        @Override public void visitFieldInsn(int opcode,String owner,String field,String desc){
                            if(owner.equals(source)&&field.equals("SESSIONS"))calls.add("sessions");
                            if(name.equals("init")&&opcode==Opcodes.GETSTATIC&&owner.startsWith("net/fabricmc/fabric/api/event/lifecycle/v1/"))event=field;
                        }
                        @Override public void visitInvokeDynamicInsn(String name,String desc,Handle bootstrap,Object... args){
                            if(event!=null)for(Object arg:args)if(arg instanceof Handle h&&h.getOwner().equals(source))bindings.put(event,h.getName());
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String call,String desc,boolean itf){
                            if(owner.equals(source)&&call.equals("survey"))calls.add("capture");
                            if(owner.endsWith("/GenerationSurveyLifecycle"))calls.add(call);
                            if(owner.equals(source+"$Session")&&call.equals("<init>"))calls.add("session-constructor");
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        var load=methods.get(bindings.get("CHUNK_LOAD"));
        check(load!=null&&load.indexOf("capture")>=0&&load.indexOf("sessions")>load.indexOf("capture"),"registered Fabric CHUNK_LOAD must survey before checking for a null Session");
        var start=methods.get(bindings.get("SERVER_STARTED"));
        check(start!=null&&start.indexOf("started")>=0&&start.indexOf("session-constructor")>start.indexOf("started"),"registered SERVER_STARTED must construct Session with the retained lifecycle queue");
        var unload=methods.get(bindings.get("CHUNK_UNLOAD"));
        check(unload!=null&&unload.indexOf("unloaded")>=0&&unload.indexOf("sessions")>unload.indexOf("unloaded"),"registered CHUNK_UNLOAD must release early work even without a Session");
        var worldUnload=methods.get(bindings.get("UNLOAD"));
        check(worldUnload!=null&&worldUnload.indexOf("unloaded")>=0&&worldUnload.indexOf("sessions")>worldUnload.indexOf("unloaded"),"registered world UNLOAD must release early dimension work before Session lookup");
        check("stopped".equals(bindings.get("SERVER_STOPPING"))&&"stopped".equals(bindings.get("SERVER_STOPPED")),"both stop events use idempotent cleanup, including failed startup");
        var stop=methods.get("stopped");
        check(stop!=null&&stop.indexOf("stopped")>=0&&stop.indexOf("sessions")>stop.indexOf("stopped"),"stop cleanup must release early generation queues without requiring a Session");
    }
    public static void main(String[] args)throws Exception{
        compiledFabricAdaptersCaptureBeforeSessionGuards();spawnLoadsBeforeSessionAreRetained();earlyUnloadAndFailedStartupDoNotLeak();
        System.out.println("GenerationSurveyStartupTest passed: "+checks+" checks");
    }
}
