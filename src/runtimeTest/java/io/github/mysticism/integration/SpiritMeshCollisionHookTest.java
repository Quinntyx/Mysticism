package io.github.mysticism.integration;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Offline bytecode target/registration guards, NOT a claim that mixins were applied in a running game. */
public final class SpiritMeshCollisionHookTest {
    private static int hooks;
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
    private static ClassNode bytecode(String name) throws Exception {
        var stream=SpiritMeshCollisionHookTest.class.getClassLoader().getResourceAsStream(name+".class");
        check(stream!=null,"Missing class "+name);
        try(stream) {var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;}
    }
    private static Object value(AnnotationNode node,String key) {
        for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(key))return node.values.get(i+1);
        return null;
    }
    private static void verify(String config,String list,String mixin,String target) throws Exception {
        var loader=SpiritMeshCollisionHookTest.class.getClassLoader();
        try(var reader=new InputStreamReader(loader.getResourceAsStream(config),StandardCharsets.UTF_8)) {
            var json=JsonParser.parseReader(reader).getAsJsonObject();
            check(json.get("required").getAsBoolean(),"Collision hooks must be required");
            check(json.getAsJsonObject("injectors").get("defaultRequire").getAsInt()==1,"Missing hooks must fail");
            long registrations=json.getAsJsonArray(list).asList().stream().filter(e->e.getAsString().equals(mixin)).count();
            check(registrations==1,"Hook missing/duplicated in "+config+": "+mixin);
            var cls=bytecode(json.get("package").getAsString().replace('.','/')+"/"+mixin);
            var vanilla=bytecode(target);
            for(var method:cls.methods) {
                if(method.visibleAnnotations==null)continue;
                for(var annotation:method.visibleAnnotations) {
                    boolean redirect=annotation.desc.endsWith("/Redirect;");
                    if(!redirect && !annotation.desc.endsWith("/Inject;"))continue;
                    @SuppressWarnings("unchecked") var selectors=(List<String>)value(annotation,"method");
                    for(String selector:selectors) {
                        var matches=vanilla.methods.stream().filter(m->selector.equals(m.name) || selector.equals(m.name+m.desc)).toList();
                        check(matches.size()==1,"Ambiguous/missing mapped target "+target+"."+selector);
                        if(redirect) {
                            var at=(AnnotationNode)value(annotation,"at");
                            String call=(String)value(at,"target");
                            boolean found=false;
                            for(var instruction:matches.getFirst().instructions)
                                if(instruction instanceof MethodInsnNode invoke && call.equals("L"+invoke.owner+";"+invoke.name+invoke.desc))found=true;
                            check(found,"Redirect call disappeared from "+target+"."+selector+": "+call);
                        } else {
                            check(Boolean.TRUE.equals(value(annotation,"cancellable")),"Authority replacement must cancel vanilla: "+method.name);
                        }
                        hooks++;
                    }
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        verify("mysticism.mixins.json","mixins","SpiritMeshCollisionMixin","net/minecraft/entity/Entity");
        verify("mysticism.mixins.json","mixins","SpiritMeshSneakMixin","net/minecraft/entity/player/PlayerEntity");
        verify("mysticism.mixins.json","mixins","SpiritMeshPacketCollisionMixin","net/minecraft/server/network/ServerPlayNetworkHandler");
        verify("mysticism.client.mixins.json","client","SpiritCarrierPushMixin","net/minecraft/client/network/ClientPlayerEntity");
        check(hooks==11,"Expected movement, clearance, suffocation, push, contact, void, pose, sneak, packet and prediction hooks; found "+hooks);
        System.out.println("SpiritMeshCollisionHookTest: "+hooks+" registered mapped hook targets checked (offline, not live mixin application)");
    }
}
