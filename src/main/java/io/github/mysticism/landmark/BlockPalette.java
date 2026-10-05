package io.github.mysticism.landmark;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** Registry names and sorted block-state properties; physical terrain resolves them via its registry. */
public record BlockPalette(List<State> states) {
    public record State(String blockId, Map<String,String> properties) {
        public State {
            if (blockId==null || !blockId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("block identifier");
            TreeMap<String,String> copy=new TreeMap<>(properties);
            copy.forEach((k,v)->{ if(k.isBlank() || v.isBlank()) throw new IllegalArgumentException("state property"); });
            properties=Collections.unmodifiableMap(copy);
        }
    }
    public BlockPalette {
        states=List.copyOf(states);
        if(states.isEmpty() || states.size()>4096 || states.stream().distinct().count()!=states.size()) throw new IllegalArgumentException("palette");
    }
    public State state(int index) { return states.get(index); }
}
