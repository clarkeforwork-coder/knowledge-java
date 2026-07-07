# Map：HashMap 基礎與正確使用

## 前言

HashMap 大概是 Java 裡使用頻率最高的資料結構——設定檔、快取、分組統計、DTO 轉換，到處都是 `get`/`put`。也正因為「會用」的門檻太低，大多數人停在會用：容量從來不給、key 什麼型別都敢放、分組統計還在寫 `containsKey` → `get` → `put` 三連擊。

[Set 篇](set-implementations.md) 說過 HashSet 內部就是一個 HashMap——欠的「hash 分桶到底怎麼運作」這筆帳，這篇來還；順便把 HashMap 用得對、用得漂亮的幾條規矩一次講完。

## 技術背景

先把「魔法」拆掉：查 Map 比查 List 快，不是魔法，是**拿 `hashCode()` 換的**——你的 key 用 hash 值預先「報好座位」，查的時候直接對號入座。

### 分桶機制：put 和 get 走同一條路

HashMap 底層是一個陣列，每一格叫一個**桶（bucket）**：

```
put("台北", v)：
  "台北".hashCode() ──> 換算成桶編號（hash 對容量取位）──> 放進 桶[i]

buckets: [0]      [1]        [2]      [3]       ...
          │        │          │
          ▼        ▼          ▼
        (台中,v)  (台北,v)   (高雄,v)
                   │
                   ▼
                  (新竹,v)   ← 碰撞：不同 key 算到同一桶，桶內串成鏈
```

- **`get` 走同一條路**：算 hash → 跳到桶 → 桶裡用 `equals()` 逐一比對。這就是平均 O(1) 的來源，也再次說明 key 的 `hashCode`/`equals` 必須成對正確（見 [equals 與 hashCode 契約](equals-hashcode-contract.md)）
- **碰撞（collision）**：不同 key 落在同一桶，桶內串成鏈逐一比對——碰撞越多越接近 O(n)。好的 hashCode 讓 key 均勻散開
- **負載因子與擴容**：預設容量 16、負載因子 0.75——元素數超過「容量 × 0.75」就**擴容一倍並全員重新分桶（rehash）**。至於 hash 怎麼擾動、鏈太長變紅黑樹（樹化）這些內部細節，見 🔬 [HashMap 內部原理](deep-hashmap-internals.md)

### 容量：知道大小就先說

裝一百萬筆卻從預設 16 開始，一路要擴容 rehash 十幾次。已知規模就預給容量：

```java
Map<String, User> users = new HashMap<>(1 << 21);   // 要裝 100 萬筆：給 > N/0.75 的容量
```

實際差多少？見下方實測——先說結論：**有感但不是數量級**，它省的是擴容瞬間的搬遷尖峰，不是每次操作的速度。

### key 的三條規矩

1. **不可變**：[Set 篇](set-implementations.md) 的幽靈元素在 Map 這裡叫幽靈 key——put 之後改了 key 的欄位，hash 變了人還在舊桶，`get` 再也找不到（見下方實測）。key 用 String、Integer、record 這類不可變型別最安全
2. **`equals`/`hashCode` 成對正確**：自訂型別當 key，兩個方法都要覆寫，否則「同一個 key」存取不到同一筆
3. **null 要知道規則**：HashMap 允許一個 null key 和多個 null value；但 `ConcurrentHashMap` **兩者都禁**——依賴 null 的程式換成並發版就炸，乾脆別依賴

### 現代 API：告別三連擊

```java
// ❌ 三連擊：查一次、拿一次、放一次
if (!map.containsKey(city)) {
    map.put(city, new ArrayList<>());
}
map.get(city).add(order);

// ✅ computeIfAbsent：沒有就造一個，然後直接用
map.computeIfAbsent(city, k -> new ArrayList<>()).add(order);
```

| 需求 | 用這個 |
|---|---|
| 拿不到給預設值 | `getOrDefault(key, def)` |
| 沒有才放 | `putIfAbsent(key, v)` |
| 沒有就造一個再用（分組收集） | `computeIfAbsent(key, k -> new ...)` |
| 有就合併、沒有就放（計數、加總） | `merge(key, 1, Integer::sum)` |

## 實際案例

### 案例一：分組與計數的慣用寫法

```java
List<String> orders = List.of("台北", "台中", "台北", "高雄", "台北", "台中");

Map<String, Integer> count = new HashMap<>();
for (String city : orders) count.merge(city, 1, Integer::sum);      // 計數一行

Map<Character, List<String>> byFirst = new HashMap<>();
for (String city : orders)
    byFirst.computeIfAbsent(city.charAt(0), k -> new ArrayList<>()).add(city);  // 分組一行
```

實測輸出：

```
計數：{台中=2, 台北=3, 高雄=1}
分組：{台=[台北, 台中, 台北, 台北, 台中], 高=[高雄]}
```

這兩個模式（`merge` 計數、`computeIfAbsent` 分組）值得練到反射——它們同時也是 Stream `Collectors.groupingBy`/`counting` 的手工版，06 章會再相遇。

### 案例二：預給容量到底差多少

▶ 可執行範例：[MapResizeBenchmark.java](examples/MapResizeBenchmark.java)

一百萬筆 String key，預設容量 vs 預給 `1 << 21`，JIT 預熱後實測：

```
預設容量    ：32 ms
預給足夠容量：18 ms
```

**快了近一倍，但不是數量級**——跟 [總覽篇](collections-overview.md) contains 那種 650 倍是兩回事。誠實的結論：預給容量是「知道就順手做」的好習慣，大 Map 尤其值得；但它不會拯救效能災難，真正的數量級問題永遠先查「是不是選錯容器、是不是 O(n²)」。

### 案例三：幽靈 key 實錄

```java
Map<List<String>, String> risky = new HashMap<>();
List<String> key = new ArrayList<>(List.of("A"));
risky.put(key, "資料");
key.add("B");                                    // put 之後改了 key
```

實測輸出：

```
改過 key 後 get：null       ← 用同一個參考去拿，拿不到
map 裡明明還有：{[A, B]=資料} ← 資料還在，只是永遠找不到了
```

`List` 的 hashCode 跟著內容變——改完內容，它還躺在舊 hash 的桶裡，用新 hash 去找當然撲空。**用可變集合當 key 是自埋地雷**；要複合 key，用 record 包起來（欄位也保持不可變）。

## 技術優缺點

### HashMap 的優勢

- **平均 O(1) 的存取**：拿 hashCode 換座位號，規模再大查找都不變慢
- **API 完整**：`merge`/`computeIfAbsent` 這代 API 讓常見模式一行完成，還順帶消掉「查與放之間」的邏輯縫隙

### 代價與前提

- **O(1) 是有條件的**：好的 hashCode（均勻散開）＋足夠容量（碰撞少）。條件壞掉就滑向 O(n)
- **記憶體開銷**：每筆資料揹一個 entry 物件（hash、key、value、next 四個欄位），大量小資料時可觀
- **無序**：遍歷順序不保證且會變；要順序找 LinkedHashMap/TreeMap（[總覽篇](collections-overview.md) 的對稱表）
- **不是 thread-safe**：多執行緒同時寫會遺失更新甚至結構損壞——並發場景用 `ConcurrentHashMap`，07 章詳談

## 小結

- 查得快不是魔法：**hashCode 分桶對號入座**，get/put 走同一條路，桶內靠 equals 確認
- 預設容量 16、負載因子 0.75，超過就擴容 rehash——已知規模預給容量（實測省近一半，但非數量級）
- key 三規矩：**不可變**（幽靈 key 實測：get 變 null）、equals/hashCode 成對、別依賴 null（ConcurrentHashMap 不容）
- 三連擊退役：計數用 `merge`、分組用 `computeIfAbsent`、預設值用 `getOrDefault`
- 無序且非 thread-safe——要序有 Linked/Tree，要並發等 07 章

hash 的世界到這裡是「會用且用對」；桶裡的完整風景——hash 擾動、擴容搬遷、鏈變紅黑樹——見 🔬 [HashMap 內部原理](deep-hashmap-internals.md)。而撐起這一切的地基「equals 與 hashCode 到底怎麼寫」，就是下一篇：見 [equals 與 hashCode 契約](equals-hashcode-contract.md)。

## 常見面試題

1. HashMap 的 `put` 流程？（提示：hashCode → 桶 → 碰撞時桶內 equals 比對；順帶講負載因子與擴容）
2. 為什麼建議 HashMap 的 key 用不可變物件？（提示：幽靈 key——hash 變了人在舊桶）
3. HashMap 和 ConcurrentHashMap 對 null 的態度？（提示：一個容一個禁；為什麼並發下 null 有歧義——get 到 null 是「沒有」還是「值是 null」？）

## 延伸閱讀

- [HashMap（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/HashMap.html) — 容量、負載因子與效能特性的官方說明
- [Map（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html) — `merge`/`computeIfAbsent` 等 default method 的完整契約
