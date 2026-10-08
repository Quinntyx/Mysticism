package io.github.mysticism.landmark;

import java.util.LinkedHashSet;

/** Regression: pending generation landmark hints must follow recency (render-distance
 * streaming keeps landmarking where players actually are) instead of freezing on the
 * first chunk hints ever seen and silently dropping everything after the budget. */
public final class SourceHintRetentionTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    private static LinkedHashSet<String> set(String... values){
        var set=new LinkedHashSet<String>();for(var value:values)set.add(value);return set;
    }

    private static void admitsInOrder(){
        var hints=new LinkedHashSet<String>();
        SourceLandmarks.admit(hints,"a",SourceLandmarks.HINT_BUDGET);
        SourceLandmarks.admit(hints,"b",SourceLandmarks.HINT_BUDGET);
        SourceLandmarks.admit(hints,"c",SourceLandmarks.HINT_BUDGET);
        check(hints.size()==3,"Admission below budget keeps every hint");
        check(hints.stream().toList().equals(java.util.List.of("a","b","c")),"Admission preserves insertion order");
    }

    private static void evictsOldestAtCapacity(){
        var hints=set("a","b","c");
        SourceLandmarks.admit(hints,"d",3);
        check(hints.stream().toList().equals(java.util.List.of("b","c","d")),"Overflow must evict the oldest hint, not reject the newest generation");
    }

    private static void duplicateRefreshesRecencyWithoutEviction(){
        var hints=set("a","b","c");
        SourceLandmarks.admit(hints,"a",3);
        check(hints.stream().toList().equals(java.util.List.of("b","c","a")),"A repeated hint must refresh to the newest position");
        SourceLandmarks.admit(hints,"c",3);
        check(hints.stream().toList().equals(java.util.List.of("b","a","c")),"Refreshing an existing hint must never evict");
        check(hints.size()==3,"Refresh must not grow the set");
    }

    private static void singleSlotBudget(){
        var hints=new LinkedHashSet<String>();
        SourceLandmarks.admit(hints,"a",1);
        SourceLandmarks.admit(hints,"b",1);
        check(hints.stream().toList().equals(java.util.List.of("b")),"A one-slot budget keeps only the newest hint");
    }

    private static void budgetIsRenderDistanceScale(){
        check(SourceLandmarks.HINT_BUDGET>=128,"Hint budget must retain at least the previous capacity");
        check(SourceLandmarks.HINT_BUDGET>128,"Hint budget must grow past the old first-128 freeze to cover render-distance streaming");
    }

    private static void rejectsBadInput(){
        var hints=new LinkedHashSet<String>();
        try{SourceLandmarks.admit(hints,"a",0);throw new AssertionError("Nonpositive budgets must be rejected");}
        catch(IllegalArgumentException expected){}
        try{SourceLandmarks.admit(hints,null,3);throw new AssertionError("Null hints must be rejected");}
        catch(NullPointerException expected){}
    }

    public static void main(String[] args){
        admitsInOrder();
        evictsOldestAtCapacity();
        duplicateRefreshesRecencyWithoutEviction();
        singleSlotBudget();
        budgetIsRenderDistanceScale();
        rejectsBadInput();
        System.out.println("SourceHintRetentionTest passed: "+assertions+" assertions");
    }
}
