# 從 AOP proxy 看 @Transactional self-invocation 失效

## 前言

[@Transactional 篇](transactional-propagation-and-pitfalls.md)實測過：`this.innerTx()` 的交易註解直接蒸發。當時給的解釋是「this 是本尊不是 proxy」——但這句話值得追問三層：**proxy 到底長什麼樣？它跟本尊是什麼關係？為什麼 Spring 不能「聰明一點」讓 this 也走 proxy？**

這篇用實驗把 proxy 抓出來看（類名、物件同一性），追進 Spring 原始碼看交易攔截器掛在哪，最後回答那個「為什麼無解」——答案不在 Spring，在 Java 語言本身。

## 技術背景

### proxy 的兩種製造方式

| | JDK Dynamic Proxy | CGLIB |
|---|---|---|
| 原理 | 執行期實作**介面** | 執行期生成**子類**、覆寫方法 |
| 前提 | 目標要有介面 | 類別與方法不能 final |
| Spring 何時用 | 有介面且未強制 CGLIB | **Spring Boot 預設**（`proxyTargetClass=true`） |

CGLIB 的「子類」在實驗一看得到：類名是 `SelfFixService$$SpringCGLIB$$1`——貨真價實的另一個 class（[擦除篇](../05-generics/generics-basics-and-type-erasure.md)那套 bytecode 生成技術的另一個應用場景，也是 [ClassLoader 深入篇](../01-jvm/deep-classloader.md)「執行期產生 class 再載入」的實例）。

### 關鍵事實：proxy 和本尊是兩個物件

CGLIB 聽起來像「繼承覆寫」，容易誤會成「proxy 就是本尊的加強版、同一個物件」。**不是**。Spring 的做法是：

```
容器裡實際存在兩個物件：

  [Proxy 物件]  SelfFixService$$SpringCGLIB$$1     ← 注入給所有人的是它
      │  欄位幾乎全空，方法被覆寫成：
      │  「先走 interceptor 鏈（交易在這），再轉呼叫 ↓」
      ▼
  [Target 物件] SelfFixService                      ← 你寫的那個，真正幹活
         方法裡的 this ＝ 它自己
```

實驗一的三行輸出就是這張圖的存證：注入的是 `$$SpringCGLIB$$1`、方法內 `this` 是 `demo.SelfFixService`、兩者 `==` 為 **false**。

### 原始碼追讀：交易掛在哪、呼叫怎麼轉

三個關鍵位置（spring-framework 原始碼）：

1. **proxy 的出生**：`AbstractAutoProxyCreator.postProcessAfterInitialization()`——[生命週期篇](bean-lifecycle-and-scope.md)說的「BPP after 是 proxy 產房」的具體地址。它檢查 bean 有沒有匹配的 advisor（如 `@Transactional`），**有才建 proxy**——實驗零的 ProbeService 沒有交易需求，容器就直接給本尊（`demo.ProbeService`，沒有 CGLIB 後綴）
2. **攔截的入口**：`CglibAopProxy.DynamicAdvisedInterceptor.intercept()`——proxy 被呼叫時進到這裡，組出 interceptor 鏈後沿鏈執行，最後 `invokeJoinpoint()` 轉呼叫 **target 物件**的原方法
3. **交易本體**：`TransactionInterceptor.invoke()` → `TransactionAspectSupport.invokeWithinTransaction()`——開交易、`invocation.proceed()`、看例外決定 commit/rollback。[@Transactional 篇](transactional-propagation-and-pitfalls.md)的一切行為（回滾規則、rollback-only 標記）都在這個類別裡

### 為什麼無解：這是 Java 的 this，不是 Spring 的

呼叫轉進 target 物件之後，方法裡寫的 `this.innerTx()` 編譯成 `invokevirtual`，收件人是**當前物件**——也就是 target 本身。這是 JVM 層的語意，**Spring 的程式碼在這一刻根本不在呼叫路徑上**，沒有任何掛鉤點。「聰明一點」的前提是有機會插手，而語言沒有給這個機會。

真要讓內部呼叫也被攔，只有一條路：**不用 proxy，改寫 bytecode**——AspectJ 編譯期／載入期織入（weaving），把切面直接編進你的 class。這就不是「代理模式」而是「改造本尊」，成本是建置流程複雜化——絕大多數專案不值得。

## 實際案例

驗證環境：延用 @Transactional 篇的 H2 專案（Docker Maven 實測）。

### 實驗零：沒有切面需求，就沒有 proxy

```
ProbeService（無 @Transactional）：demo.ProbeService
```

沒有後綴——Spring 只給需要的 bean 建 proxy，其他的注入本尊。「所有 bean 都被代理」是誤解。

### 實驗一：三行存證

```
容器注入的物件類別：demo.SelfFixService$$SpringCGLIB$$1   ← proxy（CGLIB 子類）
方法內 this 的類別  ：demo.SelfFixService                  ← 本尊
this == 注入的物件？：false                                 ← 兩個不同的物件！
```

這三行值得反覆看：全世界拿到的都是 proxy，唯獨**你的方法內部**活在本尊裡——self-invocation 失效的完整解釋就濃縮在這個 `false`。

### 實驗二：修復方案實測——注入自己的 proxy

```java
@Autowired @Lazy SelfFixService self;          // 跟容器要「自己」——拿到的是 proxy

public void outerBroken() { this.tx(); }       // ❌
public void outerFixed()  { self.tx(); }       // ✅ 繞出去再進來，走 proxy
```

實測：

```
this.tx()  ：資料留下 1 筆（交易失效）
self.tx()  ：資料留下 0 筆（走 proxy，回滾生效）
```

`self` 欄位裝的是 proxy（跟全世界拿到的同一個），呼叫 `self.tx()` 等於「繞出去再進來」——interceptor 鏈重新在路徑上。`@Lazy` 是為了打破自我注入的循環依賴。

### 修復方案的選擇排序

| 方案 | 做法 | 評價 |
|---|---|---|
| **拆 bean**（首選） | 把 `@Transactional` 方法搬到另一個 bean | 最乾淨——通常也暴露了「這方法本來就該是另一層」的設計訊號 |
| self-injection | `@Autowired @Lazy` 注入自己（實驗二） | 可行但怪味：一個類別依賴自己，讀者會愣住 |
| `AopContext.currentProxy()` | 需 `exposeProxy = true` | 業務碼耦合 Spring API，最後手段 |
| AspectJ weaving | 織入 bytecode，根治 | 建置複雜度大增，除非全專案有此需求 |

## 技術優缺點

### Proxy-based AOP 的取捨（Spring 的選擇）

- ✅ **零建置侵入**：純執行期完成，不動編譯流程——這是 Spring 能普及的關鍵決策
- ✅ 按需代理（實驗零）：不需要的 bean 零開銷
- ❌ **結構性盲區**：self-invocation、final 方法、內部 new 的物件——proxy 模式的邊界就是「呼叫有沒有經過它」
- ❌ 兩個物件的迷惑性：debug 時 watch 視窗裡的 `$$SpringCGLIB$$` 與 stack frame 裡的本尊，不懂機制的人會以為見鬼

### 回到實務

- 看到 `$$SpringCGLIB$$` 不再慌張——那是 proxy，你的中斷點該下在本尊的方法裡
- `@Transactional` 失效排查加一招：**印 `this.getClass()` 和注入參考的類名**，一行分辨呼叫有沒有走 proxy
- 設計層的啟示：**需要「內部呼叫也有交易」常是方法職責過重的訊號**——拆 bean 不只是 workaround，往往是更好的設計

## 小結

- Spring 的 CGLIB proxy 是**執行期生成的子類**，且 proxy 與本尊是**兩個物件**（實測 `$$SpringCGLIB$$1` vs 本尊、`==` 為 false）
- 交易的完整路徑：BPP 產出 proxy（`AbstractAutoProxyCreator`）→ 呼叫進 `DynamicAdvisedInterceptor.intercept` → `TransactionInterceptor` 開關交易 → 轉呼叫本尊
- **self-invocation 無解的根源在語言**：`this.method()` 是 JVM 的 invokevirtual，Spring 不在路徑上——除非 AspectJ 改寫 bytecode
- 沒有切面需求的 bean 不會被代理（實驗零）
- 修復排序：**拆 bean ＞ self-injection（實測有效）＞ AopContext ＞ AspectJ**——而「需要修」本身常是設計訊號

## 常見面試題

1. Spring AOP 的 CGLIB proxy 和目標物件是同一個物件嗎？怎麼證明？（提示：兩個；類名對照＋ `==` 實測）
2. 為什麼 self-invocation 在 proxy 模型下原理上無解？（提示：invokevirtual 的 this 語意、Spring 不在呼叫路徑上、AspectJ 是唯一例外）
3. self-invocation 的修復方案有哪些？怎麼選？（提示：四種排序；拆 bean 是設計訊號）

## 延伸閱讀

- [Spring Framework 官方文件：Understanding AOP Proxies](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html#aop-understanding-aop-proxies) — 官方親自演示 self-invocation 問題的章節
- [spring-framework 原始碼：TransactionAspectSupport](https://github.com/spring-projects/spring-framework/blob/main/spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java) — `invokeWithinTransaction` 的第一手實作
