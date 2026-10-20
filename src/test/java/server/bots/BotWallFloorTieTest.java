package server.bots;

import client.Character;
import constants.game.CharacterStance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import testutil.RequiresWz;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Helios Tower 99F (222020200): the elevator floor at y 2040 (fh 199, forbidFallDown) runs wall to wall between
 * x -265 and 265, and a sealed pit with no way out lies under it at y 2342. A bot on the floor's end pixel pushed
 * into the wall with no lift met the wall and the floor in the same column; the wall won, the bot hung at the
 * floor's height and the straight fall after it dropped through the floor into the pit (SipsBuddy3 froze there).
 */
@RequiresWz
class BotWallFloorTieTest {
    private static final int HELIOS_99F = 222020200;
    private static final int FLOOR_Y = 2040;

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void aBotPushedIntoTheWallFromTheFloorsEndStaysOnTheFloor() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(HELIOS_99F);
        List<String> fell = new ArrayList<>();
        for (int x : new int[]{-264, -250, 250, 264}) {
            for (int vx : new int[]{-30, -12, -2, 2, 12, 30}) {
                for (float vy : new float[]{0f, -10f, -22f}) {
                    Point end = knock(map, x, vx, vy);
                    if (end.y > FLOOR_Y + 30) {
                        fell.add("start x " + x + " vx " + vx + " vy " + vy + " ended " + end);
                    }
                }
            }
        }
        assertTrue(fell.isEmpty(), "fell through the elevator floor: " + fell);
    }

    private static Point knock(MapleMap map, int x, int vx, float vy) {
        Character bot = mockBot(new Point(x, FLOOR_Y), map);
        BotEntry entry = new BotEntry(bot, null, null);
        BotPhysicsEngine.resetMotion(entry, bot.getPosition());
        entry.facingDir = vx < 0 ? -1 : 1;
        BotPhysicsEngine.beginKnockback(entry, bot, bot.getPosition(), vy, vx);
        for (int tick = 0; tick < 400 && entry.inAir && bot.getPosition().y <= FLOOR_Y + 30; tick++) {
            BotPhysicsEngine.tickMotionTimers(entry);
            BotPhysicsEngine.stepAirborne(entry, bot);
        }
        return bot.getPosition();
    }

    private static Character mockBot(Point startPosition, MapleMap map) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger stance = new AtomicInteger(CharacterStance.STAND_RIGHT_STANCE);
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        return bot;
    }
}
