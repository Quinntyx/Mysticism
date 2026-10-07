package io.github.mysticism.component;

import io.github.mysticism.activity.ActivityMath;
import io.github.mysticism.vector.*;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/** CCA schema 2: personal history, retained navigation target and current supported attunement. */
public final class LatentAttunement implements ComponentV3, AutoSyncedComponent {
    private NbtCompound archive;
    private Vec384f current=Vec384f.ZERO(), target=Vec384f.ZERO(), personal=Vec384f.ZERO();
    private long revision;
    private boolean explicitTarget;
    public LatentAttunement() {}
    public LatentAttunement(Vec384f initial){set(initial);}
    /** Snapshot, never a mutable alias. Legacy readers use CURRENT, not TARGET. */
    public Vec384f get(){return current.clone();}
    public Vec384f target(){return target.clone();}
    public Vec384f personal(){return personal.clone();}
    public long revision(){return revision;}
    /** Explicit commands replace the navigation target; terrain steering must never call this. */
    public void set(Vec384f value){target=checked(value);explicitTarget=true; if(current.length()==0)current=target.clone(); revision++;}
    public void followPersonal(){explicitTarget=false;target=personal.clone();revision++;}
    public void steer(Vec384f value,double rate){current=ActivityMath.drift(current,value,rate);revision++;}
    public void observe(Vec384f observation){personal=ActivityMath.drift(personal,observation,0.025); if(!explicitTarget)target=personal.clone();if(current.length()==0)current=personal.clone();revision++;}
    private static Vec384f checked(Vec384f value){if(value==null)return Vec384f.ZERO();EmbeddingSpace.requireCurrent(value);return value.clone();}
    @Override public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup){
        archive=tag.contains("embeddingArchive",NbtElement.COMPOUND_TYPE)?tag.getCompound("embeddingArchive").copy():null;
        try {
            if(!EmbeddingNbt.compatible(tag))throw new IllegalArgumentException("profile");
            int schema=tag.getInt("attunementSchema");
            if(schema==0){ // wave-1 profile-stamped single vector -> explicit target/current migration
                current=Vec384f.fromBits(tag.getIntArray("v"));target=current.clone();personal=current.clone();explicitTarget=current.length()>0;revision=0;return;
            }
            if(schema!=2)throw new IllegalArgumentException("attunement schema");
            Vec384f c=Vec384f.fromBits(tag.getIntArray("v")),t=Vec384f.fromBits(tag.getIntArray("target")),p=Vec384f.fromBits(tag.getIntArray("personal"));
            long r=tag.getLong("revision");if(r<0)throw new IllegalArgumentException("revision");
            current=c;target=t;personal=p;revision=r;explicitTarget=tag.getBoolean("explicitTarget");
        }catch(IllegalArgumentException invalid){archive=tag.copy();current=Vec384f.ZERO();target=Vec384f.ZERO();personal=Vec384f.ZERO();revision=0;explicitTarget=false;}
    }
    @Override public void writeToNbt(NbtCompound tag,RegistryWrapper.WrapperLookup lookup){
        EmbeddingNbt.stamp(tag);tag.putInt("attunementSchema",2);tag.putIntArray("v",current.toBits());
        tag.putIntArray("target",target.toBits());tag.putIntArray("personal",personal.toBits());tag.putLong("revision",revision);tag.putBoolean("explicitTarget",explicitTarget);
        if(archive!=null)tag.put("embeddingArchive",archive.copy());
    }
}
