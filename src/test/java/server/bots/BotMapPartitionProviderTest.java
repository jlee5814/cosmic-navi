package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapManager;
import testutil.RequiresWz;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cross map router asks for partitions on bot ticks. A map not in memory must never be loaded on that
 * thread (a WZ parse that also spawns its monsters stalled ticks for up to a second); its partition is
 * derived from geometry in the background instead.
 */
@RequiresWz
class BotMapPartitionProviderTest {
    @Test
    void anUnloadedMapIsNeverLoadedOnTheCallersThreadAndItsPartitionArrivesLater() throws InterruptedException {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapManager mf = mock(MapManager.class);
        when(mf.isMapLoaded(anyInt())).thenReturn(false);
        int lithHarbor = 104000000;
        BotMapPartitionProvider.forMapId(mf, -1); // loads any persisted partitions first
        BotMapPartitionProvider.invalidate(lithHarbor);

        assertNull(BotMapPartitionProvider.forMapId(mf, lithHarbor), "not ready yet: a dead end, not a load");

        BotMapPartition partition = null;
        long until = System.currentTimeMillis() + 60_000;
        while (partition == null && System.currentTimeMillis() < until) {
            Thread.sleep(100);
            partition = BotMapPartitionProvider.forMapId(mf, lithHarbor);
        }
        assertNotNull(partition, "derived in the background from the map's geometry");
        verify(mf, never()).getMap(anyInt());
    }
}
