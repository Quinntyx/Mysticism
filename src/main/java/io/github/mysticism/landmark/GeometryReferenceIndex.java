package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Immutable, content-addressed reference directory. Only keys, never geometry leaves.
 * A record holds one root; each page holds <=256 refs and an optional next-page key.
 * The directory has no landmark-size ceiling. Mutations stage pages tail-first before
 * publishing the record/catalogue, using the store's normal page-write budget.
 * Cold metadata reads still traverse the directory synchronously, as legacy headers do.
 */
final class GeometryReferenceIndex {
    static final int PAGE_SIZE=256;
    private static final String PREFIX="mysticism.landmark.references.";
    record Page(String key,NbtCompound nbt) {}
    record Encoded(String root,List<Page> pages) {}
    private GeometryReferenceIndex() {}
    static boolean validKey(String key) { return key.matches("mysticism[.]landmark[.]references[.][0-9a-f]{64}"); }
    static Encoded encode(List<String> keys) {
        if(keys.isEmpty())throw new IllegalArgumentException("empty reference directory");
        var pages=new ArrayList<Page>();String next=null;
        for(int end=keys.size();end>0;){
            int start=((end-1)/PAGE_SIZE)*PAGE_SIZE;
            NbtCompound n=new NbtCompound();n.putInt("schema",1);NbtList refs=new NbtList();
            for(int i=start;i<end;i++)refs.add(NbtString.of(keys.get(i)));
            n.put("geometry",refs);if(next!=null)n.putString("next",next);
            next=PREFIX+digest(n);pages.add(new Page(next,n));end=start;
        }
        return new Encoded(next,List.copyOf(pages));
    }
    static List<String> decode(String root,int count,Function<String,NbtCompound> loader) {
        if(count<=0)throw new IllegalArgumentException("reference directory count");
        var keys=new ArrayList<String>();var seen=new HashSet<String>();String key=root;
        while(keys.size()<count){
            if(!validKey(key)||!seen.add(key))throw new IllegalArgumentException("invalid/cyclic reference directory");
            NbtCompound n=Objects.requireNonNull(loader.apply(key),"missing reference directory page");
            if(!key.equals(PREFIX+digest(n)))throw new IllegalArgumentException("reference directory content/key mismatch");
            if(!n.contains("schema",NbtElement.INT_TYPE)||n.getInt("schema")!=1||!n.contains("geometry",NbtElement.LIST_TYPE))
                throw new IllegalArgumentException("reference directory schema/fields");
            NbtList refs=(NbtList)n.get("geometry");
            if(refs.getHeldType()!=NbtElement.STRING_TYPE||refs.size()!=Math.min(PAGE_SIZE,count-keys.size()))
                throw new IllegalArgumentException("reference directory page size/type");
            for(NbtElement ref:refs)keys.add(ref.asString());
            if(keys.size()<count){
                if(!n.contains("next",NbtElement.STRING_TYPE))throw new IllegalArgumentException("truncated reference directory");
                key=n.getString("next");
            }else if(n.contains("next"))throw new IllegalArgumentException("reference directory exceeds count");
        }
        return List.copyOf(keys);
    }
    // Canonical NBT hashing is shared with immutable record identities. Compound insertion
    // order is deliberately irrelevant; all list ordering, types and payload bits matter.
    static String digest(NbtCompound n) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash));
            hashNbt(out,n);return HexFormat.of().formatHex(hash.digest());
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("landmark metadata digest",e);}
    }
    private static void hashNbt(DataOutput out,NbtElement value)throws IOException {
        out.writeByte(value.getType());
        if(value instanceof NbtCompound compound){
            var keys=new TreeSet<>(compound.getKeys());out.writeInt(keys.size());
            for(String key:keys){out.writeUTF(key);hashNbt(out,compound.get(key));}
        }else if(value instanceof NbtList list){
            out.writeInt(list.size());for(NbtElement element:list)hashNbt(out,element);
        }else value.write(out);
    }
}
