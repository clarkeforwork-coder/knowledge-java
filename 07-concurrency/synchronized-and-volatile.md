# synchronized 與 volatile

## 前言

[上一篇](thread-basics-and-lifecycle.md)留下的懸案：兩條 thread 各加 50 萬，counter 只剩 508,028。直覺的答案是「加鎖」——沒錯，但這裡埋著一個更深的誤解：很多人以為 `volatile` 是「輕量版的鎖」，用它來「優化」——**這篇會用實測讓你看到 volatile 版的 counter 照樣掉到 52 萬**。

synchronized 和 volatile 解的是**兩個不同的問題**。分不清這兩個問題，工具就會用錯地方。

## 技術背景

### 並發的三個問題

| 問題 | 白話 | 例子 |
|---|---|---|
| **原子性** | 多步操作被人插隊 | `counter++`（讀→加→寫三步） |
| **可見性** | 我改了，你看不到 | 一條 thread 改旗標，另一條的迴圈永遠讀到舊值 |
| **有序性** | 程式碼被重排 | 編譯器/CPU 為了效能調換執行順序（本篇點到為止，完整理論見規劃中的 🔬〈Java Memory Model 與 happens-before〉） |

可見性值得多說一句為什麼會發生：每個 CPU 核心有自己的 cache，JIT 還可能把「反覆讀同一個變數」優化成「讀一次存暫存器」——於是 thread A 寫回主記憶體的新值，thread B 根本不會再去看（實測見案例二，迴圈真的停不下來）。

### synchronized：互斥＋可見性，一次包辦

```java
synchronized (LOCK) {      // 同一時刻只有一條 thread 進得來
    counter++;             // 三步操作不再被插隊
}                          // 出門時：改動全部寫回；下一位進門時：重新讀取
```

兩個保證：**互斥**（原子性——同一把鎖同一時刻一人）＋**可見性**（進出鎖等於同步記憶體）。要點是**鎖的是物件，不是程式碼**：

| 寫法 | 鎖的是誰 |
|---|---|
| `synchronized` 實例方法 | `this`（這個物件） |
| `synchronized` static 方法 | `類別.class` 物件 |
| `synchronized (obj) {}` | 你指定的 obj |

由此推出最常見的翻車方式：**兩條 thread 鎖了不同物件＝沒有鎖**。兩個實例各自 `synchronized(this)` 去改同一個 static 變數，互不擋路，照樣掉資料。守則：**保護哪份資料，就用「跟那份資料同生命週期的同一個物件」當鎖**——最穩的寫法是 `private static final Object LOCK = new Object()`（保護 static 資料）或 `private final Object lock`（保護實例資料）。順帶一提，別拿 String 字面值或 `Integer` 當鎖——它們可能被池共享，你以為的私鎖其實是公用的。

### volatile：只管可見性，完全不管原子性

```java
static volatile boolean running = true;   // 寫直達主記憶體；讀不走快取
```

volatile 保證「一人寫、大家立刻看得到」——它的主場是**狀態旗標**：一條 thread 負責改（`running = false`），其他 thread 只讀。但它對 `counter++` 無能為力：三步還是三步，插隊照樣發生（實測：523,519）。**判斷準則一句話：這個操作是「純寫入新值」還是「依賴舊值算新值」？後者 volatile 救不了。**

### AtomicInteger：第三個工具

`counter++` 場景的正解其實是它：

```java
AtomicInteger counter = new AtomicInteger();
counter.incrementAndGet();     // 硬體級的 CAS：比較後交換，一步完成、無鎖
```

CAS（Compare-And-Swap）是 CPU 指令級的「如果值還是我剛讀到的，就換成新值，否則重來」——不阻塞、不排隊。單一變數的原子更新，Atomic 家族（`AtomicInteger`/`AtomicLong`/`AtomicReference`）幾乎總是比鎖好（實測快 4 倍）。

## 實際案例

### 案例一：四路對決——破 508,028 懸案

同一個「兩條 thread 各加 50 萬」，四種寫法實測：

```
不加任何東西    ：  3 ms    plain        = 556,958   ❌ 掉資料
volatile        ：  7 ms    volatile     = 523,519   ❌ 照樣掉！
synchronized    ： 35 ms    synchronized = 1,000,000 ✅
AtomicInteger   ：  8 ms    atomic       = 1,000,000 ✅
```

兩個帶走訊息：**volatile 不能修原子性問題**（掉得跟沒加一樣多）；synchronized 和 Atomic 都對，但 **Atomic 快了 4 倍**——無鎖 CAS vs 排隊進鎖的差距。單變數計數，用 Atomic。

### 案例二：停不下來的迴圈——可見性的經典現場

```java
static boolean plainFlag = true;                          // 沒有 volatile

new Thread(() -> { while (plainFlag) { } }).start();      // 空轉等旗標
Thread.sleep(200);
plainFlag = false;                                        // main 改了旗標
```

實測：

```
沒 volatile：改旗標 1 秒後，迴圈【還在跑】   ← 它永遠看不到 false 了
加 volatile：改旗標後，迴圈停了
```

main 明明改了，那條 thread 卻永遠讀到舊值——JIT 把迴圈裡的欄位讀取提升成了暫存器讀取。恐怖之處：**這在 debug 模式、加了 println 之後常常「自己好了」**（那些操作恰好製造了同步點），是典型的「觀察它就消失」的 bug。停機旗標永遠加 volatile，這是 volatile 的本命場景。

## 技術優缺點

| 工具 | 保證 | 代價 | 適用 |
|---|---|---|---|
| `synchronized` | 原子性＋可見性 | 阻塞排隊、鎖競爭下吞吐掉（實測 35ms 最慢）| 複合邏輯、多變數一致性——**預設的安全選擇** |
| `volatile` | 只有可見性 | 幾乎免費（7ms） | 一寫多讀的旗標、狀態發布 |
| `Atomic` 家族 | 單變數原子性＋可見性 | CAS 高競爭下空轉重試 | 計數器、單一參考的原子更新 |

三個補充：

- synchronized 的「慢」是相對的——現代 JVM 有一整套鎖優化（偏向鎖已移除、輕量鎖、自旋），無競爭時成本很低；細節見規劃中的 🔬〈synchronized 鎖升級〉
- Atomic 的邊界：**check-then-act 的複合邏輯它救不了**——「如果餘額夠就扣款」是兩步，還是要鎖（或 `compareAndSet` 迴圈）
- 粒度紀律：鎖的範圍越小越好（鎖住整個方法 vs 只鎖那三行），但**正確性優先於粒度**——先對，再快

## 小結

- 並發三問題分開想：**原子性**（被插隊）、**可見性**（看不到）、**有序性**（被重排）——工具各管各的
- **volatile 不是輕量鎖**：只管可見性，`counter++` 實測照樣掉到 523,519；它的本命是停機旗標（實測不加就停不下來）
- **synchronized 鎖的是物件不是程式碼**：實例方法鎖 this、static 方法鎖 class、鎖不同物件＝沒鎖；用 `private final Object` 當鎖最穩
- 單變數原子更新用 **Atomic**（CAS 無鎖，實測比 synchronized 快 4 倍）；複合邏輯回到 synchronized
- 508,028 懸案破案：synchronized 與 Atomic 都給出 1,000,000

「進出鎖等於同步記憶體」「volatile 寫直達主記憶體」這些白話背後有一套精確的規則——happens-before。想知道 JVM 到底承諾了什麼、重排序的邊界在哪，見規劃中的 🔬〈Java Memory Model 與 happens-before〉。而下一個實務問題是：thread 這麼貴（上一篇算過帳），任務又這麼多——見 [ExecutorService 與執行緒池參數](executorservice-and-thread-pools.md)。

## 常見面試題

1. synchronized 鎖的是什麼？三種寫法各鎖誰？（提示：物件不是程式碼；鎖錯物件＝沒鎖）
2. volatile 能保證原子性嗎？它適合什麼場景？（提示：實測 523,519；一寫多讀的旗標）
3. counter++ 的三種修法比較？（提示：四路對決的數據；Atomic 為什麼快——CAS 無鎖）

## 延伸閱讀

- [java.util.concurrent.atomic（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/atomic/package-summary.html) — Atomic 家族全目錄與記憶體效應說明
- [JLS §17.4: Memory Model](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4) — volatile 與 synchronized 語意的最終依據（🔬 JMM 篇的預習材料）
