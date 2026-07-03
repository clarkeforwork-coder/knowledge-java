# GC 基礎與分代回收

## 前言

打開任何一個 Java 服務的記憶體監控圖，你會看到一條鋸齒狀的曲線：用量緩緩爬升，然後瞬間掉下來，再爬升、再掉。另一個常見的現場是：服務平常好好的，偶爾整個卡住幾百毫秒，log 裡什麼都沒有，過了又恢復正常。

這兩個現象是同一件事：**GC 在工作**。鋸齒的每一次下降是一次回收，卡住的那幾百毫秒是回收時的停頓。前兩篇筆記都在門口停下來——[What is JVM](what-is-jvm.md) 說 Heap「由 GC 管」，[記憶體：Stack 與 Heap](memory-stack-and-heap.md) 說沒人參考的物件「由 GC 回收」。這篇來回答被跳過的問題：GC 怎麼知道誰沒人參考？為什麼要分代？為什麼會停頓？

## 技術背景

先破除一個直覺上的誤解：**GC 不是去「找垃圾」的**。

垃圾沒辦法直接找——一個物件是不是垃圾，取決於「有沒有人參考它」，你得問遍所有可能的參考者才能確定。所以主流 GC 反過來做：**從已知活著的起點出發，把摸得到的物件全部標記為活的，剩下沒被標到的，整批就是垃圾**。GC 找的是活人，不是垃圾。

### 誰活著：GC Roots 與可達性

那「已知活著的起點」是誰？稱為 **GC Roots**，主要包括：

- 每個 thread 的 Stack 上的區域變數和參數（方法還在跑，它們當然活著）
- static 變數（class 還載入著就活著）
- 活著的 thread 本身、JNI 參考等

從 GC Roots 沿著參考一路走，走得到的就是**可達（reachable）**——活的；走不到的就是垃圾，等著被回收：

```
GC Roots（Stack 變數、static…）
   │
   ├──> A ──> B          ← A、B 可達，活
   │
   └──> C                ← C 可達，活

        D <──> E         ← D、E 互相參考，但從 Roots 走不到 —— 垃圾
```

注意 D 和 E：**循環參考不是問題**。它們互相抓著對方，但只要從 Roots 走不到，整組都會被回收——這是可達性分析對比「引用計數」方案的關鍵優勢。

反過來說，這也解釋了 [記憶體：Stack 與 Heap](memory-stack-and-heap.md) 裡 `HeapBomb` 的教訓：Java 的 memory leak 不是「忘了釋放」，而是**可達但沒用**——static 的 cache、只進不出的 Map，從 Roots 永遠走得到，GC 就永遠不敢收。

### 分代假說：大多數物件朝生夕死

觀察真實程式會發現一個規律：**絕大多數物件的壽命極短**——方法裡的暫存物件、迴圈裡的字串、一次請求用完就丟的 DTO。真正長壽的物件（連線池、快取、Spring 的 singleton bean）是少數。

如果每次回收都掃整個 Heap，等於為了收一屋子的免洗餐具，連傳家的家具都翻一遍。所以 Heap 被切成兩代，分開對待：

```
Heap
├── Young Generation（新生代）—— 物件出生地，回收頻繁
│   ├── Eden          ← new 出來的物件先住這
│   ├── Survivor S0   ← Minor GC 的倖存者在 S0/S1 之間搬家
│   └── Survivor S1
└── Old Generation（老年代）—— 活夠久的物件搬來這，回收少但貴
```

一個物件的一生：在 **Eden** 出生 → Eden 滿了觸發 **Minor GC**，死掉的直接消失，活著的搬進 **Survivor** 並記一歲 → 之後每熬過一次 Minor GC 加一歲 → 活過門檻歲數（或 Survivor 裝不下）就**晉升（promotion）**到 **Old Generation** 養老。

兩種回收的對比：

| | Minor GC | Full GC |
|---|---|---|
| 範圍 | 只掃 Young | 整個 Heap（含 Old、Metaspace） |
| 頻率 | 頻繁（Eden 滿就觸發） | 少（Old 滿、晉升失敗時） |
| 速度 | 快（活的少，搬完就好） | 慢（全部都要看） |
| 對服務的影響 | 通常毫秒級，無感 | 停頓明顯，「偶爾卡一下」的主嫌 |

### Stop-the-World：為什麼會卡

標記「誰可達」的時候，物件之間的參考關係不能一邊被程式改一邊被 GC 讀——不然剛標完的圖馬上就不準了。所以 GC 的關鍵階段需要把所有應用 thread 暫停，這就是 **Stop-the-World（STW）**。

**每一種 GC 都有 STW，差別只在停多久、停幾次。** 現代收集器（G1、ZGC）的演化方向就是把大停頓拆小、把工作挪到與應用並行的階段去做——各家策略的比較見規劃中的〈GC 演算法比較（G1、ZGC）〉。對 🔰 讀者，先建立這個判斷：**Minor GC 的停頓通常無感，讓服務「偶爾卡一下」的多半是 Full GC**——它出現在 log 裡就值得查。

### 部署時該設的參數

- `-Xms` 與 `-Xmx` 設成一樣：省掉 Heap 動態擴縮的成本，也讓容量問題早點暴露
- 容器環境用 `-XX:MaxRAMPercentage=75.0` 這類比例參數，跟著容器記憶體限額走，
  記得留空間給 Heap 以外的部分（Metaspace、thread stack、native）
- `-XX:+HeapDumpOnOutOfMemoryError`：OOM 當下自動留 heap dump，事後才有東西可查
- `-Xlog:gc`：開 GC log，成本極低，出事時它就是你的行車記錄器

容量怎麼估？回想 [記憶體：Stack 與 Heap](memory-stack-and-heap.md) 的教訓——你的資料量只是下限。實際做法是看 GC log 裡**回收後剩下的量**（那才是真正活著的），乘上安全係數。

## 實際案例

▶ 可執行範例：[GcPressureDemo.java](examples/GcPressureDemo.java)

```java
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
```

這個程式在迴圈裡製造了約 1GB 的短命物件，只留下 40 個長壽的。用 64MB 的 Heap 跑它、打開 GC log：

```bash
java -Xmx64m -Xlog:gc GcPressureDemo.java
```

實測輸出（Temurin 17，節錄）：

```
[0.004s][info][gc] Using G1
[0.151s][info][gc] GC(0)  Pause Young (Normal) (G1 Evacuation Pause) 23M->3M(64M) 1.444ms
[0.153s][info][gc] GC(1)  Pause Young (Normal) (G1 Evacuation Pause) 33M->3M(64M) 1.001ms
[0.154s][info][gc] GC(3)  Pause Young (Normal) (G1 Evacuation Pause) 40M->3M(64M) 0.087ms
...
[0.163s][info][gc] GC(19) Pause Young (Normal) (G1 Preventive Collection) 58M->5M(64M) 0.102ms
[0.164s][info][gc] GC(20) Pause Young (Normal) (G1 Preventive Collection) 58M->6M(64M) 0.092ms
結束，長壽物件共 40 個
```

一行 GC log 的讀法，以 `GC(0)` 為例：

```
Pause Young (Normal)   23M -> 3M (64M)   1.444ms
└─ 回收類型：Minor GC   │      │    │       └─ 停頓時間
   （只掃 Young）        │      │    └─ Heap 總大小
                        │      └─ 回收後用量 ≈ 真正活著的量
                        └─ 回收前用量
```

三個值得注意的觀察：

1. **鋸齒的由來**：用量衝到 58M 附近就被打回 3～6M——監控圖上的鋸齒，就是這個循環
2. **1GB 的垃圾，21 次毫秒級停頓就處理完了**——朝生夕死的物件死在 Eden，回收成本極低，這就是分代假說的紅利
3. **回收後的量從 3M 緩慢爬到 6M**——那是 40 個 survivor 逐步累積、晉升的痕跡。這條「地板」就是你服務真正的存活集：**地板平穩 = 健康；地板不停爬升 = memory leak 的形狀**，接下來就該抓 heap dump 了（見規劃中的〈事故排查工具箱〉）

## 技術優缺點

### 分代設計的優勢

- **把常見情況做到最快**：Minor GC 只掃 Young，而 Young 裡大多是死的——掃描少、搬移少，毫秒級完成
- **成本跟著存活量走，不是垃圾量**：複製式回收只搬活人，垃圾再多都不花時間（上面 1GB 垃圾 21 次毫秒級停頓就是證明）
- **自動記憶體安全**：沒有 dangling pointer、double free 這些 C/C++ 的經典災難

### 代價

- **STW 停頓不可控**：GC 什麼時候跑、停多久，應用說了不算——對延遲敏感的系統，長尾延遲常常就是 GC 貢獻的
- **分代需要簿記**：Old 指向 Young 的跨代參考得額外記錄（不然 Minor GC 就得掃 Old），這是寫入時的隱形成本
- **GC 救不了「可達的洩漏」**：static cache、忘了移除的 listener——GC 只認可達性，不認「你還用不用」
- **調參是雙面刃**：Heap 越大，單次 Full GC 越久；越小，GC 越頻繁——沒有免費的方向，只能用 GC log 實測

## 小結

- GC 用**可達性**判生死：從 GC Roots 走得到的活、走不到的收；循環參考不是問題，「可達但沒用」才是（memory leak 的真面目）
- **分代假說**：大多數物件朝生夕死 → Eden 出生、Survivor 熬歲數、活夠久晉升 Old
- **Minor GC 快又頻繁、Full GC 慢又要命**——「偶爾卡一下」先懷疑 Full GC
- 每種 GC 都有 **Stop-the-World**，差別只是長短；現代收集器都在把停頓拆小、挪去並行
- GC log 一行的重點：`回收前 -> 回收後(總量) 停頓`——**回收後的量是地板，地板爬升就是洩漏的形狀**

下一步有兩條線：想知道 G1、ZGC 這些收集器各自怎麼把停頓變短，見規劃中的〈GC 演算法比較（G1、ZGC）〉；想知道地板爬升之後怎麼辦——jps、jstack、jmap、heap dump 的事故現場操作，見規劃中的〈事故排查工具箱〉。

## 常見面試題

1. GC 怎麼判斷一個物件可以被回收？（提示：可達性分析、GC Roots 有哪些；順便答為什麼循環參考不是問題）
2. Minor GC 和 Full GC 的差別？為什麼 Minor GC 快？（提示：範圍、頻率，加上「成本跟著存活量走」）
3. 物件什麼時候會從 Young 晉升到 Old？（提示：熬過的 GC 次數、Survivor 空間不足時的提前晉升）

## 延伸閱讀

- [HotSpot Virtual Machine Garbage Collection Tuning Guide（Java 17）](https://docs.oracle.com/en/java/javase/17/gctuning/) — 分代、各收集器與調校的官方指南
- [java 指令的 -Xlog 選項](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#enable-logging-with-the-jvm-unified-logging-framework) — GC log 的完整開法
