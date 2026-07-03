# 記憶體：Stack 與 Heap

## 前言

線上服務炸掉的時候，log 裡最常見的兩種記憶體錯誤長這樣：一種是 `StackOverflowError`，stack trace 裡同一個方法重複了幾千行；另一種是 `OutOfMemoryError: Java heap space`，發生點看起來完全無辜——可能只是一行普通的 `new`。

同樣是「記憶體不夠」，為什麼是兩種不同的錯誤？為什麼一種幾乎一定是你的 bug，另一種卻可能只是配置問題？因為它們爆的是兩塊完全不同的記憶體：**Stack 和 Heap**。搞懂這兩塊各放什麼、怎麼回收、什麼時候爆，這兩種錯誤就再也不用猜。

## 技術背景

先破除一個流傳很廣的說法：「**基本型別存在 Stack，物件存在 Heap**」。前半句是錯的——`int` 存哪裡，取決於它是誰的變數，不是它的型別：

```java
public class Player {
    private int hp = 100;          // ❌ 也是 int，但它是實例變數 —— 跟著物件住在 Heap

    public void attack() {
        int damage = 30;           // ✅ 區域變數的 int，才存在 Stack
    }
}
```

決定位置的是變數的**歸屬**：屬於方法呼叫的（區域變數、參數）在 Stack；屬於物件的（實例變數）跟著物件在 Heap。

### Thread Stack（執行緒堆疊）

每個 thread 各自擁有一個 Stack，結構是先進後出（LIFO）：每呼叫一個方法就疊一個 Stack Frame 上去，方法結束就彈掉。

```
main() 呼叫 a()，a() 呼叫 b()：

│           │
├───────────┤
│ b() Frame │ ← 目前執行中（b 的區域變數、參數、返回位址）
├───────────┤
│ a() Frame │
├───────────┤
│ main Frame│
└───────────┘
```

存放的東西：

- 區域變數（local variables）與方法參數
- 方法返回位址（return addresses）
- 基本型別的**值**、物件的**參考**（reference，不是物件本身）

特性：

- 每個 thread 獨立一份，天生 thread-safe——別的 thread 碰不到你的區域變數
- 生命週期跟著方法走：方法結束 Frame 彈出，記憶體直接釋放，**不需要 GC**
- 大小固定，用 `-Xss` 調整（例如 `-Xss512k`）
- 疊太深放不下時拋 `StackOverflowError`

### Heap（堆積記憶體）

所有 thread **共享**的一大塊記憶體，`new` 出來的東西都住這裡。

存放的東西：

- 物件實例（object instances）與陣列
- 實例變數（跟著物件走）
- 字串常量池（String Pool，Java 7 之後從 PermGen 搬進 Heap）

特性：

- 所有 thread 共享——這也是多執行緒問題的根源：大家改的是同一份
- 物件的生命週期跟方法呼叫**脫鉤**，沒人參考的物件由 **GC** 回收
- 分代管理（Young / Old Generation），基於「大多數物件朝生夕死」的觀察
- 大小可調（`-Xms` 初始 / `-Xmx` 上限）
- 塞不下又回收不出空間時拋 `OutOfMemoryError: Java heap space`

### 還有第三塊會爆：Metaspace（Method Area）

class 結構資訊和 static 變數不住在上面兩塊，而是住在 Method Area——Java 8 之後的實作叫
**Metaspace**，直接用 native memory，預設沒有上限（用 `-XX:MaxMetaspaceSize` 可以設限）。

它爆的時候長這樣：`OutOfMemoryError: Metaspace`。注意成因跟 Heap OOM 完全不同——
不是物件 `new` 太多，而是 **class 載入太多或回收不掉**：

- 動態代理、CGLIB 這類技術在執行期不斷生成新的 class（Spring 應用的常見來源）
- **ClassLoader 洩漏**：只要 ClassLoader 還被參考著，它載入的所有 class metadata
  都回收不掉——webapp 重複部署後的 Metaspace 洩漏就是典型，
  機制詳見 [ClassLoader 的開放性](deep-classloader.md)

完整的記憶體佈局見 [What is JVM](what-is-jvm.md) 的 Runtime Data Areas。

### 對照表

| | Stack | Heap | Metaspace |
|---|---|---|---|
| 歸屬 | 每個 thread 一份 | 所有 thread 共享 | 所有 thread 共享 |
| 存放 | 區域變數、參數、物件參考 | 物件本體、陣列、String Pool | class 結構、static 變數 |
| 回收 | 方法結束自動釋放 | GC | GC（隨 ClassLoader 一起） |
| 溢出錯誤 | `StackOverflowError` | `OutOfMemoryError: Java heap space` | `OutOfMemoryError: Metaspace` |
| 調整參數 | `-Xss` | `-Xms` / `-Xmx` | `-XX:MaxMetaspaceSize` |

## 實際案例

兩種錯誤都可以在十行以內重現。看過一次自己弄爆的，之後在 log 裡認出它們就是反射動作。

### 弄爆 Stack：無窮遞迴

```java
public class StackBomb {
    static int depth = 0;

    static void recurse() {
        depth++;
        recurse();   // 沒有終止條件，Frame 一路疊到 Stack 放不下
    }

    public static void main(String[] args) {
        try {
            recurse();
        } catch (StackOverflowError e) {
            System.out.println("爆掉時的深度：" + depth);   // 預設 -Xss 下約幾萬層
        }
    }
}
```

```bash
java StackBomb.java              # 直接跑
java -Xss256k StackBomb.java     # 把 Stack 調小，爆得更早——證明深度上限由 -Xss 決定
```

實務上 `StackOverflowError` 幾乎都是**遞迴忘了終止條件**，或兩個方法（如 `equals`/`hashCode`、雙向關聯的 `toString`）互相呼叫成環。它是邏輯 bug，調大 `-Xss` 只是延後爆炸。

### 弄爆 Heap：物件只進不出

```java
import java.util.ArrayList;
import java.util.List;

public class HeapBomb {
    public static void main(String[] args) {
        List<byte[]> hoard = new ArrayList<>();   // 這個 reference 一直活著
        while (true) {
            hoard.add(new byte[1024 * 1024]);     // 每圈 1MB，全部回收不掉
            System.out.println("已佔用 " + hoard.size() + " MB");
        }
    }
}
```

```bash
java -Xmx64m HeapBomb.java   # 上限設 64MB，實測數到 29 MB 就 OutOfMemoryError
```

等等，上限 64MB 為什麼 29 MB 就爆？因為 **Heap 不是只住你的物件**——ArrayList 自身的陣列、
JVM 的其他配置都在裡面，而且用 `java HeapBomb.java` 單檔執行時，連編譯器都在同一個 Heap 裡跑。
這正是實務上估容量的教訓：你的資料量只是 Heap 用量的下限。

注意錯誤點在 `new byte[...]` 那行，但問題不是那行——是 `hoard` 這個活著的參考讓 GC 什麼都收不走。這就是 memory leak 在 Java 裡的典型形態：**不是忘了 free（Java 沒有 free），而是忘了斷開參考**。實務上的版本是靜態的 cache、Map 只放不清、listener 註冊了不解除。

## 技術優缺點

為什麼要把記憶體切成兩塊，而不是全部放一起？因為兩塊的設計取捨剛好互補：

**Stack 的取捨**

- ✅ 極快：分配和釋放只是移動指標，而且資料集中、對 CPU cache 友善
- ✅ 零管理成本：方法結束就釋放，不需要 GC 介入
- ❌ 代價是限制：大小固定、thread 私有、生命週期綁死方法——所以只能放「方法活著才有意義」的東西

**Heap 的取捨**

- ✅ 彈性：物件可以活得比建立它的方法久，可以被多個 thread、多個物件共享
- ❌ 代價是管理成本：需要 GC 追蹤誰還活著，回收有停頓；分配位置不連續，存取比 Stack 慢

一個變數該住哪，本質上是問「它的生命週期和誰綁定」——和方法綁定的住 Stack，需要獨立存活或共享的住 Heap。語言幫你做了這個決定，但讀懂效能問題時你得知道這個決定的代價。

## 小結

- 位置由變數的**歸屬**決定，不是型別：區域變數（含 primitive 值和物件參考）在 Stack，物件本體和實例變數在 Heap
- Stack 每個 thread 一份、方法結束自動釋放、不用 GC；Heap 全 thread 共享、由 GC 管
- `StackOverflowError` ＝ Frame 疊太深，幾乎都是遞迴/互呼成環的邏輯 bug
- `OutOfMemoryError` 先看冒號後面：`Java heap space` 找不死的參考（cache、static 集合）；`Metaspace` 找 class 生成來源或 ClassLoader 洩漏
- 一句話記憶：**變數名在 Stack，物件本體在 Heap；`new` 出來的都在 Heap，指過去的參考在 Stack**

Heap「由 GC 管」這句話背後是一整套學問：GC 怎麼判斷誰還活著？分代是怎麼分的？回收時為什麼會停頓？見規劃中的〈GC 基礎與分代回收〉。而「所有 thread 共享 Heap」帶來的可見性問題，見規劃中的〈Java Memory Model 與 happens-before〉。

## 常見面試題

1. Stack 和 Heap 的差別？（提示：歸屬、存放內容、回收方式三個維度）
2. `StackOverflowError` 和 `OutOfMemoryError` 分別什麼時候發生？怎麼排查？（提示：一個看 stack trace 找環；OOM 再依冒號後綴分流——heap space 找不死的參考、Metaspace 找 class/loader 洩漏）
3. 「基本型別都存在 Stack」這句話對嗎？（提示：實例變數裡的 `int` 住哪？）

## 延伸閱讀

- [JVM Specification §2.5: Run-Time Data Areas](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-2.html#jvms-2.5) — Stack、Heap 與其他區域的官方定義
- [java 指令的 -X 選項（Oracle 文件）](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#extra-options-for-java) — `-Xss`、`-Xms`、`-Xmx` 的官方說明
