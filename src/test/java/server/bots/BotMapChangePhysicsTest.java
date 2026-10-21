package server.bots;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.keybind.KeyBinding;
import net.server.channel.Channel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import testutil.RequiresWz;

import java.awt.Point;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Helios Tower 100F (222020300) drops a player through its under portals (collision portals at y 2300) to 99F's
 * st00 (-15, 498). 99F (222020200) has a sealed pit at y 2342 under its elevator floor (y 2040). The airborne
 * integrator works from its own copy of the position, so a bot stepped by physics after the warp and before the
 * map change reset carried 100F's coordinates onto 99F and fell into the pit. SipsBuddy1 dropped in mid-cast:
 * the action lock steps physics.
 */
@RequiresWz
class BotMapChangePhysicsTest {
    private static final int HELIOS_100F = 222020300;
    private static final int HELIOS_99F = 222020200;
    private static final Point UNDER01 = new Point(-138, 2300);
    private static final int ELEVATOR_FLOOR_Y = 2040;

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void aBotCastingAsItFallsThroughTheUnderPortalArrivesAtTheTargetPortal() {
        MapleMap floor100 = BotNavigationMapLoader.loadMapGeometry(HELIOS_100F);
        MapleMap floor99 = BotNavigationMapLoader.loadMapGeometry(HELIOS_99F);
        Point st00 = floor99.getPortal("st00").getPosition();
        Character bot = mockBot(floor100, new Point(UNDER01.x, UNDER01.y - 50), floor99);
        BotEntry entry = fallingCaster(bot, floor100.getId());

        BotManager.getInstance().runCommonTickSystemsForTest(entry, bot, null, false);

        assertEquals(HELIOS_99F, bot.getMapId(), "the under portal fired");
        assertEquals(st00, bot.getPosition(), "the warp ends the tick, no step replays the 100F position on 99F");
    }

    @Test
    void aBotJustWarpedMidCastDoesNotFallFromItsOldCoordinates() {
        MapleMap floor99 = BotNavigationMapLoader.loadMapGeometry(HELIOS_99F);
        Point st00 = floor99.getPortal("st00").getPosition();
        Character bot = mockBot(floor99, st00, floor99);
        // The warp already happened: the bot stands at st00 on 99F, its integrator and lastMapId still on 100F.
        BotEntry entry = fallingCaster(bot, HELIOS_100F);
        entry.physX = UNDER01.x;
        entry.physY = UNDER01.y;

        for (int tick = 0; tick < 40; tick++) {
            try {
                BotManager.getInstance().runCommonTickSystemsForTest(entry, bot, null, false);
            } catch (RuntimeException | LinkageError dbBacked) {
                // The shop and quest scans need the item and quest data from the database; physics ran first.
            }
        }

        assertTrue(bot.getPosition().y <= ELEVATOR_FLOOR_Y, "fell into the pit from 100F's coordinates: " + bot.getPosition());
        assertEquals(HELIOS_99F, entry.lastMapId, "the map change reset ran");
    }

    private static BotEntry fallingCaster(Character bot, int lastMapId) {
        BotEntry entry = new BotEntry(bot, bot, null); // self-owned autopilot bot
        entry.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotPhysicsEngine.teleportTo(entry, bot, bot.getPosition());
        entry.inAir = true;
        entry.velY = 8f;
        entry.grinding = true; // travel runs with grinding set
        entry.attackCooldownMs = 600; // mid-cast: the action lock steps physics
        entry.lastMapId = lastMapId;
        entry.lastHeartbeatAtMs = System.currentTimeMillis();
        entry.spawnWarmupMs = 0; // past the login idle
        entry.skipDelayMs = 0;
        return entry;
    }

    private static Character mockBot(MapleMap startMap, Point start, MapleMap destination) {
        Character bot = mock(Character.class);
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);
        Inventory empty = mock(Inventory.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        AtomicReference<MapleMap> map = new AtomicReference<>(startMap);
        AtomicInteger stance = new AtomicInteger(0);

        when(client.getPlayer()).thenReturn(bot);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(destination.getId())).thenReturn(destination);

        when(bot.getName()).thenReturn("SipsBuddy1");
        when(bot.getId()).thenReturn(254);
        when(bot.getClient()).thenReturn(client);
        when(bot.getPosition()).thenAnswer(inv -> new Point(position.get()));
        doAnswer(inv -> {
            position.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenAnswer(inv -> map.get());
        when(bot.getMapId()).thenAnswer(inv -> map.get().getId());
        doAnswer(inv -> {
            map.set(inv.getArgument(0));
            position.set(new Point(((Portal) inv.getArgument(1)).getPosition()));
            return null;
        }).when(bot).changeMap(any(MapleMap.class), any(Portal.class));
        when(bot.getHp()).thenReturn(1700);
        when(bot.getCurrentMaxHp()).thenReturn(1700);
        when(bot.getMp()).thenReturn(1000);
        when(bot.getCurrentMaxMp()).thenReturn(1000);
        when(bot.getStance()).thenAnswer(inv -> stance.get());
        doAnswer(inv -> {
            stance.set(inv.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getLevel()).thenReturn(96);
        when(bot.getSkills()).thenReturn(Map.of());
        when(bot.getRemainingSps()).thenReturn(new int[5]);
        when(bot.getKeymap()).thenReturn(new HashMap<Integer, KeyBinding>());
        for (InventoryType type : InventoryType.values()) {
            when(bot.getInventory(type)).thenReturn(empty);
        }
        when(empty.getItem(anyShort())).thenReturn(null);
        when(empty.iterator()).thenAnswer(inv -> Collections.<client.inventory.Item>emptyIterator());
        when(empty.getSlotLimit()).thenReturn((byte) 24);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.isLoggedinWorld()).thenReturn(true);
        return bot;
    }
}
