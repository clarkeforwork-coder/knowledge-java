# 集合框架總覽：Collection 與 Map 兩棵樹、選型決策

## 前言

觀察一下自己的程式碼：要裝一堆東西的時候，你是不是永遠寫 `new ArrayList<>()`？要 key-value 的時候永遠 `new HashMap<>()`？

大多數時候這樣沒事——直到某天，一段「檢查會員是否在名單裡」的迴圈把 API 從 50ms 拖到 3 秒；或是一個「去重後照原本順序輸出」的需求，輸出順序每次都不一樣。集合框架給了十幾種現成的容器，每一種都是針對某個場景的取捨；全部用 ArrayList 和 HashMap，就像工具箱裡只用榔頭。這篇是整個 04 章的地圖：先看懂框架的全貌，再學會「需求 → 容器」的選型直覺。

## 技術背景

先破除一個常見誤解：「集合框架是一棵樹，Map 也是 Collection 的一種」。

**錯——是兩棵樹。** `Map` 不繼承 `Collection`：`Collection` 裝的是「一個一個的元素」，`Map` 裝的是「key → value 的對應」，語意不同，介面也就分家。

### 框架地圖

```
Iterable
└── Collection（裝元素）
    ├── List ── 有序、可重複、有索引     → ArrayList、LinkedList
    ├── Set ─── 不可重複                → HashSet、LinkedHashSet、TreeSet
    └── Queue ─ 頭尾進出                → ArrayDeque、PriorityQueue

Map（裝 key→value 對應，獨立的一棵樹）  → HashMap、LinkedHashMap、TreeMap
```

（並發場景的 `ConcurrentHashMap`、`BlockingQueue` 屬於 07 並發章的範圍，這裡先不展開。）

### 第一層選擇：選介面＝選語意

- **List**：我在乎「第幾個」——有順序、可以重複、能用索引取
- **Set**：我在乎「有沒有」——同一個東西只能出現一次
- **Map**：我在乎「用什麼查什麼」——一個 key 對一個 value
- **Queue**：我在乎「誰先誰後被處理」——從頭尾進出，不隨機存取

宣告時用介面、實例化時才選實作，是基本紀律：

```java
List<Order> orders = new ArrayList<>();   // ✅ 型別是 List，之後要換實作不用改呼叫端
ArrayList<Order> orders = new ArrayList<>();   // ❌ 綁死實作，換 LinkedList 要全面改型別
```

### 第二層選擇：選實作＝選取捨

集合框架的命名藏著一個規律——**前綴決定「順序」的性格**，而且 Set 和 Map 兩邊完全對稱：

| 前綴 | 底層 | 順序 | 代價 | Set 版 | Map 版 |
|---|---|---|---|---|---|
| `Hash` | hash 表 | **不保證任何順序** | 最快：平均 O(1) | HashSet | HashMap |
| `LinkedHash` | hash 表＋鏈結 | **保插入順序** | 略慢於 Hash，多吃記憶體 | LinkedHashSet | LinkedHashMap |
| `Tree` | 紅黑樹 | **隨時保持排序** | O(log n)，元素要能比較 | TreeSet | TreeMap |

這個對稱不是巧合：`HashSet` 內部就是一個 `HashMap`（只用 key、value 填假物件）。所以 hash 家族的一切行為——包括「靠 `equals()` 和 `hashCode()` 判斷同不同」——Set 和 Map 是同一套，這條線索後面兩篇（HashMap 基礎、equals/hashCode 契約）會一路追下去。

### 選型決策表

| 需求 | 用這個 |
|---|---|
| 就是一串東西，會照索引拿 | `ArrayList`（預設答案） |
| 頻繁從頭尾加入/移除 | `ArrayDeque` |
| 去重，順序無所謂 | `HashSet` |
| 去重，要照放入的順序 | `LinkedHashSet` |
| 去重，要隨時排好序 | `TreeSet` |
| key 查 value，順序無所謂 | `HashMap`（預設答案） |
| key 查 value，要照放入順序 | `LinkedHashMap` |
| key 查 value，要照 key 排序（如範圍查詢） | `TreeMap` |
| 大量「檢查存不存在」 | **Set，不要用 List**（見下方實測） |

## 實際案例

### 選錯結構的代價：contains 的數量級差距

▶ 可執行範例：[ContainsBenchmark.java](examples/ContainsBenchmark.java)

十萬筆資料、十萬次「在不在名單裡」的檢查，`List` 和 `Set` 各跑一次：

```java
List<Integer> list = /* 0..99999 */;
Set<Integer> set = new HashSet<>(list);

for (int i = 0; i < n; i++) if (list.contains(i)) hits++;   // 每次從頭掃：O(n)
for (int i = 0; i < n; i++) if (set.contains(i))  hits++;   // hash 定位：平均 O(1)
```

實測結果（Temurin 17，粗測非嚴謹 benchmark，但差距是數量級的）：

```
List.contains × 100,000：1,308 ms（命中 100,000）
Set.contains  × 100,000：2 ms（命中 100,000）
```

**650 倍。** `List.contains` 每次都從頭掃到找到為止，十萬次檢查就是 O(n²)；`Set` 用 hash 直接定位。「會員在不在名單裡」「這個 ID 處理過沒有」——這類需求的正確容器永遠是 Set，而它在真實系統裡最常見的偽裝，就是一個被丟進迴圈裡的 `list.contains()`。

### 順序的驚喜：同一批資料、三種 Set、三種順序

```java
List<String> cities = List.of("台北", "台中", "台南", "高雄", "新竹");
System.out.println(new HashSet<>(cities));
System.out.println(new LinkedHashSet<>(cities));
System.out.println(new TreeSet<>(cities));
```

實測輸出：

```
HashSet      ：[新竹, 台中, 台北, 台南, 高雄]   ← 順序不保證（跟著 hash 走，還可能因擴容而變）
LinkedHashSet：[台北, 台中, 台南, 高雄, 新竹]   ← 插入順序，跟放進去時一樣
TreeSet      ：[台中, 台北, 台南, 新竹, 高雄]   ← 排序——但中文是按 Unicode 碼位排，不是筆畫或注音
```

「去重後照原順序輸出」的 bug 幾乎都是拿 `HashSet` 做出來的；而 `TreeSet` 對中文字串的「排序」多半不是你要的排序（要語言相關的排序得用 `Collator` 當 Comparator——見規劃中的〈排序：Comparable vs Comparator〉）。

## 技術優缺點

### 這套框架設計的優勢

- **介面與實作分離**：呼叫端只依賴 `List`/`Set`/`Map` 的語意，實作可以按效能需求替換
- **命名即文件**：`Hash`/`LinkedHash`/`Tree` 前綴 × `Set`/`Map` 後綴，猜得出行為，Set/Map 兩邊經驗互通
- **取捨明碼標價**：每種實作的時間複雜度和順序保證都是公開契約，選型有據可依

### 使用時的代價與陷阱

- **沒有萬用容器**：順序要用記憶體換（LinkedHash 的鏈結）、排序要用時間換（Tree 的 O(log n)）、快要用順序換（Hash）——選 A 就是放棄 B
- **hash 家族把靈魂外包給你的物件**：`equals()`/`hashCode()` 寫錯，HashSet 去不了重、HashMap 找不到 key，錯誤還無聲無息（見規劃中的〈equals 與 hashCode 契約〉）
- **它們都不是 thread-safe**：多執行緒共用要用並發版本，07 章再談

## 小結

- **兩棵樹**：`Collection`（List/Set/Queue，裝元素）和 `Map`（裝對應），Map 不是 Collection
- **選介面＝選語意**：在乎第幾個 → List；在乎有沒有 → Set；用 key 查 → Map
- **選實作＝選取捨**：`Hash` 快但無序、`LinkedHash` 保插入序、`Tree` 保排序但 O(log n)，Set/Map 完全對稱
- **大量存在性檢查用 Set**：`list.contains()` 進迴圈就是 O(n²)，實測差 650 倍
- 預設答案是 `ArrayList` 和 `HashMap` 沒錯——但要知道自己什麼時候該換

這張地圖上每個節點接下來各自展開：List 兩兄弟誰快誰慢、什麼時候真的該用 LinkedList，見 [List：ArrayList vs LinkedList](list-arraylist-vs-linkedlist.md)；hash 家族憑什麼 O(1)、去重到底怎麼判斷「同一個」，見規劃中的〈Map：HashMap 基礎與正確使用〉和〈equals 與 hashCode 契約〉。

## 常見面試題

1. List、Set、Map 的差別？Map 是不是 Collection？（提示：語意各是什麼、兩棵樹）
2. HashSet、LinkedHashSet、TreeSet 怎麼選？（提示：前綴決定順序性格，各自的代價）
3. 十萬筆資料要頻繁檢查「某元素存不存在」，該用什麼？為什麼？（提示：contains 的複雜度，O(n²) 是怎麼長出來的）

## 延伸閱讀

- [Java Collections Framework 官方總覽](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/doc-files/coll-index.html) — 框架設計文件與完整的介面/實作清單
- [Outline of the Collections Framework](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/doc-files/coll-reference.html) — 各實作的行為與複雜度速查
