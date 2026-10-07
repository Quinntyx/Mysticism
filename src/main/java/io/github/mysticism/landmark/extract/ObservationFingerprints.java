package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPalette;
import io.github.mysticism.vector.EmbeddingSpace;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Worker-only content fingerprint: retries of unchanged loaded terrain don't grow immutable storage. */
public final class ObservationFingerprints {
    private ObservationFingerprints(){}
    public static String of(ExtractionGraph.Observation[] cells){
        if(cells.length!=ExtractionGraph.MAX_CELLS)throw new IllegalArgumentException("Snapshot cells");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer number=ByteBuffer.allocate(4);
            field(digest,number,ExtractionGraph.ALGORITHM+":"+EmbeddingSpace.FINGERPRINT);
            Map<BlockPalette.State,byte[]> materials=new HashMap<>();
            for(var cell:cells){
                digest.update((byte)(cell==null?0:1));if(cell==null)continue;
                byte[] material=materials.get(cell.material());if(material==null){
                    MessageDigest palette=MessageDigest.getInstance("SHA-256");field(palette,number,cell.material().blockId());
                    for(var property:new TreeMap<>(cell.material().properties()).entrySet()){field(palette,number,property.getKey());field(palette,number,property.getValue());}
                    material=palette.digest();if(materials.size()<512)materials.put(cell.material(),material);
                }
                digest.update(material);field(digest,number,cell.biome());field(digest,number,cell.item());
                digest.update((byte)(cell.air()?1:0));digest.update((byte)(cell.sky()?1:0));integer(digest,number,cell.surfaceY());integer(digest,number,cell.seaLevel());
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void field(MessageDigest digest,ByteBuffer number,String field){byte[] bytes=field.getBytes(StandardCharsets.UTF_8);integer(digest,number,bytes.length);digest.update(bytes);}
    private static void integer(MessageDigest digest,ByteBuffer number,int value){number.clear();number.putInt(value);digest.update(number.array());}
}
