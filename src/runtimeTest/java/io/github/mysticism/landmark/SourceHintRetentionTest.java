package io.github.mysticism.landmark;

import java.util.LinkedHashSet;
import java.util.List;

/** Production admission shared by vertical discovery and persisted-frontier retries.
 * Horizontal generation retains its separate lossless survey queue. */
public final class SourceHintRetentionTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    private static void backpressurePreservesEverySeed(){
        var hints=new LinkedHashSet<String>();
        for(String seed:List.of("a","b","c"))check(SourceLandmarks.admit(hints,seed,3),"admit below capacity");
        check(!SourceLandmarks.admit(hints,"d",3),"saturated producer must retain its seed for retry");
        check(hints.stream().toList().equals(List.of("a","b","c")),"overflow must not evict a vertical/frontier seed");
        check(SourceLandmarks.admit(hints,"a",3),"existing seed succeeds even when saturated");
        check(hints.stream().toList().equals(List.of("a","b","c")),"duplicate cannot reorder/starve oldest work");
        hints.removeFirst();
        check(SourceLandmarks.admit(hints,"d",3),"producer retry succeeds once consumer drains a slot");
        check(hints.stream().toList().equals(List.of("b","c","d")),"retry retains all unconsumed work in order");
    }

    private static void singleSlotBudget(){
        var hints=new LinkedHashSet<String>();
        check(SourceLandmarks.admit(hints,"a",1),"first seed admitted");
        check(!SourceLandmarks.admit(hints,"b",1),"second seed backpressured");
        check(SourceLandmarks.admit(hints,"a",1),"duplicate acknowledged");
        check(hints.stream().toList().equals(List.of("a")),"one-slot queue never silently loses first seed");
        hints.clear();
        check(SourceLandmarks.admit(hints,"b",1),"retry after drain");
    }

    private static void budgetAndInvalidInputs(){
        check(SourceLandmarks.HINT_BUDGET>128,"retain expanded hint capacity alongside independent generation debt");
        var hints=new LinkedHashSet<String>();
        try{SourceLandmarks.admit(hints,"a",0);throw new AssertionError("Nonpositive budgets must be rejected");}
        catch(IllegalArgumentException expected){assertions++;}
        try{SourceLandmarks.admit(hints,null,3);throw new AssertionError("Null hints must be rejected");}
        catch(NullPointerException expected){assertions++;}
    }

    public static void main(String[] args){
        backpressurePreservesEverySeed();singleSlotBudget();budgetAndInvalidInputs();
        System.out.println("SourceHintRetentionTest passed: "+assertions+" assertions");
    }
}
