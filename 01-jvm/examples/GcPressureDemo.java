import java.util.ArrayList;
import java.util.List;

/**
 * 製造 GC 壓力，觀察分代回收的行為。
 *
 * 執行：java -Xmx64m -Xlog:gc GcPressureDemo.java
 *
 * 迴圈裡大多數物件一出生就變垃圾（符合「朝生夕死」假說），
 * 少數被 survivors 抓住的物件會活過多次 Minor GC、晉升到 Old Generation。
 */
public class GcPressureDemo {
    static final List<byte[]> survivors = new ArrayList<>();

    public static void main(String[] args) {
        for (int i = 0; i < 16_000; i++) {
            byte[] garbage = new byte[64 * 1024];     // 大多數物件：下一輪就沒人參考
            if (i % 400 == 0) {
                survivors.add(new byte[64 * 1024]);   // 少數活口：一路活到程式結束
            }
        }
        System.out.println("結束，長壽物件共 " + survivors.size() + " 個");
    }
}
