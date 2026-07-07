import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * ArrayList vs LinkedList 四種操作的效能對比。
 *
 * 執行：java ListBenchmark.java
 *
 * 注意：粗測非嚴謹 benchmark（無 JIT 預熱），但結論差距都是數量級的。
 */
public class ListBenchmark {
    static final int N = 100_000;

    public static void main(String[] args) {
        // 1. 尾端 add
        bench("尾端 add        ", new ArrayList<>(), l -> { for (int i = 0; i < N; i++) l.add(i); });
        bench("尾端 add        ", new LinkedList<>(), l -> { for (int i = 0; i < N; i++) l.add(i); });

        // 2. 頭部 add(0, e)
        bench("頭部 add(0, e)  ", new ArrayList<>(), l -> { for (int i = 0; i < N; i++) l.add(0, i); });
        bench("頭部 add(0, e)  ", new LinkedList<>(), l -> { for (int i = 0; i < N; i++) l.add(0, i); });

        // 3. 隨機 get(i)
        bench("隨機 get(i)     ", fill(new ArrayList<>()), ListBenchmark::randomGet);
        bench("隨機 get(i)     ", fill(new LinkedList<>()), ListBenchmark::randomGet);

        // 4. 中間 add(size/2, e) —— 口訣說 LinkedList 該贏的場景
        bench("中間 add(mid, e)", fill(new ArrayList<>()), l -> { for (int i = 0; i < 10_000; i++) l.add(l.size() / 2, i); });
        bench("中間 add(mid, e)", fill(new LinkedList<>()), l -> { for (int i = 0; i < 10_000; i++) l.add(l.size() / 2, i); });
    }

    static void randomGet(List<Integer> l) {
        Random r = new Random(42);
        long sum = 0;
        for (int i = 0; i < N; i++) sum += l.get(r.nextInt(l.size()));
        if (sum == -1) System.out.println();   // 防止被優化掉
    }

    static List<Integer> fill(List<Integer> l) {
        for (int i = 0; i < N; i++) l.add(i);
        return l;
    }

    static void bench(String label, List<Integer> list, java.util.function.Consumer<List<Integer>> op) {
        long t0 = System.nanoTime();
        op.accept(list);
        System.out.printf("%s | %-10s | %,7d ms%n",
                label, list.getClass().getSimpleName(), (System.nanoTime() - t0) / 1_000_000);
    }
}
