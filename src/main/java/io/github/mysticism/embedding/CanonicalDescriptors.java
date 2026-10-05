package io.github.mysticism.embedding;

import java.util.*;

/** Locale-independent v2 descriptors: identity + human words + sorted registry tags.
 * Coordinates, player ownership, counts and weights never enter the semantic text.
 */
public final class CanonicalDescriptors {
    private CanonicalDescriptors(){}
    private static String clean(String text){return Objects.requireNonNull(text).replaceAll("\\s+"," ").trim();}
    public static String words(String id){return clean(id.replace(':',' ').replace('_',' ').replace('/',' '));}
    public static String describe(String kind,String id,Collection<String> tags){
        String identity=clean(id);
        if(!identity.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid canonical registry ID");
        TreeSet<String> sorted=new TreeSet<>();for(String tag:tags)sorted.add(clean(tag));
        StringBuilder result=new StringBuilder(kind+" "+identity+". "+words(identity)+".");
        // Deterministically bounded tags; the provider rejects actual token-context overflow.
        for(String tag:sorted){String part=" tag "+tag+" ("+words(tag)+").";if(result.length()+part.length()>1800)break;result.append(part);}
        return result.toString();
    }
    public static String item(String id,Collection<String> tags){return describe("item",id,tags);}
    public static String block(String id,Collection<String> tags){return describe("block",id,tags);}
    public static String biome(String id,Collection<String> tags){return describe("biome",id,tags);}
    public static String region(String dimension,String biome){return region(dimension,biome,List.of());}
    public static String region(String dimension,String biome,Collection<String> tags){return biome(biome,tags)+" dimension "+dimension+" ("+words(dimension)+").";}
}
