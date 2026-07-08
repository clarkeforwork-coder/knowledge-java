# @Transactional：傳播行為與常見失效情境

## 前言

`@Transactional` 大概是企業 Java 裡「以為有效但其實沒生效」比率最高的註解。最常見的事故報告長這樣：「方法標了 `@Transactional`，裡面炸了 RuntimeException，**資料卻沒回滾**」——而且測試環境還常常測不出來。

[上一篇](bean-lifecycle-and-scope.md)留的鑰匙在這裡開鎖：`@Transactional` 的一切能力**來自 proxy**、一切失效**也來自 proxy**。這篇用 H2 資料庫把四大情境全部真實重現：self-invocation、checked exception、REQUIRES_NEW、以及最陰險的「接住例外還是被回滾」。

## 技術背景

### 機制一句話：交易是 proxy 包上去的

[生命週期篇](bean-lifecycle-and-scope.md)講過，AOP proxy 誕生於 BeanPostProcessor——你以為注入的是 `OrderService`，其實是它的代理：

```
呼叫者 ──> [Proxy]                    ──> 真正的 OrderService.create()
            │ 開交易                        │ 你的程式碼
            │ （方法正常結束）提交           │
            └ （看到 RuntimeException）回滾 ┘
```

從這張圖可以**推導出所有失效情境**，共同根因只有兩種：

1. **呼叫沒有經過 proxy**——交易根本沒開
2. **例外沒有被 proxy 看見**（或不在回滾名單上）——回滾沒被觸發

### 失效清單：對照兩種根因

| 情境 | 根因 | 解法 |
|---|---|---|
| **self-invocation**：`this.method()` 呼叫自己的 `@Transactional` 方法 | this 是本尊不是 proxy——①| 拆到另一個 bean；或注入自己（`ObjectProvider`）；🔬 原始碼層解析見[深入篇](deep-transactional-self-invocation.md) |
| 方法不是 **public** | proxy 只攔 public——① | 改 public（CGLIB 技術上可攔 protected，但別依賴） |
| **checked exception** | 預設回滾名單只有 `RuntimeException` 和 `Error`——② | `rollbackFor = Exception.class` |
| **try-catch 吞掉例外** | proxy 看不見例外——② | 別吞；要吞就自己標 rollback（見下方 rollback-only 陷阱） |
| **@Async／自開 thread 裡操作** | 交易綁在 **ThreadLocal**，新 thread 沒有你的交易——① | 交易邊界內不換 thread（[07 章](../07-concurrency/thread-basics-and-lifecycle.md)的 ThreadLocal 知識在此落地） |
| final 方法／class | CGLIB 靠繼承覆寫，final 蓋不掉——① | 拿掉 final |

「checked 不回滾」的歷史邏輯：checked exception 被視為「可預期的業務結果」（如餘額不足），RuntimeException 才是「壞掉了」。你可以不同意這個哲學（很多團隊直接全域 `rollbackFor = Exception.class`），但必須知道它的存在。

### 傳播行為：交易相遇時的規則

方法 A（有交易）呼叫方法 B（也標了 `@Transactional`）——B 要加入 A 的交易、還是自己開一個？這就是 propagation：

| 傳播行為 | 語意 | 場景 |
|---|---|---|
| `REQUIRED`（預設） | 有就加入、沒有就開新 | 絕大多數情況 |
| `REQUIRES_NEW` | **掛起**現有交易，自己開一個獨立的 | 稽核 log、通知——主流程失敗它也要活（實測見案例三） |
| `NESTED` | 在現有交易裡設 savepoint，可局部回滾 | 部分失敗可接受的批次 |
| `SUPPORTS` / `NOT_SUPPORTED` / `NEVER` / `MANDATORY` | 有就用／掛起不用／有就報錯／沒有就報錯 | 邊界檢查用，少見 |

### rollback-only：最陰的一個

`REQUIRED` 之下，內外層共用**同一個**交易。內層方法拋了 RuntimeException——它的 proxy 在例外穿出時就把共用交易**標記為 rollback-only**。外層就算 catch 住例外繼續走，提交時也會發現這個標記——炸出 `UnexpectedRollbackException`，資料照樣回滾（實測見案例四）。

教訓：**「catch 住內層的例外」不等於「救回這筆交易」**——標記已經蓋下去了。真要「內層失敗不影響外層」，內層用 `REQUIRES_NEW`（獨立交易，死它自己的）。

## 實際案例

驗證環境：spring-jdbc 6.1 ＋ H2 in-memory（Docker Maven 實測），每案例斷言資料庫裡實際留下幾筆。

### 案例一：self-invocation——最著名的失效

```java
public void outerNoTx() { this.innerTx(); }        // ← this 是本尊，不是 proxy

@Transactional
public void innerTx() { insert("A"); throw new RuntimeException("boom"); }
```

實測：

```
經 this.innerTx() 呼叫：資料留下 1 筆（回滾失效！）
直接經 proxy 呼叫    ：資料留下 0 筆
```

同一個方法、同一個註解——差別只在**呼叫路徑有沒有經過 proxy**。`this.innerTx()` 是普通的 Java 方法呼叫，proxy 全程不知情，交易從未存在。

### 案例二：checked exception 預設不回滾

```java
@Transactional
public void checkedThrow() throws Exception { insert("B"); throw new Exception("checked"); }
```

實測：

```
throw Exception          ：資料留下 1 筆（沒回滾！）
加 rollbackFor=Exception ：資料留下 0 筆
```

例外有被 proxy 看見，但**不在回滾名單上**——proxy 幫你提交了。一個參數的差別。

### 案例三：REQUIRES_NEW——log 在災難中倖存

```java
@Transactional
public void orderWithLog() {
    insert("ORDER");
    logService.log();                    // 另一個 bean，REQUIRES_NEW
    throw new RuntimeException("order failed");
}
```

實測：

```
外層炸掉後，ORDER 留下 0 筆、LOG 留下 1 筆
```

外層交易回滾帶走了 ORDER，但 LOG 活在自己的獨立交易裡、早已提交——「主流程失敗也要留下稽核紀錄」的標準解。注意 `log()` 在**另一個 bean**——放同一個 class 就撞上案例一。

### 案例四：接住例外，還是被回滾

```java
@Transactional
public void outerCatches() {
    insert("D");
    try { helper.innerFails(); }                    // 內層 @Transactional，拋 RuntimeException
    catch (Exception e) { log("接住了"); }           // 外層以為沒事了
}
```

實測：

```
  外層 catch 住了：inner boom
提交時炸出：UnexpectedRollbackException
外層明明接住了例外，資料留下 0 筆
```

三重打擊：例外你接了、提交時還是炸、資料還是沒了。內層 proxy 在例外穿出的瞬間已把共用交易標成 rollback-only——外層的 catch 只是接住了訊使，救不回已蓋章的判決。這個例外訊息（`Transaction silently rolled back because it has been marked as rollback-only`）值得記住，線上看到它就知道劇本。

## 技術優缺點

### 宣告式交易買到的

- **業務碼零污染**：開關提交回滾全在 proxy，方法裡只剩業務
- **傳播行為的表達力**：REQUIRES_NEW 一個屬性解掉「稽核必留」這類需求

### 代價與紀律

- **失效清單全是暗雷**：每一條都編譯過、測試常常也過（測試沒走 proxy 路徑、沒驗資料）——**對策是驗收資料而不是驗收例外**（本篇每個實測都在數資料庫裡的筆數，就是這個原因）
- **交易即連線**：`@Transactional` 方法從開始到結束佔著一條 DB 連線——**在交易裡呼叫遠端 API、發 MQ 是連線池殺手**（連線池 50 條、API 慢 2 秒，25 QPS 就把池抽乾）。交易邊界越小越好，慢操作放交易外
- **proxy 模型的天花板**：self-invocation 這類問題不是 bug 是結構——想真正理解為什麼繞不過去，見 🔬 [從 AOP proxy 看 @Transactional self-invocation 失效](deep-transactional-self-invocation.md)

## 小結

- 一切從一句話推導：**交易是 proxy 包上去的**——失效只有兩種根因：呼叫沒過 proxy、例外沒被 proxy 看見
- 失效四天王實測：**self-invocation**（this 呼叫，1 筆 vs 0 筆）、**checked 不回滾**（rollbackFor 修）、**非 public**、**換 thread**（交易綁 ThreadLocal）
- 傳播行為記三個：`REQUIRED` 預設共生、`REQUIRES_NEW` 獨立生死（log 倖存實測）、`NESTED` savepoint
- **rollback-only 陷阱**：內層炸過的共用交易，外層 catch 也救不回——`UnexpectedRollbackException` 實測為證
- 紀律：交易邊界越小越好、慢操作（遠端呼叫）放交易外、**測交易要驗資料不是驗例外**

下一站把視角拉到請求的入口：一個 HTTP request 從進到出，經過了哪些關卡才到你的 `@GetMapping`？見 [Spring MVC 請求處理流程](spring-mvc-request-flow.md)。

## 常見面試題

1. `@Transactional` 有哪些失效情境？共同根因是什麼？（提示：兩種根因分類——沒過 proxy／例外沒被看見）
2. 預設的回滾規則是什麼？為什麼 checked exception 不回滾？（提示：RuntimeException＋Error；「可預期業務結果」哲學）
3. REQUIRED 和 REQUIRES_NEW 差在哪？內層炸了外層 catch 住會怎樣？（提示：共用 vs 獨立；rollback-only 標記、UnexpectedRollbackException）

## 延伸閱讀

- [Spring Framework 官方文件：Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html) — proxy 機制與回滾規則的官方說明
- [Spring Framework 官方文件：Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) — 七種傳播行為的權威定義
