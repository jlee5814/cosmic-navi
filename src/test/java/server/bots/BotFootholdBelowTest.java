package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The ground probe must return the NEAREST foothold below a point. {@link Foothold#compareTo}
 * calls a long steep slope equal to any flat platform whose height falls inside the slope's
 * y range, so sorted order alone can put the lower surface first (map 682000100: the stair rail
 * fh30 and the 325 floor beside and above it).
 */
class BotFootholdBelowTest {

    private static MapleMap mapWith(int mapId, Foothold... footholds) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        for (Foothold fh : footholds) {
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    @Test
    void fallLandsOnSteepSlopeAboveAFlatPlatformInsertedFirst() {
        Foothold floor = new Foothold(new Point(0, 200), new Point(240, 200), 1);
        Foothold rail = new Foothold(new Point(0, 0), new Point(240, 292), 2);
        MapleMap map = mapWith(999682101, floor, rail);

        Point probe = new Point(100, 50);
        assertEquals(2, BotPhysicsEngine.findBelowIndexed(map, probe).getId(),
                "the rail at y~121 is the first surface under the bot, not the floor at 200");

        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateFallLanding(map, probe, 0);
        assertNotNull(landing);
        assertEquals(2, landing.foothold().getId());
    }

    @Test
    void jumpFromUnderASlopeLandsOnTheFloorAboveIt() {
        Foothold rail = new Foothold(new Point(0, 0), new Point(240, 292), 2);
        Foothold floor = new Foothold(new Point(0, 100), new Point(240, 100), 1);
        MapleMap map = mapWith(999682102, rail, floor);

        Point probe = new Point(200, 60);
        assertEquals(1, BotPhysicsEngine.findBelowIndexed(map, probe).getId(),
                "the floor at 100 sits between the bot and the rail at y~243");
        assertEquals(new Point(200, 100), BotPhysicsEngine.pointBelowIndexed(map, probe));
    }
}
