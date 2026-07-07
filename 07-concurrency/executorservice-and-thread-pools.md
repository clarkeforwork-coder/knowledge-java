# ExecutorService 與執行緒池參數

## 前言

[Thread 篇](thread-basics-and-lifecycle.md)算過帳：一條 thread 約 1MB stack、建立要系統呼叫。現在把帳放進真實場景——批次寄十萬封通知，「來一個任務開一條 thread」的寫法會怎樣？輕則 context switch 把 CPU 吃光，重則 `OutOfMemoryError: unable to create native thread` 直接把整個 JVM 帶走。

答案人人都知道：用執行緒池。但執行緒池有一道大多數人答錯的題目：**core 滿了之後，下一個任務是先排隊、還是先加人？** 這篇用實測回答，順便講清楚為什麼企業編碼規約普遍禁用 `Executors.newFixedThreadPool()`。

## 技術背景

### 基本用法：把任務交給池

```java
ExecutorService pool = ...;                          // 建法見下——別用工廠方法
pool.execute(() -> doWork());                        // Runnable：射後不理
Future<Report> f = pool.submit(() -> buildReport()); // Callable：有回傳值
Report r = f.get();                                  // 阻塞等結果（Thread 篇欠的 Callable 在這）

pool.shutdown();                                     // 不收新任務，做完存量才停
```

兩個常踩的細節：**忘了 shutdown，JVM 不會退出**（池裡的 thread 不是 daemon）；還有 `submit` 版的任務**例外會被吞進 Future**——沒人呼叫 `get()` 就沒人知道它炸了，log 一片安靜。射後不理的任務用 `execute`（例外走 uncaughtExceptionHandler），或在任務裡自己 try-catch。

### ThreadPoolExecutor 七參數

```java
new ThreadPoolExecutor(
        corePoolSize,      // 常駐人力
        maximumPoolSize,   // 尖峰人力上限
        keepAliveTime, unit, // 超出 core 的 thread 閒多久資遣
        workQueue,         // 等候區（一定要有界！）
        threadFactory,     // 給 thread 命名用
        rejectionHandler)  // 全滿時怎麼辦
```

### 任務進來的決策流程：先排隊，再加人

```
任務來了
  │
  ├─ 目前人數 < core？ ──是──> 開新 thread 直接做
  │        否
  ├─ 等候區還有位？ ────是──> 進隊列排隊          ← 注意：不是先加人！
  │        否
  ├─ 人數 < max？ ──────是──> 開新 thread 直接做   ← 排隊的人看著新任務插隊
  │        否
  └─ 拒絕策略出場
```

多數人的直覺是「先把人加到 max、不夠才排隊」——**實際相反**。推論很重要：`max` 只有在**隊列滿了**之後才有意義；隊列無界，`max` 形同虛設。這正是工廠方法的問題：

| 工廠方法 | 隱藏設定 | 風險 |
|---|---|---|
| `newFixedThreadPool(n)` | 隊列無界（`LinkedBlockingQueue`） | 任務堆積 → **OOM**（實測收下十萬個不吭聲） |
| `newSingleThreadExecutor()` | 同上 | 同上 |
| `newCachedThreadPool()` | 隊列容量 0、max 無上限 | 任務一多狂開 thread → **OOM** |

所以企業編碼規約（如阿里巴巴 Java 開發手冊）明令：**手動 `new ThreadPoolExecutor`，讓七個參數攤在陽光下**。

### 拒絕策略：全滿是設計出來的，不是意外

| 策略 | 行為 | 適用 |
|---|---|---|
| `AbortPolicy`（預設） | 拋 `RejectedExecutionException` | 快速失敗，呼叫端自己看著辦 |
| `CallerRunsPolicy` | **叫提交者自己跑** | 天然背壓——提交者被拖住，上游自動放慢 |
| `DiscardPolicy` / `DiscardOldest` | 靜默丟掉（新的／最舊的） | 可丟棄的任務（如採樣）；**預設別用**，丟資料無聲無息 |

`CallerRunsPolicy` 值得認識：它把「池忙不過來」轉化成「上游變慢」，是最簡單的限流手段。

### 參數怎麼定

- **CPU-bound**（純運算）：`core ≈ CPU 核心數`——人再多也只是排隊搶 CPU（[Stream 誤用篇](../06-lambda-and-stream/stream-pitfalls.md)的 parallel 同理）
- **IO-bound**（等 DB、等 API）：thread 大部分時間在等，`core ≈ 核心數 ×（1 ＋ 等待時間/計算時間）`——等待占比越高開越多
- **隊列**：一定有界，大小＝「你願意讓任務等多久」的具象化
- **threadFactory 給名字**：`jstack` 裡的 `pool-1-thread-3` 誰都不認得，`notify-pool-3` 一眼定位——這是給[排查工具箱](../01-jvm/troubleshooting-toolbox.md)的未來的你留的路標
- 定完**壓測驗證**，公式只是起點

## 實際案例

### 案例一：決策流程實測——後到的先跑

`core=2, max=4, queue=2`，一口氣丟 8 個任務（每個做 400ms）：

```
任務 1 開始（pool-1-thread-1）   ← core
任務 6 開始（pool-1-thread-4）   ← 隊列滿了，開新 thread「插隊」先跑
任務 7 被拒絕！                  ← 4 人＋2 位全滿
任務 5 開始（pool-1-thread-3）   ← 同上，插隊
任務 2 開始（pool-1-thread-2）   ← core
任務 8 被拒絕！
任務 3 開始（pool-1-thread-3）   ← 排隊的 3、4 反而最晚跑
任務 4 開始（pool-1-thread-1）
```

對照決策流程逐格驗證：1、2 佔滿 core；3、4 進隊列；5、6 觸發加人**直接執行**；7、8 觸發拒絕。最反直覺的證據就在輸出順序裡——**排在隊列裡的 3、4，眼睜睜看著後來的 5、6 先跑**。如果你的任務有順序性期待，這個行為必須知道。

### 案例二：無界隊列的沉默

```java
ExecutorService fixed = Executors.newFixedThreadPool(1);
fixed.submit(卡住唯一的 thread);
for (int i = 0; i < 100_000; i++) fixed.submit(() -> { });   // 十萬個任務
```

實測：

```
塞了 10 萬個任務，全部被收下，隊列長度 = 100,000（一個都沒拒絕）
```

沒有例外、沒有 log、沒有任何訊號——隊列就這樣長到十萬。生產環境的版本是：下游變慢 → 任務進得快出得慢 → 隊列悄悄膨脹 → 幾小時後 OOM，而且 [heap dump](../01-jvm/troubleshooting-toolbox.md) 裡兇手是一整包排隊的任務物件。**有界隊列＋明確的拒絕策略，就是把這場事故換成一個當下可見的錯誤**。

## 技術優缺點

### 執行緒池給你的

- **重用**：thread 建一次跑千個任務，1MB 的帳只付 core 份
- **限流**：max ＋有界隊列＝系統的過載保護閥
- **管理**：統一命名、監控（`getActiveCount`/`getQueue().size()` 都是現成的健康指標）

### 新的失敗模式（池不是免費的）

- **參數不當的兩種死法**：隊列無界 → 堆積 OOM；`cached` 式無上限 → thread 爆炸 OOM
- **`submit` 吞例外**：Future 沒人 get，任務炸了無聲無息
- **ThreadLocal 洩漏**：pooled thread 不死，ThreadLocal 不清（`remove()`）就跨任務殘留——Web 應用的經典事故
- **池內互等死結**：任務 A 提交任務 B 到同一個池再等它——池滿時 A 佔著位等 B，B 排不進來，整池凍結

## 小結

- Thread 貴 → 池重用；但**先排隊再加人**：core 滿進隊列、隊列滿才加到 max、max 滿才拒絕——實測隊列裡的任務被後來者插隊
- **`max` 在隊列無界時形同虛設**——這就是 `Executors` 工廠方法被企業規約禁用的原因（實測十萬任務照單全收）
- 手動 `new ThreadPoolExecutor`：有界隊列、明確拒絕策略（`CallerRunsPolicy` 是天然背壓）、threadFactory 給名字
- 參數起點：CPU-bound 開核心數、IO-bound 按等待占比放大，**壓測收尾**
- 別忘 shutdown（JVM 不退出）、`submit` 的例外在 Future 裡（沒 get 就沒人知道）

池解決了「thread 怎麼供給」，下一個問題是「多條 thread 共享的資料怎麼辦」——[synchronized 篇](synchronized-and-volatile.md)的鎖太粗，集合有專門的並發版本：見規劃中的〈並發集合：ConcurrentHashMap〉。

## 常見面試題

1. ThreadPoolExecutor 的七個參數？任務進來的完整決策流程？（提示：先排隊再加人；實測「後到的先跑」）
2. 為什麼不建議用 `Executors` 的工廠方法建池？（提示：無界隊列／無上限；兩種 OOM 死法）
3. 拒絕策略有哪些？`CallerRunsPolicy` 有什麼特別的價值？（提示：四種；背壓）

## 延伸閱讀

- [ThreadPoolExecutor（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) — 類別註解本身就是一篇完整的使用指南
- [Executors（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Executors.html) — 對照著看工廠方法各自的隱藏設定
