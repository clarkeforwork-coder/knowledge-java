# 排序：Comparable vs Comparator

## 前言

排序需求有個特性：**永遠在變**。今天報表要按金額降冪，明天加一條「金額相同按單號」，後天客戶說中文名稱的排序「怪怪的」。

然後你發現手上的工具好像有兩套——`Comparable` 和 `Comparator`，教學說一個是「自然順序」一個是「客製順序」，聽完還是不知道什麼時候用哪個；順手寫的 `(a, b) -> a - b` 看起來也一直都能動。這篇把兩套工具的分工講清楚，順便拆掉兩顆地雷：減法 comparator 的 overflow，和中文排序的 Unicode 陷阱。

## 技術背景

先修正一個理解：Comparable 和 Comparator **不是二選一**，是分工——

- **`Comparable`**：型別**自己內建**的預設排序（自然順序），全世界共用這一種
- **`Comparator`**：**外掛**的排序規則，每個情境可以掛不同的

### Comparable：一個型別的出廠預設

```java
public record Order(String id, int amount) implements Comparable<Order> {
    @Override
    public int compareTo(Order o) {
        return Integer.compare(amount, o.amount);   // 自然順序：按金額
    }
}
```

實作了它，`Collections.sort(list)`、`list.sort(null)` 不用給參數就能排；`TreeSet`/`TreeMap` 沒給 Comparator 時也用它——[Set 篇](set-implementations.md) 講過，**Tree 容器連判重都看它**（回傳 0 就當同一個）。

兩條實作規矩：

1. **回傳值是「負／零／正」，不是 -1/0/1**——所以判斷時永遠用 `< 0`、`> 0`，別用 `== -1`
2. **強烈建議與 `equals` 一致**（compareTo 為 0 ⇔ equals 為 true）——不一致的型別進 Tree 容器就會出現 [BigDecimal 那種雙重人格](set-implementations.md)；這不是強制契約，但 [equals 契約篇](equals-hashcode-contract.md) 的教訓在這裡同樣適用

### Comparator：情境化的外掛，lambda 時代的主角

Java 8 之後的 Comparator 是一套可組合的小語言，多鍵排序像說話一樣寫：

```java
orders.sort(Comparator.comparingInt(Order::amount).reversed()   // 金額降冪
        .thenComparing(Order::id));                             // 同金額按單號
```

| 需求 | 寫法 |
|---|---|
| 按某欄位 | `Comparator.comparing(Order::date)` |
| 基本型別欄位（避免裝箱） | `comparingInt` / `comparingLong` / `comparingDouble` |
| 降冪 | `.reversed()` |
| 第二鍵、第三鍵 | `.thenComparing(...)` 一路串 |
| 欄位可能是 null | `comparing(Order::date, Comparator.nullsLast(naturalOrder()))` |

**選擇準則**：型別有一種「不言自明」的順序（日期、金額、字串）→ 實作 Comparable 當預設；其他一切情境化的排序需求 → Comparator，寫在用的地方。實務上 Comparator 出場率遠高於 Comparable——因為需求永遠在變，而 `comparing` 鏈改一行就好。

### 中文排序：Unicode 不是你要的順序

`String.compareTo` 比的是 **Unicode 碼位**——對英文剛好是字母序，對中文則是「看起來像亂排」。要語言正確的排序（台灣慣用筆畫序），用 `Collator`：

```java
cities.sort(Collator.getInstance(Locale.TRADITIONAL_CHINESE));
```

`Collator` 本身就實作了 `Comparator`，可以直接丟進 `sort`、包進 `thenComparing`，或給 `TreeSet` 當排序規則。

## 實際案例

### 案例一：報表的多鍵排序

```java
List<Order> orders = /* A03/500/台中, A01/1200/台北, A02/500/高雄 */;
orders.sort(Comparator.comparingInt(Order::amount).reversed()
        .thenComparing(Order::id));
```

實測輸出——金額降冪，同 500 元的兩筆按單號決勝：

```
A01 1200 台北
A02 500 高雄
A03 500 台中
```

### 案例二：`(a, b) -> a - b` 的災難現場

看起來聰明的減法寫法，遇到極值直接錯序：

```java
List<Integer> nums = List.of(1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE);
nums.sort((a, b) -> a - b);        // ❌ 減法當比較
nums.sort(Integer::compare);       // ✅ 標準寫法
```

實測輸出：

```
減法排序：[2147483647, -2147483648, -1, 1]   ← MAX_VALUE 被排到「最小」
正確排序：[-2147483648, -1, 1, 2147483647]
```

原因：`1 - Integer.MIN_VALUE` 溢位成負數，comparator 因此宣稱「1 比 MIN_VALUE 小」。更糟的是這種 bug **平常完全正常**，只有資料碰到夠大的正負差距才發作。規矩：**比較永遠用 `Integer.compare(a, b)`／`comparingInt`，減法一律不許**——code review 看到 `(a, b) -> a - b` 直接打回。

### 案例三：中文排序——還 [總覽篇](collections-overview.md) 的債

```java
List<String> cities = List.of("台北", "高雄", "新竹", "台中", "嘉義");
Collections.sort(unicode);                                        // 預設：Unicode 碼位
collated.sort(Collator.getInstance(Locale.TRADITIONAL_CHINESE));  // 語言感知
```

實測輸出：

```
Unicode 排序：[台中, 台北, 嘉義, 新竹, 高雄]   ← 碼位序：嘉(U+5609) < 新(U+65B0) < 高(U+9AD8)
Collator 排序：[台中, 台北, 高雄, 新竹, 嘉義]   ← 筆畫序：高(10畫) < 新(13畫) < 嘉(14畫)
```

Unicode 序把「嘉義」排在「新竹」前面，對使用者來說毫無道理；Collator 的筆畫序（台5 → 高10 → 新13 → 嘉14，第二字也照比：中4畫 在 北5畫 前）才是台灣使用者在名冊、下拉選單裡預期的順序。**凡是要給人看的中文排序，都該過 Collator**。

## 技術優缺點

### Comparable（內建預設）

- ✅ 一次實作處處生效：`sort()`、Tree 容器、`max/min` 都認得
- ❌ 每個型別只能有一種；改排序邏輯要動類別本身；很多型別根本沒有「不言自明」的順序

### Comparator（外掛）

- ✅ 情境化、可組合（`comparing` + `thenComparing` + `reversed`）、不動原類別；null 處理有現成的 `nullsFirst/Last`
- ✅ `comparingInt` 系列避開裝箱，熱路徑友善
- ❌ 規則散落在各呼叫點——同一種排序寫了五遍時，該提成常數（`static final Comparator<Order> BY_AMOUNT_DESC = ...`）
- ⚠️ 給了 TreeSet/TreeMap 的 Comparator **兼任判重標準**：compare 回傳 0 的元素進不去——拿「只比金額」的 Comparator 建 TreeSet，同金額的訂單會被當重複丟掉

## 小結

- 分工不是二選一：**Comparable 是出廠預設**（一種、內建）、**Comparator 是情境外掛**（多種、可組合），實務上後者出場率遠高
- compareTo 回傳**負／零／正**（不是 -1/0/1）；與 equals 保持一致，否則 Tree 容器出現雙重人格
- **減法 comparator 一律打回**：`1 - MIN_VALUE` 溢位，實測 MAX_VALUE 被排成最小——用 `Integer.compare`／`comparingInt`
- 多鍵排序背模板：`comparing(...).reversed().thenComparing(...)`，null 欄位加 `nullsLast`
- **給人看的中文排序用 `Collator`**（筆畫序），Unicode 碼位序只是機器覺得整齊
- TreeSet/TreeMap 的 Comparator 同時是判重標準——compare 為 0 就進不去

04 章的 🔰 軌只剩最後一塊拼圖：一邊遍歷一邊刪，`ConcurrentModificationException` 為什麼炸、正確姿勢是什麼——見 [fail-fast 與 ConcurrentModificationException](fail-fast-and-cme.md)。

## 常見面試題

1. Comparable 和 Comparator 的差別？什麼時候用哪個？（提示：內建預設 vs 情境外掛；誰能有多種）
2. 為什麼 comparator 不能寫成 `(a, b) -> a - b`？（提示：溢位；正確寫法是什麼）
3. TreeSet 傳入 Comparator 後，判斷重複的標準是什麼？（提示：compare == 0 兼任判重，會「吃掉」平手的元素）

## 延伸閱讀

- [Comparator（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Comparator.html) — `comparing`/`thenComparing`/`nullsFirst` 全家族
- [Comparable（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) — 「強烈建議與 equals 一致」的原文
- [Collator（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/Collator.html) — 語言感知排序的官方 API
