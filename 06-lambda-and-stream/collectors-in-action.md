# Collectors 實戰

## 前言

大多數人對 `Collectors` 的認識止於 `collect(Collectors.toList())`——把流變回 List，結案。

還記得 [Map 篇](../04-collections/map-hashmap-basics.md)手工寫的兩個模式嗎？`merge(city, 1, Integer::sum)` 計數、`computeIfAbsent(k, k -> new ArrayList<>())` 分組——當時預告「06 章有一行版」。這篇兌現承諾，並建立 Collectors 真正的心智模型：它不是「變回集合的工具」，是一套**可組合的收集配方系統**——`groupingBy` 加上「下游收集器」，報表級的統計一行完成。

## 技術背景

### 基礎款：變回集合

```java
.collect(toList())         // List（可變）
.toList()                  // Java 16+ 的捷徑——但回傳「不可變」List，見下方地雷
.collect(toSet())          // Set（去重靠 equals/hashCode——契約篇的老朋友）
.collect(joining("、"))    // 字串拼接（元素要先 map 成 String）
```

### toMap：最常用也最常炸

```java
.collect(toMap(Order::city, Order::id))
```

看起來直覺，但藏著一顆雷：**key 重複時直接拋 `IllegalStateException`**（見實測）。三參數版給「撞 key 時怎麼合併」的規則才安全：

```java
.collect(toMap(Order::city, Order::id, (a, b) -> a + "," + b))   // 撞了就拼起來
.collect(toMap(Order::id, Order::amount, (a, b) -> b))           // 撞了留後者
```

規矩：**除非 key 保證唯一（如以 id 當 key），一律用三參數版**。另一顆小雷：value 為 null 會 NPE——toMap 不收 null value。

### groupingBy ＋ 下游收集器：配方的組合

一參數版把元素按 key 分堆，每堆是 List：

```java
.collect(groupingBy(Order::city))              // Map<String, List<Order>>
```

真正的威力在二參數版——第二個參數回答「**每堆要熬成什麼**」，稱為**下游收集器（downstream）**：

```java
.collect(groupingBy(Order::city, counting()))                    // 每堆 → 數量
.collect(groupingBy(Order::city, summingInt(Order::amount)))     // 每堆 → 金額加總
.collect(groupingBy(Order::city, mapping(Order::id, toList()))) // 每堆 → 只留單號的 List
```

心法：把下游收集器想成「**組內的迷你 collect**」——分完組，對每一組再跑一次收集。`counting`、`summingInt`、`averagingInt`、`maxBy`、`mapping`（先轉換再收）、甚至再一層 `groupingBy`（二級分組）都能當下游。

### partitioningBy：boolean 的特化分組

條件只有真假兩堆時，用 `partitioningBy` 比 `groupingBy` 多兩個好處：語意直白，而且**保證兩個 key 都存在**（就算某堆是空的）——`groupingBy` 遇到空組連 key 都不會有，後續取用要多防一手。

## 實際案例

### 案例一：兌現 Map 篇的承諾

同一批訂單（台北 ×3、台中 ×2、高雄 ×1），04 章的手工版 vs Collectors 版：

```java
// Map 篇的手工版
Map<String, Integer> count = new HashMap<>();
for (Order o : orders) count.merge(o.city(), 1, Integer::sum);

// Collectors 版：一行，且意圖寫在臉上
orders.stream().collect(groupingBy(Order::city, counting()))
```

實測輸出（與手工版一致）：

```
計數：{台中=2, 台北=3, 高雄=1}
營收：{台中=1200, 台北=2300, 高雄=500}
分組取單號：{台中=[A03, A06], 台北=[A01, A04, A05], 高雄=[A02]}
分區(≥700)：{false=[A02, A03, A05], true=[A01, A04, A06]}
```

第三行值得看：`groupingBy(Order::city, mapping(Order::id, toList()))`——分組後每組**只留單號**，這是「下游是組內迷你 collect」最直觀的展示。

### 案例二：toMap 的地雷與拆彈

```java
orders.stream().collect(toMap(Order::city, Order::id));   // 台北有三筆……
```

實測：

```
toMap 重複 key：Duplicate key 台北 (attempted merging values A01 and A04)
toMap＋合併：{台中=A03,A06, 台北=A01,A04,A05, 高雄=A02}
```

錯誤訊息其實很友善（直接告訴你哪個 key、哪兩個 value 撞了），但它是**執行期**才炸——測試資料剛好沒重複就上線了。所以規矩定在寫的時候：key 不保證唯一就給合併函數。

### 案例三：`toList()` 的不可變陷阱

```java
List<String> ids = orders.stream().map(Order::id).toList();
ids.add("A99");
```

實測：`UnsupportedOperationException`。

Java 16 的 `Stream.toList()` 回傳**不可變 List**，而 `collect(Collectors.toList())` 目前回傳可變的 ArrayList（雖然 Javadoc 沒承諾）。收集結果之後還要增刪的話，明確寫 `collect(toCollection(ArrayList::new))`——把「我要可變」說出來，而不是賭實作細節。

## 技術優缺點

### Collectors 的價值

- **報表級統計一行完成**：分組＋聚合的意圖直接寫在型別上（`Map<String, Long>` 就是「按什麼分、算出什麼」）
- **組合性**：下游收集器像樂高——`groupingBy(city, mapping(id, toList()))` 三塊積木拼出精確需求
- 對照 04 章手工版：不是手工版不好（那是理解的基礎），是 Collectors 把「怎麼收」的細節全部收走，剩下純意圖

### 代價與紀律

- **可讀性懸崖**：下游巢狀到第二層（`groupingBy` 裡再 `groupingBy` 再 `mapping`）就開始考驗讀者——超過兩層，拆成多個步驟或提出有名字的 Collector 常數
- **toMap 的雷是執行期的**：重複 key、null value 都是跑了才知道——規矩要定在 code review
- **兩個 toList 行為不同**：`Stream.toList()` 不可變、`Collectors.toList()` 事實上可變但無承諾——要可變就明說 `toCollection`
- `joining` 只吃 `Stream<String>`，忘了先 `map` 是最常見的編譯錯誤

## 小結

- Collectors 不是「變回集合」而是**可組合的收集配方**：基礎款收形狀，`groupingBy`＋下游收集器做統計
- **下游收集器＝組內的迷你 collect**：`counting`／`summingInt`／`mapping` 都能當第二參數，回答「每堆熬成什麼」
- **toMap 沒給合併函數就是賭 key 唯一**——執行期炸 `Duplicate key`（實測），規矩：不保證唯一就用三參數版
- `partitioningBy` 是 boolean 特化：語意直白＋空堆也保證有 key
- `Stream.toList()` **不可變**（實測 add 就炸）；要可變明寫 `toCollection(ArrayList::new)`
- Map 篇的 `merge`／`computeIfAbsent` 手工版沒有白學——那是 Collectors 收走的細節本身

06 章 🔰 軌還剩最後一篇：工具都會用了，接下來是**別把它用壞**——在 `map` 裡做副作用、重複消費、`parallel()` 亂開，見 [Stream 常見誤用](stream-pitfalls.md)。

## 常見面試題

1. `groupingBy` 的下游收集器是什麼概念？舉三個例子。（提示：組內迷你 collect；counting/summing/mapping）
2. `toMap` 遇到重複 key 會發生什麼事？怎麼避免？（提示：IllegalStateException、三參數版合併函數）
3. `Stream.toList()` 和 `collect(Collectors.toList())` 有什麼差別？（提示：不可變 vs 無承諾的可變；要可變怎麼寫）

## 延伸閱讀

- [Collectors（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/stream/Collectors.html) — 全部配方的官方目錄
- [Stream.toList（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/stream/Stream.html#toList()) — 「回傳不可變 List」的白紙黑字
