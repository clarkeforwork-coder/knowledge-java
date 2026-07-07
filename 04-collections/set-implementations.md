# Set：HashSet、LinkedHashSet、TreeSet 與去重的代價

## 前言

「把 List 去重」大概是日常最頻繁的集合操作之一：`new HashSet<>(list)`，一行搞定。

直到某天翻車：明明是「同一個會員」的兩個物件，`HashSet` 死活去不了重；或者同一批金額，用 `HashSet` 去重剩兩筆、換 `TreeSet` 卻剩一筆——**同樣叫 Set，判定「重複」的標準居然不一樣**。這篇講清楚三種 Set 怎麼選，以及最容易被忽略的問題：Set 說的「同一個」，到底是誰說了算。

## 技術背景

先破除一個想當然的認知：「**Set 會自動知道兩個物件是不是同一個**」。

不會。Set 只是執行判定，**判定標準是你的物件自己提供的**——而且 hash 家族和 Tree 家族用的標準根本不同。這是本篇最重要的一句話。

### 三種實作：順序性格複習

[總覽篇](collections-overview.md) 的前綴規律在 Set 上完全成立：

| | 底層 | 順序 | 效能 |
|---|---|---|---|
| `HashSet` | hash 表（內部就是 HashMap） | 不保證，還可能因擴容而變 | 平均 O(1) |
| `LinkedHashSet` | hash 表＋鏈結 | 插入順序 | O(1)，略多記憶體 |
| `TreeSet` | 紅黑樹 | 隨時排序 | O(log n) |

### hash 家族的判重：hashCode 分桶、equals 確認

`HashSet` 判斷「有沒有重複」走兩步：

1. 先算元素的 **`hashCode()`**，直接跳到對應的桶（bucket）——這就是 O(1) 的來源
2. 桶裡若已有元素，再用 **`equals()`** 逐一確認是不是同一個

所以判定標準是 `hashCode()` ＋ `equals()` **兩個一起**：hashCode 不同連桶都不同，equals 根本沒機會出場。這也是為什麼這兩個方法必須成對正確實作——規則細節見 [equals 與 hashCode 契約](equals-hashcode-contract.md)，而 hash 表本身怎麼運作，見 [Map：HashMap 基礎與正確使用](map-hashmap-basics.md)。

### Tree 家族的判重：compareTo 說了算，equals 沒有戲份

`TreeSet` 是紅黑樹，它對元素只做一件事：**比大小**。元素要嘛自己實作 `Comparable`，要嘛建構時給一個 `Comparator`。

關鍵在這：TreeSet 判斷重複的標準是 **`compareTo()` 回傳 0**——它從頭到尾不呼叫 `equals()`。兩個物件只要比出來「一樣大」，TreeSet 就認定是同一個，後來的進不去。

當一個型別的 `compareTo` 和 `equals` 標準不一致時，HashSet 和 TreeSet 就會給出不同的答案——而標準庫裡就有一個著名的例子：`BigDecimal`（見下方實測）。

## 實際案例

### 案例一：去重失敗——因為你沒告訴它什麼叫「同一個」

```java
class Member {                     // 沒寫 equals/hashCode 的普通 class
    final String id;
    Member(String id) { this.id = id; }
}

Set<Member> a = new HashSet<>();
a.add(new Member("A001"));
a.add(new Member("A001"));        // 「同一個會員」再加一次
```

實測輸出：

```
class 沒寫 equals/hashCode：size = 2   ← 去重失敗
record 自動生成：        size = 1   ← 同樣的欄位，record 就正確
```

沒有覆寫時，`equals`/`hashCode` 用的是 `Object` 的預設實作——**比較的是記憶體位址**。兩個 `new` 出來的 Member 位址必然不同，Set 眼中就是兩個人。解法有二：老實覆寫 `equals`＋`hashCode`（IDE 生成或 `Objects.hash`），或者——值物件直接用 **record**，編譯器按欄位自動生成，上面 `record MemberR(String id) {}` 一行就是完整的正確版本。

### 案例二：同一批資料，HashSet 和 TreeSet 答案不同

```java
List<BigDecimal> prices = List.of(new BigDecimal("1.0"), new BigDecimal("1.00"));
System.out.println(new HashSet<>(prices).size());
System.out.println(new TreeSet<>(prices).size());
```

實測輸出：

```
BigDecimal → HashSet：size = 2
BigDecimal → TreeSet：size = 1
```

原因：`BigDecimal` 的 `equals` 連 scale 一起比——`1.0` 和 `1.00` 精度不同，**不相等**，所以 HashSet 留下兩筆；但 `compareTo` 只比數值——`1.0` 跟 `1.00` 一樣大，回傳 0，所以 TreeSet 認定重複只留一筆。

兩邊都沒有 bug，它們只是誠實地用了各自的標準。教訓：**金額去重前先想清楚你要的「同一個」是數值相同還是精度也相同**，並且對任何要進 TreeSet／TreeMap 的型別，檢查它的 `compareTo` 是否與 `equals` 一致（`BigDecimal` 的 Javadoc 對此有明確警告）。

## 技術優缺點

### 去重這件事的代價

- **HashSet 的 O(1) 用記憶體換**：內部是一整個 HashMap，每個元素揹一個 entry 的開銷；hash 計算本身也是成本（複雜物件的 `hashCode` 不見得便宜）
- **TreeSet 的排序用時間換**：每次增刪查都是 O(log n) 的比較走訪；還要求元素可比較，對沒有自然順序的型別得額外寫 Comparator
- **LinkedHashSet 的順序用空間換**：每個元素多兩條鏈結參考——但「去重＋保序」的需求它是唯一正解，這點錢該花

### 共同的地雷：可變物件當元素

把物件放進 `HashSet` 之後**修改了參與 hashCode 計算的欄位**，它的 hash 就變了——但它還躺在舊 hash 的桶裡。結果：`contains()` 找不到它、`remove()` 移不掉它，它變成一個「幽靈元素」永遠佔著位置。規則：**進了 hash 容器的物件，參與判定的欄位就不要再改**——這也是值物件建議用不可變設計（record、final 欄位）的實務原因之一。

## 小結

- Set 不會自動懂「同一個」——**判定標準是元素自己提供的**，而且兩家標準不同：hash 家族看 `hashCode`＋`equals`，Tree 家族**只看 `compareTo` 是否為 0**
- 沒覆寫 `equals`/`hashCode` 的 class 去重必失敗（比位址）；值物件用 **record** 一行解決
- `BigDecimal("1.0")` vs `("1.00")`：HashSet 說 2 個、TreeSet 說 1 個——`compareTo` 與 `equals` 不一致的型別，進 Tree 容器前要三思
- 選型：去重用 `HashSet`；去重＋保插入序用 `LinkedHashSet`；去重＋隨時排序用 `TreeSet`（付 O(log n)）
- 可變物件改了欄位就變 hash 容器裡的幽靈——參與判定的欄位保持不可變

兩條線接下去：hash 分桶的完整機制（容量、負載因子、碰撞了怎麼辦），見 [Map：HashMap 基礎與正確使用](map-hashmap-basics.md)；`equals`/`hashCode` 到底該怎麼寫才算對，見 [equals 與 hashCode 契約](equals-hashcode-contract.md)；TreeSet 的比較邏輯怎麼客製（含中文排序的 `Collator`），見規劃中的〈排序：Comparable vs Comparator〉。

## 常見面試題

1. HashSet 怎麼判斷元素重複？（提示：hashCode 分桶在先、equals 確認在後，兩者缺一不可）
2. TreeSet 判斷重複的標準和 HashSet 一樣嗎？（提示：compareTo == 0，equals 沒戲份；舉 BigDecimal 的 1.0/1.00 當例子）
3. 物件放進 HashSet 後修改欄位會發生什麼事？（提示：hash 變了人還在舊桶，contains/remove 雙雙失效）

## 延伸閱讀

- [Set（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html) — 介面契約，特別是關於元素可變性的警告
- [SortedSet（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/SortedSet.html) — 「ordering 必須 consistent with equals」的官方說明，BigDecimal 正是反例
