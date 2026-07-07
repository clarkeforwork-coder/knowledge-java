# Thread 基礎與生命週期

## 前言

「我還沒學並發」是個錯覺。回頭看 [01 章排查工具箱](../01-jvm/troubleshooting-toolbox.md)的 jstack 輸出——你那支「單執行緒」的程式裡躺著十幾條 thread：main、GC 執行緒、JIT 編譯執行緒。而你日常維護的 Web 應用，**每個 HTTP request 就是一條 thread**——多執行緒不是進階選修，是你一直身處其中的預設環境。

07 章要回答的核心問題只有一個：**多條 thread 同時碰同一份資料，會發生什麼、怎麼避免**。這一篇先把地基打好——thread 怎麼建、有哪些狀態、怎麼正確地停——最後用一個實測讓你看到災難的長相：兩條 thread 各加 50 萬，結果不到 51 萬。

## 技術背景

### 建立 thread：把「要做的事」跟「執行者」分開

```java
// 建議寫法：Runnable 描述要做的事（它是 functional interface，lambda 直接上）
Thread t = new Thread(() -> System.out.println("工作內容"));
t.start();

// ❌ 不建議：繼承 Thread —— 把「事」和「執行者」綁死，也佔掉唯一的繼承位
class MyThread extends Thread { ... }
```

[06 章](../06-lambda-and-stream/functional-interface-and-lambda.md)的知識直接複用：`Runnable` 就是一個 `void run()` 的 functional interface。至於有回傳值的任務（`Callable`）——它跟執行緒池是一組的，下下篇一起講。

### start() 才是開執行緒，run() 只是普通方法

最經典的初學混淆：

- **`start()`**：向 JVM 申請一條新的 OS thread，在**新執行緒**上執行 `run()`
- **`run()`**：就是個普通方法呼叫——在**當前執行緒**同步執行，什麼都沒開（實測見案例一）

### 生命週期：六個狀態

```
        start()          搶到 CPU ⇄ 讓出
NEW ──────────> RUNNABLE ──────────────> TERMINATED
                 │  ▲                      （run 結束）
    等 synchronized 鎖 │
                 ▼  │
              BLOCKED
                 │  ▲
   wait()/join() │  │ 被喚醒/等到了
                 ▼  │
       WAITING / TIMED_WAITING（有時限版：sleep、帶 timeout 的 wait/join）
```

這六個狀態就是 `Thread.State`，也正是 [排查工具箱](../01-jvm/troubleshooting-toolbox.md)裡 jstack 每條 thread 第一行寫的東西——**現在你知道那張「BLOCKED 在等鎖、WAITING 在等事」速讀表的完整版圖了**。兩個常被混淆的區分：

- **RUNNABLE ≠ 正在跑**：它包含「正在 CPU 上」和「排隊等 CPU」——Java 不區分這兩者
- **BLOCKED vs WAITING**：BLOCKED 是**被動的**（想進 synchronized 進不去）；WAITING 是**主動的**（自己呼叫了 wait/join，等人喚醒）

### 常用操作與它們的細節

| 操作 | 做什麼 | 容易忽略的細節 |
|---|---|---|
| `sleep(ms)` | 睡一下（TIMED_WAITING） | **不釋放持有的鎖**——拿著鎖睡覺，別人全卡住 |
| `join()` | 等某條 thread 做完 | 案例三靠它確保兩條加完才驗收 |
| `interrupt()` | **請求**中斷 | 是合作式的——對方要自己檢查、自己決定停（見下） |
| `setDaemon(true)` | 標為守護執行緒 | JVM 不等 daemon——所有非 daemon 結束就退出，GC 執行緒就是 daemon |

**interrupt 是請求不是命令**：它只是把對方的中斷旗標設起來（或讓睡眠中的對方收到 `InterruptedException`），對方配合才會停。處理 `InterruptedException` 的鐵律——**別吞掉**：

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // ✅ 至少把旗標復原，讓上層知道有人要求停
}
// ❌ catch 後什麼都不做 = 把「請停下」的訊息銷毀，執行緒池的取消機制全失效
```

### counter++ 為什麼會掉資料

`counter++` 看起來一行，實際是三步：**讀出來 → 加一 → 寫回去**。兩條 thread 同時做：

```
Thread A：讀到 100          Thread B：讀到 100
Thread A：算出 101          Thread B：算出 101
Thread A：寫回 101          Thread B：寫回 101   ← 兩次加只留下一次
```

這叫 **race condition**——結果取決於誰先誰後的運氣。實測見案例三，解法是下一篇的主題。

## 實際案例

### 案例一：start() vs run()

```java
Runnable task = () -> System.out.println("執行者：" + Thread.currentThread().getName());
new Thread(task, "worker").run();     // ？
new Thread(task, "worker").start();   // ？
```

實測輸出：

```
呼叫 run()  ：執行者：main      ← 根本沒開新執行緒，main 自己做了
呼叫 start()：執行者：worker    ← 這才是新執行緒
```

### 案例二：親眼看狀態轉換

讓一條 thread 先睡 300ms、再去搶一把 main 佔住的鎖，沿路用 `getState()` 拍照：

```
start 前   ：NEW
sleep 中   ：TIMED_WAITING    ← 睡眠有時限
等鎖中     ：BLOCKED          ← main 拿著鎖不放，它進不去
結束後     ：TERMINATED
```

下次 jstack 看到一條 thread 卡在 BLOCKED，你知道它在等誰了——[排查工具箱](../01-jvm/troubleshooting-toolbox.md)的死結案例就是兩條 thread 互相 BLOCKED。

### 案例三：race condition 的第一次見面

```java
static int counter = 0;
Runnable add = () -> { for (int i = 0; i < 500_000; i++) counter++; };
// 兩條 thread 同時跑，join 等它們都做完
```

實測輸出：

```
兩條 thread 各加 50 萬：counter = 508,028（應為 1,000,000）
```

**掉了快一半**，而且沒有任何例外、每次跑掉的量都不同——跟 [Stream 誤用篇](../06-lambda-and-stream/stream-pitfalls.md)的 parallel 資料遺失是同一族的病：**共享可變狀態＋沒有同步**。怎麼治？`synchronized`、`volatile`、原子類——下一篇正面迎戰。

## 技術優缺點

### Thread 給你的

- 真正的並行（多核心同時算）、阻塞操作不卡住整個程式（一條在等 IO，其他照跑）

### Thread 的成本——為什麼不能想開就開

- **記憶體**：每條 thread 一個 stack，預設約 1MB（[01 章](../01-jvm/memory-stack-and-heap.md)的 `-Xss`）——開一萬條就是 10GB 等級的帳
- **建立與切換**：platform thread 是 OS thread 的一比一包裝，建立要系統呼叫，context switch 要換暫存器、洗 cache
- **這正是兩個後續主題的存在理由**：thread 貴 → 重複利用 → **執行緒池**（下下篇）；一比一包裝太重 → 千倍輕量的 **Virtual Threads**（Java 21，本章壓軸）

## 小結

- 你從第一天就在多執行緒環境裡：main 之外有 GC/JIT threads，Web 應用一個 request 一條 thread
- **`start()` 開新執行緒，`run()` 只是普通方法**（實測：run 的執行者是 main）
- 六狀態對應 jstack 所見：**BLOCKED 被動等鎖、WAITING 主動等事**、RUNNABLE 不保證在跑
- `sleep` 不放鎖；`interrupt` 是合作式請求，`InterruptedException` **別吞**（至少復原旗標）
- `counter++` 是三步操作——兩條 thread 各加 50 萬實測剩 508,028，race condition 沒有例外、沒有規律
- thread 每條約 1MB stack、建立切換都貴——執行緒池與 Virtual Threads 的伏筆

災難已經親眼看過，接下來是武器：`synchronized` 怎麼把三步變一步、`volatile` 管的是另一件事（可見性）——見 [synchronized 與 volatile](synchronized-and-volatile.md)。

## 常見面試題

1. `start()` 和 `run()` 的差別？（提示：誰開新執行緒；run 的執行者實測是誰）
2. Thread 有哪六個狀態？BLOCKED 和 WAITING 差在哪？（提示：被動等鎖 vs 主動等事；jstack 排查的分流）
3. `interrupt()` 之後對方一定會停嗎？`InterruptedException` 該怎麼處理？（提示：合作式；復原旗標）

## 延伸閱讀

- [Thread（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Thread.html) — 全部操作的契約與棄用警告
- [Thread.State（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Thread.State.html) — 六狀態的官方定義與轉換條件
