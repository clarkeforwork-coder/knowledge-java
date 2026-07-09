# String 與 StringBuilder

## 前言

一段組報表字串的迴圈，資料量從一千長到十萬，執行時間卻不是變慢一百倍——是慢到懷疑人生。實測：十萬次 `+=` 串接要 **257ms**，換成 StringBuilder 只要 **1ms**。

慢 257 倍的根源只有四個字：**String 不可變**。這篇講清楚不可變的代價與紅利（它不是缺陷，是換來三樣好東西的交易）、什麼時候該換 StringBuilder、以及編譯器其實已經幫你優化掉哪些場景——別再把單行的 `+` 也改掉了。

## 技術背景

### String 是不可變的：改它＝造新的

```java
String s = "Hello";
s = s + "World";      // 不是修改！是造一個新 String，s 改指過去
```

原本的 `"Hello"` 一個字元都沒變——**任何「修改」操作都回傳新物件**。所以迴圈裡 `+=` 的真相：每一輪造一個更長的新字串、把舊內容整段拷過去——第 N 輪拷 N 個字元，總工作量 O(n²)。257 倍就是這樣長出來的。

### 不可變換來的三樣東西

1. **String Pool 的共享**：字面值存在池裡，`"java"` 全 JVM 共用一份——可變的話，改一處全域遭殃。副作用是 `==` 有時「碰巧」為 true（實測：字面值相等、`new String` 不等）——[IntegerCache](data-types.md) 的同一個劇本，規矩也相同：**比內容永遠 `equals`**
2. **hashCode 快取**：算一次存起來（欄位不變、hash 永遠有效）——這就是 String 是最理想 [HashMap key](../04-collections/map-hashmap-basics.md) 的原因
3. **執行緒安全**：不可變物件天生可以跨 thread 共享（[07 章](../07-concurrency/synchronized-and-volatile.md)一整章在對付的問題，它免疫）

### 編譯器已經優化的：單行的 `+` 不用改

```java
return "Hello, " + name + "!";     // 單行串接
```

`javap -c` 實測：

```
invokedynamic #0:makeConcatWithConstants
```

Java 9 起單行串接編譯成 `invokedynamic`（[06 章](../06-lambda-and-stream/functional-interface-and-lambda.md)lambda 的同款技術），由 JVM 挑最佳策略——**單行的 `+` 又快又好讀，別改**。編譯器救不了的是**迴圈**：每輪都是獨立的串接運算式，優化不過去。

### StringBuilder：可變的字元緩衝

```java
StringBuilder sb = new StringBuilder();       // 已知長度可預給容量，同 ArrayList 的道理
for (String part : parts) sb.append(part);    // 在同一個內部陣列上追加
String result = sb.toString();
```

`append` 改的是內部緩衝、不造新字串——迴圈串接的正解。至於 `StringBuffer`：StringBuilder 的 synchronized 版，**已過時**——單執行緒白付鎖的錢；真要多執行緒組字串，該重新設計（各自組完再合併），不是靠方法級的鎖。

## 實際案例

### 案例一：257 倍的帳單

十萬次追加一個字元，實測：

```
迴圈 + 串接 ×100,000：257 ms｜StringBuilder：1 ms
```

O(n²) vs O(n) 的具象化。經驗法則：**串接發生在迴圈裡 → StringBuilder；單行 → `+` 就好**（編譯器有 indy 優化）。

### 案例二：`==` 的老劇本，String 版

```
字面值 == 字面值：true    ← 同一個 Pool 物件
new    == 字面值：false   ← new 強制造新物件，跳過 Pool
equals 比較     ：true
```

跟 [IntegerCache](data-types.md) 完全同構：共享機制讓 `==` 偶爾對——「碰巧 true」比「永遠 false」更危險，因為測試會過。**字串比較，`equals` 沒有例外**。

## 技術優缺點

### 不可變的交易

- ✅ 換到：Pool 共享省記憶體、hashCode 快取（完美 Map key）、天生執行緒安全
- ❌ 付出：**修改即複製**——迴圈串接 O(n²)（實測 257 倍）、`replace`/`substring` 都造新物件

### 工具選擇

- **單行串接**：`+`——indy 優化，可讀性最好
- **迴圈／多步組裝**：`StringBuilder`（可預給容量）
- **模板場景**：`String.format`／text block——可讀性優先於微效能
- **`StringBuffer`**：遺產，新程式碼不用

## 小結

- String 不可變：**「修改」都是造新物件**——迴圈 `+=` 是 O(n²)，實測 257ms vs StringBuilder 1ms
- 不可變是交易：換來 Pool 共享、hashCode 快取（最佳 Map key）、執行緒安全
- **單行 `+` 不用改**——Java 9+ 編譯成 invokedynamic（實測 bytecode 為證），優化不了的是迴圈
- `==` 對字串偶爾「碰巧」對（Pool）——與 IntegerCache 同劇本，**比較永遠 `equals`**
- StringBuffer 已過時；多執行緒組字串該重新設計而不是加鎖

## 常見面試題

1. String 為什麼設計成不可變？（提示：三樣紅利——Pool、hash 快取、執行緒安全）
2. 什麼時候該用 StringBuilder？單行的 `+` 需要改嗎？（提示：迴圈 O(n²) 實測；invokedynamic）
3. `new String("java") == "java"` 是什麼？為什麼？（提示：Pool vs 強制新物件；equals 規矩）

## 延伸閱讀

- [String（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) — 不可變契約與 intern 的官方說明
- [JEP 280: Indify String Concatenation](https://openjdk.org/jeps/280) — 單行 `+` 的 invokedynamic 優化提案
