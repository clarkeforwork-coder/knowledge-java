# 介面 vs 抽象類別

## 前言

這題的老答案已經過期了。「介面不能有實作、抽象類別可以」——Java 8 的 default method 之後，這條界線不存在；很多人因此得出「抽象類別沒用了」的新結論——也不對。

真正的分界從來不在「能不能有實作」，而在兩件事：**有沒有狀態**、**單繼承 vs 多實作**。這篇把過期答案換掉，給一套 2020 年代還成立的決策準則——外加一個 default method 帶來的新問題：兩個介面的方法撞名了怎麼辦（實測給你看）。

## 技術背景

### 對照表（Java 8+ 的版本）

| | interface | abstract class |
|---|---|---|
| 實例欄位（狀態） | ❌ 只能有 `public static final` 常數 | ✅ 任意欄位 |
| 建構子 | ❌ | ✅（給子類 super 用） |
| 方法實作 | ✅ `default`／`static`（8+）、`private`（9+） | ✅ 任意 |
| 存取修飾 | 方法一律 public（private 輔助除外） | 完整的 protected/package 階層 |
| 一個類別能掛幾個 | **多個** | **一個**（單繼承） |

一句話記差異：**interface 沒有狀態、不佔繼承位；abstract class 有狀態、佔掉唯一的繼承位**。

### default method 是為誰而生的

不是為了「讓介面變抽象類別」——是為了**介面演進**：Java 8 要往 `Collection` 加 `stream()`、往 `List` 加 `sort()`，沒有 default method 的話，全世界每一個實作了這些介面的類別都會編譯爆炸。[06 章](../06-lambda-and-stream/stream-operations.md)天天用的 `forEach`、`removeIf`、[Map 篇](../04-collections/map-hashmap-basics.md)的 `computeIfAbsent`——全是 default method 掛進老介面的。

理解這個出身，就懂它的使用倫理：**default 適合「由其他抽象方法推導出來的便利方法」**，不適合當抽象類別用（你很快會撞到「沒有狀態」的牆——實測見案例二）。

### 決策準則

- **定義「能力」→ interface**：`Comparable`（可比較）、`AutoCloseable`（可關閉）——形容詞式的 can-do，且一個類別常常同時具備多種能力（多實作正是為此）
- **提供「骨架」→ abstract class**：共享狀態＋固定流程、留缺口給子類——模板方法模式（實測見案例二）
- **兩者不是對手，是搭檔**：JDK 的標準做法是 `List`（介面定契約）＋ `AbstractList`（骨架省力氣）雙層——API 依賴介面、實作者繼承骨架，[04 章](../04-collections/collections-overview.md)整個集合框架都是這個結構

### 新問題：diamond 衝突

default method 讓 Java 第一次有了「多重繼承實作」的味道，也帶來了它的經典問題：

```java
interface Swimmer { default String move() { return "游泳"; } }
interface Runner  { default String move() { return "跑步"; } }
class Duck implements Swimmer, Runner { }     // 兩個 default 撞名
```

實測編譯錯誤：`types Swimmer and Runner are incompatible`——**Java 不猜，逼你自己決定**：

```java
class Duck implements Swimmer, Runner {
    @Override public String move() {
        return Swimmer.super.move() + "＋" + Runner.super.move();   // X.super 指名
    }
}
```

實測輸出：`Duck.move()：游泳＋跑步`。`介面名.super.方法()` 這個語法只在這個場景出現——看到它就知道發生過 diamond。

## 實際案例

### 案例一：diamond 衝突與 X.super（實測見上）

編譯器的立場值得體會：C++ 的多重繼承讓這個問題自動化解（然後埋 bug），Java 選擇**當場報錯、強制顯式**——跟[契約篇](../04-collections/equals-hashcode-contract.md)「繼承加欄位無解就禁止」是同一種設計性格。

### 案例二：模板方法——abstract class 的不可替代性

```java
abstract class ReportJob {
    private int processed = 0;                 // ← 實例狀態：interface 給不了

    public final void run() {                  // ← 模板方法：流程鎖死（final）
        System.out.println("1. 連線");
        while (hasNext()) { processRow(); processed++; }
        System.out.println("3. 收尾（共處理 " + processed + " 筆）");
    }
    protected abstract boolean hasNext();      // ← 缺口留給子類
    protected abstract void processRow();
}
```

實測輸出：

```
1. 連線
2. 處理第 1 筆 / 第 2 筆 / 第 3 筆
3. 收尾（共處理 3 筆）
```

三個 interface 做不到的點全在這段裡：`processed` 欄位（狀態）、`final run()`（流程不許子類亂改——interface 的 default 不能 final）、`protected` 缺口（interface 的方法藏不住）。這就是 abstract class 在 default method 時代依然活著的理由。

## 繼承的代價：能組合就別繼承

前面把 abstract class 的模板方法講得很正面——但那是**繼承的少數正當場景**。在跳進繼承之前，得先知道它為什麼危險。OOP 最重要的設計準則之一：**組合優於繼承（favor composition over inheritance）**。

### 為什麼繼承危險：它破壞封裝

繼承讓子類依賴父類的**實作細節**，不只是介面。經典的翻車現場——想給 HashSet 加一個「累計加入過幾個元素」的計數器：

```java
class CountingSet<E> extends HashSet<E> {
    int addCount = 0;
    @Override public boolean add(E e) { addCount++; return super.add(e); }
    @Override public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();              // 看起來合理：加了 c.size() 個
        return super.addAll(c);
    }
}
```

實測（加 3 個元素）：

```
繼承版：加了 3 個，addCount = 6（期望 3）
```

**翻倍了。** 原因藏在 HashSet 的**內部實作**：`HashSet.addAll()` 內部是**逐一呼叫 `add()`** 的。於是 `addAll(3 個)` 先 `addCount += 3`，再呼叫 `super.addAll()`，後者又觸發 3 次被你覆寫的 `add()`——每次再 `addCount++`。**3 + 3 = 6**。

致命的是：這個 bug 取決於「HashSet 的 addAll 內部有沒有呼叫 add」——這是**父類的實作細節、沒寫在任何契約裡**。哪天 JDK 改了 HashSet 的內部實作，你的子類就會用另一種方式壞掉。這叫 **fragile base class problem（脆弱基礎類別）**：**父類的實作是子類的隱形依賴，父類一改，子類無聲崩壞**。

### 組合：把「是一個」換成「有一個」

同樣的需求，用組合——**包一個 HashSet，只轉發你要的方法**：

```java
class CountingSet<E> {
    private final Set<E> set = new HashSet<>();   // 有一個 Set（has-a），不是「是一個」Set
    int addCount = 0;
    public boolean add(E e) { addCount++; return set.add(e); }
    public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return set.addAll(c);                     // 轉發——set 內部怎麼實作，與我無關
    }
}
```

實測：

```
組合版：加了 3 個，addCount = 3（期望 3）
```

正確。差別的本質：組合只依賴 HashSet 的**公開契約**（`add`/`addAll` 做什麼），**不管它內部怎麼做**——`set.addAll` 內部呼不呼叫 `set.add` 都影響不到你的計數器，因為那個 `add` 不是你覆寫的版本。封裝沒被打破。

### 判準：真的是 is-a 才繼承

- **繼承問「是不是」**：`CountingSet` 真的**是一個** HashSet 嗎？——不是，它只是**想用** HashSet 的功能。這種「想重用」偽裝成「is-a」的，正是繼承誤用的重災區
- **只有真正的 is-a、且父類為繼承而設計**（有文件說明可覆寫哪些方法、`protected` 缺口明確——就是模板方法那種）才用繼承
- 其餘一律組合——[Set/Map 篇](../04-collections/collections-overview.md)的裝飾器、[03 章的 proxy](../03-spring-to-spring-boot/deep-transactional-self-invocation.md)（Spring 用組合包裝你的 bean）、[record 的搭配](record-and-immutability.md)都是組合

這也回頭解釋了 abstract class 的定位：模板方法之所以是繼承的**正當**場景，正因為它是「**為繼承而設計**」的——父類用 `final` 鎖死流程、`protected` 明確標出可覆寫的缺口，子類不會誤踩實作細節。**沒有這樣設計的類別，別繼承它**。

## 技術優缺點

### interface 的強項與邊界

- ✅ 多實作、零繼承負擔——「能力」的正確載體；API 參數與回傳的首選型別（[總覽篇](../04-collections/collections-overview.md)的「宣告用介面」紀律）
- ❌ 無狀態、無建構子、方法全 public——想共享欄位或控制流程就到頂了

### abstract class 的強項與邊界

- ✅ 狀態＋模板方法＋完整存取控制——「半成品骨架」的正確載體
- ❌ 佔掉唯一繼承位——強迫實作者放棄其他繼承；[契約篇](../04-collections/equals-hashcode-contract.md)講過的繼承地雷全套適用

**實務排序**：先想 interface（門檻低、彈性大）；確定需要共享狀態或鎖流程，再上 abstract class；大型 API 學 JDK 的雙層（interface ＋ Abstract 骨架）。

## 小結

- 老答案過期：分界不在「能不能有實作」，在**狀態**與**繼承位**——interface 無狀態不佔位、abstract class 有狀態佔一位
- default method 的出身是**介面演進**（stream/forEach 塞進老介面）——別拿它當抽象類別寫
- **diamond 衝突不猜**：撞名直接編譯錯誤（實測），`X.super.m()` 顯式指名
- abstract class 的不可替代三件套：**實例狀態、final 模板方法、protected 缺口**（實測）
- **組合優於繼承**：繼承破壞封裝、依賴父類實作細節（實測 CountingSet 計數翻倍 6≠3）——只有真正 is-a 且父類「為繼承而設計」才繼承，其餘用組合
- 準則：能力 → interface；骨架 → abstract class（為繼承而設計）；重用 → 組合；大型 API → 雙層搭檔（List＋AbstractList）

## 常見面試題

1. Java 8 之後介面能有實作了，抽象類別還有什麼存在價值？（提示：狀態、final 流程、protected——模板方法實測）
2. 為什麼說「組合優於繼承」？舉例。（提示：破壞封裝、fragile base class；CountingSet 計數翻倍實測）
3. 兩個介面的 default method 撞名會怎樣？（提示：編譯錯誤原文、X.super 語法）
4. default method 當初為什麼被加進 Java？（提示：介面演進；stream 塞進 Collection 的難題）

## 延伸閱讀

- [Java Tutorials: Default Methods](https://docs.oracle.com/javase/tutorial/java/IandI/defaultmethods.html) — 演進動機與衝突解析規則的官方版
- Effective Java 第三版 Item 20（介面優於抽象類別）、Item 64（用介面引用物件）— 決策準則的完整論證
