package com.optimix.optimizer.patterns;

import com.optimix.optimizer.patterns.tier1.Tier1Patterns;
import com.optimix.optimizer.patterns.tier2.Tier2Patterns;
import com.optimix.optimizer.patterns.tier3.Tier3Patterns;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all 40 optimization patterns.
 *
 * Patterns are applied in tier order:
 *   Tier 1 first  (high-impact, complex transforms)
 *   Tier 2 second (structural rewrites)
 *   Tier 3 last   (quick wins, cleanup)
 *
 * Within each tier, order matters — some patterns depend on previous ones.
 * (e.g., P15 Outer→Inner JOIN should run before P10 join ordering)
 */
public class PatternRegistry {

    private static final List<OptimizationPattern> ALL_PATTERNS;

    static {
        ALL_PATTERNS = new ArrayList<>();
        ALL_PATTERNS.addAll(Tier1Patterns.all());   // P01–P10
        ALL_PATTERNS.addAll(Tier2Patterns.all());   // P11–P25
        ALL_PATTERNS.addAll(Tier3Patterns.all());   // P26–P40
    }

    /** Returns all 40 patterns in application order. */
    public static List<OptimizationPattern> all() {
        return ALL_PATTERNS;
    }

    /** Returns only patterns of a specific tier. */
    public static List<OptimizationPattern> byTier(OptimizationPattern.Tier tier) {
        return ALL_PATTERNS.stream()
            .filter(p -> p.getTier() == tier)
            .toList();
    }

    /** Look up a pattern by its ID. */
    public static OptimizationPattern findById(String id) {
        return ALL_PATTERNS.stream()
            .filter(p -> p.getId().equals(id))
            .findFirst()
            .orElse(null);
    }
}
