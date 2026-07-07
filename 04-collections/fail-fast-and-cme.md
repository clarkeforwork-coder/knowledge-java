# fail-fast 與 ConcurrentModificationException

## 前言

需求很日常：「把清單裡過期的項目刪掉」。程式碼也很直覺——for-each 走一遍，過期的 `remove` 掉。然後：

```
Exception in thread "main" java.util.ConcurrentModificationException
```

第一反應通常是困惑：**我又沒開多執行緒，哪來的 Concurrent？** 這個例外的名字大概是 JDK 裡最成功的誤導之一。這篇講清楚它到底在防什麼、為什麼單執行緒照樣炸，以及——比炸掉更值得怕的——那種**不炸但答案錯**的寫法。

## 技術背景

先破除名字帶來的誤解：`ConcurrentModificationException`（CME）的 concurrent 指的是「**遍歷與修改同時發生**」，不是多執行緒。單執行緒裡一邊 for-each 一邊 `remove`，就是最標準的觸發方式。

### fail-fast 機制：modCount 對帳

`ArrayList`、`HashMap` 這些集合內部有一個計數器 `modCount`——每次**結構性修改**（增、刪，會改變大小的操作）就加一。iterator 建立時把當下的值抄一份（`expectedModCount`），之後**每次 `next()` 都對帳**：

```
list.iterator()          → expectedModCount = modCount = 5
list.remove(x)           → modCount = 6        ← 繞過 iterator 改了結構
iterator.next()          → 對帳：6 ≠ 5 → 拋 ConcurrentModificationException
```

而 **for-each 就是 iterator 的語法糖**——編譯器把它展開成 `iterator()`/`hasNext()`/`next()`，所以「for-each 裡呼叫 `list.remove()`」＝「繞過 iterator 修改結構」，下一次 `next()` 對帳就炸。

### 為什麼要 fail-fast：早死早超生

在被修改過的結構上繼續遍歷，會發生什麼？元素被跳過、被重複走訪、索引錯位——**產生錯的結果而不自知**。fail-fast 的設計哲學是：與其讓你拿著錯的結果走下去，不如當場死給你看。

但注意 Javadoc 的原文警告：fail-fast 是 **best-effort**——它盡力偵測、不保證偵測（尤其多執行緒下可能不炸而是資料悄悄壞掉）。所以它是「找 bug 的輔助」，**不是可以依賴的正確性機制**；真正的多執行緒安全要靠並發容器（07 章）。

### 遍歷中修改的正確姿勢

| 寫法 | 說明 |
|---|---|
| `list.removeIf(n -> ...)` | **首選**：條件刪除一行完成，內部自己處理一致性 |
| `iterator.remove()` | 遍歷中唯一合法的刪除——它會同步 `expectedModCount`，帳永遠對得上 |
| 先收集、再批次處理 | 遍歷時只把目標收進另一個 list，結束後 `removeAll`——邏輯複雜時最清晰 |
| Stream `filter` 產新集合 | 「留下要的」而非「刪掉不要的」，06 章的主場 |

Map 的對應款：遍歷中要刪 entry，用 `entrySet().iterator()` 的 `remove()`，或一樣用 `values().removeIf(...)`。

## 實際案例

### 案例一：標準炸法

```java
List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4));
for (Integer n : list) {
    if (n % 2 == 0) list.remove(n);   // ❌ 繞過 iterator 修改
}
```

實測：單執行緒，照炸 `ConcurrentModificationException`——for-each 展開後的下一次 `next()` 對帳失敗。

### 案例二：不炸的更可怕——index 迴圈邊刪

有人為了「躲開」CME 改用 index 迴圈。它確實不炸——**但答案是錯的**：

```java
List<Integer> list = new ArrayList<>(List.of(1, 2, 2, 3));
for (int i = 0; i < list.size(); i++) {
    if (list.get(i) == 2) list.remove(i);   // 想刪掉所有的 2
}
```

實測輸出：

```
想刪所有 2，結果 = [1, 2, 3]   ← 一個 2 活下來了
```

刪掉 index 1 的 2 之後，後面的元素**整批前移**——第二個 2 滑進了剛檢查完的位置 1，而 `i++` 已經走到 2。**相鄰的重複元素必漏刪**。這種 bug 不炸、不報錯、測試資料沒有相鄰重複就測不出來——比 CME 危險一個量級。CME 至少誠實。

### 案例三：正確姿勢實測

```java
list.removeIf(n -> n == 2);                    // ✅ 首選

Iterator<Integer> it = list.iterator();        // ✅ 傳統但合法
while (it.hasNext()) {
    if (it.next() == 2) it.remove();
}
```

實測兩種寫法對 `[1, 2, 2, 3]` 都給出正確的 `[1, 3]`——相鄰的 2 一個不漏。

## 技術優缺點

### fail-fast 的取捨

- ✅ **把時間炸彈變成當場爆炸**：結構壞了立刻知道，stack trace 直指現場——比拿著錯誤結果繼續跑便宜太多
- ✅ 成本極低：一個 int 對帳
- ❌ **best-effort 不是保證**：多執行緒下可能偵測不到，換來的是安靜的資料損壞——它是除錯輔助，不是安全機制
- ❌ 名字誤導：讓人以為加了鎖或換單執行緒就沒事

### 對照組：weakly consistent（07 章預告）

並發容器（`ConcurrentHashMap`、`CopyOnWriteArrayList`）的 iterator 走另一條路：**不對帳、不炸**，容忍遍歷期間的修改，代價是你看到的可能是「稍舊的快照」。fail-fast 要正確性寧可死，weakly consistent 要可用性容忍舊——沒有誰對，是場景不同，細節見 [並發集合：ConcurrentHashMap](../07-concurrency/concurrent-collections.md)。

## 小結

- CME 的 concurrent ≠ 多執行緒——指「**遍歷與修改並行**」，單執行緒 for-each 裡 remove 就是標準觸發
- 機制是 **modCount 對帳**：iterator 抄一份期望值，每次 `next()` 核對，繞過 iterator 的修改讓帳對不上
- **不炸的寫法更危險**：index 迴圈邊刪，相鄰重複元素必漏刪（實測 `[1,2,2,3]` 刪 2 剩 `[1,2,3]`），無聲無息
- 正確姿勢按序：`removeIf` → `iterator.remove()` → 先收集再批次 → Stream filter 產新集合
- fail-fast 是 best-effort 的除錯輔助，不是執行緒安全機制——真並發用並發容器

到這裡，04 章的 🔰 軌完整了：從[總覽的地圖](collections-overview.md)出發，List、Set、Map 各就各位，equals/hashCode 和排序兩大契約補完，最後以遍歷修改的安全守則收尾。選修軌還有一篇 🔬 [HashMap 內部原理](deep-hashmap-internals.md) 給想鑽桶子裡看的人；而「真正的」並發修改問題——多執行緒下的集合——07 並發章見。

## 常見面試題

1. for-each 裡呼叫 `list.remove()` 為什麼會拋 CME？機制是什麼？（提示：for-each 是 iterator 語法糖、modCount 對帳）
2. 單執行緒會發生 ConcurrentModificationException 嗎？（提示：會——名字裡的 concurrent 指什麼）
3. 遍歷中刪除元素的正確做法有哪些？index 迴圈邊刪有什麼問題？（提示：removeIf / iterator.remove；相鄰重複漏刪）

## 延伸閱讀

- [ArrayList（Javadoc）的 fail-fast 段落](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ArrayList.html) — 「best-effort」警告的原文出處
- [Iterator.remove（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Iterator.html#remove()) — 遍歷中唯一合法刪除的官方契約
