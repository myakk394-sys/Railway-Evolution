package com.fizzylovely.railwayevolution.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualBlockSystemOptimizationTest {

    @Test
    void segmentKeyIsOrderIndependent() {
        BlockPos a = new BlockPos(10, 64, 20);
        BlockPos b = new BlockPos(30, 64, 50);

        VirtualBlockSystem.SegmentKey key1 = VirtualBlockSystem.SegmentKey.of(a, b);
        VirtualBlockSystem.SegmentKey key2 = VirtualBlockSystem.SegmentKey.of(b, a);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void segmentKeyDifferentiatesDimensions() {
        BlockPos a = new BlockPos(10, 64, 20);
        BlockPos b = new BlockPos(30, 64, 50);

        VirtualBlockSystem.SegmentKey overworld = VirtualBlockSystem.SegmentKey.of("minecraft:overworld", a, b);
        VirtualBlockSystem.SegmentKey nether = VirtualBlockSystem.SegmentKey.of("minecraft:the_nether", a, b);

        assertNotEquals(overworld, nether);
    }
}
