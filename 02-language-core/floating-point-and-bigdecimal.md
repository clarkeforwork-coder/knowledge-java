# 浮點數與 BigDecimal

## 前言

先看一行讓所有金融從業者心臟停一拍的實測：

```java
System.out.println(0.1 + 0.2);        // 0.30000000000000004
System.out.println(0.1 + 0.2 == 0.3); // false
```

一毛加兩毛不等於三毛。對報表是誤差，對保費、理賠金額是**事故**。這篇講兩件事：為什麼浮點數天生不精確（IEEE-754 的結構性原因，不是 bug），以及金額運算的正解 `BigDecimal`——連同它自己的三顆地雷。

## 技術背景

### 浮點數在記憶體裡長什麼樣（IEEE-754）

以 `float`（32 bit）為例：

| 欄位 | 位元數 | 用途 |
|---|---|---|
| sign | 1 bit | 正負號 |
| exponent | 8 bit | 指數（以 127 為偏移：存 124 代表指數 -3） |
| fraction | 23 bit | 尾數 |

本質是**二進位的科學記號**：值 ＝ 尾數 × 2^指數。兩個結構性限制：

1. **尾數位有限**（float 23 bit、double 52 bit）——存不下無限位數，超出就依規則捨入（IEEE-754 用「向最接近的偶數捨入」）
2. **二進位表示不了大多數十進位小數**——0.1 in binary 是無限循環（`0.0001100110011…`），就像十進位表示不了 1/3。**0.1 存進 double 的那一刻就已經不是 0.1 了**，後面的運算只是把誤差累積放大

所以 `0.1 + 0.2 ≠ 0.3` 不是 Java 的 bug，是所有用 IEEE-754 的語言的共同宿命——規矩因此只有一條：**金額運算絕不用 float／double**。

### BigDecimal：十進位的精確算術

用「無限位數的十進位整數＋小數位數（scale）」表示數字——十進位小數存多少是多少，代價是物件開銷與較慢的運算。但它有三顆自己的地雷：

**地雷一：建構子收 double ＝ 把污染帶進來**

```java
new BigDecimal(0.8)     // ❌ 0.8000000000000000444089209850062616169452667236328125
new BigDecimal("0.8")   // ✅ 0.8
```

`0.8` 這個 double 早就不是 0.8 了（見上），`BigDecimal` 只是忠實記錄了那個被污染的值。**建構一律用字串**（或 `BigDecimal.valueOf(double)`——它內部先轉字串）。

**地雷二：divide 除不盡直接炸**

```java
new BigDecimal("1").divide(new BigDecimal("3"))
// ❌ ArithmeticException: Non-terminating decimal expansion
```

十進位下 1/3 是無限循環，BigDecimal 不替你決定捨入——**`divide` 必帶精度與捨入模式**：

```java
new BigDecimal("1").divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP)   // 0.33
```

**地雷三：equals 連 scale 一起比**

```java
new BigDecimal("0.1").equals(new BigDecimal("0.10"))          // false！
new BigDecimal("0.1").compareTo(new BigDecimal("0.10")) == 0  // true
```

`equals` 認為 0.1 和 0.10 精度不同、不相等——**比數值一律 `compareTo`**。這個「equals 與 compareTo 不一致」的特性，在 [Set 篇](../04-collections/set-implementations.md)造成過 HashSet 說 2 個、TreeSet 說 1 個的雙重人格——同一顆雷的容器版。

## 實際案例

全部實測輸出：

```
0.1 + 0.2 = 0.30000000000000004
== 0.3 ？ false

new BigDecimal(0.8)  ：0.8000000000000000444089209850062616169452667236328125
new BigDecimal("0.8")：0.8

divide 除不盡：Non-terminating decimal expansion; no exact representable decimal result.
指定精度與捨入：0.33

equals   ：false｜compareTo == 0：true
```

四段輸出對應四條規矩：金額不用浮點、建構用字串、divide 帶捨入、比較用 compareTo——每一條都有一個真實的爆炸現場當理由。

## 技術優缺點

### double 的正當用途

- ✅ 快（硬體指令直算）、省（8 bytes 無物件）——科學計算、統計、圖形、機率這些「誤差可容忍」的領域是它的主場
- ❌ 十進位小數天生失真——**錢、費率、任何要跟人對帳的數字，出局**

### BigDecimal

- ✅ 十進位精確、精度與捨入全由你控制——金額運算的唯一正解
- ❌ 物件開銷＋方法呼叫式的運算（`a.add(b).multiply(c)`，讀起來囉嗦）
- ❌ 三顆地雷全是 API 設計的稜角：double 建構子、divide 預設不捨入、equals 比 scale——**知道就免疫，不知道就中獎**
- 實務補充：資料庫對應 `DECIMAL`/`NUMERIC` 型別；JPA entity 的金額欄位型別宣告 `BigDecimal`，讓精確性貫穿全鏈路

## 小結

- `0.1 + 0.2 == 0.3` 為 **false**（實測）——二進位表示不了大多數十進位小數，存進去那刻就失真，這是 IEEE-754 的結構不是 bug
- **金額規矩四條**（各有實測爆炸現場）：不用 float/double、`BigDecimal` 建構用字串、`divide` 必帶精度與 `RoundingMode`、比較用 `compareTo`
- `equals` 連 scale 比（0.1 ≠ 0.10）——這顆雷在 [Set 篇](../04-collections/set-implementations.md)有容器版續集
- double 沒有錯，錯的是用錯地方——誤差可容忍的計算它又快又好

## 常見面試題

1. 為什麼 `0.1 + 0.2 != 0.3`？（提示：二進位無限循環、尾數位有限——存進去就失真）
2. `new BigDecimal(0.8)` 有什麼問題？正確寫法？（提示：double 的污染被忠實記錄；字串或 valueOf）
3. BigDecimal 的 `equals` 和 `compareTo` 差在哪？（提示：scale；HashSet/TreeSet 的雙重人格）

## 延伸閱讀

- [BigDecimal（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) — 開頭的 API note 就在警告 equals 與 double 建構子
- [What Every Computer Scientist Should Know About Floating-Point Arithmetic](https://docs.oracle.com/cd/E19957-01/806-3568/ncg_goldberg.html) — 浮點數的經典論文，Oracle 官方轉載
