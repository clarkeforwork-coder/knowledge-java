# Virtual Threads（Java 21）

## 前言

回顧整個 07 章，所有的複雜度都源自同一個前提：**thread 很貴**——每條約 1MB stack、與 OS thread 一比一、建立要系統呼叫（[Thread 篇](thread-basics-and-lifecycle.md)算過的帳）。因為貴，所以要[池化重用](executorservice-and-thread-pools.md)；因為池裡的 thread 不能浪費在等待上，所以要 [CompletableFuture](completablefuture.md) 的回調鏈閃避阻塞。

Java 21 的 Virtual Threads 直接把這個前提打掉：**如果 thread 便宜到可以開一百萬條呢？** 本篇實測給你看——同樣一萬個 IO 任務，傳統池要 5,103ms，virtual threads 只要 133ms；規模放大十倍，也才 175ms。壓軸篇，也是這一章的「重新思考一切」。

## 技術背景

先破除最常見的誤解：「**virtual thread 讓程式跑得更快**」。

不對。它不會讓任何一行運算變快——**它提升的是「同時等待的容量」**。CPU-bound 的工作它毫無幫助（CPU 就那幾顆）；它的主場是 IO-bound：thread 生命中 99% 在等 DB、等 API 的那種工作負載，也就是**典型的企業後端**。

### M:N：阻塞不再佔用 OS thread

- **Platform thread**（傳統）：與 OS thread **一比一**——阻塞時，那條 OS thread 就閒置陪等
- **Virtual thread**：由 JVM 管理，**多對少**地掛載（mount）在少量 platform thread（稱為 **carrier**）上。關鍵動作發生在阻塞的瞬間：`Thread.sleep`、socket 讀寫、JDBC 等待時，virtual thread **卸載（unmount）**，carrier 立刻轉身去跑別的 virtual thread——**等待不再佔用任何 OS 資源**

```
100,000 條 virtual threads（等待中的不佔 carrier）
      ↓ mount / unmount
     ~8 條 carrier（≈ CPU 核心數的 ForkJoinPool）
      ↓ 1:1
     ~8 條 OS threads
```

成本對比：platform thread 約 1MB 起跳的 stack；virtual thread 的 stack 活在 **Heap** 上、按需伸縮，起點只有幾百 bytes——百萬條是實際可行的數字。

### 怎麼用：寫法回到「一條 thread 從頭寫到尾」

```java
Thread.ofVirtual().start(() -> handle(request));              // 單發

try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Order o : orders) vt.execute(() -> process(o));      // 每任務一條，想開就開
}   // ExecutorService 實作了 AutoCloseable（Java 19+）：離開時等全部做完
```

最大的紅利是**寫法的解放**：查會員 → 查訂單 → 組結果，直接順序寫、直接阻塞等——[CompletableFuture 篇](completablefuture.md)結尾的時代註腳在此兌現：**CF 的複雜度大半是為了不阻塞昂貴的 thread；thread 便宜之後，簡單的阻塞式寫法重新成為第一選擇**（CF 保留給真正需要「並行組合、競速、降級」語意的場景）。

### 池的兩個理由，只剩一個

[執行緒池篇](executorservice-and-thread-pools.md)給過池的價值：**重用**與**限流**。Virtual threads 之下：

- **重用的理由消失**：建立近乎免費，per-task 一條就是正確用法——**不要池化 virtual threads**（池化反而把它降級回「有限資源」）
- **限流的理由還在**：thread 便宜了，但**下游沒有**——DB 連線池就 50 條，十萬條 virtual threads 同時打過去就是自殺。限流改用 `Semaphore`：

```java
Semaphore dbSlots = new Semaphore(50);
vt.execute(() -> {
    dbSlots.acquire();
    try { queryDb(); } finally { dbSlots.release(); }
});
```

### 地雷區

- **Pinning（釘住）**：在 `synchronized` 區塊**內**阻塞時，JDK 21 的 virtual thread 無法卸載——carrier 被釘住陪等，量大時整個 carrier 池被釘死。對策：熱路徑的 synchronized 改用 `ReentrantLock`。（JDK 24 的 JEP 491 已修掉這個限制——但你維護的系統跑在哪個版本，自己心裡要有數）
- **ThreadLocal 慎用**：百萬條 thread × 每條一份 ThreadLocal 快取＝記憶體災難；它也是 pooled-thread 時代「借 thread 傳上下文」的習慣，per-task 時代改用方法參數或 Scoped Values（JDK 21 preview）
- **CPU-bound 無益**：再說一次——它增加的是等待容量，不是運算能力

## 實際案例

▶ 可執行範例：[VirtualThreadBenchmark.java](examples/VirtualThreadBenchmark.java)（需 JDK 21；本機沒有可用 Docker：`docker run --rm -v "$PWD":/work -w /work eclipse-temurin:21-jdk java VirtualThreadBenchmark.java`）

一萬個「睡 100ms」的任務（模擬 IO 等待），三種跑法實測（Temurin 21）：

```
固定池 200 條   ×10,000 任務： 5,103 ms
virtual thread ×10,000 任務：   133 ms
virtual thread ×100,000 任務：   175 ms
virtual thread toString：VirtualThread[#110240]/runnable@ForkJoinPool-1-worker-4
```

逐行解讀：

1. **固定池 5,103ms**：一萬個任務輪流用 200 個位子，10,000 ÷ 200 × 100ms ＝ 5 秒——實測與理論完美吻合，池的天花板就是數學
2. **virtual 133ms**：一萬條同時睡、同時醒——總時間 ≈ 單一任務的時間。**吞吐提升 38 倍，程式碼還更簡單**（不用算池要開多大）
3. **十倍規模只多 42ms**：十萬條並發等待對它是日常——這就是「等待容量」的意思
4. **最後一行是它的真面目**：`VirtualThread[#110240]` 掛在 `ForkJoinPool-1-worker-4` 上——carrier 就這樣看得見

## 技術優缺點

### 拿到的

- **IO 吞吐的量級提升**：等待不佔 OS thread，實測 38 倍且隨規模幾乎不變
- **寫法回歸簡單**：thread-per-request 直接阻塞式寫，不用池參數玄學、不用回調鏈
- **與既有程式碼相容**：還是 `Thread`、還是 `ExecutorService`——遷移成本低

### 該小心的

- **不是加速器**：CPU-bound 無益；也不會讓單一請求變快，只讓「同時服務更多請求」變可能
- **Pinning**（JDK 21）：`synchronized` 內阻塞會釘住 carrier——熱路徑改 `ReentrantLock`，或等 JDK 24
- **限流責任轉移**：以前池順便幫你限流，現在要自己用 `Semaphore` 保護下游
- **舊習慣要改**：不要池化 VT、ThreadLocal 要重新審視

## 小結

- Virtual thread 是 JVM 管理的 **M:N** 輕量 thread：阻塞時卸載、carrier 轉身跑別人——**等待不再佔用 OS 資源**
- 它提升**吞吐**不提升速度：IO-bound 是主場（實測 5,103ms → 133ms），CPU-bound 無益
- **不要池化**（重用理由消失）、**仍要限流**（下游沒變便宜，用 Semaphore）
- 地雷：JDK 21 的 synchronized pinning（JEP 491 在 JDK 24 修掉）、ThreadLocal 的百萬倍放大
- 對本章的意義：thread 貴的前提被打掉後——池的價值剩限流、CF 的價值剩組合語意、阻塞式寫法重新成為預設

**07 章 🔰 軌完軌**：從 thread 的生老病死、鎖與可見性、池與編排，到把前提整個翻掉的 virtual threads。選修軌還有兩個深洞：「進出鎖等於同步記憶體」的精確理論見規劃中的 🔬〈Java Memory Model 與 happens-before〉；「synchronized 其實沒那麼慢」的原因見規劃中的 🔬〈synchronized 鎖升級〉。

## 常見面試題

1. Virtual thread 和 platform thread 差在哪？（提示：1:1 vs M:N、阻塞時的 unmount、stack 在 Heap）
2. Virtual threads 還需要執行緒池嗎？（提示：重用與限流兩個理由拆開答；Semaphore）
3. 什麼是 pinning？怎麼避免？（提示：synchronized 內阻塞、ReentrantLock、JDK 24 的 JEP 491）

## 延伸閱讀

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — 設計動機與完整語意，「不要池化」的原文出處
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) — pinning 問題在 JDK 24 的終結
