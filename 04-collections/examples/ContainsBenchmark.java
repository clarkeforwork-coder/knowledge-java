import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * List.contains vs Set.contains 的效能對比。
 *
 * 執行：java ContainsBenchmark.java
 *
 * 注意：這是示意用的粗測（沒有 JIT 預熱、單次取樣），不是嚴謹的 benchmark，
 * 但兩者差距是「數量級」等級，足以說明選錯結構的代價。
 */
public class ContainsBenchmark {
    public static void main(String[] args) {
        int n = 100_000;
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        Set<Integer> set = new HashSet<>(list);

        long t0 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < n; i++) {
            if (list.contains(i)) hits++;        // 每次從頭掃到找到為止：O(n)
        }
        System.out.printf("List.contains × %,d：%,d ms（命中 %,d）%n",
                n, (System.nanoTime() - t0) / 1_000_000, hits);

        t0 = System.nanoTime();
        hits = 0;
        for (int i = 0; i < n; i++) {
            if (set.contains(i)) hits++;         // 用 hash 直接定位：平均 O(1)
        }
        System.out.printf("Set.contains  × %,d：%,d ms（命中 %,d）%n",
                n, (System.nanoTime() - t0) / 1_000_000, hits);
    }
}
