# CompletableFuture

## 前言

會員頁要顯示三份資料：會員資訊、訂單、優惠券——三個下游查詢各 200ms。串著呼叫就是 600ms 起跳；用[執行緒池](executorservice-and-thread-pools.md)的 `submit` 拿三個 `Future` 呢？好一點，但 `Future.get()` 只會**阻塞傻等**：不能說「查完會員接著查它的等級」、不能說「三個都好了再組頁面」、更不能說「誰失敗就用預設值」。

`CompletableFuture`（以下簡稱 CF）補的就是這一塊：**任務的編排**——並行發出、結果組合、失敗降級，全部宣告式地接起來。這篇也要先立一個觀念：**CF 的正確用法是「接鏈」，不是「加強版的 get」**——你寫的 `get()`/`join()` 越多，越接近用錯。

## 技術背景

### 建立與組合：像 Stream 一樣接鏈

```java
CompletableFuture<Member> member =
        CompletableFuture.supplyAsync(() -> memberService.find(id), ioPool);   // 開工

member.thenApply(Member::level)             // 轉換結果（同步接在後面）
      .thenAccept(this::render)             // 消費結果
      .thenRun(() -> log.info("done"));     // 不關心結果，接著做
```

這套 API 和 [06 章的 Stream](../06-lambda-and-stream/stream-operations.md) 是刻意同構的：

| CF 方法 | Stream 對應 | 語意 |
|---|---|---|
| `thenApply(fn)` | `map` | 拿到結果，轉換成另一個值 |
| `thenCompose(fn)` | `flatMap` | 拿到結果，**再開一個非同步任務**（避免 `CF<CF<T>>` 套娃） |
| `thenCombine(other, fn)` | — | **兩個並行任務都完成後**，合併兩個結果 |
| `allOf(...)` / `anyOf(...)` | — | 全部完成／任一完成 |

分辨 `thenApply` 和 `thenCompose` 一句話：後續動作是**算一下就有**（converter）用 apply；後續動作**本身又是一次非同步呼叫**（查 DB、打 API，回傳 CF）用 compose。

### 例外處理：會傳染，也會蒸發

例外沿著鏈**向下傳播**：中間的 `thenApply` 全部跳過，直到遇見處理者：

```java
cf.thenApply(...)                       // 上游炸了 → 跳過
  .exceptionally(e -> 預設值)           // 接住，鏈恢復正常
  .handle((val, err) -> ...)            // 成功失敗都進來的版本
```

**最危險的是什麼都不接**：沒 handler、沒人 `join`——例外無聲蒸發（實測見案例二），跟[執行緒池篇](executorservice-and-thread-pools.md)「submit 吞例外」是同一族。紀律：**每條鏈的尾端，要嘛有 `exceptionally`/`handle`，要嘛有人 `join`**。

### 跑在哪個池：又是 common pool

`supplyAsync(task)` 不給第二個參數，預設跑在 **`ForkJoinPool.commonPool`**——實測輸出裡的 `commonPool-worker-1` 就是證據。這和 [Stream 誤用篇](../06-lambda-and-stream/stream-pitfalls.md)的 `parallel()` 是**同一個全 JVM 共享的池**，同一條警告再貼一次：**IO 型任務（查 DB、打 API）自帶 executor**：

```java
CompletableFuture.supplyAsync(() -> dbCall(), ioPool);   // 用執行緒池篇教的方式建 ioPool
```

順帶兩個版本細節：`thenApply` vs `thenApplyAsync`——前者可能直接在「完成上一步的那條 thread」上執行，後者重新提交回池；回調很輕用前者，回調有份量用後者（並考慮指定池）。

### 別忘了給等待設限

`join()`/`get()` 是阻塞的——沒有時限的等待在生產環境等於自願被下游拖死：

```java
cf.orTimeout(2, TimeUnit.SECONDS)                       // 超時讓鏈以 TimeoutException 失敗
  .exceptionally(e -> 降級值);
```

## 實際案例

### 案例一：三查詢聚合——619ms 變 214ms

三個各 200ms 的模擬查詢，串行 vs `thenCombine` 並行，實測：

```
  會員 查完（main）
  訂單 查完（main）
  優惠 查完（main）
串行總耗時：619 ms

  會員 查完（ForkJoinPool.commonPool-worker-1）
  訂單 查完（ForkJoinPool.commonPool-worker-2）
  優惠 查完（ForkJoinPool.commonPool-worker-3）
並行總耗時：214 ms（結果：會員資料+訂單資料+優惠資料）
```

兩個看點：總耗時從「三段之和」變成「最慢那段」；執行緒名直接暴露了**預設池就是 commonPool**——這是示範程式所以無妨，生產環境的 IO 查詢請自帶池。組合寫法：

```java
var member = CompletableFuture.supplyAsync(() -> memberQuery(), ioPool);
var orders = CompletableFuture.supplyAsync(() -> orderQuery(), ioPool);
var coupon = CompletableFuture.supplyAsync(() -> couponQuery(), ioPool);

String page = member.thenCombine(orders, (m, o) -> ...)
                    .thenCombine(coupon, (mo, c) -> ...)
                    .join();                      // 尾端有人收，例外不會蒸發
```

### 案例二：例外的兩種下場

**有接 handler**——上游拋 `IllegalStateException`，中間的 `thenApply` 被跳過，`exceptionally` 接住降級：

```
有接 handler：降級預設值（原因：下游掛了）
```

**沒接 handler、沒人 join**——實測主程式安靜地跑完：

```
沒接 handler：主程式什麼都沒看到，例外蒸發了
```

生產環境的長相：某個聚合欄位永遠是空的、log 乾乾淨淨、監控一片綠。**每條鏈的尾端必須有收尾**——這條紀律值得寫進 team 的 code review checklist。

## 技術優缺點

### CF 給你的

- **編排能力**：並行、依賴、合併、競速（`anyOf`）、降級，全部宣告式
- **非阻塞**：回調在任務完成時被觸發，thread 不用傻等——省下的就是[執行緒池篇](executorservice-and-thread-pools.md)算過的那筆帳

### 代價與邊界

- **長鏈可讀性差**：超過四五步的鏈、鏈中帶分支，讀起來像義大利麵——拆成有名字的方法，或考慮「這段是不是其實該同步寫」
- **例外處理易漏**：蒸發實測過了；`handle`/`exceptionally` 的位置也有講究（放中間只接得住它上游的）
- **除錯困難**：stack trace 斷在池的邊界，看不到「是誰發起的」
- **一個時代註腳**：CF 的複雜度，一大部分是為了「不讓昂貴的 thread 阻塞等待」。如果 thread 便宜到可以隨便阻塞呢？——這正是壓軸篇 Virtual Threads 要動搖的前提：很多 CF 鏈，在 VT 的世界可以退回「一條 thread 從頭順序寫到尾」的簡單寫法。

## 小結

- CF 是**任務編排**工具：正確姿勢是接鏈（`thenApply`/`thenCompose`/`thenCombine`），`get`/`join` 寫越多越可疑
- 對照 Stream 記 API：`thenApply`≈`map`、`thenCompose`≈`flatMap`；兩個並行結果合併用 `thenCombine`
- 三查詢聚合實測：619ms → 214ms——總耗時從「和」變「最慢者」
- **例外會沿鏈傳染、沒人收就蒸發**（實測）：每條鏈尾端要有 `exceptionally`/`handle` 或 `join`
- **預設池是 commonPool**（實測執行緒名為證）：IO 任務自帶 executor；等待要設 `orTimeout`

thread 很貴，所以我們發明了池來省、發明了 CF 來閃避阻塞——整個 07 章都建立在這個前提上。壓軸篇要問的是：**如果這個前提消失了呢？** Java 21 的 Virtual Threads，見規劃中的〈Virtual Threads（Java 21）〉。

## 常見面試題

1. CompletableFuture 比 Future 多了什麼？（提示：組合、回調、例外處理——Future 只有阻塞的 get）
2. `thenApply`、`thenCompose`、`thenCombine` 怎麼分？（提示：map/flatMap 類比；combine 是兩個並行任務）
3. `supplyAsync` 不給 executor 時跑在哪？有什麼風險？（提示：commonPool 實測為證；IO 任務、與 parallel stream 共池）

## 延伸閱讀

- [CompletableFuture（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html) — 全部組合方法與執行池規則的官方定義
- [CompletionStage（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletionStage.html) — 鏈式模型的介面契約，方法命名規律的出處
