# 重構與 Code Review 原則

## 前言

前面兩章半在講「怎麼寫對」和「怎麼測對」。這篇講一件更難的事：**怎麼讓程式碼在半年後、換一個人維護時，還是對的**。

重構（不改行為、只改結構）和 code review（在合併前擋下問題）是同一枚硬幣的兩面——都在對抗「能跑就好」的熵增。這篇不堆術語，而是把整個 repo 散落各處的工程判斷收束成一套可操作的原則：**測試是重構的安全網、壞味道有跡可循、review 看的是什麼**。前面每一篇實測過的坑，在這裡都成了 review checklist 的一條。

## 技術背景

### 重構的前提：先有測試網

重構的定義是「**改變內部結構、不改變外部行為**」。怎麼證明「行為沒變」？——靠[測試](junit5-basics.md)。沒有測試的重構是**在裸奔中換引擎**：你以為只是整理，其實悄悄改了行為，沒人知道。

所以順序是鐵的：**先補測試 → 確認綠 → 重構 → 再跑測試確認還綠**。這也是[測試金字塔](spring-boot-test-layers.md)的實戰價值——快速的單元測試讓你敢動手，跑一輪幾秒就知道有沒有弄壞。

### 壞味道：重構的觸發訊號

「壞味道（code smell）」不是 bug，是「這裡遲早會出事」的結構訊號。本 repo 一路實測過的，正是最經典的幾種：

| 壞味道 | 本 repo 的實測現場 | 重構方向 |
|---|---|---|
| 迴圈裡的隱形複雜度 | [List.contains 進迴圈](../04-collections/collections-overview.md) O(n²)、[N+1](../08-data-access/jpa-lazy-loading-and-n-plus-1.md)、[字串 +=](../02-language-core/string-and-stringbuilder.md) | 換對的資料結構／一次撈齊 |
| 到處是 switch/if-else | [enum 散落各處的 switch](../02-language-core/enum-done-right.md) | constant-specific method／多型 |
| 誤用繼承重用 | [CountingSet extends HashSet 翻倍](../02-language-core/interface-vs-abstract-class.md) | 改組合 |
| 可變共享狀態 | [迴圈共用物件](../02-language-core/pitfall-shared-reference-in-loop.md)、[race condition](../07-concurrency/synchronized-and-volatile.md) | 不可變（record） |
| 吞例外／裸 get | [空 catch](../02-language-core/exception-handling.md)、[Optional.get](../02-language-core/optional.md) | 保留 cause／帶 Plan B |
| 神方法、神類別 | 一個方法做五件事 | 拆分（extract method） |

**認得出壞味道，是資深的分水嶺**——初級看到「能跑」，資深聞到「這裡會爛」。

### 重構的基本手法

- **Extract Method**：一段有註解解釋「這在幹嘛」的程式碼，就是一個該被抽出的方法——**方法名取代註解**
- **Rename**：`data`、`temp`、`flag2` 是認知負擔——好名字是最便宜的文件（IDE 的 rename 是安全的，放心改）
- **Replace Conditional with Polymorphism**：型別判斷的 switch → 多型（[enum](../02-language-core/enum-done-right.md) 或策略）
- **Introduce Parameter Object**：一長串參數 → 一個 record
- 原則：**一次一小步、每步都跑測試**——大爆炸式重寫是重構的反面（沒有安全網、無法驗證行為不變）

### Code Review 看什麼：四個層次

review 不是抓錯字，是**四個由淺到深的層次**：

1. **能不能動**：邏輯對嗎？邊界有處理嗎（null、空集合、[例外路徑](../02-language-core/exception-handling.md)）？——測試該涵蓋，review 補查
2. **會不會爛**：壞味道、命名、重複、神方法——**未來維護成本**
3. **對不對得起約定**：符合團隊慣例、正確用了框架（[@Transactional 沒 self-invocation](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)、連線有 close、[SQL 用 PreparedStatement](../08-data-access/jdbc-and-connection-pool.md)）
4. **值不值得**：這個抽象需要嗎？過度設計和設計不足一樣是問題

### Review 的人際面：對事不對人

技術 review 的最大陷阱是**變成批鬥**。幾條被驗證有效的紀律：

- **批評程式碼、不批評人**：「這個方法可以拆」不是「你不會拆方法」
- **區分「必須改」和「建議」**：用 `[必須]`／`[建議]`／`[討論]` 標記——別讓一個 nitpick 擋住合併
- **問問題勝過下命令**：「這裡沒 close 連線是有原因嗎？」給對方台階，也可能是你漏看了脈絡
- **被 review 的一方**：review 是對程式碼的、不是對你的——這和[測試紅了](junit5-basics.md)一樣，是資訊不是指控

## 實際案例

### 一次 review 的思路示範

假設收到這段 PR：

```java
@Transactional
public void processOrders(List<Long> ids) {
    for (Long id : ids) {
        Order o = orderRepo.findById(id).get();        // ①
        o.setStatus("DONE");
        externalApi.notify(o);                         // ②
    }
}
```

四層 review 逐一過：

- **能不能動**：`.get()` ①——查無資料直接 `NoSuchElementException`（[Optional 篇](../02-language-core/optional.md)的裸 get）。`[必須]` 改 `orElseThrow` 給語意化例外
- **會不會爛**：迴圈裡逐筆處理——若 `ids` 很大，是 [N+1 的變形](../08-data-access/jpa-lazy-loading-and-n-plus-1.md)。`[建議]` 批次查詢
- **對不對得起約定**：②在 `@Transactional` 裡呼叫外部 API——[交易佔著連線等慢 API](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)，還可能[外部成功但交易回滾](../08-data-access/transactions-and-database-locks.md)造成不一致。`[必須]` 通知移到交易外
- **值不值得**：邏輯簡單、不算過度設計——OK

一段八行的方法，四個層次挑出兩個 `[必須]`、一個 `[建議]`——**每一條都是前面章節實測過的坑**。這就是 review 的價值：把散落在各人腦中的教訓，變成合併前的一道閘門。

## 技術優缺點

### 重構＋review 買到的

- **對抗熵增**：程式碼會自然腐化——持續的小重構和 review 是唯一的解藥
- **知識擴散**：review 讓團隊看到彼此的程式碼，坑不會重複踩、風格趨於一致
- **品質閘門**：綠測試 ＋ 通過 review 才合併——[CI](junit5-basics.md) 把這變成強制

### 代價與平衡

- **review 是有成本的**：它擋住合併、佔用時間——所以要**分層**（`[必須]` vs `[建議]`），別讓完美擋住夠好
- **重構要有節制**：「順手重構整個模組」會讓 PR 巨大到無法 review——重構和功能改動**分開 PR**
- **測試是重構的稅也是本**：沒測試不敢重構、不重構程式碼爛掉——這個循環只能靠「一開始就寫測試」打破
- **別為了原則而原則**：DRY、SOLID 是工具不是教條——過度抽象（為了「以後可能」）和不足一樣有害

## 小結

- 重構＝**改結構不改行為**，前提是**測試網**——先補測試、確認綠、再動手（一次一小步）
- **壞味道有跡可循**：迴圈裡的複雜度、滿地 switch、誤用繼承、可變共享、吞例外——本 repo 一路實測的坑就是清單
- Code Review 四層次：**能不能動 → 會不會爛 → 對不對得起約定 → 值不值得**
- **對事不對人**：標記 `[必須]`/`[建議]`、問問題勝過下命令、review 是對碼不對人
- 平衡：重構與功能分開 PR、review 分層別讓完美擋住夠好、原則是工具不是教條

到這裡，09 工程實務章完軌——[測試框架](junit5-basics.md)、[替身](mockito-and-test-doubles.md)、[分層策略](spring-boot-test-layers.md)到這篇的重構與 review，「怎麼讓程式碼持續可信」的一條線走完。而這一整章的紀律，其實是全 repo 每一篇「壞味道實測」的收束——寫對、測對、然後守住。

## 常見面試題

1. 重構的前提是什麼？為什麼？（提示：測試網；如何證明行為不變）
2. 你 code review 會看哪些面向？（提示：四層次——動/爛/約定/值得；對事不對人）
3. 舉幾個你認得的 code smell 和對應重構。（提示：本 repo 的表——迴圈複雜度、switch、誤用繼承、可變共享）

## 延伸閱讀

- Martin Fowler《Refactoring》第二版 — 重構手法與壞味道的權威目錄
- [Google Engineering Practices: Code Review](https://google.github.io/eng-practices/review/) — 業界最完整的 review 準則（含人際面）
