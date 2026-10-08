package io.github.mysticism.client.spiritworld;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mysticism.client.net.SpiritSceneClient;
import io.github.mysticism.net.SpiritScenePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.entity.*;
import net.minecraft.entity.ItemEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.entity.data.DataTracker;
import io.github.mysticism.client.mixin.SpiritTrackerEntriesAccess;
import io.github.mysticism.client.mixin.SpiritTrackerInitialValueAccess;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import java.util.*;

/** Render-only models, never inserted into world/entity hit tests. Canonical network snapshots. */
public final class SpiritSemanticEntityRenderer {
    private static final Map<String,Visual> visuals=new LinkedHashMap<>();
    private static final Set<String> failedTypes=new HashSet<>();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("Mysticism/SpiritEntities");
    private static BufferAllocator allocator;
    private static VertexConsumerProvider.Immediate immediate;
    private static Object world,player;
    private static boolean initialized,manual;
    private static long lastFrame;
    private static final class Visual {
        Entity model; ItemStack stack; Vec3d target,position; int light=LightmapTextureManager.MAX_LIGHT_COORDINATE; float size=1,alpha,yaw,pitch,bodyYaw,headYaw; boolean present;
        final Map<Integer,DataTracker.SerializedEntry<?>> defaults=new LinkedHashMap<>();
        SpiritScenePayload.Ghost applied;
        Visual(Entity model,Vec3d position) { this.model=model; this.position=target=position;captureDefaults(); }
        void captureDefaults() {
            defaults.clear();applied=null;
            if(model!=null)for(var entry:((SpiritTrackerEntriesAccess)(Object)model.getDataTracker()).mysticism$entries())
                defaults.put(entry.getData().id(),defaultEntry(entry));
        }
    }
    private SpiritSemanticEntityRenderer() {}
    public static void init() {
        if (initialized) return; initialized=true;
        WorldRenderEvents.AFTER_ENTITIES.register(SpiritSemanticEntityRenderer::render);
        SpiritRenderReload.register(Identifier.of("mysticism","spirit_entities"),SpiritSemanticEntityRenderer::clear);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
    }
    public static void clear() {
        visuals.clear(); failedTypes.clear(); if (allocator!=null) allocator.close(); allocator=null; immediate=null;
        lastFrame=0; world=player=null; manual=false;
    }
    /** Optional query only; parent owns native-scene suppression at WorldRenderer.renderEntity. */
    public static boolean suppressVanilla(Entity entity) {
        var client=MinecraftClient.getInstance();
        return !manual && ClientSpiritCache.active() && client.world!=null && entity!=client.player
                && client.world.getRegistryKey().getValue().equals(Identifier.of("mysticism","spirit"))
                && SpiritSceneClient.snapshot().isPresent();
    }
    public static boolean tryTouch(MinecraftClient client,double reach) {
        if (!Double.isFinite(reach) || reach<=0 || client.player==null || !ClientSpiritCache.active()
                || !ClientSpiritCache.deep() || !ClientSpiritCache.observerReady()) return false;
        var snapshot=SpiritSceneClient.snapshot();
        var mesh=io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient.frame();
        if (snapshot.isEmpty() || !snapshot.get().deep() || mesh.isEmpty()) return false;
        var camera=client.gameRenderer.getCamera(); Vec3d from=camera.getPos();
        Vec3d end=from.add(Vec3d.fromPolar(camera.getPitch(),camera.getYaw()).multiply(Math.min(4,reach)));
        UUID selected=null; Vec3d selectedHit=null; double nearest=Double.POSITIVE_INFINITY;
        for (var peer:snapshot.get().peers()) {
            if (!peer.deep() || peer.id().equals(client.player.getUuid())) continue;
            var visual=visuals.get("peer:"+peer.id());
            if (visual==null || !visual.present || visual.alpha<.3 || visual.model==null) continue;
            double height=visual.model.getHeight()*visual.size,width=visual.model.getWidth()*visual.size;
            var box=Box.of(visual.position.add(0,height/2,0),width,height,width);
            var hit=box.raycast(from,end); if (hit.isEmpty()) continue;
            double distance=hit.get().squaredDistanceTo(from);
            if (distance<nearest) { nearest=distance; selected=peer.id(); selectedHit=hit.get(); }
        }
        if (selected==null || !clearMeshRay(mesh.get(),from,selectedHit)) return false;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new io.github.mysticism.net.SpiritTouchPayload(selected));
        return true;
    }
    /** Affine LOCAL shape ray, never a different reconstructed Source Mesh/solid cube. */
    private static boolean clearMeshRay(io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame frame,Vec3d from,Vec3d to) {
        for (var cell:frame.cells()) {
            if (cell.opacity()<=0 || !cell.bounds().intersects(Box.of(from.lerp(to,.5),
                    Math.abs(from.x-to.x)+.001,Math.abs(from.y-to.y)+.001,Math.abs(from.z-to.z)+.001))) continue;
            Vec3d r0=cell.axisY().crossProduct(cell.axisZ()); double determinant=cell.axisX().dotProduct(r0);
            if (Math.abs(determinant)<1e-9) return false; // visible degenerate surface: fail closed
            Vec3d r1=cell.axisZ().crossProduct(cell.axisX()),r2=cell.axisX().crossProduct(cell.axisY());
            Vec3d a=from.subtract(cell.min()),b=to.subtract(cell.min());
            Vec3d localA=new Vec3d(a.dotProduct(r0)/determinant,a.dotProduct(r1)/determinant,a.dotProduct(r2)/determinant);
            Vec3d localB=new Vec3d(b.dotProduct(r0)/determinant,b.dotProduct(r1)/determinant,b.dotProduct(r2)/determinant);
            for (var shape:cell.collision()) if (shape.contains(localA) || shape.raycast(localA,localB).isPresent()) return false;
        }
        return true;
    }
    /** Actual affine source cell, not a second terrain projection. <=2048 scalar probes/ghost. */
    private record GhostProjection(Vec3d point,int light) {}
    private static GhostProjection ghostPoint(SpiritScenePayload.Ghost ghost,SpiritScenePayload scene,Vec3d observerFeet) {
        var frame=io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient.frame();
        io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame.Cell closest=null;
        double best=64;Vec3d p=ghost.sourcePosition();
        if(frame.isPresent())for(var cell:frame.get().cells()) {
            if(cell.opacity()<=0 || !cell.landmarkId().equals(ghost.landmarkId()))continue;
            Vec3d min=cell.sourceMin(),size=cell.size();
            double dx=Math.max(Math.max(min.x-p.x,0),p.x-min.x-size.x);
            double dy=Math.max(Math.max(min.y-p.y,0),p.y-min.y-size.y);
            double dz=Math.max(Math.max(min.z-p.z,0),p.z-min.z-size.z);
            double distance=dx*dx+dy*dy+dz*dz;
            if(distance<best){best=distance;closest=cell;if(distance==0)break;}
        }
        if(closest!=null) {
            Vec3d relative=p.subtract(closest.sourceMin()),size=closest.size();
            return new GhostProjection(closest.point(relative.x/size.x,relative.y/size.y,relative.z/size.z),closest.light());
        }
        // Source-local translation for flying/air ghosts with no sampled cell below them.
        return new GhostProjection(observerFeet.add(p.subtract(scene.binding().sourcePosition())),LightmapTextureManager.MAX_LIGHT_COORDINATE);
    }
    private static OtherClientPlayerEntity playerModel(MinecraftClient client,GameProfile profile) {
        if(profile==null)throw new IllegalArgumentException("Authentic player profile missing");
        var skins=client.getSkinProvider().getSkinTexturesSupplier(profile);
        return new OtherClientPlayerEntity(client.world,profile) {
            @Override public net.minecraft.client.util.SkinTextures getSkinTextures(){return skins.get();}
        };
    }
    private static boolean sameProfile(GameProfile a,GameProfile b) {
        return a!=null && b!=null && a.equals(b) && a.getProperties().equals(b.getProperties());
    }
    private static <T> DataTracker.SerializedEntry<T> copyEntry(DataTracker.SerializedEntry<T> entry) {
        return new DataTracker.SerializedEntry<>(entry.id(),entry.handler(),entry.handler().copy(entry.value()));
    }
    @SuppressWarnings("unchecked")
    private static <T> DataTracker.SerializedEntry<T> defaultEntry(DataTracker.Entry<T> entry) {
        T initial=(T)((SpiritTrackerInitialValueAccess)(Object)entry).mysticism$initialValue();
        return DataTracker.SerializedEntry.of(entry.getData(),initial);
    }
    /** COMPLETE appearance, not a delta. Only private render models are changed.
     * Use declared tracker defaults, NOT constructor-mutated values (e.g. a roosting bat).
     * Copies prevent mutable received/model values from poisoning the retained defaults. */
    private static void applyAppearance(Visual visual,SpiritScenePayload.Ghost appearance) {
        if(visual.applied==appearance)return;
        List<DataTracker.SerializedEntry<?>> reset=new ArrayList<>(visual.defaults.size());
        for(var entry:visual.defaults.values())reset.add(copyEntry(entry));
        visual.model.getDataTracker().writeUpdatedEntries(reset);
        if(visual.model instanceof LivingEntity living)
            for(var slot:EquipmentSlot.values())if(living.canUseSlot(slot))living.equipStack(slot,ItemStack.EMPTY);
        visual.model.getDataTracker().writeUpdatedEntries(appearance.tracked());
        visual.model.setPose(EntityPose.valueOf(appearance.pose()));
        if(visual.model instanceof LivingEntity living)for(var equipment:appearance.equipment())
            living.equipStack(equipment.slot(),equipment.stack());
        visual.yaw=appearance.yaw();visual.pitch=appearance.pitch();
        visual.bodyYaw=appearance.bodyYaw();visual.headYaw=appearance.headYaw();visual.applied=appearance;
    }
    private static void render(WorldRenderContext context) {
        var client=MinecraftClient.getInstance();
        if (world!=client.world || player!=client.player) { clear(); world=client.world; player=client.player; }
        var snapshot=SpiritSceneClient.snapshot();
        if (!ClientSpiritCache.active() || client.player==null || snapshot.isEmpty()) { clear(); return; }
        var view=SpiritGlyphFrame.current(context.tickCounter().getTickDelta(false));
        if (view==null || context.matrixStack()==null || context.frustum()==null) return;
        var scene=snapshot.get(); long now=System.nanoTime();
        float dt=lastFrame==0?0:(float)Math.min(.1,(now-lastFrame)*1e-9); lastFrame=now;
        visuals.values().forEach(v->v.present=false);
        for (var peer:scene.peers()) {
            if (peer.id().equals(client.player.getUuid())) continue;
            String key="peer:"+peer.id(); Visual visual=visuals.get(key);
            try {
                var appearance=peer.appearance();
                var entry=client.getNetworkHandler().getPlayerListEntry(peer.id());
                var profile=entry==null?appearance.profile():entry.getProfile();
                if(visual==null && visuals.size()<128) {
                    visual=new Visual(playerModel(client,profile),view.project(peer.q()));visuals.put(key,visual);
                }
                if(visual==null)continue;
                if(!sameProfile(((OtherClientPlayerEntity)visual.model).getGameProfile(),profile)) {
                    visual.model=playerModel(client,profile);visual.captureDefaults();
                }
                applyAppearance(visual,appearance);
                visual.size=Math.max(.05f,(float)Math.pow(view.alignment(peer.basis()),2));
                visual.target=view.project(peer.q()).add(0,-visual.model.getStandingEyeHeight()*visual.size,0);
                visual.present=true;
            } catch(RuntimeException failure) {
                if(failedTypes.size()<64 && failedTypes.add("minecraft:player"))
                    LOGGER.warn("Peer canonical appearance unavailable; no fabricated profile/model",failure);
                visuals.remove(key);
            }
        }
        if (!scene.deep() && scene.binding()!=null) for (var ghost:scene.ghosts()) {
            String key="ghost:"+ghost.id()+":"+ghost.type(); Visual visual=visuals.get(key);
            var projected=ghostPoint(ghost,scene,client.player.getLerpedPos(context.tickCounter().getTickDelta(false)));
            Vec3d point=projected.point();
            if (failedTypes.contains(ghost.type()) || visual==null && failedTypes.size()>=64) continue;
            try {
                if (visual==null && visuals.size()<128) {
                    Identifier type=Identifier.tryParse(ghost.type());
                    if (type==null || !Registries.ENTITY_TYPE.containsId(type)) throw new IllegalArgumentException("Unavailable registry type");
                    Entity model;
                    if (type.equals(Identifier.ofVanilla("player"))) {
                        model=playerModel(client,ghost.profile());
                    } else model=Registries.ENTITY_TYPE.get(type).create(client.world);
                    if (model==null) throw new IllegalArgumentException("Registry type cannot create client ghost");
                    model.setUuid(ghost.id()); visual=new Visual(model,point); visuals.put(key,visual);
                }
                if (visual==null) continue;
                if(visual.model instanceof OtherClientPlayerEntity sourcePlayer
                        && !sameProfile(sourcePlayer.getGameProfile(),ghost.profile())) {
                    visual.model=playerModel(client,ghost.profile());visual.captureDefaults();
                }
                applyAppearance(visual,ghost);
                visual.target=point;visual.light=projected.light();visual.present=true;
            } catch (RuntimeException failure) {
                if (failedTypes.size()<64 && failedTypes.add(ghost.type()))
                    LOGGER.warn("Source ghost type {} unavailable; skip model until session/reload, not a marker",ghost.type(),failure);
                visuals.remove(key);
            }
        }
        for (var drop:scene.drops()) {
            String key="drop:"+drop.id(); Visual visual=visuals.get(key);
            if (visual==null && visuals.size()<128) { visual=new Visual(null,view.project(drop.q())); visuals.put(key,visual); }
            if (visual==null) continue;
            // Canonical bounded ItemStack retains type/count/appearance components even when XYZ
            // vanilla tracking cannot see the recoverable native ItemEntity.
            visual.stack=drop.stack();
            visual.target=view.project(drop.q()); visual.present=true;
        }
        visuals.values().removeIf(v->!v.present && v.alpha<=0);
        if (immediate==null) { allocator=new BufferAllocator(262144); immediate=VertexConsumerProvider.immediate(allocator); }
        var camera=context.camera().getPos(); var matrices=context.matrixStack();
        client.getFramebuffer().beginWrite(false); RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
        manual=true;
        try {
            for (var entry:visuals.entrySet()) {
                Visual visual=entry.getValue(); visual.alpha=Math.max(0,Math.min(1,visual.alpha+(visual.present?1:-1)*dt*3));
                visual.position=visual.position.lerp(visual.target,1-Math.exp(-dt*12));
                if (visual.alpha<=0 || visual.position.squaredDistanceTo(camera)>64*64
                        || !context.frustum().isVisible(Box.of(visual.position,4,4,4))) continue;
                float alpha=visual.alpha;
                if (visual.model==null) {
                    if (visual.stack!=null) SpiritItemProjectionRenderer.drawStack(context,visual.stack,visual.position,.5f,
                            alpha*ShaderManager.effectStrength(),entry.getKey().hashCode());
                    continue;
                }
                if (failedTypes.contains(Registries.ENTITY_TYPE.getId(visual.model.getType()).toString())) continue;
                visual.model.refreshPositionAndAngles(visual.position.x,visual.position.y,visual.position.z,visual.yaw,visual.pitch);
                visual.model.prevYaw=visual.yaw; visual.model.prevPitch=visual.pitch;
                visual.model.age=client.player.age;
                if (visual.model instanceof LivingEntity living) { living.headYaw=visual.headYaw; living.bodyYaw=visual.bodyYaw; living.prevHeadYaw=visual.headYaw; living.prevBodyYaw=visual.bodyYaw; }
                if(immediate==null){allocator=new BufferAllocator(262144);immediate=VertexConsumerProvider.immediate(allocator);}
                matrices.push();
                try {
                    matrices.translate(visual.position.x-camera.x,visual.position.y-camera.y,visual.position.z-camera.z);
                    matrices.scale(visual.size,visual.size,visual.size);
                    RenderSystem.setShaderColor(1,1,1,alpha);
                    // World-space model positions are already in matrices; dispatcher offsets are zero.
                    client.getEntityRenderDispatcher().render(visual.model,0,0,0,visual.yaw,
                            context.tickCounter().getTickDelta(false),matrices,SpiritRenderLayers.main(immediate),visual.light);
                    immediate.draw(); // Include color/depth before scene-depth capture/Fabulous clear.
                } catch (RuntimeException failure) {
                    String type=Registries.ENTITY_TYPE.getId(visual.model.getType()).toString();
                    if (failedTypes.size()<64 && failedTypes.add(type)) {
                        LOGGER.warn("Projected model {} failed; skipping until reload/session",type,failure);
                        client.player.sendMessage(net.minecraft.text.Text.literal("Spirit projected model unavailable: "+type),true);
                    }
                    if(allocator!=null)allocator.close();allocator=null;immediate=null;
                    visual.alpha=0; visual.present=false;
                } finally { matrices.pop(); }
            }
        } finally { manual=false; RenderSystem.setShaderColor(1,1,1,1); client.getFramebuffer().beginWrite(false); }
    }
}
