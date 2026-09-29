package top.yunitytech.maven.jooq.binding.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpatialWkbPoolTest {

    private final GeometryFactory gf = new GeometryFactory();

    @Test
    @DisplayName("Thread-local reuse: same thread receives the exact same reader and writer instances")
    void testSameThreadInstanceReuse() {
        WKBReader reader1 = SpatialWkbPool.getWkbReader();
        WKBReader reader2 = SpatialWkbPool.getWkbReader();
        assertThat(reader1).isNotNull().isSameAs(reader2);

        WKBReader packed1 = SpatialWkbPool.getPackedWkbReader();
        WKBReader packed2 = SpatialWkbPool.getPackedWkbReader();
        assertThat(packed1).isNotNull().isSameAs(packed2);
        assertThat(packed1).isNotSameAs(reader1);

        WKBWriter writer2d1 = SpatialWkbPool.getWkbWriter(2);
        WKBWriter writer2d2 = SpatialWkbPool.getWkbWriter(2);
        assertThat(writer2d1).isNotNull().isSameAs(writer2d2);

        WKBWriter writer3d1 = SpatialWkbPool.getWkbWriter(3);
        WKBWriter writer3d2 = SpatialWkbPool.getWkbWriter(3);
        assertThat(writer3d1).isNotNull().isSameAs(writer3d2);
        assertThat(writer3d1).isNotSameAs(writer2d1);

        WKTReader wktReader1 = SpatialWkbPool.getWktReader();
        WKTReader wktReader2 = SpatialWkbPool.getWktReader();
        assertThat(wktReader1).isNotNull().isSameAs(wktReader2);
    }

    @Test
    @DisplayName("Thread isolation: different threads receive distinct reader and writer instances")
    void testDifferentThreadIsolation() throws Exception {
        WKBReader mainReader = SpatialWkbPool.getWkbReader();
        WKBWriter mainWriter = SpatialWkbPool.getWkbWriter(2);

        AtomicReference<WKBReader> otherReader = new AtomicReference<>();
        AtomicReference<WKBWriter> otherWriter = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            otherReader.set(SpatialWkbPool.getWkbReader());
            otherWriter.set(SpatialWkbPool.getWkbWriter(2));
        });
        thread.start();
        thread.join();

        assertThat(otherReader.get()).isNotNull().isNotSameAs(mainReader);
        assertThat(otherWriter.get()).isNotNull().isNotSameAs(mainWriter);
    }

    @Test
    @DisplayName("Concurrent reads and writes produce correct geometries across multiple threads")
    void testConcurrentReadWrite() throws InterruptedException, ExecutionException {
        int threads = 8;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        Future<?>[] futures = new Future<?>[threads];
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures[t] = executor.submit(() -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                    Point point = gf.createPoint(new Coordinate(threadId * 10.0 + i, threadId * 20.0 + i));
                    point.setSRID(4326);

                    // Write to WKB
                    WKBWriter writer = SpatialWkbPool.getWkbWriter(2);
                    byte[] bytes = writer.write(point);

                    // Read from WKB
                    WKBReader reader = SpatialWkbPool.getWkbReader();
                    try {
                        Geometry restored = reader.read(bytes);
                        assertThat(restored.getSRID()).isEqualTo(4326);
                        assertThat(restored.getCoordinate().x).isEqualTo(threadId * 10.0 + i);
                        assertThat(restored.getCoordinate().y).isEqualTo(threadId * 20.0 + i);
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("clear() removes thread-local instances and subsequent calls create new ones")
    void testClearRemovesInstances() {
        WKBReader reader1 = SpatialWkbPool.getWkbReader();
        WKBWriter writer1 = SpatialWkbPool.getWkbWriter(2);
        WKTReader wkt1 = SpatialWkbPool.getWktReader();

        SpatialWkbPool.clear();

        WKBReader reader2 = SpatialWkbPool.getWkbReader();
        WKBWriter writer2 = SpatialWkbPool.getWkbWriter(2);
        WKTReader wkt2 = SpatialWkbPool.getWktReader();

        assertThat(reader2).isNotNull().isNotSameAs(reader1);
        assertThat(writer2).isNotNull().isNotSameAs(writer1);
        assertThat(wkt2).isNotNull().isNotSameAs(wkt1);
    }
}
