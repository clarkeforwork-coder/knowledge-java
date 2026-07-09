# 經典陷阱：迴圈裡共用同一個物件

## 前言

需求很普通：組一份三筆資料的清單。結果印出來**三筆全部一樣**，而且都是最後一筆的值：

```
錯誤版：2 2 2 （預期 0 1 2）
```

每個 Java 新手都在某個深夜撞過這面牆，資深的則是在 code review 裡一眼認出它。這篇把牆拆給你看——它是[資料型態篇](data-types.md)「變數存的是參考」這句話的第一次實戰驗收。

## 技術背景

### 案發現場

```java
List<Member> list = new ArrayList<>();
Member m = new Member();              // ❌ 只 new 了一次
for (int i = 0; i < 3; i++) {
    m.count = String.valueOf(i);      // 每輪改的是「同一個物件」
    list.add(m);                      // 每輪加進去的是「同一個參考」
}
```

### 為什麼是 2 2 2：畫出來就懂

`list.add(m)` 加進去的不是物件、是**參考**（指向物件的地址）。三次 add，三格都指向同一個本體：

```
list：[ ref ]──┐
      [ ref ]──┼──> Member { count = "2" }   ← 本體只有一個
      [ ref ]──┘        最後一輪的賦值覆蓋了一切
```

實測的鐵證：`list.get(0) == list.get(2)` 為 **true**——第一格和第三格是同一個物件。改它一次，三格「看到的」全變，因為根本是同一份。

### 正確版：生命週期進迴圈

```java
for (int i = 0; i < 3; i++) {
    Member m = new Member();          // ✅ 每輪 new 一個新物件
    m.count = String.valueOf(i);
    list.add(m);
}
```

規則一句話：**要 N 個獨立的東西，就要 new N 次**——把物件的建立搬進迴圈，每格參考各指各的本體。

### 根治：讓這類 bug 不可能發生

錯誤版能成立的前提是「物件可變」——`m.count` 可以事後改。用**不可變設計**直接拆掉前提：

```java
record Member(String count) { }                       // 不可變：沒有可改的欄位

List<Member> list = IntStream.range(0, 3)
        .mapToObj(i -> new Member(String.valueOf(i))) // 每個元素天生是新物件
        .toList();
```

record 沒有 setter——「重用一個物件反覆改值」這條路直接不存在。這也是為什麼不可變設計反覆出現在本 repo：[Set 篇的幽靈元素](../04-collections/set-implementations.md)、[Map 篇的幽靈 key](../04-collections/map-hashmap-basics.md)、[07 章的共享可變狀態](../07-concurrency/synchronized-and-volatile.md)——**可變共享物件是一整族 bug 的共同祖先**，這篇只是家族裡最早遇到的那個。

## 實際案例

實測（三輪迴圈版）：

```
錯誤版：2 2 2 （同一物件？true）
```

修正後為 `0 1 2`。變化只有一行的位置——`new Member()` 從迴圈外搬進迴圈內。

## 技術優缺點

這個陷阱給的教訓比它本身大：

- **集合存參考不存值**——`add` 進去的東西「還連著」外面的變數，改一邊動兩邊
- **重用可變物件是效能上的偽優化**：省一次 new 的代價是正確性風險；短命小物件的配置在 [GC 分代](../01-jvm/gc-basics-and-generations.md)下極便宜（朝生夕死大軍本來就是常態），別為它折腰
- **不可變優先**：值物件用 record，這族 bug 從根消失——比「記得把 new 放進迴圈」更可靠的是「沒有東西可以改」

## 小結

- `list.add(obj)` 加的是**參考**——本體只有一個時，三格全指向它，最後一次賦值蓋掉一切（實測 `2 2 2`＋`get(0) == get(2)` 為 true）
- 修法：**要 N 個獨立物件就 new N 次**——建立搬進迴圈
- 根治：**值物件用 record**——不可變讓「重用改值」這條路不存在
- 這是「可變共享物件」bug 家族的入門款——它的親戚住在 04 章（幽靈元素）和 07 章（race condition）

## 常見面試題

1. 迴圈外 new 一次、迴圈內改值並 add，清單內容會是什麼？為什麼？（提示：參考語意、同一本體）
2. 怎麼證明清單裡的元素是同一個物件？（提示：`==` 比參考——這裡是它少數的正當用途）
3. 除了把 new 搬進迴圈，還有什麼更根本的預防方式？（提示：不可變設計、record）

## 延伸閱讀

- [資料型態：基本型別 vs 參考型別](data-types.md) — 「變數存參考」的完整地基
- Effective Java 第三版 Item 17（最小化可變性）— 不可變設計的完整論證
