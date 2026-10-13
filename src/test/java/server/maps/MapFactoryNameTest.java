package server.maps;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapFactoryNameTest {
    // Bot statuses read "farming ... at map 682000501" for maps String.wz names: name lookups from many
    // threads at once failed on the shared, lazily expanded DOM and came back empty.
    @Test
    void mapNamesResolveWhenManyThreadsLookThemUpAtOnce() throws Exception {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        List<Integer> ids = List.of(682000501, 682000600, 101030001, 101020003, 100000000, 600000000,
                800000000, 220000100, 541000000, 105070200, 200000000, 221023400);
        ConcurrentLinkedQueue<String> missing = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 16; t++) {
            pool.submit(() -> {
                go.await();
                for (int round = 0; round < 50; round++) {
                    for (int id : ids) {
                        if (MapFactory.loadPlaceName(id).isEmpty()) {
                            missing.add(String.valueOf(id));
                        }
                    }
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(missing.isEmpty(), "names came back empty: " + missing.size() + ", e.g. " + missing.peek());
    }
}
