package io.github.mysticism.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executes the compiled production handlers, unchanged, with isolated world/player doubles.
 * No Minecraft bootstrap, live mixin transformation, or gameplay is performed. The separate
 * hook suite verifies required registration, mapped targets, HEAD ordering and shadow fields.
 */
public final class SpiritCarrierAutoJumpTest implements Opcodes {
    private static final String MIXIN="io/github/mysticism/client/mixin/SpiritCarrierPushMixin";
    private static final String PLAYER="net/minecraft/client/network/ClientPlayerEntity";
    private static final String WORLD="net/minecraft/world/World";
    private static final String KEY="net/minecraft/registry/RegistryKey";
    private static final String SERVICE="io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService";
    private static int checks;

    private static void check(boolean value,String message) {
        checks++;
        if(!value)throw new AssertionError(message);
    }
    private static ClassWriter fixture(String name,String parent) {
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21,ACC_PUBLIC,name,null,parent,null);
        var init=writer.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode();
        init.visitVarInsn(ALOAD,0);
        init.visitMethodInsn(INVOKESPECIAL,parent,"<init>","()V",false);
        init.visitInsn(RETURN);
        init.visitMaxs(0,0);
        init.visitEnd();
        return writer;
    }
    private static byte[] holder(String name,String parent,String field,String type,String getter) {
        var writer=fixture(name,parent);
        writer.visitField(ACC_PUBLIC,field,"L"+type+";",null,null).visitEnd();
        var method=writer.visitMethod(ACC_PUBLIC,getter,"()L"+type+";",null,null);
        method.visitCode();
        method.visitVarInsn(ALOAD,0);
        method.visitFieldInsn(GETFIELD,name,field,"L"+type+";");
        method.visitInsn(ARETURN);
        method.visitMaxs(0,0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String,byte[]> fixtures=new HashMap<>();
        FixtureLoader() throws Exception {
            super(SpiritCarrierAutoJumpTest.class.getClassLoader());
            try(var stream=getParent().getResourceAsStream(MIXIN+".class")) {
                if(stream==null)throw new AssertionError("Missing production carrier guard");
                fixtures.put(MIXIN.replace('/','.'),stream.readAllBytes());
            }
            // Only environment dependencies are doubled; handler instructions are NOT rewritten.
            var key=fixture(KEY,"java/lang/Object");
            key.visitEnd();
            fixtures.put(KEY.replace('/','.'),key.toByteArray());
            fixtures.put(WORLD.replace('/','.'),holder(WORLD,"java/lang/Object","key",KEY,"getRegistryKey"));
            fixtures.put(PLAYER.replace('/','.'),holder(PLAYER,MIXIN,"world",WORLD,"getWorld"));
            var service=fixture(SERVICE,"java/lang/Object");
            service.visitField(ACC_PUBLIC|ACC_STATIC,"WORLD","L"+KEY+";",null,null).visitEnd();
            service.visitEnd();
            fixtures.put(SERVICE.replace('/','.'),service.toByteArray());
        }
        @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(!fixtures.containsKey(name))return super.loadClass(name,resolve);
            var loaded=findLoadedClass(name);
            if(loaded==null) {
                var bytes=fixtures.get(name);
                loaded=defineClass(name,bytes,0,bytes.length);
            }
            if(resolve)resolveClass(loaded);
            return loaded;
        }
    }
    private static Method handler(Class<?> mixin,String name,Class<?>... arguments) throws Exception {
        var method=mixin.getDeclaredMethod(name,arguments);
        method.setAccessible(true);
        return method;
    }
    private static CallbackInfo callback(boolean cancellable) {
        return new CallbackInfo("offline-carrier-guard",cancellable);
    }

    public static void main(String[] args) throws Exception {
        var loader=new FixtureLoader();
        var keyClass=loader.loadClass(KEY.replace('/','.'));
        Object spirit=keyClass.getConstructor().newInstance();
        loader.loadClass(SERVICE.replace('/','.')).getField("WORLD").set(null,spirit);
        var worldClass=loader.loadClass(WORLD.replace('/','.'));
        Object world=worldClass.getConstructor().newInstance();
        Field key=worldClass.getField("key");
        var playerClass=loader.loadClass(PLAYER.replace('/','.'));
        Object player=playerClass.getConstructor().newInstance();
        playerClass.getField("world").set(player,world);
        var mixin=playerClass.getSuperclass();
        Field countdown=mixin.getDeclaredField("ticksToNextAutojump");
        countdown.setAccessible(true);
        var autoJump=handler(mixin,"mysticism$noCarrierAutoJump",float.class,float.class,CallbackInfo.class);
        var tick=handler(mixin,"mysticism$clearCarrierAutoJump",CallbackInfo.class);
        var push=handler(mixin,"mysticism$meshOnlyPush",double.class,double.class,CallbackInfo.class);

        key.set(world,spirit);
        // Positive, negative and stationary moves, with and without already queued jumps. HEAD
        // cancellation prevents ALL vanilla carrier probes, including invisible obstacles. It is
        // intentionally unconditional on the user's Auto-Jump option or mesh-frame availability.
        for(float dx:new float[]{0,.1f,-.1f})for(int pending:new int[]{0,1,3}) {
            countdown.setInt(player,pending);
            var result=callback(true);
            autoJump.invoke(player,dx,.2f,result);
            check(result.isCancelled(),"Spirit move must never reach vanilla voxel Auto-Jump");
            check(countdown.getInt(player)==0,"Suppressed Auto-Jump must leave no queued jump");
        }
        for(int pending:new int[]{0,1,3}) {
            countdown.setInt(player,pending);
            // Non-cancellable callback catches accidental cancellation of normal/manual movement.
            var result=callback(false);
            tick.invoke(player,result);
            check(countdown.getInt(player)==0,"Spirit tick must clear a jump queued before entry, even when stationary");
            check(!result.isCancelled(),"Cleanup must not cancel manual jump/movement processing");
        }
        var pushResult=callback(true);
        push.invoke(player,1d,2d,pushResult);
        check(pushResult.isCancelled(),"Existing carrier push suppression must remain active");

        // Different source/native dimension keys, then re-entry with the SAME player object:
        // no option mutation, global flag, or persistent suppression may escape spirit terrain.
        for(int dimension=0;dimension<3;dimension++) {
            key.set(world,keyClass.getConstructor().newInstance());
            countdown.setInt(player,1);
            var jumpResult=callback(true);
            autoJump.invoke(player,.1f,0f,jumpResult);
            check(!jumpResult.isCancelled(),"Source/native world must retain vanilla Auto-Jump");
            check(countdown.getInt(player)==1,"Source/native Auto-Jump queue must not be cleared");
            var tickResult=callback(false);
            tick.invoke(player,tickResult);
            check(countdown.getInt(player)==1,"Source/native tick must retain queued jump");
            var nativePush=callback(true);
            push.invoke(player,1d,2d,nativePush);
            check(!nativePush.isCancelled(),"Source/native push-out must remain vanilla");
            key.set(world,spirit);
            tick.invoke(player,callback(false));
            check(countdown.getInt(player)==0,"Seamless re-entry must discard the source-world queued jump");
        }
        System.out.println("SpiritCarrierAutoJumpTest: "+checks+" compiled-handler checks passed (isolated doubles, not gameplay/live mixin application)");
    }
}
