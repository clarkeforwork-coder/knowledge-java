import java.util.ArrayList;
import java.util.List;

/**
 * GC 收集器對照實驗：同一份負載，換收集器比較停頓。
 *
 * 執行（各收集器跑一次，看 gc log 的 Pause 行）：
 *   java -Xms1g -Xmx1g -XX:+UseSerialGC    -Xlog:gc GcAlgoBench.java
 *   java -Xms1g -Xmx1g -XX:+UseParallelGC  -Xlog:gc GcAlgoBench.java
 *   java -Xms1g -Xmx1g -XX:+UseG1GC        -Xlog:gc GcAlgoBench.java
 *   java -Xms1g -Xmx1g -XX:+UseZGC         -Xlog:gc GcAlgoBench.java
 *
 * 負載設計：維持約 190MB 的存活集（輪替窗口），總共攪動約 6GB 配置——
 * 有夠多的垃圾要收、也有夠多的活人要搬，GC 的性格才顯得出來。
 */
public class GcAlgoBench {
    static final int WINDOW = 3_000;            // 存活窗口：3000 × 64KB ≈ 190MB
    static final int TOTAL = 100_000;           // 總配置：100000 × 64KB ≈ 6.1GB

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        List<byte[]> window = new ArrayList<>(WINDOW);
        for (int i = 0; i < TOTAL; i++) {
            byte[] block = new byte[64 * 1024];
            if (window.size() < WINDOW) {
                window.add(block);
            } else {
                window.set(i % WINDOW, block);  // 輪替：舊的變垃圾、新的活著
            }
        }
        System.out.printf("完成：總耗時 %,d ms（存活 %d MB）%n",
                (System.nanoTime() - t0) / 1_000_000, window.size() * 64 / 1024);
    }
}
