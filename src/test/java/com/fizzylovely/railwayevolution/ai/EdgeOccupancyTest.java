package com.fizzylovely.railwayevolution.ai;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EdgeOccupancyTest {

    @Test
    void retainsControllersAndClears() {
        EdgeOccupancy occupancy = new EdgeOccupancy();
        TrainAIController c1 = new TrainAIController(UUID.randomUUID());
        TrainAIController c2 = new TrainAIController(UUID.randomUUID());

        occupancy.add(c1);
        assertEquals(1, occupancy.count);
        assertSame(c1, occupancy.first);
        assertNull(occupancy.second);

        occupancy.add(c2);
        assertEquals(2, occupancy.count);
        assertSame(c1, occupancy.first);
        assertSame(c2, occupancy.second);

        occupancy.clear();
        assertEquals(0, occupancy.count);
        assertNull(occupancy.first);
        assertNull(occupancy.second);
    }
}
