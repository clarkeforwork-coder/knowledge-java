# 泛型與陣列的地雷

## 前言

[PECS 篇](wildcards-and-pecs.md)費了整篇說明泛型為什麼**不變**——允許 `List<Integer>` 升格成 `List<Number>` 就開了塞錯東西的門。然後你發現一件怪事：

```java
Number[] nums = new Integer[3];    // 陣列版的「升格」——編譯器居然放行！
```

**陣列是協變的**。同一個危險動作，泛型用編譯錯誤擋死，陣列卻大方放行——為什麼雙重標準？把兩套哲學混在一起用（泛型陣列、generic varargs）又會發生什麼？05 章的收官篇，把這對歷史冤家的恩怨講完。

## 技術背景

### 陣列敢協變的底氣：執行期記得自己是誰

```java
Number[] nums = new Integer[3];    // 協變：編譯放行
nums[0] = 3.14;                    // 💥 ArrayStoreException（實測見案例一）
```

門確實開了，但塞錯東西**當場被抓**——因為陣列是 **reified** 的：`Integer[]` 在執行期知道自己裝 Integer，**每次寫入都做型別檢查**。錯誤從編譯期延後到執行期，但至少抓得到、而且抓在案發現場。

為什麼當年這樣設計？前泛型時代需要多型的容器——`Arrays.sort(Object[])` 要能收所有陣列，協變是唯一的路。今天有了泛型＋萬用字元，這個理由已經消失，協變陣列更多是歷史包袱。

### 兩套相反的型別哲學

| | 陣列 | 泛型 |
|---|---|---|
| 型別關係 | **協變**（`Integer[]` 是 `Number[]`） | **不變**（`List<Integer>` 與 `List<Number>` 無關） |
| 型別資訊 | **reified**——執行期記得 | **erased**——執行期失明（[擦除篇](generics-basics-and-type-erasure.md)） |
| 塞錯東西 | 執行期 `ArrayStoreException`，案發現場抓 | 編譯期直接擋死 |

一個延後抓、一個提前擋——各自成立。**災難發生在混用**：

### 為什麼 `new List<String>[10]` 被禁止

推演一次「如果允許」：

```java
List<String>[] lists = new List<String>[10];   // 假設合法（實際：編譯錯誤）
Object[] arr = lists;                          // 陣列協變：合法
arr[0] = List.of(42);                          // 陣列的執行期檢查：擦除後只看到 List——放行！
String s = lists[0].get(0);                    // 💥 CCE 在完全無辜的一行
```

看到問題了嗎：**協變把門打開，擦除把警衛弄瞎**。陣列引以為傲的執行期檢查，遇到泛型（擦除後 `List<String>` 和 `List<Integer>` 都只是 `List`）完全失效——錯的東西住進來沒有任何警報，這就是 **heap pollution**。編譯器的對策乾脆利落：泛型陣列的建立，直接禁止（實測 `error: generic array creation`）。

### varargs：禁令上的一道縫

可變參數的底層**就是陣列**——`void f(List<String>... lists)` 的 `lists` 就是一個 `List<String>[]`。剛剛禁掉的東西從 varargs 的門縫溜回來了，所以編譯器退而求其次：不禁止，但每次都給 **unchecked / heap pollution 警告**。上面的推演可以原封不動地真實上演（實測見案例二）。

`@SafeVarargs` 是這個警告的滅音器——**加上它等於立下軍令狀**：「我保證方法內部不對這個 varargs 陣列做危險的事（不往裡寫、不把它以 `Object[]` 身分外洩）」。JDK 的 `List.of(...)`、`Arrays.asList(...)` 都有這個註解——它們只讀，所以安全。你自己加之前，先確認你守得住同樣的承諾。

### 實務姿勢

- **泛型場景一律用 List，不用陣列**——`List<T>` 編譯期安全、API 完整；`T[]` 從建立那一刻就要跟擦除搏鬥
- 真的需要 `T[]`（如實作容器）：`(T[]) new Object[n]` ＋ `@SuppressWarnings("unchecked")`，並保證這個陣列不外洩；或 `collection.toArray(new T[0])`
- 原始型別的效能場景（`int[]` vs `List<Integer>` 的裝箱差距）是陣列僅存的正當勝場——那是 [02 章 autoboxing](../02-language-core/autoboxing-unboxing.md) 的帳，不是型別系統的

## 實際案例

### 案例一：協變的代價——ArrayStoreException

```java
Number[] nums = new Integer[3];
nums[0] = 3.14;
```

實測：

```
執行期攔截：ArrayStoreException: java.lang.Double
```

編譯器放行、執行期抓人——陣列的「延後檢查」哲學完整呈現。注意例外訊息直接告訴你兇器是 Double。

### 案例二：varargs 的完整案發過程

```java
static void dirty(List<String>... lists) {     // varargs 就是 List<String>[]
    Object[] arr = lists;                      // 陣列協變：合法
    arr[0] = List.of(42);                      // 擦除讓 store check 失明：放行！
    String s = lists[0].get(0);                // 💥
}
```

實測：編譯只給 `unchecked` 警告（**照樣編過**），執行結果：

```
案發：class java.lang.Integer cannot be cast to class java.lang.String
```

三步案發過程正是「協變開門 × 擦除弄瞎警衛」的合體：第一步合法（陣列協變）、第二步放行（store check 看不見 `<String>`）、第三步在**完全無辜的一行**爆炸——這一行連強轉都沒寫（是[擦除篇](generics-basics-and-type-erasure.md)講過的編譯器代寫強轉）。這就是每個 heap pollution 警告背後的劇本。

### 案例三：編譯器的禁令原文

```java
List<String>[] lists = new List<String>[10];
```

實測：

```
error: generic array creation
```

四個字的錯誤，背後是上面整套推演——現在你可以把這個錯誤講成一個故事，而不是「Java 就是不給」。

## 技術優缺點

### List vs 陣列，泛型場景的對決

- **List 全面勝出**：編譯期擋錯（陣列要等執行期）、與泛型體系無縫（PECS、Collectors 全家）、API 豐富
- **陣列僅存的勝場**：原始型別的密集數值運算（`int[]` 零裝箱、記憶體連續）——這是效能帳不是型別帳
- **協變陣列的歷史定位**：前泛型時代的必要之惡，今日的地雷來源——新程式碼裡「把子類陣列賦給父類陣列參考」幾乎都是壞味道

## 小結

- **陣列協變、泛型不變**——同一個危險，陣列選「執行期案發現場抓」（reified＋store check），泛型選「編譯期擋死」（erased）
- **混用就是災難**：協變開門＋擦除弄瞎警衛＝heap pollution——所以 `new List<String>[10]` 直接被禁（實測 generic array creation）
- **varargs 是禁令的縫**：底層就是陣列，只能警告不能禁；實測三步案發，炸在沒寫強轉的無辜行
- **`@SafeVarargs`＝軍令狀**：保證不寫入、不外洩才能加
- 實務：泛型場景用 List；原始型別效能是陣列僅存的正當勝場

**05 章 🔰 軌完軌**，三篇一條線：[擦除](generics-basics-and-type-erasure.md)講「編譯後失明」、[PECS](wildcards-and-pecs.md) 講「不變性與階梯上的界線」、本篇講「另一套哲學（協變陣列）與它相撞的火花」。泛型的地基打完，之後 03 章 Spring 補深遇到的 `ResolvableType`、`ParameterizedTypeReference` 這類框架技巧，根都在這裡。

## 常見面試題

1. 陣列是協變的、泛型是不變的——各是什麼意思？各自怎麼防塞錯東西？（提示：ArrayStoreException vs 編譯錯誤；reified vs erased）
2. 為什麼 Java 禁止 `new List<String>[10]`？（提示：協變＋擦除的推演，三步案發）
3. `@SafeVarargs` 是什麼？什麼條件下才能加？（提示：varargs＝陣列；不寫入、不外洩的軍令狀）

## 延伸閱讀

- Effective Java 第三版 Item 28（List 優於陣列）、Item 32（謹慎組合泛型與 varargs）— 本篇兩大主題的完整論證
- [Java Tutorials: Non-Reifiable Types](https://docs.oracle.com/javase/tutorial/java/generics/nonReifiableVarargsType.html) — heap pollution 與 varargs 的官方說明
