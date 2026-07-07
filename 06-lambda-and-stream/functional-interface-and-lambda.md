# Functional Interface 與 Lambda

## 前言

回頭看 [04 章](../04-collections/collections-overview.md)的程式碼，lambda 其實已經用了一路：`sort(Comparator.comparing(...))`、`removeIf(n -> n == 2)`、`computeIfAbsent(k, k -> new ArrayList<>())`——你大概也天天這樣寫。

但停下來問三個問題：`n -> n == 2` 這段程式碼的**型別**是什麼？它跟寫一個匿名內部類是同一回事嗎？為什麼 lambda 裡面不能改外面的變數？「會寫」和「懂了」的差距就在這三題。這篇補上地基，06 章接下來的 Stream 全部蓋在上面。

## 技術背景

### Functional Interface：lambda 的「型別」

Lambda 自己沒有型別——它是**某個 functional interface 的實作**。Functional interface 的定義：**恰好一個抽象方法**的介面（可以有 default/static 方法）：

```java
@FunctionalInterface                 // 非必要，但加了編譯器會幫你守門
interface StringCheck {
    boolean check(String s);         // 唯一的抽象方法
}

StringCheck c = s -> s.isEmpty();    // lambda = 這個方法的實作
```

lambda 的型別由**接收它的位置**決定（target typing）——同一段 `s -> s.isEmpty()` 賦給 `Predicate<String>` 就是 Predicate、賦給 `StringCheck` 就是 StringCheck（見實測）。這就是為什麼 lambda 不能單獨存在：`var f = s -> s.isEmpty()` 編譯不過，沒有目標型別可推。

### 內建四大家族：先查表，再自己發明

`java.util.function` 把常用的「方法形狀」都定義好了，98% 的情況不需要自訂：

| 介面 | 方法 | 語意 | 你已經在哪用過 |
|---|---|---|---|
| `Predicate<T>` | `boolean test(T)` | 判斷 | `removeIf`、Stream `filter` |
| `Function<T, R>` | `R apply(T)` | 轉換 | `computeIfAbsent`、Stream `map` |
| `Consumer<T>` | `void accept(T)` | 消費 | `forEach` |
| `Supplier<T>` | `T get()` | 生產 | 延遲取值、`orElseGet` |

延伸款按規律組合：`Bi` 前綴收兩個參數（`BiFunction`）、`UnaryOperator<T>` 是輸入輸出同型的 Function、原始型別特化（`IntPredicate`、`ToIntFunction`）避免裝箱——[排序篇](../04-collections/sorting-comparable-comparator.md)的 `comparingInt` 就是同一個道理。

### 方法參考：lambda 的再簡寫

當 lambda 只是「轉呼叫一個現成方法」，用 `::` 更乾淨。四種形式：

| 形式 | 寫法 | 等價 lambda |
|---|---|---|
| 靜態方法 | `Integer::parseInt` | `s -> Integer.parseInt(s)` |
| 特定物件的方法 | `System.out::println` | `x -> System.out.println(x)` |
| 任意物件的方法 | `String::isEmpty` | `s -> s.isEmpty()` |
| 建構子 | `ArrayList::new` | `() -> new ArrayList<>()` |

第三種最容易迷惑：`String::isEmpty` 的參數就是**被呼叫的那個物件**。準則：方法參考讀起來更順就用，需要腦內展開才懂就退回 lambda。

### 變數捕獲：為什麼一定要 effectively final

Lambda 可以用外層的區域變數，但那個變數必須是 **effectively final**（沒宣告 final 但從未被改過）。原因回到 [01 章的 Stack](../01-jvm/memory-stack-and-heap.md)：區域變數活在 stack frame 裡，**方法返回就消失**——而 lambda 可能活得更久（存起來、丟給別的執行緒）。所以 lambda 捕獲的是**值的拷貝**，不是變數本身；既然是拷貝，就不能讓你以為改得動原本——乾脆禁止改，兩邊永遠一致。

想在 lambda 裡累加的正確出路：**改寫成回傳值的形式**（Stream 的 `reduce`/`sum`，下一篇的主場），而不是拿單元素陣列 `int[] total` 硬繞——那是在自己騙自己。

## 實際案例

### 案例一：同一段 lambda，兩種身分

```java
Predicate<String> p = s -> s.isEmpty();
StringCheck     c = s -> s.isEmpty();    // 一字不差的 lambda
```

實測兩個都正常運作——**型別來自賦值的目標**，不在 lambda 本身。

### 案例二：lambda 不是匿名內部類的語法糖

兩個最實在的證據。第一，`this` 指的東西不同：

```java
Runnable anon = new Runnable() {
    @Override public void run() { System.out.println(this.getClass().getSimpleName()); }
};
Runnable lambda = () -> System.out.println(this.getClass().getSimpleName());
```

實測輸出：

```
匿名類的 this  ：            ← this 是匿名類自己（SimpleName 是空字串）
lambda 的 this ：LambdaDemo  ← this 是外層物件，lambda 沒有自己的身分
```

第二，編譯產物不同——`javac` 之後 `ls *.class`：

```
LambdaDemo.class
LambdaDemo$1.class          ← 匿名類：實實在在多一個 class 檔
LambdaDemo$StringCheck.class
```

**lambda 沒有對應的 class 檔**——它編譯成 `invokedynamic` 指令，執行期才由 JVM 決定怎麼生成實作。這帶來實務差異：匿名類的 `this`/`super` 有自己的一套、lambda 完全借用外層；大量匿名類會撐大 jar 與 Metaspace，lambda 不會。

### 案例三：effectively final 的真實錯誤訊息

```java
int total = 0;
List.of(100, 250, 80).forEach(n -> total += n);   // 想在 lambda 裡累加
```

實測編譯錯誤：

```
error: local variables referenced from a lambda expression must be final or effectively final
```

看到這個錯誤時，正確反應不是想辦法繞過（單元素陣列、AtomicInteger），而是**換形式**——「把值累出來」是 `stream().mapToInt(...).sum()` 的工作，下一篇見。

## 技術優缺點

### Lambda 帶來的

- **行為參數化**：把「做什麼」當參數傳，`removeIf`/`computeIfAbsent` 這類 API 才可能存在
- **延遲執行**：lambda 是「一段還沒跑的程式碼」——`orElseGet(() -> expensive())` 不到需要不執行，這是 Stream 惰性求值的基礎
- **零類檔案**：invokedynamic 讓 lambda 不佔 jar 體積與 Metaspace

### 代價與紀律

- **Stack trace 變醜**：出錯時看到 `lambda$main$0`，除錯體驗差一截——lambda 一長就該提成有名字的方法（方法參考順勢接手）
- **可讀性會反轉**：超過三行的 lambda 是壞味道；巢狀 lambda 是災難
- **檢查型例外很尷尬**：內建 functional interface 都不宣告 throws，lambda 裡呼叫會拋 `IOException` 的方法就得包一層——這個痛點 Stream 篇會再遇到
- 首次呼叫有一次 linkage 成本（之後與匿名類無異）——熱路徑之外無感，別為此犧牲可讀性

## 小結

- Lambda 沒有自己的型別——它是某個 **functional interface（恰好一個抽象方法）** 的實作，型別由目標位置推斷（同一段 lambda 可以是不同介面）
- 四大家族先查表：判斷 `Predicate`、轉換 `Function`、消費 `Consumer`、生產 `Supplier`；原始型別特化避裝箱
- 方法參考四式（靜態／特定物件／任意物件／建構子），讀起來更順才用
- **Lambda ≠ 匿名內部類**：`this` 借用外層、不產生 class 檔（invokedynamic）——兩個都有實測證據
- **effectively final 的本質**：捕獲的是值的拷貝（stack 變數活不過方法），想累加就換成「回傳值」的形式

行為可以當參數傳之後，整條資料處理管線就能組裝起來了——`filter` 收 Predicate、`map` 收 Function、`forEach` 收 Consumer，這就是下一篇的 Stream：見規劃中的〈Stream：中間操作與終端操作〉。

## 常見面試題

1. 什麼是 functional interface？`@FunctionalInterface` 有什麼作用？（提示：恰好一個抽象方法；註解只是編譯期守門）
2. Lambda 和匿名內部類有什麼差別？（提示：this 指誰、產不產生 class 檔、invokedynamic）
3. 為什麼 lambda 捕獲的區域變數必須 effectively final？（提示：stack frame 的生命週期、值的拷貝）

## 延伸閱讀

- [java.util.function（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/function/package-summary.html) — 內建 functional interface 全目錄
- [State of the Lambda（Brian Goetz）](https://cr.openjdk.org/~briangoetz/lambda/lambda-state-final.html) — 設計者親筆的 lambda 設計文件，target typing 與 invokedynamic 決策的源頭
