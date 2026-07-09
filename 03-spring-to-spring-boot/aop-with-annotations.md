# 用 Annotation 設定 AOP

## 前言

需求：全站的關鍵方法都要記執行時間。直覺寫法是每個方法頭尾各加一行計時——一百個方法就是兩百行複製貼上，而且業務碼被監控碼淹沒。

本章已經看過 AOP 的兩個成品：[`@Transactional`](transactional-propagation-and-pitfalls.md) 和[它的 proxy 產地](bean-lifecycle-and-scope.md)。這篇換你當生產者——**自己寫一個切面**，用一個自訂註解讓「計時」這件事從兩百行變成一個 class；順便實測一個很多人不知道的事實：**你自己寫的切面，跟 @Transactional 死在同一個地方**。

## 技術背景

### 詞彙先對齊（白話版）

| 術語 | 白話 | 在計時例子裡 |
|---|---|---|
| Aspect（切面） | 「橫切邏輯」的載體 class | `TimingAspect` |
| Advice（通知） | 切進去之後**做什麼**＋**什麼時機** | `@Around` 的那個方法 |
| Pointcut（切入點） | **切在哪些方法**的篩選條件 | `@annotation(ExecutionTime)` |
| Join point（連接點） | 被切中的那次方法呼叫 | `ProceedingJoinPoint` |

### 五種 advice 時機

| 註解 | 時機 | 備註 |
|---|---|---|
| `@Before` / `@After` | 方法前／後（後者無論成敗） | 簡單場景 |
| `@AfterReturning` / `@AfterThrowing` | 正常返回／拋例外時 | 拿得到回傳值／例外 |
| `@Around` | **環繞**整個方法 | 最強：能改參數、改回傳、吞例外、甚至**不執行原方法** |

`@Around` 的能力伴隨一條軍令：**必須呼叫 `proceed()` 並回傳其結果**——忘了呼叫，原方法就被無聲吃掉（比忘了寫 return 更難查，因為編譯全過）。

### Pointcut 表達式：常用三款就夠

```java
@Around("@annotation(demo.ExecutionTime)")            // 標了某註解的方法（最精準，推薦）
@Around("execution(* com.example.service.*.*(..))")   // 某 package 下所有方法（傳統，容易切太寬）
@Around("within(com.example.service..*)")             // 某 package（含子包）內
```

推薦**自訂註解**當開關：要監控哪個方法就標哪個——比 `execution` 的字串匹配精準、重構安全、意圖寫在方法上一目瞭然。

### 機制沒有新東西：還是那個 proxy

切面生效的原理與本章一路講的完全相同：[BeanPostProcessor 產出 proxy](bean-lifecycle-and-scope.md)、呼叫先進 proxy 走 interceptor 鏈、再轉呼叫本尊。推論也完全相同——**[deep 篇](deep-transactional-self-invocation.md)的所有限制原樣適用於你的切面**：self-invocation 切不到（實測見案例二）、final 方法切不到、內部 `new` 的物件切不到。`@Transactional` 只是官方寫好的一個切面，你我的切面沒有特權。

## 實際案例

驗證環境：spring-aspects 6.1（Docker Maven 實測）。

### 案例一：從兩百行到一個 class

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ExecutionTime { }                              // 自訂開關註解

@Aspect
class TimingAspect {
    @Around("@annotation(demo.ExecutionTime)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object result = pjp.proceed();                    // 軍令：必呼叫、必回傳
        System.out.printf("⏱ %s 耗時 %d ms%n",
                pjp.getSignature().toShortString(), (System.nanoTime() - t0) / 1_000_000);
        return result;
    }
}
```

業務方法只需一個標記：

```java
@ExecutionTime
public void generate() { ... }
```

實測輸出：

```
產生報表中…
⏱ ReportService.generate() 耗時 121 ms
```

呼叫端與被呼叫端都不知道計時的存在——這就是「橫切」的意思。

### 案例二：你的切面也逃不過 self-invocation

```java
public void outer() {
    this.generate();      // this 呼叫標了 @ExecutionTime 的方法
}
```

實測：

```
產生報表中…              ← 方法照跑
（沒有 ⏱ 那一行）         ← 切面無聲消失
```

跟 [@Transactional 的失效](transactional-propagation-and-pitfalls.md)一字不差的劇本：this 直達本尊、proxy 不在路徑上。**尤其陰險的是「無聲」**——計時切面失效沒有任何錯誤，只是監控圖上少了一個數據點，可能好幾個月沒人發現。

## 技術優缺點

### AOP 適合的（橫切關注點）

日誌、耗時監控、交易（`@Transactional`）、快取（`@Cacheable`）、權限檢查、稽核——共同點：**跟業務邏輯無關、但到處都需要**。抽成切面，業務碼保持乾淨、橫切邏輯集中一處可改。

### 代價與紀律

- **行為隱形**：讀業務碼看不到切面的存在——除錯時「這 log 誰印的」「這方法怎麼變慢了」要想到切面層。對策：切面數量克制、用自訂註解讓切入點**可見**（方法上的 `@ExecutionTime` 就是線索）
- **proxy 限制全套繼承**：self-invocation（實測）、final、非 Spring 管理的物件——寫切面前先複習 [deep 篇](deep-transactional-self-invocation.md)
- **pointcut 切太寬是災難**：`execution(* com..*.*(..))` 一不小心把 getter、健康檢查全切進去——效能與 log 噪音雙輸。從最窄的註解式開始
- `@Around` 的軍令（proceed 必呼叫必回傳）——code review 必查

## 小結

- 詞彙四件套：**Aspect**（載體）、**Advice**（做什麼＋時機）、**Pointcut**（切在哪）、**Join point**（那次呼叫）
- 五種時機記兩個常用：簡單用 `@Before`/`@After`，要控制全程用 `@Around`——**proceed 必呼叫、結果必回傳**
- Pointcut 首選**自訂註解**（精準、重構安全、意圖可見），`execution` 字串是切太寬的高風險區
- 機制就是本章的 proxy——**你的切面與 @Transactional 同享所有限制**：self-invocation 實測無聲失效
- 適用判準一句話：跟業務無關、但到處都要——抽切面；否則老實寫在方法裡

03 章的閱讀動線至此成形：從[演進史](spring-evolution.md)、[自動配置](spring-boot-autoconfiguration.md)、[生命週期](bean-lifecycle-and-scope.md)到 [proxy 深水區](deep-transactional-self-invocation.md)——一條「Spring 的魔法都是機制」的線貫穿到底。

## 常見面試題

1. AOP 的核心概念（aspect/advice/pointcut）各是什麼？（提示：載體／做什麼＋時機／切在哪）
2. `@Around` 比其他 advice 強在哪？有什麼義務？（提示：控制全程；proceed 軍令）
3. 自己寫的切面會遇到 self-invocation 問題嗎？（提示：會——同一個 proxy 機制，實測無聲失效）

## 延伸閱讀

- [Spring Framework 官方文件：Aspect Oriented Programming](https://docs.spring.io/spring-framework/reference/core/aop.html) — pointcut 表達式的完整語法
- [從 AOP proxy 看 @Transactional self-invocation 失效](deep-transactional-self-invocation.md) — 本篇機制與限制的深水區版本
