# 例外處理

## 前言

事故排查最絕望的一種場景：資料明顯處理錯了，但 log **一片安靜**——沒有 stack trace、沒有 ERROR、什麼都沒有。三天後才發現兇手是一個空的 catch 區塊：

```java
try {
    processOrder(order);
} catch (Exception e) {
}                          // 例外死在這裡，屍體都不留
```

例外處理的語法五分鐘就學完，但「處理的紀律」是資深與新手的分水嶺。這篇從體系講到紀律，每條規矩都配一個爆炸（或不爆炸——那更糟）的現場。

## 技術背景

### 體系：兩棵樹，兩種契約

```
Throwable
├── Error                 ← JVM 級災難（OutOfMemoryError、StackOverflowError）——不要接
└── Exception
    ├── RuntimeException  ← unchecked：程式邏輯錯誤（NPE、IllegalArgument…）
    └── 其他               ← checked：可預期的外部問題（IOException、SQLException）
```

| | Checked Exception | Runtime Exception |
|---|---|---|
| 檢查時機 | **編譯期**強制處理 | 執行期才現身 |
| 代表意義 | 可預期的外部狀況（檔案不存在、網路斷） | 程式寫錯了（NPE、越界） |
| 設計意圖 | 逼呼叫者面對「可能失敗」 | 修 code，不是 catch |

這個分類的實務影響遠超語法：[03 章](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)的 `@Transactional` **預設只回滾 unchecked**——「checked 是可預期的業務結果」這個哲學就是從這裡來的。

### 紀律一：要嘛處理、要嘛上拋，不准假裝沒事

```java
// ❌ 吞掉：案發現場被抹除（前言的三天事故）
catch (Exception e) { }

// ❌ 半吞：印了卻沒人看 stdout，呼叫端以為成功
catch (Exception e) { e.printStackTrace(); }

// ✅ 處理不了就包成語意化的例外上拋——保留 cause！
catch (SQLException e) {
    throw new OrderPersistException("訂單 " + id + " 儲存失敗", e);   // e 是 cause
}
```

「保留 cause」是被低估的一條：`new XxxException(msg, e)` 少傳那個 `e`，stack trace 就從你這裡斷頭——排查者看得到你的包裝、看不到真正的根因。兩個特例：`InterruptedException` 有自己的規矩（復原旗標，[07 章](../07-concurrency/thread-basics-and-lifecycle.md)講過）；框架層的「最後防線」catch（如 [03 章的 @ControllerAdvice](../03-spring-to-spring-boot/exception-handling-and-validation.md)）是集中處理，不是吞。

### 紀律二：資源用 try-with-resources

```java
try (FileReader fr = new FileReader(path);
     BufferedReader reader = new BufferedReader(fr)) {
    // 用資源
}   // 離開時自動 close，多資源以「宣告的反序」關閉（實測見案例二）
```

實作 `AutoCloseable` 的資源都適用。比手寫 finally 好的不只是省字：中途拋例外**保證關閉**、關閉本身拋的例外會被**壓制（suppressed）**附掛在主例外上而不是蓋掉它——手寫版幾乎沒人做對這兩件事。

### 紀律三：例外不是流程控制

```java
// ❌ 拿例外當 if 用
try { return Integer.parseInt(input); }
catch (NumberFormatException e) { return 0; }
```

除了語意錯亂，還有一筆效能帳：**建立例外要抓整條 stack trace**（`fillInStackTrace`）——實測 10 萬次 `new Exception()` 要 32ms，而 `new Object()` 是 0ms。正常流程的分支用 if／Optional；例外留給真正的「異常」。「查無資料」該回空集合或 Optional，不是拋例外（[03 章](../03-spring-to-spring-boot/exception-handling-and-validation.md)同一條紀律的 Web 層版本）。

### 自訂例外：業務語意的載體

```java
public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String policyNo) {
        super("查無保單：" + policyNo);
    }
}
```

兩個決定：**繼承 RuntimeException**（現代主流——checked 的強制處理在多層架構下淪為 throws 傳染病）；**名字即文件**（`PolicyNotFoundException` 比 `BusinessException` + error code 可讀）。它的下游是 [03 章的 @RestControllerAdvice](../03-spring-to-spring-boot/exception-handling-and-validation.md)——業務碼拋語意，advice 管格式。

## 實際案例

### 案例一：吞例外的事故重演

前言的空 catch 不是虛構——它是每個團隊的 log 裡都躺過的真實案件。對照組一行之差：

```java
catch (Exception e) { }                                    // 三天排查
catch (Exception e) { throw new OrderException("...", e); } // 三分鐘定位
```

### 案例二：反序關閉，實測為證

```java
try (AutoCloseable first  = () -> System.out.println("關閉 first");
     AutoCloseable second = () -> System.out.println("關閉 second")) {
    System.out.println("使用資源中…");
}
```

實測輸出：

```
使用資源中…
關閉 second    ← 後宣告的先關
關閉 first
```

反序是刻意的：`second`（如 BufferedReader）通常依賴 `first`（如 FileReader）——依賴者先關、被依賴者後關，跟建立順序鏡像對稱。

### 案例三：例外的價格

```
new Object    ×100,000：0 ms
new Exception ×100,000：32 ms
```

差距全在 stack trace 的抓取。這就是「例外當流程控制」的帳單——熱路徑上每秒幾千次的 parse 失敗回退，例外版比 if 版貴出數量級。

## 技術優缺點

### 例外機制買到的

- **錯誤路徑與正常路徑分離**：不用每層 return code 檢查（C 時代的痛苦）
- **強制性**（checked）：API 作者能逼呼叫者面對失敗——雖然這把雙面刃現代用法偏向少用
- **完整的案發現場**：stack trace ＋ cause 鏈，是排查的第一手證據——**前提是你別吞掉它**

### 代價與紀律清單

- 建立成本高（實測 32ms/10萬次）——不當流程控制
- checked 的 throws 傳染性——自訂業務例外用 RuntimeException
- **吞例外＝銷毀證據**、**包裝不帶 cause＝斷頭 stack trace**——code review 的必查兩條

## 小結

- 兩棵樹記住契約：**checked＝可預期的外部狀況**（編譯期強制）、**unchecked＝程式寫錯**——這個哲學延伸到 @Transactional 的回滾預設
- 鐵律：**要嘛處理、要嘛上拋**——吞例外銷毀證據（三天事故）、包裝必帶 cause（斷頭 trace）
- 資源一律 **try-with-resources**：保證關閉、反序關閉（實測）、suppressed 例外不丟失
- **例外不當流程控制**：實測 new Exception 比 new Object 貴一個量級（stack trace 的價格）
- 自訂業務例外：繼承 RuntimeException、名字即文件——下游交給 [@RestControllerAdvice](../03-spring-to-spring-boot/exception-handling-and-validation.md)

02 章至此翻新完畢——這章的六篇是整個 repo 的「語言地基」：型別、裝箱、參考語意、精確度、字串、例外，每一篇都有後面章節的迴響。

## 常見面試題

1. checked 和 unchecked exception 差在哪？各自的設計意圖？（提示：編譯期強制 vs 程式錯誤；@Transactional 的回滾預設）
2. try-with-resources 比手寫 finally 好在哪？多資源的關閉順序？（提示：保證關閉、suppressed、反序實測）
3. 為什麼不該拿例外當流程控制？（提示：語意＋stack trace 的價格，實測數據）

## 延伸閱讀

- [Java Tutorials: Exceptions](https://docs.oracle.com/javase/tutorial/essential/exceptions/) — 體系與 try-with-resources 的官方教學
- Effective Java 第三版 Item 69–77（例外章）— 本篇每條紀律的完整論證
