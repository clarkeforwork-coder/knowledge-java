# Stream 常見誤用

## 前言

前三篇把 Stream 的工具箱備齊了。這篇講怎麼**不要把它用壞**——因為 Stream 的誤用有個共同特徵：**程式碼看起來更優雅了，行為卻錯了或慢了**，而且錯得無聲無息。

最典型的一句話：「加個 `.parallel()` 就變快了吧？」——這篇會用實測讓你看到，這個念頭可以讓一萬筆資料**無聲消失一半以上**，也可以讓程式**慢 75 倍**。

## 技術背景

誤用分三類：錯的（正確性）、慢的（效能）、醜的（可讀性）。

### 正確性誤用：在 lambda 裡搞副作用

Stream 對 `map`/`filter`/`forEach` 裡的 lambda 有個隱含期望：**純函數**——只依賴輸入、不改外部狀態。最常見的違規：

```java
// ❌ 用 forEach 把結果塞進外部集合
List<String> result = new ArrayList<>();
orders.stream().filter(...).map(...).forEach(result::add);

// ✅ 收集本來就是終端操作的工作
List<String> result = orders.stream().filter(...).map(...).toList();
```

循序執行時 ❌ 版「碰巧」能動，於是活過 code review——直到某天有人加上 `.parallel()`：多執行緒同時對 ArrayList 做 `add`，資料遺失或直接炸（見實測，[Map 篇](../04-collections/map-hashmap-basics.md)說過它不是 thread-safe）。規矩很簡單：**管線裡的 lambda 不碰外部可變狀態**，要收集就交給 `collect`/`toList`——它們在 parallel 下也保證正確。

`peek` 同理：它的設計用途是**除錯觀察**，拿它做業務邏輯（改物件狀態）是把副作用藏得更深而已。

### 效能誤用：parallel() 不是加速鍵

`.parallel()` 背後是 Fork/Join：把資料切塊、分給多執行緒、再合併結果。三個常被忽略的事實：

1. **切分與合併本身有固定成本**——資料量小或操作太便宜時，成本遠大於收益（實測：慢 75 倍）
2. **全 JVM 共用一個 common pool**——你在 Web 應用裡拿 parallel stream 跑 IO（查 DB、呼叫 API），佔住的是全站共用的執行緒池，別人的 parallel 操作全部排隊。**IO-bound 的工作不要進 parallel stream**，那是 thread pool 的事（07 章）
3. 來源要**好切分**：ArrayList/陣列切分便宜，LinkedList／`iterate` 產生的流切不動

適用檢查表（全部成立才考慮）：資料量大、每元素運算是 CPU-bound、lambda 無副作用、來源好切分——然後**實測**，不是感覺。

### 效能誤用：有狀態操作擺錯位置

`sorted`/`distinct` 要**攢齊所有元素**才能放行（[Stream 篇](stream-operations.md)說的垂直處理被它打斷）。原則：**先縮小再攢**——`filter` 永遠擺在 `sorted` 前面；排序後只要前十名，`sorted().limit(10)` 雖然還是全排，但至少別把 `sorted` 放在 `filter` 前面白排一堆將被丟掉的元素。

### 可讀性誤用：為 Stream 而 Stream

[lambda 篇](functional-interface-and-lambda.md)的伏筆在這裡爆：lambda 裡呼叫會拋 checked exception 的方法，得包 try-catch——包完的管線比迴圈醜三倍。這類訊號（要處理例外、要累積複雜狀態、要用 index、巢狀超過兩層）都在說同一件事：**這段邏輯用迴圈寫更誠實**。Stream 是工具不是信仰，混用完全正常。

## 實際案例

### 案例一：parallel ＋ 副作用 ＝ 資料無聲消失

```java
List<Integer> unsafe = new ArrayList<>();
IntStream.range(0, 10_000).parallel().forEach(unsafe::add);   // ❌
```

實測跑三輪：

```
第 1 輪：size = 4839（應為 10000）
第 2 輪：size = 1564（應為 10000）
第 3 輪：size = 1359（應為 10000）
collect 版：size = 10000            ← 同樣 parallel，交給 toList 就一筆不少
```

注意三個恐怖點：**沒有例外**、**每次遺失的量都不同**、**循序版完全正常**。這種 bug 上線後的長相是「偶爾少幾筆資料，重跑又好了」——排查成本天文數字。而修法只是把 `forEach(list::add)` 換成 `.toList()`。

### 案例二：parallel 的兩面實測

▶ 可執行範例：[ParallelBenchmark.java](examples/ParallelBenchmark.java)

同一台機器（Apple Silicon，多核心）、JIT 預熱後：

```
小資料 ×1萬次   ：循序 3 ms｜parallel 224 ms     ← 慢 75 倍
2 億次 CPU 運算 ：循序 91 ms｜parallel 6 ms      ← 快 15 倍
```

兩個數字放在一起就是完整的答案：parallel 是**大量 CPU 運算**的利器，也是**小資料**的災難——切分、派工、合併的固定成本，要夠大的工作量才攤得回來。「加個 parallel 試試」不是優化，是抽獎。

## 技術優缺點

這篇的「優缺點」是 Stream 本身的使用邊界——什麼時候該回頭寫迴圈：

### 適合 Stream

- 過濾→轉換→收集的直線流程（它的主場，前三篇的一切）
- 分組統計（Collectors 的天下）
- 邏輯能拆成獨立小步驟、每步一行的場景

### 回頭用迴圈

- **要處理 checked exception**：包裝後的管線比迴圈難讀
- **要累積複雜狀態或用 index**：effectively final 擋著你，硬繞是自欺
- **提早退出的條件複雜**：`break` 比設計一個彆扭的 `takeWhile` 誠實
- **熱路徑上的小資料**：管線固定開銷不划算（[Stream 篇](stream-operations.md)就說過效能不是賣點）

## 小結

- **管線裡不碰外部可變狀態**：`forEach(list::add)` 循序時碰巧能動，parallel 下實測一萬筆剩一千多——收集交給 `toList`/`collect`
- **parallel 檢查表**：資料量大＋CPU-bound＋無副作用＋來源好切分，缺一不可；實測小資料慢 75 倍、大運算快 15 倍——用數據決定，不是感覺
- **IO-bound 別進 parallel stream**：佔的是全 JVM 共用的 common pool，禍及全站
- 有狀態操作（`sorted`/`distinct`）先縮小再攢：`filter` 永遠在 `sorted` 前面
- `peek` 只做除錯觀察；checked exception、複雜狀態、index、複雜早退——**回頭寫迴圈，混用不丟臉**

06 章 🔰 軌完軌：lambda 是地基、Stream 是管線、Collectors 是收集配方、這篇是邊界守則。「IO-bound 交給 thread pool」「共享狀態怎麼安全」這兩條線，都指向同一個地方——07 並發章。

## 常見面試題

1. parallel stream 什麼時候該用？有什麼隱藏風險？（提示：四條檢查表；common pool 共享、IO-bound 的災難）
2. 為什麼 Stream 的 lambda 裡不該有副作用？（提示：循序碰巧對、parallel 資料遺失實測；純函數期望）
3. `forEach(list::add)` 收集和 `collect(toList())` 有什麼差別？（提示：外部 vs 內部收集；誰在 parallel 下保證正確）

## 延伸閱讀

- [java.util.stream 套件文件：Side-effects 與 Parallelism 段落](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/stream/package-summary.html#SideEffects) — 官方對「為什麼不要副作用」的完整論述
- [ForkJoinPool（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ForkJoinPool.html) — parallel stream 背後的 common pool
