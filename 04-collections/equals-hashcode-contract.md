# equals 與 hashCode 契約

## 前言

這一章走到這裡，同一對方法已經被點名五次：[總覽篇](collections-overview.md) 說 hash 家族「把靈魂外包給你的物件」、[Set 篇](set-implementations.md) 的去重失敗、[Map 篇](map-hashmap-basics.md) 的 key 規矩——罪魁禍首全是 `equals()` 和 `hashCode()`。

「IDE 一鍵生成不就好了？」——生成沒問題，問題是**看不懂規則的人，也看不出生成後被改壞的版本**：加了欄位忘了重新生成、繼承後偷偷加嚴 equals、參與計算的欄位被改成可變……這些 bug 全部無聲無息，直到某天 HashMap 拿不到資料。這篇把契約一次講清楚，讓你 review 時抓得出來。

## 技術背景

先破除最危險的誤解：「**覆寫 equals 就夠了，hashCode 只是效能優化**」。

錯。在 hash 容器的世界，`hashCode` 是**正確性**的一部分——[Map 篇](map-hashmap-basics.md) 講過查找流程是「hashCode 分桶 → equals 確認」，hashCode 錯了，連桶都走錯，equals 寫得再對也沒機會出場。

### equals 的五條契約

`Object.equals` 的 Javadoc 白紙黑字要求：

| 契約 | 白話 | 誰最常踩 |
|---|---|---|
| 自反性 | `x.equals(x)` 必為 true | 很難踩 |
| **對稱性** | `x.equals(y)` ⇔ `y.equals(x)` | **繼承＋加欄位**（見實測） |
| 遞移性 | x=y、y=z ⇒ x=z | 同上 |
| 一致性 | 欄位沒變，結果就不能變 | 拿會變的欄位參與比較 |
| 非空性 | `x.equals(null)` 必為 false | `instanceof` 天然處理掉 |

最難守的是對稱性和遞移性，而且幾乎都栽在同一件事上：**繼承一個類別後加了欄位、又想加嚴 equals**。這在數學上就無解（Effective Java 的著名結論）——實務對策：值物件宣告成 `final` 類別（或直接用 record）斷了繼承的路；真要擴充，用組合代替繼承。

### hashCode 的契約：只有一條，但夠致命

> **`equals` 判定相等的兩個物件，`hashCode` 必須相等。**

反方向不要求——不相等的物件允許撞出相同 hash（碰撞本來就允許）。從這條可以直接推出鐵律：

- **兩個方法必須一起覆寫**：只覆寫 equals，兩個「相等」的物件用 `Object` 預設的 hashCode（各自的位址）→ 進了不同的桶 → hash 容器全面失靈
- **兩邊必須用同一組欄位**：equals 比 A、B 欄位，hashCode 卻只算 A——某些「相等」物件 hash 不同，契約破裂

### 正確的寫法，按優先順序

```java
// 首選：值物件直接用 record —— 編譯器按全部欄位生成，永遠成對、永遠一致
record Coupon(String code, int discount) {}

// 次選：手寫（或 IDE 生成），格式固定
final class Coupon {
    private final String code;
    private final int discount;

    @Override public boolean equals(Object o) {
        return o instanceof Coupon c
                && discount == c.discount
                && Objects.equals(code, c.code);
    }
    @Override public int hashCode() {
        return Objects.hash(code, discount);   // 跟 equals 用同一組欄位
    }
}
```

幾個實務備註：

- **IDE 生成的最大風險是「過時」**——之後加欄位忘了重新生成，契約悄悄破裂。record 沒有這個問題
- Lombok 的 `@EqualsAndHashCode` 在企業專案很常見，效果同 IDE 生成，同樣注意欄位變動
- **JPA Entity 是特例**：有 proxy、id 延遲生成的問題，「用哪些欄位」有專門的爭論——留給 08 資料存取章，別直接套本篇的值物件寫法

## 實際案例

### 案例一：半套覆寫——List 找得到，HashMap 找不到

```java
class Coupon {                       // 只覆寫 equals，沒覆寫 hashCode
    final String code;
    @Override public boolean equals(Object o) {
        return o instanceof Coupon c && code.equals(c.code);
    }
}
```

同一個 `new Coupon("SAVE20")`，三種容器實測：

```
List.contains  ：true       ← List 只用 equals，一切正常
HashSet 去重後 ：size = 2   ← hash 不同，進了不同桶，去重失敗
HashMap.get    ：null       ← 走錯桶，equals 沒機會出場
```

這就是半套覆寫最陰險的地方：**在 List 裡測起來完全正常**，單元測試都會過——直到某天有人把容器換成 HashSet/HashMap。

### 案例二：繼承加欄位——對稱性當場破裂

```java
class Point {           // equals: instanceof Point，比 x、y
    ...
}
class ColorPoint extends Point {    // 加了 color 欄位，equals 加嚴
    @Override public boolean equals(Object o) {
        return o instanceof ColorPoint c && super.equals(o) && color.equals(c.color);
    }
}
```

實測：

```
p.equals(cp)：true    ← Point 眼中：座標一樣就是同一個
cp.equals(p)：false   ← ColorPoint 眼中：你連顏色都沒有
[cp] contains p ：true    ← 同一對物件，contains 的答案
[p] contains cp ：false   ← 取決於誰在集合裡、誰當參數
```

`x.equals(y)` 和 `y.equals(x)` 答案不同，`contains`、`remove`、`indexOf` 的行為就跟著看方向——這種 bug 的重現條件詭異到讓人懷疑 JDK 有問題。root cause 只是子類加嚴了 equals。

## 技術優缺點

各種實作方式的取捨：

| 方式 | 優點 | 代價/風險 |
|---|---|---|
| **record** | 編譯器代管、永不過時、天生不可變 | 只適合值物件；欄位全參與（不能挑） |
| IDE 生成 | 快、格式標準 | **加欄位忘了重生成**，契約無聲破裂 |
| `Objects.hash(...)` 手寫 | 明確可控、可挑欄位 | varargs 有裝箱小開銷（熱路徑可改手動組合） |
| Lombok `@EqualsAndHashCode` | 零樣板 | 編譯期魔法；`callSuper`、排除欄位的設定要看懂 |
| `getClass()` 比較 vs `instanceof` | getClass 嚴格同類、對稱性穩 | 換來「子類永不等於父類」，代理物件（如 Hibernate proxy）會出事 |

共同的鐵律不變：**成對覆寫、同組欄位、欄位不可變**（可變欄位參與 hash 的下場見 [Map 篇的幽靈 key](map-hashmap-basics.md)）。

## 小結

- hashCode 不是優化，是**正確性**：hash 容器先分桶再 equals，桶錯全錯
- 兩條鐵律：**一起覆寫**（半套的下場：List 正常、HashMap 失靈）；**同一組欄位**（equals 比什麼，hashCode 就算什麼）
- equals 五契約中最難守的是對稱性——**繼承＋加欄位＋加嚴 equals 無解**，值物件用 final class 或 record 斷根
- 首選 record；用 IDE/Lombok 生成的，加欄位時記得重生成
- JPA Entity 是特例，別套值物件寫法（詳見未來 08 章）

契約的世界還有一位鄰居：`compareTo`。它跟 equals 的一致性沒有強制契約，只有「強烈建議」——[Set 篇](set-implementations.md) 的 BigDecimal 就是不一致的著名案例。排序的完整規則，見規劃中的〈排序：Comparable vs Comparator〉。

## 常見面試題

1. 為什麼覆寫 equals 一定要覆寫 hashCode？（提示：hash 容器的兩段式查找；答得出「List 正常、HashMap 失靈」就是懂了）
2. equals 有哪五條契約？哪條最難守、為什麼？（提示：對稱性；繼承加欄位在數學上無解）
3. record 生成的 equals/hashCode 是什麼規則？什麼時候不能用 record？（提示：全欄位參與；需要挑欄位、需要繼承、JPA Entity）

## 延伸閱讀

- [Object.equals / Object.hashCode（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Object.html#equals(java.lang.Object)) — 契約的原始出處
- Effective Java 第三版 Item 10（覆寫 equals 的通用約定）、Item 11（覆寫 equals 就覆寫 hashCode）— 本篇「繼承無解」結論的完整論證
