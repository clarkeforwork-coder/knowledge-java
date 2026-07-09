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
| [記憶體：Stack 與 Heap](01-jvm/memory-stack-and-heap.md) | 🔰 | ✅ |
| [GC 基礎與分代回收](01-jvm/gc-basics-and-generations.md) | 🔰 | ✅ |
| [事故排查工具箱：jps、jstack、jmap 與 heap dump](01-jvm/troubleshooting-toolbox.md) | 🔰 | ✅ |
| [ClassLoader 的開放性：從 Tomcat 隔離到 Native Image 的死穴](01-jvm/deep-classloader.md) | 🔬 | ✅ |
| [GC 演算法比較（G1、ZGC）](01-jvm/deep-gc-algorithms.md) | 🔬 | ✅ |
| [進階剖析：JFR 與 profiler](01-jvm/deep-jfr-profiling.md) | 🔬 | ✅ |

### 02 - Java 語言核心

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [資料型態：基本型別 vs 參考型別](02-language-core/data-types.md) | 🔰 | ✅ |
| [Autoboxing 與 Unboxing](02-language-core/autoboxing-unboxing.md) | 🔰 | ✅ |
| [經典陷阱：迴圈裡共用同一個物件](02-language-core/pitfall-shared-reference-in-loop.md) | 🔰 | ✅ |
| [浮點數與 BigDecimal](02-language-core/floating-point-and-bigdecimal.md) | 🔰 | ✅ |
| [String 與 StringBuilder](02-language-core/string-and-stringbuilder.md) | 🔰 | ✅ |
| [例外處理](02-language-core/exception-handling.md) | 🔰 | ✅ |
| [介面 vs 抽象類別](02-language-core/interface-vs-abstract-class.md) | 🔰 | ✅ |
| [static 與 final](02-language-core/static-and-final.md) | 🔰 | ✅ |
| [enum 的正確姿勢](02-language-core/enum-done-right.md) | 🔰 | ✅ |
| [Optional](02-language-core/optional.md) | 🔰 | ✅ |
| [record 與不可變物件](02-language-core/record-and-immutability.md) | 🔰 | ✅ |

### 03 - Spring 到 Spring Boot

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [Spring 的演進：XML → Annotation → Boot](03-spring-to-spring-boot/spring-evolution.md) | 🔰 | ✅ |
| [Spring Boot 自動配置](03-spring-to-spring-boot/spring-boot-autoconfiguration.md) | 🔰 | ✅ |
| [用 Annotation 設定 AOP](03-spring-to-spring-boot/aop-with-annotations.md) | 🔰 | ✅ |
| [Bean 生命週期與 Scope](03-spring-to-spring-boot/bean-lifecycle-and-scope.md) | 🔰 | ✅ |
| [@Transactional：傳播行為與常見失效情境](03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md) | 🔰 | ✅ |
| [Spring MVC 請求處理流程](03-spring-to-spring-boot/spring-mvc-request-flow.md) | 🔰 | ✅ |
| [統一例外處理與 Validation](03-spring-to-spring-boot/exception-handling-and-validation.md) | 🔰 | ✅ |
| [從 AOP proxy 看 @Transactional self-invocation 失效](03-spring-to-spring-boot/deep-transactional-self-invocation.md) | 🔬 | ✅ |

### 04 - 集合框架

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [集合框架總覽：Collection 與 Map 兩棵樹、選型決策](04-collections/collections-overview.md) | 🔰 | ✅ |
| [List：ArrayList vs LinkedList](04-collections/list-arraylist-vs-linkedlist.md) | 🔰 | ✅ |
| [Set：HashSet、LinkedHashSet、TreeSet 與去重的代價](04-collections/set-implementations.md) | 🔰 | ✅ |
| [Map：HashMap 基礎與正確使用](04-collections/map-hashmap-basics.md) | 🔰 | ✅ |
| [equals 與 hashCode 契約](04-collections/equals-hashcode-contract.md) | 🔰 | ✅ |
| [排序：Comparable vs Comparator](04-collections/sorting-comparable-comparator.md) | 🔰 | ✅ |
| [fail-fast 與 ConcurrentModificationException](04-collections/fail-fast-and-cme.md) | 🔰 | ✅ |
| [HashMap 內部原理：hash 擾動、擴容與樹化](04-collections/deep-hashmap-internals.md) | 🔬 | ✅ |

### 06 - Lambda 與 Stream

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [Functional Interface 與 Lambda](06-lambda-and-stream/functional-interface-and-lambda.md) | 🔰 | ✅ |
| [Stream：中間操作與終端操作](06-lambda-and-stream/stream-operations.md) | 🔰 | ✅ |
| [Collectors 實戰](06-lambda-and-stream/collectors-in-action.md) | 🔰 | ✅ |
| [Stream 常見誤用](06-lambda-and-stream/stream-pitfalls.md) | 🔰 | ✅ |

### 07 - 並發

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [Thread 基礎與生命週期](07-concurrency/thread-basics-and-lifecycle.md) | 🔰 | ✅ |
| [synchronized 與 volatile](07-concurrency/synchronized-and-volatile.md) | 🔰 | ✅ |
| [ExecutorService 與執行緒池參數](07-concurrency/executorservice-and-thread-pools.md) | 🔰 | ✅ |
| [並發集合：ConcurrentHashMap](07-concurrency/concurrent-collections.md) | 🔰 | ✅ |
| [CompletableFuture](07-concurrency/completablefuture.md) | 🔰 | ✅ |
| [Virtual Threads（Java 21）](07-concurrency/virtual-threads.md) | 🔰 | ✅ |
| [Java Memory Model 與 happens-before](07-concurrency/deep-jmm-happens-before.md) | 🔬 | ✅ |
| [synchronized 鎖升級](07-concurrency/deep-synchronized-lock-optimization.md) | 🔬 | ✅ |

### 05 - 泛型

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [泛型基礎與型別擦除](05-generics/generics-basics-and-type-erasure.md) | 🔰 | ✅ |
| [萬用字元與 PECS](05-generics/wildcards-and-pecs.md) | 🔰 | ✅ |
| [泛型與陣列的地雷](05-generics/generics-and-arrays.md) | 🔰 | ✅ |

### 08 - 資料存取

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [JDBC 與連線池](08-data-access/jdbc-and-connection-pool.md) | 🔰 | ✅ |
| [JPA/Hibernate：lazy loading 與 N+1](08-data-access/jpa-lazy-loading-and-n-plus-1.md) | 🔰 | ✅ |
| [交易與資料庫鎖](08-data-access/transactions-and-database-locks.md) | 🔰 | ✅ |
| [Hibernate 一級快取與 dirty checking](08-data-access/deep-hibernate-first-level-cache.md) | 🔬 | ✅ |

### 09 - 測試與工程實務

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [JUnit 5 基礎](09-testing-and-engineering/junit5-basics.md) | 🔰 | ✅ |

## Roadmap

各章先完成 🔰 軌；🔬 軌只挑最有價值的主題寫。章節資料夾於第一篇筆記落地時建立。

### 中期

### 長期

**09 - 測試與工程實務**（`09-testing-and-engineering/`）

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
