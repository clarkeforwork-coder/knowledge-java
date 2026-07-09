# Autoboxing 與 Unboxing

## 前言

一道找碴題——這兩段程式碼只差**一個字母**，效能差了 11 倍：

```java
long sum = 0;                                      // 23 ms
Long sum = 0L;                                     // 265 ms ← 就差在大寫 L
for (long i = 0; i < 100_000_000L; i++) sum += i;
```

[資料型態篇](data-types.md)講了 primitive 與 wrapper 是兩個世界；本篇講**編譯器在兩個世界之間偷偷搭的橋**——自動裝箱與拆箱。橋很方便，但過橋要收費、橋上還有地雷（null、三元運算子），這篇用 bytecode 和實測把橋看個清楚。

## 技術背景

### 機制：又是編譯器代寫

```java
Integer boxed = 10;      // 你寫的
int raw = boxed;
```

`javap -c` 實測看到的：

```
invokestatic  Integer.valueOf:(I)Ljava/lang/Integer;   ← 裝箱＝呼叫 valueOf
invokevirtual Integer.intValue:()I                      ← 拆箱＝呼叫 intValue
```

跟[泛型擦除篇](../05-generics/generics-basics-and-type-erasure.md)的 `checkcast` 同一個模式：**語法糖背後是編譯器代寫的方法呼叫**。兩個直接推論：裝箱走 `valueOf` → 所以有 [IntegerCache](data-types.md)（-128~127 共用）；拆箱走 `intValue()` → 所以 **null 拆箱＝對 null 呼叫方法＝NPE**。

### 什麼時候會發生（比你以為的多）

| 場景 | 例子 | 方向 |
|---|---|---|
| 賦值／傳參 | `Integer x = 10`、`f(10)` 收 Integer | 裝箱 |
| 集合放取 | `list.add(1)`、`int v = list.get(0)` | 放裝、取拆 |
| 算術運算 | `wrapper + 1`、`wrapper++` | **先拆再算再裝**（三次操作！） |
| 混合比較 | `Integer == int` | 拆箱比值（反而安全） |
| **三元運算子** | `cond ? Integer : int` | **整式拆成 int**——地雷區（見案例三） |

兩個不對稱值得記：`Integer == int` 會拆箱**比值**，但 `Integer == Integer` 比**參考**——同一個運算子兩種語意；`wrapper++` 不是一步，是「拆、加、裝」三步——迴圈裡就是三倍的隱形工作＋每輪一個新物件。

## 實際案例

### 案例一：bytecode 存證

一行 `Integer boxed = 10` 編譯後就是 `Integer.valueOf(10)`（實測輸出見上）——「自動」的意思是編譯器代寫，不是零成本。

### 案例二：一個字母 11 倍——Long 累加的帳單

一億次累加，`long` vs `Long`，JIT 預熱後實測：

```
long 累加：23 ms｜Long 累加：265 ms
```

`Long sum` 版每一輪都是「拆箱→相加→裝箱」，而且累加值超出快取範圍後**每輪 new 一個 Long 物件**——一億次迴圈就是上億個垃圾（[GC 篇](../01-jvm/gc-basics-and-generations.md)的朝生夕死大軍就是這樣製造的）。宣告型別的一個字母，決定整條迴圈的性格。這也是 [06 章](../06-lambda-and-stream/stream-operations.md) `mapToInt`／`IntStream` 存在的理由——同一筆帳。

### 案例三：三元運算子的隱形拆箱

```java
Integer discount = null;
Integer result = true ? discount : 0;    // 看起來人畜無害
```

實測：`NullPointerException`。

原因藏在型別推斷：分支一邊是 `Integer`、一邊是 `int` 字面值 `0`——**整個三元運算式被推斷為 `int`**，於是 `discount` 被強制拆箱，null 當場爆炸。修法：讓兩邊型別一致（`true ? discount : Integer.valueOf(0)`，或直接 `(Integer) 0`）。這顆雷的陰險在於：它跟 [null 拆箱](data-types.md)同源，但**藏在一個看起來沒有拆箱的運算式裡**。

## 技術優缺點

### 自動裝拆箱買到的

- **語法統一**：primitive 能直接用在需要物件的地方（集合、泛型），不用手寫 `Integer.valueOf` 滿天飛
- 配合 IntegerCache，小值裝箱免配置

### 過橋費與地雷

- **效能是隱形的**：`Long sum` 慢 11 倍、`wrapper++` 三步走——型別宣告錯一個字，profiler 才找得到
- **null 是隨時會踩的雷**：直接拆箱、三元運算子、`Map.get` 沒中回 null 再拆箱——全是同一顆
- 紀律三條：**熱路徑檢查型別**（迴圈累加變數、大陣列一律 primitive）；**數字流用特化**（`IntStream`/`mapToInt`）；**比較永遠 `equals`**（[資料型態篇](data-types.md)的規矩）

## 小結

- 裝箱＝`valueOf`、拆箱＝`intValue`——**編譯器代寫的方法呼叫**（javap 實測為證），不是零成本
- 發生場景比直覺多：賦值、傳參、集合、算術（拆算裝三步）、**三元運算子的型別推斷**
- 一個字母 11 倍（實測 23ms vs 265ms）：`Long` 累加每輪造一個物件——熱路徑用 primitive、數字流用 `IntStream`
- 三元運算子混用 `Integer`/`int` → 整式變 int → 隱形拆箱 NPE（實測）
- `Integer == int` 拆箱比值、`Integer == Integer` 比參考——同運算子兩種語意，別考驗記憶，用 `equals`

裝箱的物件共享（IntegerCache）讓 `==` 偶爾「碰巧」對——這個劇本在另一個地方以更大規模上演：String Pool。見 [String 與 StringBuilder](string-and-stringbuilder.md)。

## 常見面試題

1. 自動裝箱／拆箱的底層機制是什麼？（提示：valueOf／intValue，javap 可證；跟快取的關係）
2. `Long sum = 0L` 在迴圈裡累加有什麼問題？（提示：拆算裝三步＋每輪新物件；實測 11 倍）
3. `true ? integerObj : 0` 可能拋什麼例外？為什麼？（提示：三元運算子的型別推斷、隱形拆箱）

## 延伸閱讀

- [Java Tutorials: Autoboxing and Unboxing](https://docs.oracle.com/javase/tutorial/java/data/autoboxing.html) — 官方教學與發生場景清單
- Effective Java 第三版 Item 6（避免建立不必要的物件）— Long 累加案例的原始出處
