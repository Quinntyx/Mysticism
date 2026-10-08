package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.LandmarkExtractionService;
import net.minecraft.server.MinecraftServer;
import java.io.*;
import java.util.*;

/** Newly published landmarks must announce their availability to runtime consumers (terrain
 * selection/navigation) at the moment Ensure commits, instead of being discoverable only by a
 * later full catalog sweep. Fires on the real extraction topology event contract. */
public final class LandmarkPublicationAvailabilityTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private record Notice(MinecraftServer server,List<String> previous,List<String> replacement) {}
    private static final class Recorder implements LandmarkExtractionService.TopologyListener {
        final List<Notice> notices=new ArrayList<>();
        public void committed(MinecraftServer server,List<String> previous,List<String> replacement){notices.add(new Notice(server,previous,replacement));}
    }

    private static void notifiesOnPublication() {
        Recorder recorder=new Recorder();
        var event=LandmarkExtractionService.COMMITTED_TOPOLOGY;
        event.register(recorder);
        try {
            SourceLandmarks.notifyCommitted(null,List.of("lm-"+"a".repeat(64)),List.of("lm-"+"b".repeat(64)));
            check(recorder.notices.size()==1,"publication notifies availability listeners");
            var notice=recorder.notices.getFirst();
            check(notice.previous().equals(List.of("lm-"+"a".repeat(64))),"notification carries the pre-publication identity");
            check(notice.replacement().equals(List.of("lm-"+"b".repeat(64))),"notification carries the canonical live identity");
            check(notice.server()==null,"notification passes the publishing server through");
            SourceLandmarks.notifyCommitted(null,List.of(),List.of());
            check(recorder.notices.size()==1,"an empty replacement never notifies");
            SourceLandmarks.notifyCommitted(null,List.of(),null);
            check(recorder.notices.size()==1,"a null replacement never notifies");
        } finally { /* Listener stays registered for this JVM; the recording list bounds its effect. */ }
    }

    /** The real Ensure publication path must call the notifier; a test-only duplicate would not
     * make discovered landmarks available in production. Checked on the compiled class. */
    private static void ensurePublicationIsWired() throws Exception {
        String className=SourceLandmarks.class.getName()+"$Ensure";
        byte[] bytes;
        try(var in=SourceLandmarks.class.getClassLoader().getResourceAsStream(className.replace('.','/')+".class")){
            check(in!=null,"compiled Ensure class is on the runtime classpath");
            bytes=in.readAllBytes();
        }
        String constantPool=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
        check(constantPool.contains("notifyCommitted"),"Ensure publication invokes the availability notifier");
        check(constantPool.contains("LandmarkExtractionService"),"notification uses the shared extraction topology event");
    }

    public static void main(String[] args) throws Exception {
        notifiesOnPublication();
        ensurePublicationIsWired();
        System.out.println("PASS landmark publication availability: "+checks+" checks");
    }
}
