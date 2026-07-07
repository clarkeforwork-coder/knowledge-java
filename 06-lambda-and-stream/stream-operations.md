# Stream：中間操作與終端操作

## 前言

同一個需求——「從訂單裡挑出台北的、取出金額、加總」——傳統寫法是一個 for 迴圈裡混雜三件事：過濾的 if、轉換的取值、累加的狀態變數。讀的人得在腦中重演整個迴圈，才能還原你的意圖。

[上一篇](functional-interface-and-lambda.md)說過，行為可以當參數傳之後，資料處理就能組裝成管線。Stream 就是那條管線——但多數人用它的方式是「照著同事的鏈依樣畫葫蘆」，一旦問「為什麼 `filter` 後面能接 `map`、`count` 後面什麼都不能接」就答不上來。這篇建立 Stream 的正確心智模型：**一條有登記、有開工的生產線**。

## 技術背景

先破除最根本的誤解：「**Stream 是一種新的集合**」。

不是。Stream **不裝資料**——它是「對資料來源做哪些處理」的**流水線描述**。集合是倉庫，Stream 是加工流程單：流程單可以一直往下寫，但**不下「開工」指令就什麼都不會發生**，而且一張流程單只能跑一次。

### 三段式結構

```java
int total = orders.stream()                       // ① 來源：從集合開一條流
        .filter(o -> o.city().equals("台北"))     // ② 中間操作：登記步驟（回傳 Stream，可續接）
        .mapToInt(Order::amount)                  // ② 中間操作
        .sum();                                   // ③ 終端操作：開工！（回傳結果，管線結束）
```

分辨中間／終端只看一件事：**回傳型別**。回傳 `Stream`（或 `IntStream` 等）的是中間操作，還能往下接；回傳其他東西（值、集合、Optional、void）的是終端操作，管線到此為止。

| 中間操作（惰性，登記） | 作用 |
|---|---|
| `filter(Predicate)` | 過濾——只留判斷為 true 的 |
| `map(Function)` / `mapToInt` | 轉換——一個變一個 |
| `flatMap(Function)` | 攤平——一個變多個再接成一條流（訂單 → 明細） |
| `sorted(Comparator)` | 排序（[04 章的 Comparator](../04-collections/sorting-comparable-comparator.md) 直接用） |
| `distinct()` | 去重（靠 equals——[契約篇](../04-collections/equals-hashcode-contract.md)又出場） |
| `limit(n)` / `skip(n)` | 截前 n 個／跳過前 n 個 |

| 終端操作（觸發執行） | 回傳 |
|---|---|
| `collect(...)` / `toList()` | 收集成集合（Collectors 下下篇專講） |
| `count()` / `sum()` / `max(...)` | 統計值 |
| `reduce(...)` | 自訂累合 |
| `anyMatch` / `allMatch` / `noneMatch` | boolean（短路） |
| `findFirst()` / `findAny()` | Optional（短路） |
| `forEach(Consumer)` | void |

### 惰性求值：元素是「垂直」走完管線的

兩個關鍵行為，直覺常常猜錯：

1. **中間操作是惰性的**——`filter`/`map` 呼叫當下什麼都不做，只是登記；終端操作才觸發執行（見實測：沒有終端操作，filter 裡的 print 一行都不出）
2. **元素逐個「垂直」走完整條管線**，不是整批做完 filter 再整批做 map——元素 a 走 filter→map→收集，然後才輪到元素 b

垂直處理讓**短路**成為可能：`findFirst` 拿到第一個走完管線的元素就喊停，後面的元素**連 filter 都不會進**（實測：四個元素只檢查了兩個）。`limit`、`anyMatch` 同理。這不是優化技巧，是 Stream 的天性——你白拿的。

### 原始型別流：算數字就換軌

`Stream<Integer>` 每個元素都是包裝物件（[02 章的 autoboxing](../02-language-core/autoboxing-unboxing.md) 成本）。算數字時用 `mapToInt` 切到 `IntStream`——不只免裝箱，還送你 `sum()`、`average()`、`max()` 這些數值專屬終端操作；要回物件流用 `boxed()`。

## 實際案例

### 案例一：惰性與短路的實錄

```java
Stream.of("a", "bb", "ccc", "dd")
        .filter(s -> { System.out.println("filter 檢查：" + s); return s.length() >= 2; })
        .map(s -> { System.out.println("  map 轉換 ：" + s); return s.toUpperCase(); });
// 到這裡為止：什麼都沒印 —— 沒有終端操作，流程單還沒開工
```

加上 `.findFirst()` 之後的實測輸出：

```
filter 檢查：a       ← a 沒過 filter，換下一個
filter 檢查：bb      ← bb 過了
  map 轉換 ：bb      ← bb 垂直走完 map
結果：BB             ← findFirst 拿到人，收工
```

注意 `ccc` 和 `dd` **從頭到尾沒被碰過**。如果 filter 後面接的是昂貴操作（查 DB、呼叫 API），垂直＋短路省下的就是真金白銀。

### 案例二：一條 Stream 只能消費一次

```java
Stream<String> once = Stream.of("x", "y");
once.count();     // 第一次：2
once.count();     // 第二次：？
```

實測：

```
第一次 count：2
第二次 count：stream has already been operated upon or closed
```

`IllegalStateException`——流程單跑完即作廢。想「重跑」就從來源再開一條（`list.stream()` 很便宜）；會想快取 Stream 本身，通常代表把它誤當成了集合。

### 案例三：重構對照——迴圈的意圖考古 vs 管線的一目瞭然

```java
// ❌ 傳統：三件事攪在一個迴圈裡，讀者要自己還原意圖
List<String> result = new ArrayList<>();
for (Order o : orders) {
    if (o.city().equals("台北") && o.amount() >= 1000) {
        result.add(o.id());
    }
}

// ✅ Stream：每一行就是一個意圖
List<String> result = orders.stream()
        .filter(o -> o.city().equals("台北"))
        .filter(o -> o.amount() >= 1000)
        .map(Order::id)
        .toList();
```

兩個 `filter` 拆開寫是刻意的——**一行一個條件**，之後加減條件都是整行進出，diff 乾淨、review 好讀。

## 技術優缺點

### Stream 的價值

- **宣告式**：程式碼長得像需求描述（「過濾→轉換→收集」），意圖不用考古
- **惰性＋短路白拿**：垂直處理天生只做必要的工，手寫迴圈要自己 break 才有同樣效果
- **組合性**：每個操作獨立成行，加減步驟不動其他行

### 代價與邊界

- **除錯體驗打折**：中斷點難下、stack trace 一串 `lambda$`——複雜邏輯先提成有名字的方法再塞進管線
- **效能不是它的賣點**：小資料量的簡單處理，for 迴圈通常更快（管線本身有固定開銷）——選 Stream 是為了可讀性，不是速度
- **`parallel()` 不是免費加速鍵**——沒搞清楚代價前不要碰，這是下下篇誤用篇的主角之一
- 有狀態的中間操作（`sorted`、`distinct`）要**攢齊所有元素**才能放行——它們會打斷垂直處理與短路的美好，管線裡能晚放就晚放

## 小結

- Stream 不是集合，是**流水線描述**：來源 → 中間操作（登記，回傳 Stream）→ 終端操作（開工，回傳結果）——分辨就看回傳型別
- **惰性**：沒有終端操作，中間操作一步都不執行（實測零輸出）
- **垂直處理＋短路**：元素逐個走完整條管線，`findFirst`/`limit`/`anyMatch` 夠了就停（實測四個元素只碰兩個）
- 一條 Stream **只能消費一次**，重跑就從來源再開；想存 Stream 通常是誤把它當容器
- 算數字換 `IntStream`（`mapToInt`）——免裝箱還送統計終端操作

管線的終點站「收集」值得專篇：`toList` 只是入門，分組、分區、下游聚合才是 `Collectors` 的真本事——[Map 篇](../04-collections/map-hashmap-basics.md)手工寫的 `merge` 計數、`computeIfAbsent` 分組，都有一行版。見規劃中的〈Collectors 實戰〉。

## 常見面試題

1. 中間操作和終端操作怎麼分？各舉三個。（提示：回傳型別；惰性 vs 觸發）
2. 「惰性求值」在 Stream 裡是什麼意思？元素是怎麼流過管線的？（提示：登記 vs 開工；垂直處理、短路）
3. 一條 Stream 可以重複使用嗎？為什麼？（提示：IllegalStateException；流程單 vs 倉庫）

## 延伸閱讀

- [java.util.stream 套件文件](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/stream/package-summary.html) — 官方對管線、惰性、有狀態操作的完整定義，品質極高
- [Stream（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/stream/Stream.html) — 全部操作的契約
