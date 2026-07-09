# synchronized 鎖升級

## 前言

[synchronized 篇](synchronized-and-volatile.md)留下一個看似矛盾的說法：四路對決裡它「最慢」（35ms），卻又說「無競爭時成本很低」。到底哪個對？

先亮實測答案：五千萬次迴圈，**無鎖 28ms、有鎖無競爭 30ms、有鎖真競爭 2,228ms**——「synchronized 很慢」是個需要拆解的都市傳說：**慢的不是鎖，是競爭**。這篇打開 JVM 的鎖優化工具箱，用 JOL 直接觀察物件頭裡鎖的形態變化，順便處理一個網路文章的重災區：**偏向鎖已經死了**（JDK 15 移除），還在背「無鎖→偏向→輕量→重量」四段論的教材已經過期。

## 技術背景

### 鎖住在哪：物件頭的 mark word

每個 Java 物件都有物件頭，其中 64 bit 的 **mark word** 是多功能欄位——identity hashCode、GC 年齡（[分代](../01-jvm/gc-basics-and-generations.md)的 age 就記在這）、以及**鎖狀態**，全部擠在這 8 bytes 裡輪流住。鎖的形態，就是 mark word 的編碼形態（實驗三直接看給你）。

### 現在的兩種形態（JDK 15 之後）

| 形態 | 機制 | 適用 |
|---|---|---|
| **Thin lock（輕量鎖）** | 一次 **CAS** 把 mark word 指向 thread 的 stack ——[synchronized 篇](synchronized-and-volatile.md)講過的 CAS，用在鎖自己身上 | 無競爭：成本 ≈ 一次 CAS，這就是 30ms ≈ 28ms 的原因 |
| **Fat lock（重量鎖／膨脹）** | mark word 指向獨立的 **monitor 物件**，等待者掛 OS 層（park）——thread 進 [BLOCKED](thread-basics-and-lifecycle.md) | 有競爭：CAS 搶不到 → 先自旋幾圈（adaptive spinning，賭對方馬上放）→ 賭輸才 park |

真競爭的 2,228ms 貴在哪：park/unpark 是系統呼叫、context switch 洗 cache、加上鎖的**串行化本質**（兩條 thread 輪流過獨木橋，並行度歸零）。

### 偏向鎖的訃聞（JEP 374）

四段論裡的「偏向鎖」值得一段歷史交代。它的設計動機：同一條 thread 反覆進出同一把鎖（老集合類 Vector/Hashtable 時代的常態），連 CAS 都想省——第一次記下 thread ID，之後重入零成本。**為什麼刪**（JDK 15 起預設停用、後續移除）：撤銷（revocation）要等 safepoint、成本高且拖累所有 thread；現代程式改用 j.u.c. 和無鎖結構，「單執行緒反覆鎖」的場景大減；維護複雜度巨大。實驗三的 JOL 輸出裡，新物件的狀態直接寫著 **`non-biasable`**——JVM 親口告訴你這件事。**面試若被問四段論，答得出「偏向鎖已移除＋為什麼」才是加分題**。

### JIT 的釜底抽薪：鎖消除與鎖粗化

升級是「鎖怎麼變便宜」，JIT 還有更狠的——**讓鎖消失**：

- **鎖消除（lock elision）**：逃逸分析證明鎖物件是純區域變數（不逃逸＝不可能有第二條 thread 碰它）→ synchronized **整個刪除**。實驗二：區域 StringBuffer 的 append（帶 synchronized）在預設下 4ms、關掉優化 68ms——**17 倍的差距就是被刪掉的那些鎖**
- **鎖粗化（lock coarsening）**：迴圈裡反覆進出同一把鎖 → 合併成「鎖一次做全部」——加解鎖次數從 N 次變 1 次

## 實際案例

### 實驗一：慢的不是鎖，是競爭

五千萬次 `counter++`，三種姿勢（JIT 預熱後）：

```
無鎖：28 ms｜有鎖無競爭：30 ms｜有鎖真競爭：2228 ms
```

無競爭的 synchronized 只比無鎖貴 **2ms**（一次 CAS 的價格）；真競爭貴 **74 倍**。實務推論：與其糾結「要不要用 synchronized」，不如糾結**怎麼減少競爭**——縮小臨界區、拆鎖（[ConcurrentHashMap 的桶級鎖](concurrent-collections.md)就是這個思路的極致）、或無鎖化（Atomic）。

### 實驗二：JIT 把鎖整個刪了

```java
for (int i = 0; i < 5_000_000; i++) {
    StringBuffer sb = new StringBuffer();   // 方法都帶 synchronized——但物件不逃逸
    sb.append("a").append("b").append("c");
}
```

```
預設（鎖消除開啟）        ：4 ms
關閉（-XX:-EliminateLocks -XX:-DoEscapeAnalysis）：68 ms
```

一個 JVM 旗標的差距＝被 JIT 刪掉的一千五百萬次加解鎖。這也解釋了[String 篇](../02-language-core/string-and-stringbuilder.md)沒細講的一件事：區域使用的 StringBuffer 其實沒那麼虧——但**別依賴這個**，逃逸分析有它的極限，寫對工具（StringBuilder）永遠比賭優化可靠。

### 實驗三：用 JOL 看鎖的一生

`jol-core` 直接印物件頭的 mark word（Temurin 17 實測）：

```
剛出生          ：0x…0001 (non-biasable; age: 0)     ← 偏向鎖已死的官方蓋章
synchronized 中 ：0x…e2e0 (thin lock: …)              ← 一次 CAS，指向 stack
釋放之後        ：0x…0001 (non-biasable; age: 0)      ← 還原
發生競爭之後    ：0x…70f2 (fat lock: …)               ← 膨脹：指向 monitor
要過 hashCode 後：0x…70f2 (fat lock: …)               ← 膨脹後不回頭
```

三個看點：**`non-biasable`** 是 JDK 15+ 的時代印記；thin → fat 的**膨脹單向不可逆**（monitor 建了就不拆——這是「一把鎖被競爭污染過就永遠貴」的原因）；mark word 的多功能性——hashCode、age、鎖狀態擠同一格，所以膨脹後 identity hash 要搬去 monitor 裡住。

## 技術優缺點

### 這套自適應設計的智慧

- **為常見情況付最低價**：絕大多數鎖從未被競爭——thin lock 讓它們只付一次 CAS；JIT 更進一步，證明不可能競爭的直接刪
- **為壞情況止血**：自旋賭短等待、park 保 CPU——比純自旋（燒 CPU）或純阻塞（高延遲）都好

### 回到實務的判斷

- **「synchronized 慢」的正確版本**：「**競爭**慢」——優化方向永遠是減少競爭（縮臨界區、拆鎖、無鎖），不是換一種鎖的寫法
- **膨脹不可逆**：熱點鎖被競爭污染後常駐 fat——jstack 看到大量 BLOCKED 在同一把鎖（[排查工具箱](../01-jvm/troubleshooting-toolbox.md)的死結流程同款讀法），該做的是重新設計共享，不是調 JVM 參數
- **別為舊知識付費**：偏向鎖相關的 JVM 參數（`-XX:+UseBiasedLocking`）在新版 JVM 已無效——網路教程的年份要看
- 與 `ReentrantLock` 的選擇：語意夠用時 synchronized 優先（JVM 優化最深、[virtual threads 的 pinning 也已在 JDK 24 修復](virtual-threads.md)）；要 tryLock／公平鎖／多條件變數才上 ReentrantLock

## 小結

- **慢的不是鎖是競爭**：實測無鎖 28ms、無競爭 synchronized 30ms（≈一次 CAS）、真競爭 2,228ms（74 倍）
- 現行兩形態：**thin lock**（CAS 標記）→ 競爭時**膨脹成 fat lock**（monitor＋park），且**膨脹不可逆**（JOL 實測）
- **偏向鎖已死**（JEP 374，JDK 15）——JOL 印出的 `non-biasable` 是時代印記，四段論教材已過期
- JIT 更狠的兩招：**鎖消除**（不逃逸就刪，實測 17 倍）、鎖粗化（合併相鄰加解鎖）
- 優化方向：減少競爭（縮臨界區、拆鎖、無鎖化），不是逃避 synchronized

## 常見面試題

1. 「synchronized 很慢」對嗎？（提示：三段實測數據；慢的是競爭；無競爭 ≈ 一次 CAS）
2. 鎖升級的現行路徑？偏向鎖去哪了？（提示：thin → fat 不可逆；JEP 374 與移除原因）
3. 什麼是鎖消除？什麼條件下發生？（提示：逃逸分析；區域 StringBuffer 實測 17 倍）

## 延伸閱讀

- [JEP 374: Deprecate and Disable Biased Locking](https://openjdk.org/jeps/374) — 偏向鎖之死的官方文件，動機段寫得極清楚
- [JOL（Java Object Layout）](https://github.com/openjdk/jol) — 本篇實驗三的工具，觀察物件頭的第一手方式
