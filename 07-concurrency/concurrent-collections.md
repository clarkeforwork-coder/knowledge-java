# 並發集合：ConcurrentHashMap

## 前言

這是三條伏筆的會合點。[HashMap 深入篇](../04-collections/deep-hashmap-internals.md)劇透過「JDK 7 的併發擴容能把鏈搬成環」；[Map 篇](../04-collections/map-hashmap-basics.md)說 ConcurrentHashMap 禁 null「07 章解釋」；[fail-fast 篇](../04-collections/fail-fast-and-cme.md)預告過「不對帳、不炸」的 weakly consistent 迭代器。全部在這篇結清。

實務場景也很日常：用一個 Map 當 in-memory 快取，所有 request thread 都來讀寫——用 HashMap 會怎樣？換成 ConcurrentHashMap 就萬事大吉了嗎？**這篇最重要的一句話是後半題的答案：thread-safe 的容器，不等於你的複合邏輯 thread-safe**（實測 18,092 個 key 翻車給你看）。

## 技術背景

### HashMap 在併發下的病歷

多執行緒同時寫 HashMap，從輕到重：**遺失更新**（實測掉了 5.7 萬筆）、**遍歷時 CME**、以及最惡名昭彰的——JDK 7 併發擴容把桶內鏈表搬成**環**，之後的 `get` 直接死循環燒滿 CPU。JDK 8 改了搬法（尾插），死循環沒了，但遺失更新與結構損壞依舊——**HashMap 從來就不承諾併發安全，能跑只是運氣**。

### 兩種加鎖哲學：全表一把鎖 vs 桶級細鎖

```java
Map<K,V> m1 = Collections.synchronizedMap(new HashMap<>());   // 全表一把鎖
Map<K,V> m2 = new ConcurrentHashMap<>();                       // 桶級細鎖
```

- **synchronizedMap**：每個方法包一層 `synchronized(全表)`——正確，但所有 thread 排一條隊，讀也要排
- **ConcurrentHashMap（JDK 8+）**：**讀完全不加鎖**（靠 volatile 語意），寫用 CAS＋只鎖**單一桶的頭節點**——不同桶的操作完全並行。[synchronized 篇](synchronized-and-volatile.md)的兩件武器（CAS、物件鎖）在這裡以「粒度最小化」的方式合體

### 本篇最重要的觀念：複合操作不會自動安全

ConcurrentHashMap 保證的是**單一方法呼叫**的原子性。你自己組合的多步邏輯，它管不著：

```java
// ❌ check-then-act：兩步之間別人可以插隊——即使容器是 thread-safe 的
if (!map.containsKey(k)) {
    map.put(k, new AtomicInteger());   // 兩條 thread 都通過檢查 → 各放各的 → 一個被蓋掉
}
map.get(k).incrementAndGet();          // 蓋掉的那個計數器連同計數一起蒸發

// ✅ 一步原子版：ConcurrentHashMap 的 computeIfAbsent 保證整件事一氣呵成
map.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
```

還記得 [Map 篇](../04-collections/map-hashmap-basics.md)的現代 API（`putIfAbsent`、`computeIfAbsent`、`merge`）嗎？**在 ConcurrentHashMap 上它們全部升級為原子操作**——04 章學的寫法，07 章成了並發武器。這也是「用了現代 API」和「還在三連擊」的差距從可讀性問題升級成正確性問題的地方。

一條配套紀律：`compute*` 的 mapping function 在鎖內執行——**別在裡面做重活，更別碰同一個 map**（會死鎖）。

### 兩個設計決策的「為什麼」

**為什麼禁 null**（還 Map 篇的債）：`get(k)` 回傳 null 有兩種意思——「沒這個 key」或「值就是 null」。單執行緒可以再問 `containsKey` 分辨；併發下這兩步之間 map 可能已經變了，**歧義無法消除**——乾脆禁止 null，讓 null 只有一種意思。

**weakly consistent 迭代器**（還 fail-fast 篇的債）：ConcurrentHashMap 的迭代器不對帳（沒有 modCount 檢查）、遍歷中容忍併發修改、**保證不炸但不保證看到最新**——你看到的是「遍歷路過時」的狀態。同一個取捨的另一面：`size()` 也只是**估計值**，高併發下不要拿它做精確判斷。

### 其他並發容器速覽

| 容器 | 策略 | 適用 |
|---|---|---|
| `CopyOnWriteArrayList` | 寫時複製整個陣列，讀零鎖零複製 | **讀極多、寫極少**（監聽器清單、設定快照）；寫頻繁是災難 |
| `BlockingQueue`（`ArrayBlockingQueue` 等） | 滿了阻塞放、空了阻塞取 | 生產者—消費者；[執行緒池](executorservice-and-thread-pools.md)的 workQueue 就是它 |

## 實際案例

### 案例一：HashMap vs ConcurrentHashMap 併發寫入

兩條 thread 各 put 50 萬個**不同的** key：

```
HashMap           ：size = 942,815（應為 1,000,000）   ← 掉了 5.7 萬筆
ConcurrentHashMap ：size = 1,000,000
```

key 完全不重疊、理論上零衝突，照樣掉——擴容搬遷時兩條 thread 互相蓋寫。這還是運氣好的一次：同一段程式碼也可能拋例外或（JDK 7）死循環。

### 案例二：18,092 個 key 的教訓——複合操作實測

兩條 thread 用 `CountDownLatch` 同時起跑，同步走過十萬個 key、每 key 各加一次（每個 key 應為 2）：

```
check-then-act  ：18,092 個 key 計數錯誤（應為 0）
computeIfAbsent ：0 個 key 計數錯誤
```

**容器是 ConcurrentHashMap、計數器是 AtomicInteger——每個零件都 thread-safe，組合起來 18% 的 key 錯了**。錯的不是零件，是 `containsKey` 和 `put` 之間那道縫。`computeIfAbsent` 把縫焊死，一字之差，零錯誤。

### 案例三：遍歷中修改，不炸

```java
Map<Integer, Integer> live = new ConcurrentHashMap<>();   // 放 5 筆
for (Integer k : live.keySet()) {
    live.put(k + 100, k);                                 // 遍歷中一直加
}
```

實測：**沒有 CME，正常跑完**（size 長到 24——迭代器沿路看到了部分新加的 key，也印證「不保證看到什麼」）。對照 [fail-fast 篇](../04-collections/fail-fast-and-cme.md)：HashMap 這樣寫當場炸。兩邊都對——一個要一致性寧可死，一個要可用性容忍舊。

## 技術優缺點

### ConcurrentHashMap

- ✅ 讀不加鎖、寫鎖單桶——高併發下吞吐跟 synchronizedMap 是量級差距
- ✅ `computeIfAbsent`/`merge` 原子化——複合邏輯有官方解法
- ❌ 弱一致的世界觀：迭代器看到的是「路過時」、`size()` 是估計值——要「某一瞬間的精確快照」它給不了
- ❌ 禁 null（設計決策，不是缺陷——但移植舊程式碼時是地雷）

### 選型速記

- 多執行緒共享 Map → **ConcurrentHashMap，預設答案**
- 讀極多寫極少的 List → CopyOnWriteArrayList；生產者消費者 → BlockingQueue
- synchronizedMap 剩下的理由：需要「鎖住整個 map 做多步強一致操作」時，它的全表鎖反而是語意（自己 `synchronized(map)` 包複合邏輯）

## 小結

- HashMap 併發寫實測掉 5.7 萬筆（JDK 7 還會死循環）——它從未承諾併發安全
- ConcurrentHashMap 的功夫在**鎖粒度**：讀無鎖、寫 CAS＋單桶鎖
- **本篇最重要**：thread-safe 容器 ≠ 複合邏輯安全——check-then-act 實測 18,092 個 key 翻車，`computeIfAbsent` 一字之差零錯誤；04 章的現代 API 在這裡升級為原子武器
- 禁 null 是為了消除併發下的歧義；迭代器 weakly consistent、`size()` 是估計——弱一致是它選的世界觀
- 速記：共享 Map 用 CHM；讀多寫極少用 CopyOnWrite；生產者消費者用 BlockingQueue

到這裡，「共享資料」的防護配齊了。下一個主題換方向——**任務的編排**：查會員、查訂單、查優惠三個遠端呼叫，怎麼並行發出、聚合結果、優雅處理失敗？見 [CompletableFuture](completablefuture.md)。

## 常見面試題

1. ConcurrentHashMap 怎麼做到 thread-safe？跟 synchronizedMap 差在哪？（提示：JDK 8 的 CAS＋桶級鎖 vs 全表鎖；讀要不要鎖）
2. 在 thread-safe 的容器上做 check-then-act 安全嗎？（提示：實測 18,092；computeIfAbsent 為什麼安全）
3. 為什麼 ConcurrentHashMap 不允許 null？（提示：get 回傳 null 的歧義在併發下無法消除）

## 延伸閱讀

- [ConcurrentHashMap（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html) — 弱一致語意與原子方法的官方定義
- [java.util.concurrent 套件總覽](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/package-summary.html) — 並發容器全家福與記憶體一致性效應
