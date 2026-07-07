# List：ArrayList vs LinkedList

## 前言

面試背過的口訣大家都會：「**ArrayList 查詢快，LinkedList 插入刪除快**——頻繁插入刪除的場景用 LinkedList。」

這句話的後半，在絕大多數真實場景是**錯的**。本篇用實測數據拆掉這個流傳最廣的迷思：四種操作、兩種 List 各跑一輪，你會看到 LinkedList 在「它該贏的場景」輸了 24 倍——然後我們回頭解釋為什麼，以及它僅存的勝場在哪。

## 技術背景

### ArrayList：一塊會長大的陣列

底層就是一個陣列（`Object[]`），元素緊挨著放：

```
[0][1][2][3][4][5]...        ← 連續記憶體
```

- **`get(i)` O(1)**：位址算一下直接跳過去
- **尾端 `add` 均攤 O(1)**：直接放進下一格；格子不夠時**擴容**——開一個 1.5 倍大的新陣列、整批搬過去。偶爾一次的搬家成本攤到每次操作上，所以叫「均攤」
- **中間/頭部插入 O(n)**：插入點之後的所有元素要整批往後挪一格（`System.arraycopy`）

> 已知大概要裝多少，就給初始容量 `new ArrayList<>(10_000)`——省掉一路上的多次擴容搬家。

### LinkedList：一串互相牽手的節點

雙向鏈結串列，每個元素包在一個節點裡，節點之間用參考相連：

```
head ⇄ [prev|item|next] ⇄ [prev|item|next] ⇄ ... ⇄ tail
```

- **頭尾操作 O(1)**：head/tail 直接接上新節點，不用搬任何人
- **`get(i)` O(n)**：沒有索引可跳，只能從頭（或尾）一個一個走過去
- **「插入 O(1)」的完整條件**：改兩個參考確實 O(1)——**但前提是你已經站在那個位置**。用 `add(index, e)` 得先花 O(n) 走到 index，O(1) 只是最後一步
- **每個元素多揹三個參考**（prev、next、item）：記憶體開銷是 ArrayList 的數倍，而且節點散落各處

### 複雜度對照

| 操作 | ArrayList | LinkedList |
|---|---|---|
| `get(i)` | **O(1)** | O(n) |
| 尾端 `add` | **均攤 O(1)** | O(1) |
| 頭部 `add(0, e)` | O(n)（整批後挪） | **O(1)** |
| 中間 `add(i, e)` | O(n)（搬移） | O(n)（走過去）＋ O(1) |
| 記憶體 | 緊湊、連續 | 每元素多 ~24 bytes、散落 |

注意中間插入那格：**兩邊都是 O(n)**。口訣騙人的地方就在這——它只記了 LinkedList 最後一步的 O(1)，忘了走過去的 O(n)。而同樣是 O(n)，兩者的常數差距巨大：ArrayList 的搬移是對連續記憶體的整批複製，CPU cache 極度友善；LinkedList 的遍歷是一次次指標跳躍，每跳一步都可能 cache miss。

## 實際案例

▶ 可執行範例：[ListBenchmark.java](examples/ListBenchmark.java)

四種操作實測（十萬元素規模，Temurin 17，粗測但差距皆為數量級）：

```
尾端 add         | ArrayList  |       1 ms
尾端 add         | LinkedList |       1 ms
頭部 add(0, e)   | ArrayList  |     284 ms
頭部 add(0, e)   | LinkedList |       2 ms
隨機 get(i)      | ArrayList  |       3 ms
隨機 get(i)      | LinkedList |   3,657 ms
中間 add(mid, e) | ArrayList  |      31 ms   ← 口訣說 LinkedList 該贏的場景
中間 add(mid, e) | LinkedList |     757 ms   ← 實際慢 24 倍
```

逐條解讀：

1. **尾端 add：平手**。ArrayList 的均攤擴容和 LinkedList 的接尾巴都夠快——最常見的用法根本分不出勝負
2. **頭部插入：LinkedList 唯一的勝場**（2ms vs 284ms）。ArrayList 每插一次就整批後挪，十萬次就是 O(n²)
3. **隨機存取：1,200 倍差距**。LinkedList 每次 `get` 都從頭走，這也是為什麼**用 `for (int i...) get(i)` 遍歷 LinkedList 是災難**——要遍歷就用 for-each（iterator 會記住位置）
4. **中間插入：口訣翻車現場**。理論上都是 O(n)，實測 ArrayList 快 24 倍——連續記憶體的整批搬移，完勝指標跳躍的逐節點走訪

結論很殘酷：LinkedList 只在「頭尾進出」贏，**而那個場景有更好的選擇——`ArrayDeque`**（同樣頭尾 O(1)，底層環形陣列，cache 友善、記憶體省）。這也是為什麼 LinkedList 的作者 Joshua Bloch 自己說過他從不用它。

## 技術優缺點

### ArrayList

- ✅ 隨機存取 O(1)、遍歷快、記憶體緊湊——現代 CPU 就愛連續記憶體
- ✅ 尾端追加均攤 O(1)，是「預設 List」的不二人選
- ❌ 頭部/中間插刪要搬移；擴容瞬間要整批複製（可用初始容量緩解）

### LinkedList

- ✅ 頭尾操作真 O(1)，不會有擴容尖峰
- ❌ 隨機存取 O(n)、cache 不友善、每元素多揹 ~24 bytes
- ❌ 「插入快」只在 iterator 已就位時成立，靠 index 的操作全部退化
- ❌ 它的勝場（頭尾進出）被 `ArrayDeque` 全面壓制

**實務決策**：預設 `ArrayList`；要當 queue/stack 用 `ArrayDeque`；LinkedList 幾乎沒有非它不可的場景。

## 小結

- ArrayList＝會長大的陣列：`get` O(1)、尾插均攤 O(1)、中間插刪要搬家
- LinkedList＝雙向鏈結：頭尾 O(1)，但 `get`/index 操作都要走 O(n)，還多揹記憶體
- **「頻繁插入刪除用 LinkedList」是迷思**：中間插入兩邊都 O(n)，實測 ArrayList 反而快 24 倍（cache locality）
- LinkedList 唯一勝場是頭部插入（實測 2ms vs 284ms），但該場景 `ArrayDeque` 更好
- 遍歷 LinkedList 永遠用 for-each，`for (int i...) get(i)` 是 O(n²)

List 管「第幾個」，下一站是管「有沒有」的家族——HashSet、LinkedHashSet、TreeSet 怎麼選、去重的代價是什麼，見 [Set：HashSet、LinkedHashSet、TreeSet 與去重的代價](set-implementations.md)。

## 常見面試題

1. ArrayList 和 LinkedList 怎麼選？（提示：先講複雜度表，再戳破「頻繁插刪用 LinkedList」——中間插入兩邊都 O(n)，cache locality 決定勝負）
2. ArrayList 的擴容機制？怎麼避免擴容成本？（提示：1.5 倍新陣列整批搬、初始容量）
3. 為什麼 LinkedList 理論上的 O(1) 插入，實務上快不起來？（提示：O(1) 的前提是「已站在該位置」；index 操作要先 O(n) 走過去）

## 延伸閱讀

- [ArrayList（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ArrayList.html)、[LinkedList（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/LinkedList.html) — 各操作複雜度的官方描述
- [ArrayDeque（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ArrayDeque.html) — 「比 LinkedList 更好的 LinkedList」，官方明言當 stack 比 Stack 快、當 queue 比 LinkedList 快
