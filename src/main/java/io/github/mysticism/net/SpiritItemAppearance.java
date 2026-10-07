package io.github.mysticism.net;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.*;
import net.minecraft.component.type.*;
import net.minecraft.item.ItemStack;
import java.util.*;

/** Bounded visual copy only; real ItemEntity/inventory retains ALL original components. */
public final class SpiritItemAppearance {
    private SpiritItemAppearance(){}
    public static GameProfile profile(GameProfile source){
        if(source==null)return null;if(source.getName()==null||source.getName().length()>16)throw new IllegalArgumentException("Ghost player profile");var copy=new GameProfile(source.getId(),source.getName());
        var textures=source.getProperties().get("textures");if(textures.size()>1)throw new IllegalArgumentException("Ghost texture count");for(var p:textures){if(p.value().length()>4096||p.signature()!=null&&p.signature().length()>2048)throw new IllegalArgumentException("Ghost texture budget");copy.getProperties().put("textures",p);}return copy;
    }
    private static <T> void copy(ItemStack source,ItemStack to,ComponentType<T> type){T value=source.get(type);if(value!=null)to.set(type,value);}
    public static ItemStack copy(ItemStack source){
        if(source.isEmpty())return ItemStack.EMPTY;var result=new ItemStack(source.getItem(),Math.min(source.getCount(),999));
        copy(source,result,DataComponentTypes.CUSTOM_MODEL_DATA);copy(source,result,DataComponentTypes.DYED_COLOR);copy(source,result,DataComponentTypes.TRIM);copy(source,result,DataComponentTypes.DAMAGE);copy(source,result,DataComponentTypes.BASE_COLOR);
        result.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,source.hasGlint());
        var patterns=source.get(DataComponentTypes.BANNER_PATTERNS);if(patterns!=null&&patterns.layers().size()<=32)result.set(DataComponentTypes.BANNER_PATTERNS,patterns);
        var potion=source.get(DataComponentTypes.POTION_CONTENTS);if(potion!=null)result.set(DataComponentTypes.POTION_CONTENTS,new PotionContentsComponent(Optional.empty(),Optional.of(potion.getColor()),List.of()));
        var skull=source.get(DataComponentTypes.PROFILE);if(skull!=null&&skull.gameProfile()!=null)result.set(DataComponentTypes.PROFILE,new ProfileComponent(profile(skull.gameProfile())));
        return result;
    }
}
