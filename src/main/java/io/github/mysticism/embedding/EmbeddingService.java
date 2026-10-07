package io.github.mysticism.embedding;

import com.google.gson.*;
import io.github.mysticism.vector.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Official Ollama /api/tags and /api/embed protocol, including compatible deployments.
 * Only reads administrator-provisioned models. No pull, zoo, fallback, or software installer.
 */
public final class EmbeddingService implements EmbeddingProvider {
    private static final int MAX_RESPONSE_BYTES = 131072;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;
    private volatile boolean closed;
    public EmbeddingService() {
        this(URI.create(System.getProperty("mysticism.embedding.endpoint", "http://127.0.0.1:11434/")),
            Duration.ofMillis(Long.getLong("mysticism.embedding.connectTimeoutMillis", 2000)),
            Duration.ofMillis(Long.getLong("mysticism.embedding.requestTimeoutMillis", 10000)));
    }
    public EmbeddingService(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        if (!Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null)
            throw new IllegalArgumentException("Expected HTTP(S) Ollama base URL without credentials/query");
        if (connectTimeout.toMillis()<1 || connectTimeout.toMillis()>120000 || requestTimeout.toMillis()<1 || requestTimeout.toMillis()>120000)
            throw new IllegalArgumentException("Timeouts must be positive; requests at most 120 seconds");
        if (!EmbeddingSpace.REVISION.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Model revision must be full SHA256 Ollama manifest digest");
        this.endpoint=URI.create(endpoint.toString().replaceAll("/+$", "")+"/");this.timeout=requestTimeout;
        this.client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
    public void checkReady() throws Exception { verifyRevision(); infer("readiness probe"); verifyRevision(); }
    private static String canonicalModel(String model) { return model.contains(":")?model:model+":latest"; }
    private void verifyRevision() throws Exception {
        JsonObject response=request("api/tags",null);
        JsonArray models=response.getAsJsonArray("models");
        if(models==null)throw new IllegalStateException("Ollama /api/tags missing models");
        for(JsonElement entry:models) {
            JsonObject model=entry.getAsJsonObject();
            if(model.has("name") && canonicalModel(model.get("name").getAsString()).equals(canonicalModel(EmbeddingSpace.MODEL))) {
                String digest=model.get("digest").getAsString().replaceFirst("^sha256:", "");
                if(!EmbeddingSpace.REVISION.equals(digest))throw new IllegalStateException("Ollama model revision mismatch; refusing mixed embeddings");
                return;
            }
        }
        throw new IllegalStateException("Ollama model not provisioned: "+EmbeddingSpace.MODEL+" (administrator must provision explicitly)");
    }
    public Vec384f getEmbedding(String descriptor) throws Exception {
        verifyRevision();
        Vec384f vector=infer(descriptor);
        verifyRevision(); // Do not publish vectors if a deployment changed its tag during inference.
        return vector;
    }
    private Vec384f infer(String descriptor) throws Exception {
        if(descriptor==null || descriptor.isBlank() || descriptor.length()>8192)throw new IllegalArgumentException("Descriptor must be nonempty and at most 8192 characters");
        JsonObject body=new JsonObject();body.addProperty("model",EmbeddingSpace.MODEL);
        body.addProperty("input","search_document: "+descriptor);
        body.addProperty("truncate",false); // 512-token overflow must be explicit, not silently changed semantics
        JsonObject response=request("api/embed",body.toString());
        if(!response.has("model") || !canonicalModel(response.get("model").getAsString()).equals(canonicalModel(EmbeddingSpace.MODEL)))
            throw new IllegalStateException("Ollama response model mismatch");
        return decodeEmbedding(response);
    }
    /** Approved native v2 profile: retain all 768 coordinates, L2; no old vector resizing. */
    public static Vec384f decodeEmbedding(JsonObject response) {
        JsonArray batch=response.getAsJsonArray("embeddings");
        if(batch==null || batch.size()!=1)throw new IllegalArgumentException("Expected exactly one embedding");
        JsonArray values=batch.get(0).getAsJsonArray();
        if(values.size()!=EmbeddingSpace.NATIVE_DIMENSIONS)throw new IllegalArgumentException("Expected native Nomic v2 768 dimensions, got "+values.size());
        if(EmbeddingSpace.DIMENSIONS!=EmbeddingSpace.NATIVE_DIMENSIONS)throw new IllegalStateException("Parent must activate native Nomic v2 dimensions/profile before inference");
        float[] reduced=new float[EmbeddingSpace.NATIVE_DIMENSIONS];
        for(int i=0;i<values.size();i++) {
            JsonElement value=values.get(i);
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Non-numeric embedding");
            double number=value.getAsDouble();
            if(!Double.isFinite(number) || Math.abs(number)>Float.MAX_VALUE)throw new IllegalArgumentException("Nonfinite embedding");
            reduced[i]=(float)number;
        }
        Vec384f vector=new Vec384f(reduced);
        if(vector.length()==0)throw new IllegalArgumentException("Zero embedding");
        return new Vec384f(vector.norm());
    }
    private JsonObject request(String path,String body) throws Exception {
        if(closed)throw new IllegalStateException("Embedding provider closed");
        HttpRequest.Builder request=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout).header("Accept","application/json");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(request.build(), info->new LimitedBody());
        try {
            HttpResponse<byte[]> response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200)throw new IllegalStateException("Ollama HTTP "+response.statusCode()+" for "+path);
            JsonElement parsed=JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8));
            if(!parsed.isJsonObject())throw new IllegalArgumentException("Expected JSON response object");
            return parsed.getAsJsonObject();
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        finally { if(!future.isDone())future.cancel(true); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){
                if(buffer.remaining()>MAX_RESPONSE_BYTES-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Oversized Ollama response"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    public void close(){closed=true;client.shutdownNow();}
}
