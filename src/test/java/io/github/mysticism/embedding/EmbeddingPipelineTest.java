package io.github.mysticism.embedding;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import io.github.mysticism.vector.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic standalone regression suite, no test framework or new dependencies. */
public final class EmbeddingPipelineTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void near(float actual,float expected){check(Math.abs(actual-expected)<1e-5,"Expected "+expected+", got "+actual);}
    private interface Throwing{void run() throws Exception;}
    private static void rejects(Throwing action){assertions++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("Expected rejection");}
    private static Vec384f axis(int index){float[] a=new float[EmbeddingSpace.DIMENSIONS];a[index]=1;return new Vec384f(a);}
    private static <T> T get(CompletableFuture<T> future)throws Exception{return future.get(2,TimeUnit.SECONDS);}
    private static void failed(CompletableFuture<?> future)throws Exception{assertions++;try{get(future);}catch(ExecutionException|CancellationException expected){return;}throw new AssertionError("Future did not fail");}
    private static void vectors(){
        float[] input=new float[EmbeddingSpace.DIMENSIONS];input[0]=3;input[1]=4;Vec384f v=new Vec384f(input);input[0]=99;
        near(v.length(),5);near(v.norm()[0],.6f);v.norm()[0]=99;v.data()[0]=99;near(v.norm()[0],.6f);
        v.mul(-2);near(v.norm()[0],-.6f);v.mul(0);check(Arrays.equals(v.norm(),new float[EmbeddingSpace.DIMENSIONS]),"Zero must clear normalized cache");
        Vec384f copy=axis(0).clone();copy.add(axis(1));near(copy.length(),(float)Math.sqrt(2));near(Vec384f.fromBits(copy.toBits()).squareDistance(copy),0);
        Basis384f defaults=new Basis384f();near(defaults.i.dot(axis(0)),1);near(defaults.j.dot(axis(1)),1);near(defaults.k.dot(axis(2)),1);
        Vec384f original=axis(0);Basis384f basis=new Basis384f(original,axis(1),axis(2));original.mul(0);near(basis.i.length(),1);
        check(basis.toBits().length==3*EmbeddingSpace.DIMENSIONS,"Basis uses central dimensions");near(Basis384f.fromBits(basis.toBits()).k.dot(axis(2)),1);
        rejects(()->new Vec384f(new float[384]));rejects(()->Vec384f.fromBits(new int[384]));rejects(()->Basis384f.fromBits(new int[1152]));
        int[] bits=new int[EmbeddingSpace.DIMENSIONS];bits[3]=Float.floatToIntBits(Float.NaN);rejects(()->Vec384f.fromBits(bits));
        float[] bad=new float[EmbeddingSpace.DIMENSIONS];bad[0]=Float.POSITIVE_INFINITY;rejects(()->new Vec384f(bad));
        rejects(()->axis(0).add(new Vec384f(axis(0).data(),"other-profile")));rejects(()->axis(0).mul(Float.NaN));rejects(()->axis(0).converge(axis(1),Float.NaN));
        near(axis(0).converge(axis(1),4).dot(axis(1)),1);near(axis(0).converge(axis(1),-4).dot(axis(0)),1);
        Vec384f big=axis(0).mul(Float.MAX_VALUE);rejects(()->big.mul(2));near(big.norm()[0],1);
    }
    private static void movement(){
        Basis384f basis=new Basis384f(axis(0),axis(1),axis(2));
        check(BasisIntegrator384f.step(basis,axis(0).mul(-1),1,0,0,1),"Antiparallel rotation converges");
        near(basis.i.dot(axis(0)),-1);near(basis.i.length(),1);near(basis.j.length(),1);near(basis.i.dot(basis.j),0);
        Basis384f toward=new Basis384f(axis(0),axis(1),axis(2));
        check(BasisIntegrator384f.step(toward,axis(0),axis(0).add(axis(1)),1,0,0,1),"Target-minus-current API");near(toward.i.dot(axis(1)),1);
        check(!BasisIntegrator384f.step(toward,axis(1),0,0,0,1),"Idle basis stable");
        rejects(()->BasisIntegrator384f.step(toward,axis(1),Double.NaN,0,0,1));
    }
    private static void index(){
        SimpleKnnIndex one=new SimpleKnnIndex(),two=new SimpleKnnIndex();
        for(String id:List.of("z","a","m"))one.upsert(id,axis(0));for(String id:List.of("m","a","z"))two.upsert(id,axis(0));
        for(Metric metric:Metric.values()){
            var a=one.kNN(2,axis(0),metric);var b=two.kNN(2,axis(0),metric);
            check(a.stream().map(p->p.getKey()).toList().equals(List.of("a","m")),"KNN stable ties");check(a.equals(b),"Insertion-independent KNN");
            for(int count:List.of(1,2,8)){
                var results=one.kNN(count,axis(0),metric);
                // Exact comparator used by the existing item/spatial command callers.
                results.sort((x,y)->Float.compare(y.getValue(),x.getValue()));
                var expected=List.of("a","m","z").subList(0,Math.min(count,3));
                check(results.stream().map(p->p.getKey()).toList().equals(expected),"Command-compatible mutable KNN results");
                results.clear();
                check(one.kNN(count,axis(0),metric).stream().map(p->p.getKey()).toList().equals(expected),"Caller mutation cannot affect index or later results");
            }
        }
        check(one.kNN(0,axis(0),Metric.COSINE).isEmpty(),"Zero k");
        for(int count:List.of(0,-1)){
            var empty=one.kNN(count,axis(0),Metric.COSINE);
            empty.sort((x,y)->Float.compare(y.getValue(),x.getValue()));
            empty.addAll(one.kNN(1,axis(0),Metric.COSINE));
            check(empty.size()==1&&one.kNN(count,axis(0),Metric.COSINE).isEmpty(),"Nonpositive k returns independently owned mutable list");
        }
        SimpleKnnIndex emptyIndex=new SimpleKnnIndex();
        var missing=emptyIndex.kNN(1,axis(0),Metric.COSINE);
        missing.sort((x,y)->Float.compare(y.getValue(),x.getValue()));
        missing.addAll(one.kNN(1,axis(0),Metric.COSINE));
        check(emptyIndex.size()==0&&emptyIndex.kNN(1,axis(0),Metric.COSINE).isEmpty(),"Empty index result remains mutable and independent");
        Vec384f submitted=axis(1);one.upsert("copy",submitted);submitted.mul(0);near(one.get("copy").length(),1);
        one.get("copy").mul(0);one.forEach((id,v)->v.mul(0));near(one.get("copy").length(),1);
        rejects(()->one.upsert("foreign",new Vec384f(axis(0).data(),"foreign")));
        one.upsert("far",axis(0).mul(4));check(one.kNN(1,axis(0),Metric.EUCLIDEAN).get(0).getKey().equals("a"),"Squared-distance order");
        near(one.kNN(100,axis(0),Metric.EUCLIDEAN).get(4).getValue(),-9);
        near(one.kNN(1,axis(0),Metric.DOT).get(0).getValue(),4);
    }
    private static void descriptors(){
        String one=CanonicalDescriptors.block("minecraft:oak_log",List.of("minecraft:logs","minecraft:burnable"));
        check(one.equals(CanonicalDescriptors.block("minecraft:oak_log",List.of("minecraft:burnable","minecraft:logs","minecraft:logs"))),"Canonical tag ordering/dedup");
        check(one.contains("minecraft:oak_log")&&one.contains("minecraft oak log"),"ID plus human words");rejects(()->CanonicalDescriptors.item("bad id",List.of()));
        Vec384f input=axis(0);var first=new DescriptorVectors.WeightedVector(input,3);input.mul(0);first.vector().mul(0);
        Vec384f blended=DescriptorVectors.compose(List.of(first,new DescriptorVectors.WeightedVector(axis(1),4)));
        near(blended.data()[0],.6f);near(blended.data()[1],.8f);near(blended.length(),1);
        rejects(()->new DescriptorVectors.WeightedVector(axis(0),-1));rejects(()->new DescriptorVectors.WeightedDescriptor("x",Double.NaN));
        rejects(()->DescriptorVectors.compose(List.of()));rejects(()->DescriptorVectors.compose(List.of(new DescriptorVectors.WeightedVector(Vec384f.ZERO(),1))));
        rejects(()->DescriptorVectors.compose(List.of(new DescriptorVectors.WeightedVector(axis(0),1),new DescriptorVectors.WeightedVector(axis(0).mul(-1),1))));
        rejects(()->new DescriptorVectors.WeightedVector(new Vec384f(axis(0).data(),"foreign"),1));
    }
    private static JsonObject response(int dimensions){
        JsonArray values=new JsonArray();for(int i=0;i<dimensions;i++)values.add(i==0?3:i==1?4:0);
        JsonArray embeddings=new JsonArray();embeddings.add(values);JsonObject response=new JsonObject();response.add("embeddings",embeddings);response.addProperty("model",EmbeddingSpace.MODEL);return response;
    }
    private static void decode(){
        Vec384f vector=EmbeddingService.decodeEmbedding(response(EmbeddingSpace.NATIVE_DIMENSIONS));near(vector.data()[0],.6f);near(vector.data()[1],.8f);check(vector.data().length==EmbeddingSpace.DIMENSIONS&&EmbeddingSpace.DIMENSIONS==EmbeddingSpace.NATIVE_DIMENSIONS,"Native v2 coordinates are retained without Matryoshka reduction");
        rejects(()->EmbeddingService.decodeEmbedding(response(256)));rejects(()->EmbeddingService.decodeEmbedding(response(384)));rejects(()->EmbeddingService.decodeEmbedding(new JsonObject()));
        JsonObject r=response(EmbeddingSpace.NATIVE_DIMENSIONS);r.getAsJsonArray("embeddings").add(new JsonArray());rejects(()->EmbeddingService.decodeEmbedding(r));
        for(JsonElement invalid:List.of(JsonNull.INSTANCE,new JsonPrimitive("1"),new JsonPrimitive(Double.NaN),new JsonPrimitive(Double.POSITIVE_INFINITY),new JsonPrimitive(1e100))){
            JsonObject malformed=response(EmbeddingSpace.NATIVE_DIMENSIONS);malformed.getAsJsonArray("embeddings").get(0).getAsJsonArray().set(700,invalid);rejects(()->EmbeddingService.decodeEmbedding(malformed));
        }
        JsonObject tail=response(EmbeddingSpace.NATIVE_DIMENSIONS);JsonArray values=tail.getAsJsonArray("embeddings").get(0).getAsJsonArray();values.set(0,new JsonPrimitive(0));values.set(1,new JsonPrimitive(0));values.set(700,new JsonPrimitive(1));near(EmbeddingService.decodeEmbedding(tail).data()[700],1);check(EmbeddingService.decodeEmbedding(tail).length()==1,"Nonzero native tail coordinates cannot be truncated into a zero vector");values.set(700,new JsonPrimitive(0));rejects(()->EmbeddingService.decodeEmbedding(tail));
    }
    private static class Fake implements EmbeddingProvider {
        final AtomicInteger calls=new AtomicInteger();final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        boolean gate,failReady,failWork;volatile boolean closed;
        public EmbeddingProfile profile(){return EmbeddingProfile.current();}
        public void checkReady(){if(failReady)throw new IllegalStateException("not ready");}
        public Vec384f getEmbedding(String descriptor)throws Exception{calls.incrementAndGet();started.countDown();if(gate)release.await();if(failWork)throw new IllegalStateException("worker failure");return axis(0).mul(3);}
        public void close(){closed=true;release.countDown();}
    }
    private static AsyncEmbeddingEngine engine(Fake provider,int queue,Duration deadline){return new AsyncEmbeddingEngine(provider,1,queue,2,deadline);}
    private static void asynchronous()throws Exception{
        Fake f=new Fake();f.gate=true;
        try(var engine=engine(f,4,Duration.ofSeconds(1))){
            get(engine.readiness());var first=engine.embed("same");check(f.started.await(1,TimeUnit.SECONDS),"Worker started");var second=engine.embed("same");var cancelled=engine.embed("same");cancelled.cancel(true);f.release.countDown();
            Vec384f a=get(first),b=get(second);a.mul(0);near(b.length(),1);near(get(engine.embed("same")).length(),1);check(f.calls.get()==1,"Deduplicated cache, caller cancellation isolated");
            get(engine.embed("two"));get(engine.embed("three"));check(engine.cacheSize()==2,"Bounded LRU");get(engine.embed("same"));check(f.calls.get()==4,"Oldest cache entry evicted");
        }
        check(f.closed,"Provider closed");
        Fake broken=new Fake();broken.failWork=true;try(var e=engine(broken,4,Duration.ofSeconds(1))){get(e.readiness());failed(e.embed("fail"));check(e.inflightSize()==0,"Exception completes/removes promise");}
        Fake unhealthy=new Fake();unhealthy.failReady=true;try(var e=engine(unhealthy,4,Duration.ofSeconds(1))){failed(e.readiness());failed(e.embed("fail immediately"));check(!e.isReady(),"Health readiness required");}
        Fake queued=new Fake();queued.gate=true;try(var e=engine(queued,1,Duration.ofSeconds(1))){get(e.readiness());var a=e.embed("a");check(queued.started.await(1,TimeUnit.SECONDS),"Gate reached");var b=e.embed("b");failed(e.embed("overflow"));e.close();failed(a);failed(b);check(e.cacheSize()==0&&e.inflightSize()==0,"Shutdown clears outstanding/cache");failed(e.embed("after shutdown"));}
        Fake late=new Fake();late.gate=true;try(var e=engine(late,4,Duration.ofMillis(100))){get(e.readiness());var future=e.embed("timeout");check(late.started.await(1,TimeUnit.SECONDS),"Timeout started");failed(future);check(e.inflightSize()==0&&e.cacheSize()==0,"Deadline cleanup");late.release.countDown();}
        Fake foreign=new Fake(){@Override public EmbeddingProfile profile(){return new EmbeddingProfile("wrong","wrong",256,"wrong","wrong");}};
        rejects(()->engine(foreign,4,Duration.ofSeconds(1)));
    }
    private static void http()throws Exception{
        HttpServer stub=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        ExecutorService executor=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});stub.setExecutor(executor);
        AtomicReference<String> mode=new AtomicReference<>("ok");List<String> paths=Collections.synchronizedList(new ArrayList<>());AtomicReference<JsonObject> body=new AtomicReference<>();
        stub.createContext("/api/",exchange->{
            paths.add(exchange.getRequestURI().getPath());String state=mode.get();String text;int status=200;
            if(exchange.getRequestURI().getPath().equals("/api/tags")){
                text="{\"models\":[{\"name\":\""+EmbeddingSpace.MODEL+"\",\"digest\":\""+(state.equals("revision")?"0".repeat(64):EmbeddingSpace.REVISION)+"\"}]}";
                if(state.equals("missing"))text="{\"models\":[]}";
            }else{
                body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
                JsonObject r=response(EmbeddingSpace.NATIVE_DIMENSIONS);if(state.equals("model"))r.addProperty("model","another-model");text=r.toString();
                if(state.equals("changedDuring"))mode.set("revision");
                if(state.equals("malformed"))text="[broken";
                if(state.equals("oversized"))text=" ".repeat(131073);
                if(state.equals("status")){status=503;text="error";}
                if(state.equals("timeout"))try{Thread.sleep(800);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);try{exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });stub.start();
        URI endpoint=URI.create("http://127.0.0.1:"+stub.getAddress().getPort()+"/");
        try(var provider=new EmbeddingService(endpoint,Duration.ofMillis(200),Duration.ofSeconds(1))){
            provider.checkReady();near(provider.getEmbedding("oak log").length(),1);check(body.get().get("input").getAsString().equals("search_document: oak log"),"Documented v2 input prefix");check(!body.get().get("truncate").getAsBoolean(),"No silent context truncation");
            for(String failure:List.of("revision","missing","model","malformed","oversized","status","changedDuring")){mode.set(failure);rejects(()->provider.getEmbedding("x"));}
            check(paths.stream().allMatch(p->p.equals("/api/tags")||p.equals("/api/embed")),"No download/pull endpoints");
        }
        rejects(()->new EmbeddingService(endpoint,Duration.ofSeconds(121),Duration.ofSeconds(1)));
        rejects(()->new EmbeddingService(endpoint,Duration.ofSeconds(1),Duration.ofNanos(1)));
        mode.set("timeout");try(var provider=new EmbeddingService(endpoint,Duration.ofMillis(100),Duration.ofMillis(100))){rejects(()->provider.getEmbedding("timeout"));}
        mode.set("missing");try(var e=new AsyncEmbeddingEngine(new EmbeddingService(endpoint,Duration.ofMillis(200),Duration.ofMillis(500)),1,4,2,Duration.ofSeconds(1))){failed(e.readiness());failed(e.embed("unavailable"));}
        stub.stop(0);executor.shutdownNow();
        try(var provider=new EmbeddingService(endpoint,Duration.ofMillis(100),Duration.ofMillis(200))){rejects(provider::checkReady);provider.close();rejects(()->provider.getEmbedding("closed"));}
    }
    public static void main(String[] args)throws Exception{vectors();movement();index();descriptors();decode();asynchronous();http();System.out.println("PASS embedding pipeline: "+assertions+" assertions");}
}
