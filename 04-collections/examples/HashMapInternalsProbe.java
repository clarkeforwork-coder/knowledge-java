import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * 用 reflection 直接觀察 HashMap 內部的 table，驗證三件事：
 * 1. table 是 lazy 配置的（建構後是 null，第一次 put 才建）
 * 2. 給的初始容量會被拉到最近的 2 的次方（17 → 32）
 * 3. 同一桶塞滿衝突 key 之後，節點從 Node 變 TreeNode（樹化）
 *
 * 執行（要開 reflection 權限）：
 *   java --add-opens java.base/java.util=ALL-UNNAMED HashMapInternalsProbe.java
 */
public class HashMapInternalsProbe {

    /** 所有實例的 hashCode 都一樣 —— 保證全部撞進同一個桶 */
    static class CollidingKey implements Comparable<CollidingKey> {
        final int v;
        CollidingKey(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof CollidingKey c && v == c.v; }
        @Override public int compareTo(CollidingKey o) { return Integer.compare(v, o.v); }
    }

    public static void main(String[] args) throws Exception {
        // 實驗一：lazy 配置 + 容量修正
        HashMap<String, Integer> m = new HashMap<>(17);
        System.out.println("new HashMap<>(17) 後 table：" + describe(table(m)));
        m.put("first", 1);
        System.out.println("第一次 put 後 table 長度  ：" + table(m).length);

        // 實驗二：樹化 —— 觀察每次 put 之後，桶裡的節點型別與容量
        HashMap<CollidingKey, Integer> c = new HashMap<>();
        String lastType = "";
        for (int i = 1; i <= 12; i++) {
            c.put(new CollidingKey(i), i);
            Object[] tab = table(c);
            String type = bucketNodeType(tab);
            if (!type.equals(lastType)) {
                System.out.printf("放入第 %2d 個衝突 key：容量 %3d，節點型別 = %s%n", i, tab.length, type);
                lastType = type;
            }
        }
    }

    static Object[] table(HashMap<?, ?> map) throws Exception {
        Field f = HashMap.class.getDeclaredField("table");
        f.setAccessible(true);
        return (Object[]) f.get(map);
    }

    static String bucketNodeType(Object[] tab) {
        if (tab == null) return "（尚未配置）";
        for (Object cell : tab) {
            if (cell != null) return cell.getClass().getSimpleName();
        }
        return "（空）";
    }

    static String describe(Object[] tab) {
        return tab == null ? "null（還沒配置）" : "長度 " + tab.length;
    }
}
