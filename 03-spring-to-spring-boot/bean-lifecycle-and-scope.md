# Bean 生命週期與 Scope

## 前言

每天寫 `@Autowired`，但停下來想過嗎：注入進來的那個物件是**誰 new 的、什麼時候 new 的、new 完之後又經歷了什麼**？

兩個真實事故最能暴露這個黑盒：在**建構子裡**使用 `@Autowired` 的欄位——NPE，明明有標註解為什麼是 null？在 singleton 裡注入一個 prototype bean——說好的「每次都是新的」呢，怎麼永遠同一個？這篇打開 IoC 容器的生產線，兩個事故都用實測重現、講清楚、給解法。

## 技術背景

### Bean 不是 new 出來就完事：一條流水線

Spring 建一個 bean，是一條有明確工序的流水線：

```
實例化（建構子）
  │      ← 此刻 @Autowired 欄位還是 null！
依賴注入（populate properties）
  │
Aware 回調（BeanNameAware、ApplicationContextAware…）
  │
BeanPostProcessor #before
  │
初始化回調（順序固定）：
  ① @PostConstruct
  ② InitializingBean.afterPropertiesSet()
  ③ @Bean(initMethod = "...")
  │
BeanPostProcessor #after   ← ★ AOP proxy 在這裡誕生
  │
就緒，進容器服役
  │
容器關閉：④ @PreDestroy → ⑤ destroyMethod
```

兩個最有實務價值的節點：

- **建構子執行時，依賴注入還沒發生**（用 field injection 時）——這就是「建構子裡用 `@Autowired` 欄位 NPE」的答案。要在初始化時用依賴：要嘛改建構子注入（依賴以參數進來，永遠不會 null，這也是官方建議），要嘛把邏輯放到 `@PostConstruct`（實測證明此刻已注入完）
- **BeanPostProcessor 的 after 階段是 AOP proxy 的產房**——`@Transactional`、`@Async` 這些註解的魔法，都是這一站把你的 bean **偷偷換成 proxy** 實現的（[既有的 AOP 筆記](aop-with-annotations.md)講了怎麼用，這裡是它的出生地）。這個事實是下一篇 `@Transactional` 失效問題的鑰匙

### 三個初始化回調：用哪個？

順序是鐵的（實測見案例一）：`@PostConstruct` → `afterPropertiesSet` → init-method。選擇上：**首選 `@PostConstruct`**（標準註解、不耦合 Spring 介面）；`InitializingBean` 是舊時代產物；`initMethod` 保留給「你動不了原始碼的第三方類別」。

### Scope：這個 bean 一次做幾份

| Scope | 語意 | 注意 |
|---|---|---|
| `singleton`（預設） | **每個容器**一份——不是每個 JVM | 無狀態才安全：所有 request thread 共用它（[07 章](../07-concurrency/thread-basics-and-lifecycle.md)的共享可變狀態警告直接適用） |
| `prototype` | 每次向容器要，就 new 一份 | **Spring 不管它的死**——不會呼叫 `@PreDestroy`，資源清理自己來 |
| `request` / `session` | 每個 HTTP 請求／session 一份 | Web 環境限定 |

### Scope 地雷：singleton 裡的 prototype

```java
@Component @Scope("prototype") class Task { }

@Component class Worker {
    @Autowired Task task;    // ❌ 注入只發生一次——singleton 建立的那次
}
```

直覺以為「每次用 task 都是新的」，實際上 **Worker 是 singleton，它的欄位只在流水線的注入站被填一次**——之後拿到的永遠是同一個 Task（實測見案例二）。prototype 的「每次都新」只在**每次向容器要**時成立，而欄位注入只跟容器要了一次。

解法按推薦排序：

```java
@Autowired ObjectProvider<Task> tasks;      // ✅ 首選：每次 getObject() 都跟容器要
Task t = tasks.getObject();

@Lookup abstract Task task();               // 次選：Spring 幫你覆寫這個方法

@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)  // 注入的是代理
```

## 實際案例

驗證環境：spring-context 6.1（最小 Maven 專案，Docker `maven:3.9-eclipse-temurin-17` 執行）。

### 案例一：生命週期全程直播

一個 bean 同時實作全部回調，每站報到；`dep` 用 field injection：

```
1. 建構子（此刻 dep = null）              ← 有 @Autowired 但還沒輪到注入站
2. @PostConstruct（此刻 dep = demo.Dep@6f1de4c7）   ← 注入完成了
3. afterPropertiesSet
4. init-method
--- 容器就緒，開始用 bean ---
5. @PreDestroy                             ← ctx.close() 觸發
6. destroy-method
```

兩個實測重點：**建構子時 `dep` 是 null、`@PostConstruct` 時已就位**——「初始化邏輯放哪」的答案一目瞭然；三個 init 回調的順序與流水線圖完全一致。

### 案例二：prototype 的「每次都新」在哪裡失效

```java
Worker w = ctx.getBean(Worker.class);        // Worker 是 singleton
w.direct() == w.direct();                    // 直接注入的 Task
w.fresh()  == w.fresh();                     // ObjectProvider 拿的 Task
```

實測：

```
直接注入：兩次拿到同一個？true    ← prototype「失效」——注入只發生一次
Provider ：兩次拿到同一個？false   ← 每次 getObject() 都是新的
```

嚴格說 prototype 沒失效——是**注入的時機**只有一次。`ObjectProvider` 把「向容器要」這個動作推遲到每次呼叫，語意才對齊。

## 技術優缺點

### 容器管生命週期，買到什麼

- **時機正確的初始化**：`@PostConstruct` 保證依賴就緒——比起在建構子裡賭注入順序，這是結構性的解法
- **資源的對稱清理**：`@PreDestroy` 隨容器關閉自動觸發（連線池、排程器的歸還點）
- **擴充點**：BeanPostProcessor 讓 AOP、`@Transactional` 這些橫切能力「無感」地織入——這是 Spring 生態一半魔法的地基

### 代價與地雷

- **魔法的除錯成本**：不懂流水線的人，看到「建構子 NPE」「prototype 不新」只能靠猜——這篇的兩個實測就是解藥
- **singleton 的並發責任**：容器只保證「一份」，不保證「執行緒安全」——有狀態的 singleton 就是 [07 章](../07-concurrency/synchronized-and-volatile.md)的共享可變狀態
- **prototype 的死無人管**：`@PreDestroy` 不會被呼叫，有資源的 prototype 要自己收
- **啟動成本**：流水線每站都有反射與檢查——bean 越多啟動越慢（[what-is-jvm](../01-jvm/what-is-jvm.md) 講過的 JVM 啟動成本之上再加一層）

## 小結

- Bean 的一生：**建構子 → 依賴注入 → Aware → BPP before → `@PostConstruct` → `afterPropertiesSet` → init-method → BPP after（AOP proxy 產房）→ 服役 → `@PreDestroy` → destroy-method**（順序實測為證）
- **建構子時注入還沒發生**（field injection 下是 null）——初始化邏輯放 `@PostConstruct`，或改用建構子注入
- singleton 是**每容器**一份且不保證執行緒安全；prototype 每次要才新、**且 Spring 不管銷毀**
- **singleton 注入 prototype 只注入一次**（實測 true/false 對照）——要「每次都新」用 `ObjectProvider`
- AOP proxy 誕生於 BeanPostProcessor——這句話是下一篇的鑰匙

proxy 在流水線裡偷偷替換了你的 bean——那麼「自己呼叫自己的方法」會經過 proxy 嗎？`@Transactional` 最著名的失效情境就藏在這個問題裡：見規劃中的〈@Transactional：傳播行為與常見失效情境〉。

## 常見面試題

1. 描述 Spring bean 的生命週期。（提示：流水線各站＋三個 init 回調的固定順序）
2. `@PostConstruct` 和建構子有什麼差別？什麼邏輯該放哪？（提示：實測 dep = null vs 已注入）
3. singleton bean 裡注入 prototype bean 會怎樣？怎麼解？（提示：注入只發生一次；ObjectProvider／@Lookup／proxyMode）

## 延伸閱讀

- [Spring Framework 官方文件：Bean Lifecycle Callbacks](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html) — 回調機制與順序的官方定義
- [Spring Framework 官方文件：Bean Scopes](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html) — 各 scope 語意與 scoped proxy
