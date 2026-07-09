# enum 的正確姿勢

## 前言

多數人的 enum 停在第一課：「一組有名字的常數」。然後代價在別處付：保單類型的費率計算散落在五個 `switch` 裡，新增一種類型要全域搜尋；資料庫裡存了 `ordinal()`，某天有人在 enum 中間插了一個值——**整張表的歷史資料默默對錯位**。

Java 的 enum 其實是**完整的類別**：能帶欄位、帶行為、甚至每個值有自己的實作。這篇用一個保單類型的例子把 enum 用滿，並把兩顆企業級地雷（ordinal 入庫、valueOf 誤用）拆給你看。

## 技術背景

### enum 的真身：一組 JVM 保證的單例

```java
enum PolicyType { TERM_LIFE, WHOLE_LIFE, ACCIDENT }
```

編譯後它是一個繼承 `java.lang.Enum` 的類別，每個值是**一個唯一實例**（JVM 保證、序列化也不破功）。三個直接紅利：

- **`==` 合法且正確**——單例讓參考比較就是值比較（[資料型態篇](data-types.md)「參考型別別用 ==」的唯一豁免）
- **switch 原生支援**、`values()`／`valueOf()` 自動生成
- **天生單例**——需要單例模式時，單值 enum 是最強實作（Effective Java 的著名建議：免疫反射與序列化攻擊）

### 帶欄位：常數的檔案袋

```java
enum PolicyType {
    TERM_LIFE("T", "定期壽險"), WHOLE_LIFE("W", "終身壽險"), ACCIDENT("A", "意外險");

    private final String code, label;                     // final：不可變（static/final 篇的紀律）
    PolicyType(String code, String label) { ... }         // 建構子天生 private
}
```

代碼、顯示名、費率——跟這個列舉值綁定的資料全部收進來，取代散落各處的對照 Map。

### 帶行為：把 switch 收編進 enum

```java
enum PolicyType {
    TERM_LIFE("T", "定期壽險")  { double premium(double base) { return base * 1.0; } },
    WHOLE_LIFE("W", "終身壽險") { double premium(double base) { return base * 2.5; } },
    ACCIDENT("A", "意外險")     { double premium(double base) { return base * 0.6; } };

    abstract double premium(double base);    // 每個值必須提供自己的實作
}
```

這叫 **constant-specific method**——策略模式住進 enum。對比「switch 散落五處」：新增一種保單類型時，**編譯器逼你補上 premium 實作**（abstract 沒實作就不過），而 switch 版是靜靜漏掉一個 case。行為跟著資料走，[04 章](../04-collections/sorting-comparable-comparator.md)「意圖寫在定義旁」的同一哲學。

### 兩顆企業級地雷

**地雷一：`ordinal()` 入庫**。ordinal 是「宣告順序的流水號」（實測 0、1、2）——有人在中間插一個新值，**所有後面的 ordinal 全部位移**，資料庫的歷史資料瞬間指鹿為馬，而且無聲無息。規矩：**持久化用 `name()` 或自訂 code，永遠不用 ordinal**（同理 `EnumMap`/`EnumSet` 內部用 ordinal 是安全的——它們活在記憶體裡跟 class 同生死）。

**地雷二：`valueOf()` 收的是「名字」**。`valueOf("W")` 不會找 code 是 W 的值——它找**名字**叫 W 的常數，找不到直接炸（實測 `No enum constant`）。從外部輸入（DB、API 參數）還原 enum，寫自己的 `fromCode`（實測），順便決定「未知代碼」的策略（炸、回預設、回 Optional）。

### 專屬集合：EnumSet 與 EnumMap

```java
Set<PolicyType> active = EnumSet.of(TERM_LIFE, ACCIDENT);   // 內部是 bit vector
Map<PolicyType, Long> stats = new EnumMap<>(PolicyType.class);  // 內部是陣列
```

key 是 enum 時**永遠優先用它們**——比 HashSet/HashMap 快（不用算 hash）、省（無 entry 物件）、遍歷順序穩定（宣告序）。[04 章的選型表](../04-collections/collections-overview.md)之外的特化選項。

## 實際案例

### 帶行為的 enum 全家福（實測）

```
定期壽險(T)：ordinal=0，保費=1000
終身壽險(W)：ordinal=1，保費=2500
意外險(A)：ordinal=2，保費=600
fromCode("W")：終身壽險
valueOf("W")：No enum constant PolicyType.W    ← 名字不是 code，當場炸
```

一份輸出覆蓋四個知識點：constant-specific 的費率各自生效、ordinal 的真面目（流水號——想像在 TERM_LIFE 後插入新值，WHOLE_LIFE 的 ordinal 就從 1 變 2，DB 裡存 1 的舊資料全部變意外險）、`fromCode` 的安全還原、`valueOf` 的炸點。

## 技術優缺點

### enum 買到的

- **型別安全的有限集合**：比 String/int 常數強一個次元——編譯器擋非法值、IDE 能列全、switch 能檢查完整性
- **資料＋行為的內聚**：constant-specific method 讓「新增一個值」變成編譯器監督的 checklist
- 單例、序列化、執行緒安全全部白送（每個值都是不可變單例——[不可變設計](pitfall-shared-reference-in-loop.md)的官方示範）

### 邊界與紀律

- **ordinal 不入庫、valueOf 不收外部輸入**——兩顆地雷的規矩
- enum 是**編譯期固定**的集合——值需要執行期增減（後台可設定的類別清單）就不是 enum 的場景，該用資料表
- constant-specific 實作變長時是壞味道——行為複雜到一定程度，該升級成正式的策略介面（enum 持有 lambda 或委派）

## 小結

- enum 是**完整的類別**：欄位、方法、建構子（天生 private）、每值單例（`==` 合法的唯一豁免區）
- **constant-specific method** 把散落的 switch 收編——新增值時編譯器逼你補實作
- 兩顆地雷實測：**ordinal 不入庫**（插值即位移）、**valueOf 收名字不收 code**（自寫 fromCode）
- key 是 enum 就用 **EnumSet／EnumMap**——bit vector 與陣列的特化速度
- 需要單例？單值 enum 是最強實作

## 常見面試題

1. enum 可以有建構子和方法嗎？constant-specific method 是什麼？（提示：完整類別；策略模式收編 switch）
2. 為什麼不該把 `ordinal()` 存進資料庫？（提示：宣告順序流水號；插值位移的災難）
3. 為什麼說 enum 是實作單例的最佳方式？（提示：JVM 保證唯一、序列化與反射免疫）

## 延伸閱讀

- [Java Tutorials: Enum Types](https://docs.oracle.com/javase/tutorial/java/javaOO/enum.html) — 官方教學
- Effective Java 第三版 Item 34–38（enum 全章）— constant-specific、EnumMap、ordinal 警告的原始出處
