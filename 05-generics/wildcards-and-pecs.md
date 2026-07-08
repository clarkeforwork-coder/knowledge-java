# 萬用字元與 PECS

## 前言

[擦除篇](generics-basics-and-type-erasure.md)結尾的懸念：寫一個 `double sum(List<Number> list)`，拿 `List<Integer>` 去呼叫——編譯錯誤。Integer 明明是 Number 的子類，為什麼裝著 Integer 的 List 就不是裝著 Number 的 List？

這是泛型最反直覺的一課：**不變性（invariance）**。而解藥——`? extends`、`? super`——又帶來人人聽過、沒人記得住的口訣 **PECS**。這篇把「為什麼不變」講到你能自己推出來，把 PECS 從口訣還原成常識。

## 技術背景

### 為什麼 `List<Integer>` 不是 `List<Number>`：先自己推一次

假設允許好了，看會發生什麼：

```java
List<Integer> ints = new ArrayList<>();
List<Number> nums = ints;      // 假設這行合法（實際上編譯錯誤）
nums.add(3.14);                // 透過 Number 視角塞一個 Double —— 完全合法的操作
Integer i = ints.get(0);       // 💥 ints 裡躺著一個 Double
```

第三行的每一步都無可指摘——問題出在第二行。**只要允許「子型別元素的容器」升格成「父型別元素的容器」，就開了塞錯東西的門**。所以泛型選擇不變：`List<Integer>` 與 `List<Number>` 就是兩個無關的型別（實測錯誤訊息見案例一）。

### `? extends`：只出不進的唯讀視角

```java
static double sum(List<? extends Number> list) {   // 收 List<Integer>、List<Double>…都行
    double total = 0;
    for (Number n : list) total += n.doubleValue(); // 讀出來保證是 Number ✓
    return total;
}
```

`List<? extends Number>` 的意思是「**某種** Number 子型別的 List，具體是哪種我不知道」。因為不知道：

- **讀，安全**——不管實際是 Integer 還是 Double，當 Number 用都對
- **寫，禁止**——你想 add 一個 Integer？萬一它實際是 `List<Double>` 呢？編譯器直接擋（實測那個著名的 `CAP#1` 錯誤，見案例二）

### `? super`：只進不出的寫入視角

```java
static void fill(List<? super Integer> list) {      // 收 List<Integer>、List<Number>、List<Object>
    list.add(42);                                    // 寫 Integer 保證安全 ✓
    Object o = list.get(0);                          // 讀出來只能當 Object
}
```

`List<? super Integer>` 是「**某種** Integer 父型別的 List」——不管實際是哪種，放 Integer 進去都合法；但讀出來只能保證是 Object。

### 用繼承階梯看：上界、下界與變異性

把型別的繼承關係想成一座梯子——父類在高處、子類在低處，兩種萬用字元就是在梯子上**劃界線**：

```
      Object          ─┐
        │              │  List<? super Integer>
      Number          ─┤  「Integer 以上（含）的某一階」
        │              │   ＝下界（lower bound）是 Integer
      Integer         ─┴─┐
        │                │  List<? extends Number> 也抓得到這裡
   （更低的子類…）      ─┘  「Number 以下（含）的某一階」
                            ＝上界（upper bound）是 Number
```

- **`? extends Number`＝設上界**：unknown 型別被封頂在 Number——只知道「不高於 Number」，所以讀出來**往高處看**（當 Number 用）永遠安全；往低處塞（寫入某個具體子類）不安全
- **`? super Integer`＝設下界**：unknown 型別被封底在 Integer——只知道「不低於 Integer」，所以**往低處塞** Integer（或其子類）永遠安全；讀出來只能往最高處看（Object）

這三種行為在型別理論裡各有名字：泛型預設**不變（invariant）**；`? extends` 讓容器順著元素的繼承方向走，叫**共變（covariant）**；`? super` 逆著走，叫**逆變（contravariant）**。名詞不用背，但「**在梯子上劃線：extends 封頂、super 封底**」這個圖像值得記——PECS 只是它的使用說明書。

### PECS：從口訣還原成常識

**Producer Extends, Consumer Super**——主詞是那個集合，視角是你的方法：

- 這個集合是來**給我資料**的（producer，我只讀）→ `? extends T`
- 這個集合是來**收我資料**的（consumer，我只寫）→ `? super T`
- 又讀又寫 → 不用萬用字元，用精確的 `List<T>`

JDK 自己就是最好的例句。`Collections.copy` 的官方簽名：

```java
public static <T> void copy(List<? super T> dest, List<? extends T> src)
//                          目的地=consumer=super    來源=producer=extends
```

還有 [06 章](../06-lambda-and-stream/stream-operations.md)天天用的 `Stream.map(Function<? super T, ? extends R>)`——現在你看得懂這個簽名在說什麼了：吃 T（consumer 視角收 super）、產 R（producer 視角給 extends）。

### 兩條使用紀律

1. **萬用字元用在參數，不用在回傳**——回傳 `List<? extends Number>` 是把「不知道是哪種」的痛苦轉嫁給每一個呼叫者
2. **`List<?>`（無界）**只在完全不碰元素型別時用（`size()`、`clear()`、`isEmpty()`）

## 實際案例

### 案例一：不變性的真實錯誤

```java
static double sum(List<Number> list) { ... }
sum(List.of(1, 2, 3));                       // List<Integer>
```

實測：

```
error: incompatible types: List<Integer> cannot be converted to List<Number>
```

沒有商量餘地——兩個型別無關。修法就是案例三的 `? extends`。

### 案例二：`? extends` 不能 add——認識 CAP#1

```java
List<? extends Number> nums = new ArrayList<Integer>();
nums.add(42);
```

實測：

```
error: incompatible types: int cannot be converted to CAP#1
  where CAP#1 is a fresh type-variable:
```

這個嚇人的 `CAP#1` 是編譯器給「那個不知道是誰的型別」取的代號（capture）。翻譯成人話：「你要 add 的是 int，但這個 List 實際裝的是**某種我抓不到的型別**，我不能保證相容」。看到 CAP# 系列錯誤，第一反應就該是：**我在對 extends 視角做寫入**。

### 案例三：PECS 實戰

```java
static double sum(List<? extends Number> list) { ... }           // producer
static <T> void copyAll(List<? super T> dest, List<? extends T> src) {   // JDK 同款簽名
    dest.addAll(src);
}
```

實測：

```
sum(List<Integer>)：6.0
sum(List<Double>) ：4.0
copyAll 到 List<Object>：[1, 2, 3]
```

同一個 `sum` 同時服務 Integer 和 Double 的 List（案例一的錯誤消失）；`copyAll` 能把 `List<Integer>` 拷進 `List<Object>`——**API 的彈性就是 PECS 買回來的**。

## 技術優缺點

### 萬用字元買到的

- **API 彈性**：一個 `? extends Number` 簽名服務所有數字型別的集合——沒有它，泛型的不變性會讓工具方法寸步難行
- **編譯期的讀寫紀律**：extends 擋寫、super 擋讀——方向錯誤在編譯期就死，不會等到執行期

### 付出的

- **可讀性成本**：`Function<? super T, ? extends R>` 對初學者是天書——但這成本由 API 作者付一次，換所有呼叫者的順暢
- **CAP# 錯誤訊息難懂**：capture 的概念沒學過就看不懂（現在你懂了）
- **紀律要求**：回傳型別用萬用字元是把成本轉嫁給呼叫者——code review 該擋

## 小結

- **泛型是不變的**：`List<Integer>` 與 `List<Number>` 無關（自己推一次：允許升格＝開門塞錯東西）
- **繼承階梯上劃線**：`? extends T` 封頂（上界，共變）、`? super T` 封底（下界，逆變）——泛型預設不變
- **`? extends T`**：producer 視角，只讀不寫（add 會撞 CAP#1）；**`? super T`**：consumer 視角，能寫 T、讀出只是 Object
- **PECS**＝「這集合是給我資料還是收我資料」；`Collections.copy(dest super, src extends)` 是官方例句，`Stream.map` 的簽名現在你看得懂了
- 紀律：萬用字元用在**參數**不用在回傳；又讀又寫用精確的 `T`

還剩一個歷史遺留的對照組：**陣列是協變的**——`Integer[]` 可以升格成 `Number[]`，編譯器不擋！那不就開了「塞錯東西」的門嗎？對，而且真的會炸——泛型與陣列的恩怨，見規劃中的〈泛型與陣列的地雷〉。

## 常見面試題

1. `List<Integer>` 能傳給參數型別 `List<Number>` 的方法嗎？為什麼？（提示：不變性；自己推「允許會怎樣」）
2. PECS 是什麼？舉一個 JDK 裡的真實簽名。（提示：producer/consumer 視角；Collections.copy 或 Stream.map）
   延伸追問：什麼是共變、逆變？（提示：繼承階梯上劃線——extends 封頂、super 封底，泛型預設不變）
3. 為什麼 `List<? extends Number>` 不能 add？（提示：CAP#1——實際型別抓不到，寫入無法保證相容）

## 延伸閱讀

- [Java Tutorials: Wildcards](https://docs.oracle.com/javase/tutorial/java/generics/wildcards.html) — 官方教學的萬用字元全章
- Effective Java 第三版 Item 31（用 bounded wildcards 增加 API 彈性）— PECS 的原始出處
