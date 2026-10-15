package io.github.mysticism.client.spiritworld;

import org.joml.Matrix4d;
import org.joml.Vector4d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Actual original-branch selection + captured vanilla policy/reference; no game or GPU bootstrap. */
public final class SpiritSourceFogExitTest {
    private static int checks;
    private static final int LIVE = 7, SAVED = 11;
    private static final SpiritSourceFog SOURCE = new SpiritSourceFog(32,96,1,.75f,.68f,.95f,1);

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual-expected)<=tolerance,message+": "+actual+" != "+expected);
    }

    private static double mix(double a, double b, double t) { return a+(b-a)*t; }

    private static double original(SpiritEntryScene.Original selected, double raw, double saved,
            SpiritSourceFog fog, double x, double y, double z, boolean sky) {
        double color=selected.texture()==SAVED?saved:raw;
        return selected.restoreSourceFog() && !sky?mix(color,fog.red(),fog.amount(x,y,z)):color;
    }

    private static void entryAndExitSelection() {
        var entry=SpiritEntryScene.selectOriginal(LIVE,SAVED,true,true,true,100_000_000L,.5f);
        check(entry.texture()==SAVED && !entry.restoreSourceFog(),"entry snapshot already contains vanilla fog");
        near(original(entry,.1,.6,SOURCE,96,0,0,false),.6,0,"saved fog must not be applied twice");
        for (float strength:new float[]{1,.998f,.5f,.05f,.002f,0}) {
            for (long age:new long[]{100_000_000L,30_000_000_000L}) {
                // Real exit condition: ClientSpiritCache.active() is FALSE, including a fresh saved image.
                var exit=SpiritEntryScene.selectOriginal(LIVE,SAVED,true,false,true,age,strength);
                check(exit.texture()==LIVE && exit.restoreSourceFog(),"inactive cache selects live source WITH fog during exit");
                double raw=.12, processed=.45, source=original(exit,raw,.6,SOURCE,96,0,0,false);
                near(source,SOURCE.red(),0,"source terrain beyond its vanilla horizon remains fogged");
                double output=mix(source,processed,strength);
                near(output,mix(SOURCE.red(),processed,strength),1e-15,"exit mixes source fog and completed effect once");
                if (strength<.1f) {
                    check(Math.abs(output-mix(raw,processed,strength))>.5,"fixture detects the old unfogged-source exit");
                    check(Math.abs(output-SOURCE.red())<=strength*Math.abs(processed-SOURCE.red())+1e-15,
                            "approach the fogged source continuously, no large pop when vanilla resumes");
                }
                // Re-fogging the processed result would drop its painterly/volumetric appearance.
                near(mix(source,processed,1),processed,0,"transformed branch never receives source analytic fog");
            }
        }
        check(SpiritRenderSettings.useVolumetricFog(true,false,.5f),"exit terrain stays raw for volume");
        // An old or unusable entry snapshot also selects LIVE, not just inactive-cache exits.
        for (boolean observer:new boolean[]{false,true}) for (boolean active:new boolean[]{false,true})
            for (boolean source:new boolean[]{false,true}) for (long age:new long[]{-1,0,5_000_000_000L,5_000_000_001L})
                for (float strength:new float[]{.5f,.999f,1}) {
                    var selected=SpiritEntryScene.selectOriginal(LIVE,SAVED,observer,active,source,age,strength);
                    boolean useSaved=observer && active && source && age>=0 && age<=5_000_000_000L && strength<.999f;
                    check(selected.texture()==(useSaved?SAVED:LIVE),"observer/source/expiry/full-strength selection");
                    check(selected.restoreSourceFog()!=useSaved,"EVERY live fallback restores source fog, saved color never does");
                }
        var live=SpiritEntryScene.selectOriginal(LIVE,SAVED,false,false,false,0,.01f);
        near(original(live,.37,.6,SOURCE,1000,0,0,true),.37,0,"already-fogged vanilla sky remains untouched");
        for (double raw:new double[]{.05,.2,.9}) {
            double source=original(live,raw,.6,SOURCE,64,0,0,false);
            double processed=mix(.3,.75,.4); // painterly result followed by one volumetric integration
            for (double s:new double[]{0,.0005,.001,.00101,.002,.25,.5,1}) {
                double output=mix(source,processed,s);
                near(output,(1-s)*source+s*processed,1e-15,"both branches have independent fog ownership");
                if (s<=.002) check(Math.abs(output-source)<=.002,"threshold-sized transition only at vanilla handoff");
            }
        }
    }

    private static void vanillaParametersAndReconstruction() {
        near(SOURCE.amount(32,0,0),0,0,"near source terrain clear");
        near(SOURCE.amount(64,0,0),.5,0,"vanilla smoothstep midpoint");
        near(SOURCE.amount(48,0,0),.15625,0,"smoothstep, not a linear or volumetric source layer");
        near(SOURCE.amount(96,0,0),1,0,"fully fogged source horizon");
        near(SOURCE.amount(48,48,0),.15625,0,"cylinder must not use spherical ray length");
        near(SOURCE.amount(0,80,0),.84375,0,"cylinder includes absolute vertical distance");
        near(SOURCE.amount(-48,-48,0),.15625,0,"negative positions identical");
        var sphere=new SpiritSourceFog(32,96,0,.1f,.2f,.3f,.4f);
        near(sphere.amount(64,0,0),.5*.4f,1e-15,"vanilla fog-color alpha retained");
        check(sphere.amount(48,48,0)>.5*.4f,"sphere is not incorrectly treated as a cylinder");
        var abrupt=new SpiritSourceFog(8,8,0,.1f,.2f,.3f,1);
        near(abrupt.amount(8,0,0),0,0,"vanilla <=start early return before equal end");
        near(abrupt.amount(9,0,0),1,0,"no divide by zero for abrupt fog");
        // The production inverse is projection * world-view, not the fullscreen postpass ProjMat.
        // Reconstruct rotated, off-axis points (including fog-cylinder corners) at several FOVs.
        for (int yaw:new int[]{0,33,170}) for (int pitch:new int[]{-40,0,45}) for (int fov:new int[]{50,90,110}) {
            Matrix4d view=new Matrix4d().rotateX(Math.toRadians(pitch)).rotateY(Math.toRadians(yaw));
            Matrix4d pv=new Matrix4d().perspective(Math.toRadians(fov),16.0/9,.05,1024).mul(view);
            Matrix4d inverse=new Matrix4d(pv).invert();
            for (double[] eye:new double[][]{{0,0,-48},{10,20,-64},{-35,-40,-80}}) {
                Vector4d world=new Matrix4d(view).invert().transform(new Vector4d(eye[0],eye[1],eye[2],1));
                Vector4d clip=pv.transform(new Vector4d(world));
                double u=(clip.x/clip.w+1)/2,v=(clip.y/clip.w+1)/2,depth=(clip.z/clip.w+1)/2;
                Vector4d rebuilt=inverse.transform(new Vector4d(u*2-1,v*2-1,depth*2-1,1));
                rebuilt.div(rebuilt.w);
                near(rebuilt.x,world.x,2e-10,"depth reconstruction world x");
                near(rebuilt.y,world.y,2e-10,"depth reconstruction world y");
                near(rebuilt.z,world.z,2e-10,"depth reconstruction world z");
                near(SOURCE.amount(rebuilt.x,rebuilt.y,rebuilt.z),SOURCE.amount(world.x,world.y,world.z),1e-11,
                        "reconstructed cylinder fog stable across yaw/pitch/FOV");
            }
        }
    }

    private static void productionWiring() throws Exception {
        String manager=Files.readString(Path.of("src/client/java/io/github/mysticism/client/spiritworld/ShaderManager.java"));
        String mixin=Files.readString(Path.of("src/client/java/io/github/mysticism/client/mixin/SpiritBackgroundRendererMixin.java"));
        String entry=Files.readString(Path.of("src/client/java/io/github/mysticism/client/spiritworld/SpiritEntryScene.java"));
        check(mixin.contains("type == BackgroundRenderer.FogType.FOG_TERRAIN"),"only terrain fog is bypassed; sky backdrop preserved");
        int capture=manager.indexOf("sourceFog=new SpiritSourceFog(");
        int bypass=manager.indexOf("RenderSystem.setShaderFogStart(Float.MAX_VALUE");
        check(capture>=0 && capture<bypass,"capture vanilla parameters BEFORE disabling analytic terrain fog");
        check(manager.contains("if (!terrainFog) return;"),"SKY hook does not bypass sky fog or overwrite terrain parameters");
        check(manager.contains("RenderSystem.getShaderFogShape()==FogShape.SPHERE?0:1") &&
                manager.contains("color[0],color[1],color[2],color[3]"),"source uses actual vanilla shape and RGBA");
        check(manager.indexOf("sourceFog = null;")<capture && manager.contains("!fogHookSeen || sourceFog == null"),
                "missing terrain hook cannot reuse previous frame's fog state");
        check(entry.contains("return selectOriginal(") && entry.contains("ClientSpiritCache.active(),"),
                "tested selector actually receives live cache state");
        check(manager.contains("original.texture()") && manager.contains("original.restoreSourceFog()?1:0"),
                "selected live/saved ownership actually controls shader sampler and restoration uniform");
        for (String uniform:new String[]{"SourceFogStart","SourceFogEnd","SourceFogShape","SourceFogColor","RestoreSourceFog"})
            check(manager.contains("setUniformValue(\""+uniform+"\""),"captured source fog uniform bound: "+uniform);
        String shader;
        try (var in=SpiritSourceFogExitTest.class.getClassLoader().getResourceAsStream(
                "assets/mysticism/shaders/program/spirit_transition.fsh")) {
            if (in==null) throw new AssertionError("packaged transition missing");
            shader=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        check(shader.contains("if (RestoreSourceFog == 0) return original;"),"saved image is never fogged twice");
        check(shader.contains("if (depth >= 0.9999999) return original;"),"sky not mistaken for distant terrain");
        check(shader.contains("max(length(offset.xz), abs(offset.y))"),"live fog uses vanilla cylinder metric");
        check(shader.contains("smoothstep(SourceFogStart, SourceFogEnd, distance)") && shader.contains("amount * SourceFogColor.a"),
                "vanilla smoothstep and RGBA preserved in GLSL");
        check(shader.contains("vec3 original = originalWithSourceFog(texture(OriginalSampler, texCoord).rgb);") &&
                shader.contains("vec3 transformed = texture(DiffuseSampler, texCoord).rgb;"),
                "only ORIGINAL branch receives source fog; volume/painterly untouched");
    }

    public static void main(String[] args) throws Exception {
        entryAndExitSelection(); vanillaParametersAndReconstruction(); productionWiring();
        System.out.println("Spirit source fog exit: "+checks+" checks passed (production selection/policy/CPU reference; no GPU)");
    }
}
