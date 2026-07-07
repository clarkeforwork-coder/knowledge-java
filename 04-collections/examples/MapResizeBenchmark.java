import java.util.HashMap;
import java.util.Map;

/**
 * HashMap 預給容量 vs 預設容量的效能對比。
 *
 * 執行：java MapResizeBenchmark.java
 *
 * 預設容量 16、負載因子 0.75：裝一百萬筆要一路擴容 rehash 十幾次。
 * 先各跑一輪預熱 JIT，再取第二輪數字。粗測非嚴謹 benchmark，量級供參考。
 */
public class MapResizeBenchmark {
    static final int N = 1_000_000;
    static final String[] keys = new String[N];

    public static void main(String[] args) {
        for (int i = 0; i < N; i++) keys[i] = "user-" + i;

        run("（預熱）預設容量    ", new HashMap<>());
        run("（預熱）預給足夠容量", new HashMap<>(1 << 21));
        run("預設容量    ", new HashMap<>());               // 預設 16，一路擴容 rehash
        run("預給足夠容量", new HashMap<>(1 << 21));        // 2^21 > N/0.75，不會擴容
    }

    static void run(String label, Map<String, Integer> map) {
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) map.put(keys[i], i);
        System.out.printf("%s：%,d ms%n", label, (System.nanoTime() - t0) / 1_000_000);
    }
}
