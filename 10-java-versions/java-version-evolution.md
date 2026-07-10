# Java 版本演進：8 → 11 → 17 → 21

## 前言

「我們還在用 Java 8」——這句話在 2020 年代的企業裡依然常見，而它背後往往不是技術判斷，是「不知道升上去有什麼、怕升壞」。這篇是整個 repo 的收尾章，做一件事：**把散落在前面九章的版本特性，收成一張演進地圖**——你會發現這本筆記其實一路都在講「Java 8 之後的世界」。

不堆 JEP 清單。只答三個實際問題：**每個 LTS 版本帶來什麼真正改變寫法的東西、為什麼該升、升級要小心什麼**。

## 技術背景

### 先搞懂發布節奏：LTS 是錨點

Java 9 之後改成**每半年一個版本**，其中每兩三年指定一個 **LTS（長期支援）**——企業只跟 LTS：

| LTS | 年份 | 一句話定位 |
|---|---|---|
| **8** | 2014 | lambda/Stream 的元年——現代 Java 的起點 |
| **11** | 2018 | 第一個「值得從 8 升」的 LTS |
| **17** | 2021 | 語言特性大豐收（record/sealed/switch） |
| **21** | 2023 | Virtual Threads——並發模型的地殼變動 |

非 LTS 版本（12-16、18-20…）是 LTS 特性的「預覽場」——功能在那裡孵化、成熟後併進下一個 LTS。日常只需認得四個 LTS。

### Java 8：現代 Java 的地基（本 repo 的預設）

這本筆記的預設語言水平就是 8——因為 8 是斷代點：

- **[Lambda 與 Stream](../06-lambda-and-stream/functional-interface-and-lambda.md)**（06 章整章）——函數式風格進入 Java
- **[interface 的 default method](../02-language-core/interface-vs-abstract-class.md)**——介面能演進了
- **[Optional](../02-language-core/optional.md)**——把「可能沒有」寫進型別
- 新的日期時間 API（`java.time`，取代地雷遍地的 `Date`/`Calendar`）

「還在用 8」不是災難——8 已經是現代 Java。但停在 8 會錯過後面三個 LTS 的紅利。

### Java 11：務實的第一步

11 的語言新特性不多，但它是**該升的第一個理由**：

- **`var` 區域變數型別推斷**（其實 10 就有）——`var list = new ArrayList<String>()`
- **內建 HTTP Client**——不用再拉第三方
- 真正的動機是**非語言面**：8 的商業支援到期、11 的 GC（[G1 成預設、ZGC 進場](../01-jvm/deep-gc-algorithms.md)）、模組系統（JPMS）、以及一堆效能與安全修復

11 的訊息是：**升級不總是為了新語法，也為了活在有支援、更快、更安全的 runtime 上**。

### Java 17：語言特性大爆發

17 是 8 之後語言變化最大的 LTS——而且這些特性本 repo 一路在用：

- **[record](../02-language-core/record-and-immutability.md)**——值物件一行搞定（不可變敘事線的主角）
- **sealed classes**——限制誰能繼承/實作（配合 record 做代數資料型別）
- **switch 表達式與 pattern matching**——`switch` 從語句變表達式、能回傳值、能匹配型別
- **text blocks**——多行字串（`"""..."""`），JSON/SQL 不用再拼接
- runtime 面：**[ZGC 正式化](../01-jvm/deep-gc-algorithms.md)**、更省的容器感知

17 的訊息是：**Java 的語言表達力補上了這十年欠現代語言的課**。

### Java 21：並發模型的地殼變動

21 最重的一顆是 **[Virtual Threads](../07-concurrency/virtual-threads.md)**（07 章壓軸）——它不是語法糖，是**重新定義了「thread 很貴」這個延續二十年的前提**：

- **[Virtual Threads](../07-concurrency/virtual-threads.md)**——百萬條 thread 成為可能，阻塞式寫法重新成為第一選擇（實測 IO 吞吐 38 倍）
- **pattern matching for switch 正式化**＋ **record patterns**——解構 record、按型別分支，配 sealed 做窮盡檢查
- **sequenced collections**——集合終於有統一的「第一個/最後一個」API
- 還修了 [17 的 virtual thread pinning](../07-concurrency/virtual-threads.md)（JDK 24）

21 的訊息是：**Java 在雲原生時代重新校準——用更輕的並發模型回應 Serverless 與高併發。**

## 實際案例

### 同一段邏輯，跨越四個版本

「把訂單按城市分組、每組算總額」——看它如何隨版本演進：

```java
// Java 7：命令式，樣板淹沒意圖
Map<String, Integer> totals = new HashMap<>();
for (Order o : orders) {
    if (!totals.containsKey(o.getCity())) totals.put(o.getCity(), 0);
    totals.put(o.getCity(), totals.get(o.getCity()) + o.getAmount());
}

// Java 8：Stream + Collectors，意圖浮現（06 章）
Map<String, Integer> totals = orders.stream()
        .collect(groupingBy(Order::getCity, summingInt(Order::getAmount)));

// Java 16+：record 當資料載體（02 章）
record Order(String city, int amount) { }        // getter/equals/hashCode 全免

// Java 21：virtual thread 讓「每筆訂單一條 thread 併發查價」變得可行（07 章）
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    orders.forEach(o -> executor.submit(() -> repriceFromRemote(o)));
}
```

同一個業務需求，四個版本、四種寫法——**每一步都少一點樣板、多一點意圖，或多一種以前做不到的能力**。這就是升級的具體回報：不是「新語法好玩」，是**同樣的事寫得更短、更清楚、或根本第一次變得可行**。

### 版本佐證

本 repo 的實測橫跨兩個 LTS：多數在 **Temurin 17**（`java -version` 為證）、[Virtual Threads 篇](../07-concurrency/virtual-threads.md)用 Docker 的 **Temurin 21** 跑——這本筆記本身就是「用著 17/21、講著 8 之後演進」的實例。

## 技術優缺點

### 升級的回報

- **語言表達力**：record/switch/text block 讓程式碼更短更清楚（維護成本直接下降）
- **runtime 紅利**：更好的 GC（[G1→ZGC](../01-jvm/deep-gc-algorithms.md)）、容器感知、啟動與記憶體優化——**不改一行碼就變快**
- **並發模型**：virtual threads 是十年一遇的範式轉移
- **安全與支援**：留在有安全更新的 LTS 上，本身就是風險管理

### 升級的代價與紀律

- **不是零成本**：本 repo 就撞過兩個真實升級坑——[Spring 6.1 的 `-parameters` 旗標](../03-spring-to-spring-boot/exception-handling-and-validation.md)（Boot 3.2 災情）、[虛擬執行緒的 synchronized pinning](../07-concurrency/virtual-threads.md)。升級要讀 release notes 的 breaking changes
- **跨 8→11 的門檻最高**：模組系統、移除的 API（`javax.*` → `jakarta.*`）、反射封裝——這一跳最痛，之後的 LTS 升級平順得多
- **只跟 LTS**：別追每半年的版本——生產環境的穩定性遠比嘗鮮重要
- **升 runtime ≠ 升語法**：可以先把 8 的碼跑在 17 的 JVM 上（拿 runtime 紅利），再逐步採用新語法——兩件事可以分開

## 小結

- 節奏：每半年一版、**只跟四個 LTS**（8/11/17/21）——非 LTS 是預覽場
- **8** ＝現代 Java 地基（lambda/Stream/Optional，本 repo 預設）；**11** ＝務實升級（var、runtime 紅利、支援）；**17** ＝語言豐收（record/sealed/switch/text block）；**21** ＝virtual threads 的範式轉移
- 升級回報：更短的碼、免費的 runtime 加速、新並發模型、活在有支援的版本上
- 代價：8→11 門檻最高（模組/jakarta）、要讀 breaking changes（本 repo 實測兩坑）、只跟 LTS、升 runtime 與升語法可分開
- 這本筆記其實一路在講「8 之後的世界」——這篇只是把地圖畫出來

---

**這是本 repo Roadmap 的最後一篇。** 從 [01 JVM](../01-jvm/what-is-jvm.md) 到這裡，七個技術章＋工程實務，一條線貫穿：**Java 的每一個「魔法」都是機制、每一個「最佳實踐」都有實測的理由**。願這本筆記對讀它的人——無論是準備面試、還是想把「會用」變成「懂了」——都是一張夠用的地圖。

## 常見面試題

1. Java 的 LTS 是什麼？現在有哪幾個？（提示：長期支援、每半年一版只跟 LTS；8/11/17/21）
2. 從 Java 8 升到 17，最值得用的新特性有哪些？（提示：record、switch 表達式、text block、var；runtime 的 GC）
3. Java 21 最重要的變化是什麼？為什麼重要？（提示：virtual threads；重定義「thread 很貴」的前提）

## 延伸閱讀

- [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) — LTS 與支援期限的官方時間表
- [The Arrival of Java 21（官方）](https://blogs.oracle.com/java/post/the-arrival-of-java-21) — 21 的特性總覽
