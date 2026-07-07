import java.util.ArrayList;
import java.util.List;

/**
 * 緩慢的 memory leak，當 jmap 的練習活靶。
 *
 * 執行：java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError LeakDemo.java
 * 大約 20 秒後 OOM。趁它還活著時用 jps 找 pid、jmap -histo <pid> 觀察誰在吃記憶體；
 * OOM 後當前目錄會多一個 java_pid<pid>.hprof —— 事後分析用的 heap dump。
 */
public class LeakDemo {
    static final List<byte[]> cache = new ArrayList<>();   // 「只進不出的 cache」

    public static void main(String[] args) throws InterruptedException {
        while (true) {
            cache.add(new byte[256 * 1024]);   // 每 100ms 漏 256KB
            if (cache.size() % 40 == 0) {
                System.out.println("cache 已累積 " + cache.size() / 4 + " MB");
            }
            Thread.sleep(100);
        }
    }
}
