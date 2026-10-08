package io.github.mysticism.movement;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;

/** Test-only resource-backed host for the installed Mixin engine. Provides unchanged production
 * and Minecraft class bytes, not dummy game classes or an emulation of redirect validation. */
public final class FlightMixinTestService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider {
    public String getName() { return "Mysticism offline flight mixin regression"; }
    public boolean isValid() { return true; }
    public IClassProvider getClassProvider() { return this; }
    public IClassBytecodeProvider getBytecodeProvider() { return this; }
    public ITransformerProvider getTransformerProvider() { return null; }
    public IClassTracker getClassTracker() { return null; }
    public IMixinAuditTrail getAuditTrail() { return null; }
    public Collection<String> getPlatformAgents() { return List.of(); }
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleURI(Path.of("build/classes/java/runtimeTest").toUri());
    }
    public Collection<IContainerHandle> getMixinContainers() { return List.of(); }
    public InputStream getResourceAsStream(String name) {
        if (name.equals("mysticism-flight-application-test.json")) {
            try { return SpiritFlightMixinApplicationTest.config(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        return getClass().getClassLoader().getResourceAsStream(name);
    }
    public URL[] getClassPath() { return new URL[0]; }
    public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
    }
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true);
    }
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return SpiritFlightMixinApplicationTest.suppliedNode(name);
    }
    IMixinTransformer transformer() {
        return getInternal(IMixinTransformerFactory.class).createTransformer();
    }
}
