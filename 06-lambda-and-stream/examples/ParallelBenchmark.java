import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * parallel() 的兩面：小資料變慢、大量 CPU 運算才變快。
 *
 * 執行：java ParallelBenchmark.java
 *
 * 各跑兩輪、取第二輪（第一輪當 JIT 預熱）。粗測非嚴謹 benchmark。
 */
public class ParallelBenchmark {
    public static void main(String[] args) {
        for (int round = 1; round <= 2; round++) {
            boolean report = round == 2;

            // 場景一：小資料（1,000 筆）簡單加總
            long t0 = System.nanoTime();
            long s1 = 0;
            for (int i = 0; i < 10_000; i++) s1 += IntStream.range(0, 1_000).sum();
            long seqSmall = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            long s2 = 0;
            for (int i = 0; i < 10_000; i++) s2 += IntStream.range(0, 1_000).parallel().sum();
            long parSmall = (System.nanoTime() - t0) / 1_000_000;

            // 場景二：大量 CPU 運算（2 億次乘加）
            t0 = System.nanoTime();
            long b1 = LongStream.range(0, 200_000_000).map(n -> n * 31 + 7).sum();
            long seqBig = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            long b2 = LongStream.range(0, 200_000_000).parallel().map(n -> n * 31 + 7).sum();
            long parBig = (System.nanoTime() - t0) / 1_000_000;

            if (report) {
                System.out.printf("小資料 ×1萬次   ：循序 %,d ms｜parallel %,d ms%n", seqSmall, parSmall);
                System.out.printf("2 億次 CPU 運算 ：循序 %,d ms｜parallel %,d ms%n", seqBig, parBig);
            }
            if (s1 != s2 || b1 != b2) System.out.println("結果不一致！");
        }
    }
}
