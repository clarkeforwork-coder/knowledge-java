# knowledge-java

Java 技術筆記，整理自我準備的 Java 教育訓練教材（通用化公開版）。

> Part of my [portfolio](https://github.com/clarkeforwork-coder/portfolio) —
> naming convention: `knowledge-*` = technical notes.

**範圍**：專注在 Java 語言與 Spring 生態。微服務、快取、訊息佇列等後端架構主題不在此 repo，未來以其他 `knowledge-*` repo 承接。

## 筆記深度：雙軌制

| 標記 | 軌道 | 說明 |
|---|---|---|
| 🔰 | 基礎（訓練主軌） | 概念說明、可執行範例、常見陷阱。每章以寫完 🔰 軌為「完整」 |
| 🔬 | 深入（選修軌） | 原始碼、底層機制、效能分析。檔名以 `deep-` 開頭，從對應的 🔰 筆記連結過去。寧缺勿濫 |

## 目錄

### 01 - JVM

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [What is JVM](01-jvm/what-is-jvm.md) | 🔰 | ✅ |
| [記憶體：Stack 與 Heap](01-jvm/memory-stack-and-heap.md) | 🔰 | 🔧 |
| [ClassLoader 的開放性：從 Tomcat 隔離到 Native Image 的死穴](01-jvm/deep-classloader.md) | 🔬 | ✅ |

### 02 - Java 語言核心

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [資料型態：基本型別 vs 參考型別](02-language-core/data-types.md) | 🔰 | 🔧 |
| [Autoboxing 與 Unboxing](02-language-core/autoboxing-unboxing.md) | 🔰 | 🔧 |
| [經典陷阱：迴圈裡共用同一個物件](02-language-core/pitfall-shared-reference-in-loop.md) | 🔰 | 🔧 |
| [浮點數與 BigDecimal](02-language-core/floating-point-and-bigdecimal.md) | 🔰 | 🔧 |
| [String 與 StringBuilder](02-language-core/string-and-stringbuilder.md) | 🔰 | 🔧 |
| [例外處理](02-language-core/exception-handling.md) | 🔰 | 🔧 |

### 03 - Spring 到 Spring Boot

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [Spring 的演進：XML → Annotation → Boot](03-spring-to-spring-boot/spring-evolution.md) | 🔰 | 🔧 |
| [Spring Boot 自動配置](03-spring-to-spring-boot/spring-boot-autoconfiguration.md) | 🔰 | 🔧 |
| [用 Annotation 設定 AOP](03-spring-to-spring-boot/aop-with-annotations.md) | 🔰 | 🔧 |

## Roadmap

各章先完成 🔰 軌；🔬 軌只挑最有價值的主題寫。章節資料夾於第一篇筆記落地時建立。

### 近期

**04 - 集合框架**（`04-collections/`）

- 🔰 List：ArrayList vs LinkedList
- 🔰 Map：HashMap 基礎與正確使用
- 🔰 equals 與 hashCode 契約
- 🔰 排序：Comparable vs Comparator
- 🔰 fail-fast 與 ConcurrentModificationException
- 🔬 HashMap 內部原理（hash 擾動、擴容、樹化）

**06 - Lambda 與 Stream**（`06-lambda-and-stream/`）

- 🔰 Functional Interface 與 Lambda
- 🔰 Stream：中間操作與終端操作
- 🔰 Collectors 實戰
- 🔰 Stream 常見誤用（副作用、重複消費）

### 中期

**05 - 泛型**（`05-generics/`）

- 🔰 泛型基礎與型別擦除
- 🔰 萬用字元與 PECS
- 🔰 泛型與陣列的地雷

**07 - 並發**（`07-concurrency/`）

- 🔰 Thread 基礎與生命週期
- 🔰 synchronized 與 volatile
- 🔰 ExecutorService 與執行緒池參數
- 🔰 並發集合：ConcurrentHashMap
- 🔰 CompletableFuture
- 🔰 Virtual Threads（Java 21）
- 🔬 Java Memory Model 與 happens-before
- 🔬 synchronized 鎖升級

**03 - Spring 補深**

- 🔰 Bean 生命週期與 Scope
- 🔰 @Transactional：傳播行為與常見失效情境
- 🔰 Spring MVC 請求處理流程
- 🔰 統一例外處理與 Validation
- 🔬 從 AOP proxy 看 @Transactional self-invocation 失效

### 長期

**01 - JVM 補深**

- 🔰 GC 基礎與分代回收
- 🔬 GC 演算法比較（G1、ZGC）
- 🔬 診斷工具實戰（jstack / jmap / JFR）

**02 - 語言核心補完**

- 🔰 介面 vs 抽象類別
- 🔰 static 與 final
- 🔰 enum 的正確姿勢
- 🔰 Optional
- 🔰 record 與不可變物件

**08 - 資料存取**（`08-data-access/`）

- 🔰 JDBC 與連線池
- 🔰 JPA/Hibernate：lazy loading 與 N+1
- 🔰 交易與資料庫鎖
- 🔬 Hibernate 一級快取與 dirty checking

**09 - 測試與工程實務**（`09-testing-and-engineering/`）

- 🔰 JUnit 5 基礎
- 🔰 Mockito 與測試替身
- 🔰 Spring Boot Test 分層測試策略
- 🔰 重構與 Code Review 原則

**10 - Java 版本演進**（`10-java-versions/`，可選）

- 🔰 8 → 11 → 17 → 21 關鍵變化總覽

## 慣例

- 每篇筆記一個主題，結構依 [TEMPLATE.md](TEMPLATE.md)：
  - 🔰 基礎：情境開場 → 概念段落（先程式碼後解釋）→ 重點 → 常見面試題 → 延伸閱讀
  - 🔬 深入：問題起點 → 追蹤過程 → 結論 → 回到實務
- 深度：🔰 基礎（訓練主軌）/ 🔬 深入（選修軌，檔名 `deep-` 開頭）
- 🔰 筆記結尾以「🔬 想深入：[標題](deep-xxx.md)」連到對應的深入筆記
- 可執行範例採混合制：預設文內 snippet；「不跑看不出結果」的主題才在該章 `examples/` 附單檔 .java（JDK 11+ 單檔執行）
- 狀態：✅ 完成（符合模板）/ 🔧 待翻新（內容完成，尚未符合 [TEMPLATE.md](TEMPLATE.md)）/ 🚧 進行中 / 📝 待補
