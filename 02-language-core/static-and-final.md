# static 與 final

## 前言

兩個從第一天就在用的修飾詞，各藏著一個高頻誤區：

```java
final List<String> list = new ArrayList<>();
list.add("A");          // 咦，final 的東西怎麼還能改？（完全合法，實測 [A, B]）
```

以及「static 方法可以覆寫嗎」——很多人以為可以，因為**編譯過了、也真的執行了子類版本**……直到用父類參考呼叫的那天。這篇把兩個修飾詞的精確語意講透：static 管**歸屬**、final 管**指派**——各自的坑都從誤解這一句話開始。

## 技術背景

### static：屬於類別，不屬於實例

| 用在 | 意思 | 要注意 |
|---|---|---|
| 欄位 | 全類別共享一份，存 [Method Area](../01-jvm/memory-stack-and-heap.md) | **可變的 static ＝ 全域變數**：測試互相污染、[07 章](../07-concurrency/synchronized-and-volatile.md)的共享可變狀態災難 |
| 方法 | 不依賴實例——沒有 `this` | **不能覆寫，只能遮蔽**（hiding，實測見案例一） |
| 區塊 | 類別初始化時跑一次（`<clinit>`） | 載入時機見 [ClassLoader 篇](../01-jvm/deep-classloader.md)的 Initialization |
| 巢狀類 | 不持有外部實例的參考 | 內部類優先加 static——不加會隱式抓著外部實例（洩漏源） |

**static 方法沒有多型**，值得單獨劃重點：多型靠「執行期看實際物件挑方法」，而 static 呼叫在**編譯期就按宣告型別綁死**。子類寫同名 static 方法只是「遮蔽」——同一個物件，用父類參考呼叫得到父類版（實測）。所以 static 方法呼叫**永遠寫 `類別名.方法()`**，寫 `物件.staticMethod()` 是在給自己埋誤導。

### final：三個位置，三種「不能再」

| 用在 | 意思 | 例子 |
|---|---|---|
| 變數 | **不能再指派**（reassign） | 常數、[lambda 捕獲](../06-lambda-and-stream/functional-interface-and-lambda.md)的 effectively final |
| 方法 | 不能被子類覆寫 | [模板方法](interface-vs-abstract-class.md)的 `final run()`——流程鎖死 |
| 類別 | 不能被繼承 | `String`、所有 record——[契約篇](../04-collections/equals-hashcode-contract.md)「斷繼承的路」 |

### 最大誤區：final ≠ 不可變

```java
final List<String> list = new ArrayList<>();
list.add("A");                    // ✅ 合法——改的是「內容」
list = new ArrayList<>();         // ❌ 編譯錯誤——擋的是「指派」
```

實測兩者：add 之後 `[A, B]`、重新指派吃到 `cannot assign a value to final variable`。用[資料型態篇](data-types.md)的語言精確描述：**final 鎖的是變數格子裡的參考，不是參考指向的物件**。真正的不可變要物件自己配合——欄位私有且 final、不外洩可變內部（record、`List.copyOf`）——[不可變設計](pitfall-shared-reference-in-loop.md)是物件的性格，final 只是變數的紀律。

兩個 final 的加值知識：`static final` 常數是兩個修飾詞的正當合體（一份、不許換）；final **欄位**有 [JMM](../07-concurrency/deep-jmm-happens-before.md) 的特殊保證——建構完成後對所有 thread 安全可見，這是不可變物件天生執行緒安全的理論根基。

## 實際案例

### 案例一：static 的「假覆寫」

```java
Parent p = new Child();
p.who();          // instance 方法
Parent.name();    // static 方法——Child 也定義了同名版本
```

實測：

```
instance 方法（多型）  ：Child 的 instance 方法     ← 執行期看實際物件
static 方法（hiding）  ：Parent 的 static 方法       ← 編譯期看宣告型別！
static 經子類呼叫      ：Child 的 static 方法
```

同一對類別，instance 方法走多型、static 方法走遮蔽——**依賴 static「覆寫」的程式，換個參考型別行為就變**，而且沒有任何警告。順帶一提：`@Override` 標不上 static 方法（編譯器擋），這本身就是「它不是覆寫」的官方表態。

### 案例二：final 的邊界（實測見上）

`add` 合法、reassign 編譯錯誤——一組對照把「final 管指派不管內容」釘死。實務推論：宣告 `final List` **不會**讓集合安全，要唯讀給 `List.copyOf(...)` 或 [`Stream.toList()`](../06-lambda-and-stream/collectors-in-action.md)（實測過的不可變 List）。

## 技術優缺點

### static 的使用準則

- ✅ 正當場景：純函數工具（`Math.max`）、常數（`static final`）、工廠方法（`List.of`）、[static 巢狀類](../04-collections/deep-hashmap-internals.md)（HashMap.Node 就是）
- ❌ 危險場景：**可變的 static 欄位**——全域狀態讓測試無法隔離、並發必須設防；「用 static 省得 new」是壞理由，該問的是「這東西真的全域唯一嗎」

### final 的使用哲學

- **區域變數與參數**：現代共識是「盡量 effectively final」——不必到處寫 final 關鍵字，但一個變數被反覆重新指派是壞味道
- **欄位**：能 final 就 final——不可變的地基，還送 JMM 保證
- **方法／類別**：預設開放、刻意封閉——確定不該被繼承時果斷 final（[契約篇](../04-collections/equals-hashcode-contract.md)的教訓），但別為了「防禦」全面 final（Spring 的 [CGLIB proxy](../03-spring-to-spring-boot/deep-transactional-self-invocation.md) 就蓋不掉 final 方法——框架相容性是真實代價）

## 小結

- **static 管歸屬**（class 級、一份、無 this）：可變 static ＝ 全域變數災難源；**static 方法沒有多型**——遮蔽不是覆寫（實測父類參考得父類版），呼叫永遠寫類別名
- **final 管指派**：變數不能 reassign、方法不能覆寫、類別不能繼承——**它不等於不可變**（實測 final List 照樣 add）
- 真不可變靠物件自己（record、copyOf）；final 欄位另有 JMM 安全發布保證
- 準則：static 問「真的全域唯一嗎」、final 欄位能加就加、final 類別要想框架代價（CGLIB）

## 常見面試題

1. static 方法可以被覆寫嗎？（提示：hiding vs overriding；父類參考的實測結果；@Override 標不上）
2. `final List` 可以 add 元素嗎？final 到底保證什麼？（提示：參考 vs 內容；真不可變怎麼做）
3. 可變的 static 欄位有什麼風險？（提示：全域狀態、測試污染、共享可變的並發問題）

## 延伸閱讀

- [JLS §8.4.8.2: Hiding (by Class Methods)](https://docs.oracle.com/javase/specs/jls/se17/html/jls-8.html#jls-8.4.8.2) — 遮蔽語意的官方定義
- Effective Java 第三版 Item 17（最小化可變性）— final 與不可變設計的完整關係
