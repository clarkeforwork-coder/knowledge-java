# 用 Annotation 設定 AOP

## 範例：方法執行時間記錄

```java
@Aspect
@Component
public class MethodExecutionTimeAspect {

    @Around("@annotation(com.example.demo.annotation.ExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object proceed = joinPoint.proceed();   // 執行原方法

        long executionTime = System.currentTimeMillis() - start;
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();
        System.out.println(className + "." + methodName + " executed in " + executionTime + "ms");

        return proceed;
    }
}
```

任何標了自定義 `@ExecutionTime` 註解的方法，執行時都會被這個切面環繞、記錄耗時——
呼叫端和被呼叫端都不需要知道這件事。

## 怎麼定義一個切面

1. 建一個類別，加上 `@Aspect` 標註為切面、`@Component` 讓 Spring 掃描到
2. 用 `@Pointcut` 定義切入點（哪些方法要被攔截），或直接寫在通知註解裡
3. 用通知註解定義時機：
   - `@Before` — 方法執行前
   - `@After` — 方法執行後（無論成功失敗）
   - `@AfterReturning` / `@AfterThrowing` — 正常返回 / 拋出例外時
   - `@Around` — 環繞整個方法，最靈活（要記得呼叫 `proceed()`）

## AOP 適合做什麼

橫切關注點（cross-cutting concerns）：日誌、耗時監控、交易管理（`@Transactional`
本身就是 AOP）、快取（`@Cacheable`）、權限檢查。

共通點：**跟業務邏輯無關、但到處都需要**的東西，抽成切面讓業務程式碼保持乾淨。
