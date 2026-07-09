# Java Memory Model 與 happens-before

## 前言

[synchronized 篇](synchronized-and-volatile.md)用了兩句白話：「進出鎖等於同步記憶體」「volatile 寫直達主記憶體」。它們夠用，但**都是簡化**——現代 CPU 沒有「直達主記憶體」這回事（cache 一直都在），編譯器和處理器還會偷偷調換你的指令順序。

那 JVM 到底承諾了什麼？答案是一份精確的契約：**Java Memory Model（JLS §17.4）**，核心是一個可以拿來做證明的關係——**happens-before**。這篇先用實驗抓到「指令重排」的現行犯（百萬分之二的機率，但抓到了），再把 happens-before 從名詞變成你會用的推理工具。

## 技術背景

### 為什麼需要一份記憶體模型

從你的程式碼到真正執行，中間每一層都在「作弊」求快：**編譯器**重排指令、**JIT** 更激進地重排、**CPU** 亂序執行、**每核的 cache** 讓寫入延遲可見。單執行緒下有 as-if-serial 保證（怎麼排，結果都跟照順序跑一樣），你毫無感覺；**跨執行緒沒有這個保證**——thread B 看 thread A 的操作，順序可能是亂的、寫入可能遲遲不可見（[停不下來的迴圈](synchronized-and-volatile.md)就是實例）。

JMM 就是在「允許各層繼續作弊」與「給程式員可推理的保證」之間畫的那條線。

### happens-before：不是時間，是可見性

定義一句話：**若 A happens-before B，則 A 的寫入保證對 B 可見**。

兩個最常見的誤讀要先排掉：

1. **它不是「時間上先發生」**——hb 是規則推出來的偏序關係，跟牆上時鐘無關；反過來，時間上先發生但沒有 hb 關係的寫入，**允許**永遠不可見
2. **它不禁止重排**——JVM 只保證「可見性結果」符合 hb；只要你觀察不到差異，底下想怎麼排就怎麼排

### 六條實用規則

| 規則 | 內容 | 白話對應 |
|---|---|---|
| Program order | 單執行緒內，前面的操作 hb 後面的 | 自己看自己永遠是順的 |
| Monitor lock | **unlock hb 之後的 lock**（同一把鎖） | 「進出鎖＝同步記憶體」的正式版 |
| Volatile | **volatile 寫 hb 之後的讀**（同一變數） | 「直達主記憶體」的正式版 |
| Thread start | `t.start()` hb `t` 內的一切 | 開工前準備的資料，工人看得到 |
| Thread join | `t` 內的一切 hb `t.join()` 返回 | 等人做完，成果必可見 |
| 遞移性 | A hb B、B hb C ⇒ A hb C | 規則可以串接——威力來源 |

### 用規則做證明（一）：停機旗標為什麼非 volatile 不可

[synchronized 篇](synchronized-and-volatile.md)實測過不加 volatile 的迴圈停不下來。現在用規則說**為什麼允許**：main 寫 `flag = false`、迴圈 thread 讀 `flag`——**翻遍六條規則，沒有任何一條把這個寫連到那個讀**。沒有 hb ＝ 沒有可見性保證 ＝ JIT 把讀取提升出迴圈是**合法**優化。加 volatile 後，規則三直接連上——不是「比較快看到」，是**從無保證變有保證**。

### 用規則做證明（二）：順風車（piggyback）

遞移性讓 volatile 能「捎帶」普通變數：

```java
// Thread A                          // Thread B
data = 42;            // ①普通寫     if (ready) {          // ③volatile 讀
ready = true;         // ②volatile寫     use(data);        // ④讀 data —— 保證看到 42！
                                     }
```

證明：① hb ②（program order）、② hb ③（volatile 規則）、③ hb ④（program order）→ 遞移得 **① hb ④**。`data` 自己不是 volatile，卻搭了 `ready` 的順風車。這不是奇技淫巧——**[ConcurrentHashMap 讀不加鎖](concurrent-collections.md)、執行緒池的任務傳遞，底層全靠這一招**：一次同步動作，捎帶之前的所有寫入。

### 用規則做證明（三）：雙檢鎖為什麼要 volatile

經典的 double-checked locking：

```java
private static volatile Config instance;          // 沒有 volatile 就是 bug
static Config get() {
    if (instance == null) {                       // 第一檢：無鎖快路徑
        synchronized (Config.class) {
            if (instance == null) instance = new Config();
        }
    }
    return instance;
}
```

`instance = new Config()` 是三步：配記憶體、跑建構子、把參考寫給 instance。**後兩步允許重排**——別的 thread 可能在第一檢看到非 null 的 `instance`，拿到的卻是**建構到一半的物件**（欄位還是預設值）。寫入 thread 有鎖，但讀取 thread 的快路徑**沒進鎖**——monitor 規則搆不到它，只有 volatile 規則能把「建構完成」連到「快路徑的讀」。

## 實際案例

### 實驗：抓重排序的現行犯

```java
// Thread 1： x = 1;  r1 = y;        // Thread 2： y = 1;  r2 = x;
```

兩條 thread 都是「先寫後讀」。若一切照程式順序執行，(r1, r2) 至少有一個是 1——**(0, 0) 只有重排能解釋**（某條的讀被排到寫前面）。跑一百萬次，實測（Apple Silicon，Temurin 17）：

```
1,000,000 次試驗中，抓到 (r1=0, r2=0) 共 2 次
```

**兩次就夠了**——「程式碼照寫的順序執行」這個直覺被正式推翻。百萬分之二也正是這類 bug 的恐怖之處：測試幾乎不會遇到，量產環境每天遇到。

加映一個反轉：改用 `CyclicBarrier` 讓兩條 thread 同步起跑，抓到 **0 次**——同步工具自帶記憶體柵欄，把重排壓住了。這個「觀察它就消失」的性質（跟 [synchronized 篇](synchronized-and-volatile.md)的 heisenbug 同款），是併發 bug 排查困難的根源，也是為什麼專業驗證要用 [JCStress](https://github.com/openjdk/jcstress) 這類框架而不是手寫迴圈。

## 技術優缺點

### JMM 這份契約的設計智慧

- **弱得剛好**：不承諾順序一致性（那會殺死所有優化），只承諾「有 hb 就可見」——效能與可推理性的平衡點
- **可證明**：六條規則＋遞移性＝可以紙上驗證併發程式碼的正確性，不用靠「跑起來沒事」

### 回到實務

- **日常不用親手推 hb**：`ConcurrentHashMap`、`BlockingQueue`、executor 的任務提交——[java.util.concurrent 全家的 API 文件都寫明了自帶的 hb 保證](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/package-summary.html#MemoryVisibility)，用它們＝借現成的證明
- **要親手推的三個時機**：自己寫無鎖結構、雙檢鎖／延遲初始化、跨 thread 發布物件——這時「感覺沒問題」一文不值，逐條規則連線才算數
- **final 欄位有專屬保證**：建構子完成後，final 欄位對所有 thread 可見（不需要 volatile）——不可變物件天生安全發布的理論基礎，[不可變設計](../02-language-core/pitfall-shared-reference-in-loop.md)又贏一分

## 小結

- 各層都在重排求快，**跨執行緒沒有 as-if-serial**——實驗抓到現行（百萬分之二，測試遇不到、量產天天遇）
- **happens-before ＝ 可見性保證**，不是時間順序、也不禁止重排
- 六條規則背三條：**program order、unlock→lock、volatile 寫→讀**，加上**遞移性**就能做證明
- 三個證明範例：停機旗標（無 hb ＝ 合法不可見）、順風車（volatile 捎帶普通寫入——CHM 無鎖讀的原理）、雙檢鎖（快路徑沒進鎖，只有 volatile 搆得到）
- 實務：優先借 j.u.c. 現成的 hb；自己發布物件時才親手推；final 欄位有專屬保證

「synchronized 很慢」的印象與 hb 的成本有關但不全對——JVM 對鎖做了一整套自適應優化，無競爭時幾乎免費：見規劃中的 🔬〈synchronized 鎖升級〉。

## 常見面試題

1. happens-before 是什麼？它和「時間上先發生」有什麼不同？（提示：可見性偏序；時間先發生但無 hb ＝ 允許不可見）
2. volatile 如何讓一個普通變數的寫入也變得可見？（提示：順風車證明；CHM 的應用）
3. 雙檢鎖為什麼需要 volatile？（提示：new 的三步重排、快路徑沒進鎖）

## 延伸閱讀

- [JLS §17.4: Memory Model](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4) — 契約原文
- [JSR-133 FAQ](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html) — JMM 設計者親寫的白話問答，本篇多個例子的源頭
- [Aleksey Shipilëv: JMM Pragmatics](https://shipilev.net/blog/2014/jmm-pragmatics/) — 實務派的 JMM 深講，配 JCStress 實驗
