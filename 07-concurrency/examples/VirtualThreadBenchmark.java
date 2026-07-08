import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual Threads（Java 21）vs 傳統執行緒池：IO 型任務的吞吐對比。
 *
 * 需要 JDK 21。本機沒有的話用 Docker 執行：
 *   docker run --rm -v "$PWD":/work -w /work eclipse-temurin:21-jdk \
 *       java VirtualThreadBenchmark.java
 *
 * 任務一律「睡 100ms」模擬等待 IO（查 DB、呼叫 API）。
 */
public class VirtualThreadBenchmark {
    static final int N = 10_000;

    public static void main(String[] args) throws Exception {
        // 場景一：傳統定容池 200 —— 10,000 個 100ms 任務要排幾輪？
        try (ExecutorService pool = Executors.newFixedThreadPool(200)) {
            bench("固定池 200 條   ×10,000 任務", pool, N);
        }

        // 場景二：virtual thread —— 每任務一條，想開就開
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            bench("virtual thread ×10,000 任務", vt, N);
        }

        // 場景三：規模再放大十倍
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            bench("virtual thread ×100,000 任務", vt, 100_000);
        }

        // 觀察：virtual thread 的真面目（掛在哪條 carrier 上）
        AtomicReference<String> who = new AtomicReference<>();
        Thread.ofVirtual().start(() -> who.set(Thread.currentThread().toString())).join();
        System.out.println("virtual thread toString：" + who.get());
    }

    static void bench(String label, ExecutorService pool, int n) throws Exception {
        long t0 = System.nanoTime();
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                try { Thread.sleep(Duration.ofMillis(100)); } catch (InterruptedException ignored) { }
                done.countDown();
            });
        }
        done.await();
        System.out.printf("%s：%,6d ms%n", label, (System.nanoTime() - t0) / 1_000_000);
    }
}
