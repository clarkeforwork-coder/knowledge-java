# 統一例外處理與 Validation

## 前言

沒有統一例外處理的專案長什麼樣？每個 controller 方法都包著 try-catch、每個人回的錯誤格式都不一樣、業務邏輯淹沒在防禦碼裡；輸入檢查則是一排 `if (req.getName() == null || req.getName().isBlank())` 的 if-else 地獄。

[MVC 篇](spring-mvc-request-flow.md)留的懸念在這裡收：你的方法炸了之後，例外沿著管線往回走——**誰接住它、怎麼把它變成一個格式統一的錯誤回應**？這篇把例外處理與輸入驗證一起收進「宣告式」的世界：controller 只剩業務，錯誤格式全站一致。

## 技術背景

### 例外的回程：HandlerExceptionResolver

你的方法拋出例外後，`DispatcherServlet` 不會讓它裸奔——它交給一排 **HandlerExceptionResolver** 輪流認領。其中最重要的一個，就是負責 `@ExceptionHandler` 的 resolver。所以例外處理仍然在 MVC 管線**之內**——這句話有個重要推論，見下方「邊界」。

### @RestControllerAdvice：全站的例外接待處

```java
@RestControllerAdvice                                 // 攔全站 controller 的例外
class GlobalErrorHandler {

    @ExceptionHandler(MemberNotFound.class)           // 按例外型別認領
    ResponseEntity<ProblemDetail> notFound(MemberNotFound e) {
        var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("資源不存在");
        pd.setDetail(e.getMessage());
        return ResponseEntity.status(404).body(pd);
    }
}
```

三條匹配規則：

1. **就近優先**：controller **內部**的 `@ExceptionHandler` 贏過全域 advice（實測見案例三）——留給「只有這支 API 要特殊處理」的場景
2. **型別越具體越優先**：`MemberNotFound` 的 handler 贏過 `RuntimeException` 的
3. 多個 advice 之間用 `@Order` 排序；`basePackages` 屬性可以限縮管區

錯誤回應的格式，Spring 6 給了官方答案：**`ProblemDetail`**（RFC 7807 標準）——`type`/`title`/`status`/`detail` 的標準骨架，還能 `setProperty` 加自訂欄位（實測的 `fieldErrors` 就是）。自己發明錯誤格式的時代可以結束了。

### Validation：把 if-else 地獄變成註解

```java
record MemberReq(
        @NotBlank(message = "姓名不可為空") String name,
        @Min(value = 0, message = "年齡不可為負") int age) { }

@PostMapping("/members")
String create(@Valid @RequestBody MemberReq req) { ... }   // @Valid 觸發驗證
```

常用註解速查：`@NotNull`／`@NotBlank`（字串非空白）／`@NotEmpty`（集合非空）、`@Min`/`@Max`、`@Size`、`@Email`、`@Pattern`；巢狀物件在欄位上再標 `@Valid` 逐層下探。

驗證失敗**不會進你的方法**——直接拋例外，交給 advice 統一變成 400。但注意有**兩種例外要分流**：

| 驗證對象 | 觸發方式 | 拋出的例外 |
|---|---|---|
| `@RequestBody` 物件 | 參數標 `@Valid` | `MethodArgumentNotValidException` |
| `@RequestParam`／`@PathVariable` 散裝參數 | **class 上標 `@Validated`**＋參數標約束 | `ConstraintViolationException` |

兩個 handler 都要寫，漏一個就會有一類驗證錯誤以 500 裸奔。

### 邊界：advice 接不到的例外

`@ControllerAdvice` 活在 MVC 管線內——**Filter 層炸的例外它接不到**（回看 [MVC 篇](spring-mvc-request-flow.md)的七站圖：Filter 在 DispatcherServlet 之外）。最常見的受害者是 Spring Security：認證失敗的例外發生在 security filter chain，要用它自己的 `AuthenticationEntryPoint`／`AccessDeniedHandler` 處理。「為什麼我的 401 格式跟其他錯誤不一樣」——答案就是這條邊界。

## 實際案例

驗證環境：MockMvc standalone ＋ hibernate-validator（Docker Maven 實測）。

### 案例一：驗證失敗 → 全站統一的 400

送出 `{"name":"","age":-5}`，實測回應：

```json
HTTP 400：{"type":"about:blank","title":"驗證失敗","status":400,
           "instance":"/members",
           "fieldErrors":{"name":"姓名不可為空","age":"年齡不可為負"}}
```

兩個欄位的錯誤**一次全部回報**（不是撞到第一個就停），訊息就是註解上寫的中文；controller 方法從頭到尾沒被呼叫。`ProblemDetail` 的標準骨架＋自訂的 `fieldErrors`，前端拿到的永遠是同一種格式。

### 案例二：業務例外 → 404

```java
throw new MemberNotFound("M999");     // 業務碼只管拋，不管格式
```

實測回應：

```json
HTTP 404：{"type":"about:blank","title":"資源不存在","status":404,
           "detail":"查無會員：M999","instance":"/members/M999"}
```

業務例外與 HTTP 回應的對映集中在一個 advice 裡——新增一種業務例外，加一個 handler 方法就完事。

### 案例三：就近優先實測

controller 內和全域 advice **同時**宣告了 `IllegalStateException` 的 handler，實測由誰接手：

```json
HTTP 500：{"handledBy":"controller 內的 handler"}
```

近的贏。全域 advice 是預設值，controller 內的 handler 是覆寫——這個優先序讓「全站統一＋個別特殊」可以共存。

### 加映：一個真實世界的坑

實測案例二時撞上的：Spring 6.1 起，`@PathVariable String id` 這種**不寫參數名**的寫法需要編譯器開 `-parameters` 旗標（Maven：`<maven.compiler.parameters>true</maven.compiler.parameters>`），否則執行期炸 `Name for argument ... not specified`。Spring Boot 3.2 的升級災情榜上有名——Boot 的官方 parent 已幫你開，但自建專案或特殊建置流程要自己確認。

## 技術優缺點

### 集中化買到的

- **格式全站一致**：前端只需要認得一種錯誤結構（ProblemDetail）
- **業務碼淨化**：controller 和 service 只管拋語意化的例外，格式與狀態碼的對映集中一處
- **驗證宣告式**：規則寫在資料的定義旁邊（record 欄位上），一目瞭然、自動全報

### 邊界與紀律

- **Filter 層例外接不到**（Security 的 401/403 要另外處理）——格式不一致的頭號原因
- **兩種驗證例外要分流**：`MethodArgumentNotValidException` 與 `ConstraintViolationException` 缺一個就有 500 裸奔
- **別拿例外當流程控制**：例外的建立成本高（stack trace）、語意是「異常」——「查無資料」在列表場景回空集合，在單筆場景才是 404
- 錯誤訊息想國際化：`message` 可寫成 `{member.name.blank}` 走 MessageSource——先知道有這條路

## 小結

- 例外的回程有人接：**HandlerExceptionResolver → @ExceptionHandler**，全域集中在 `@RestControllerAdvice`
- 匹配規則：**就近優先**（實測 controller 內贏過 advice）、型別越具體越優先
- 錯誤格式用 **ProblemDetail**（RFC 7807，Spring 6 內建）——別再發明自己的
- 驗證宣告式：約束註解＋`@Valid` 觸發，失敗**不進方法**、欄位錯誤**一次全報**（實測）；記得兩種例外都要接
- **advice 的邊界是 MVC 管線**：Filter 層（含 Security）的例外接不到
- 彩蛋級的坑：Spring 6.1 的 `-parameters` 旗標（Boot 3.2 升級災情）

**中期 Roadmap 的 🔰 軌至此全部完工。** 03 章補深還欠一篇 🔬——`@Transactional` 的 self-invocation 為什麼在 proxy 模型下無解，要從原始碼找答案：見 🔬 [從 AOP proxy 看 @Transactional self-invocation 失效](deep-transactional-self-invocation.md)。

## 常見面試題

1. `@ControllerAdvice` 的原理？它接得到 Filter 層的例外嗎？（提示：HandlerExceptionResolver、MVC 管線邊界、Security 的 401 為什麼格式不同）
2. `@Valid` 驗證失敗會發生什麼？怎麼統一回 400？（提示：不進方法、MethodArgumentNotValidException、與 ConstraintViolationException 的分流）
3. controller 內的 `@ExceptionHandler` 和全域 advice 同時存在，誰生效？（提示：就近優先，實測為證）

## 延伸閱讀

- [Spring Framework 官方文件：Exceptions（@ExceptionHandler）](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html) — 匹配規則與可用的回傳型別
- [RFC 7807: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc7807) — ProblemDetail 格式的標準原文
- [Jakarta Bean Validation 規格](https://beanvalidation.org/) — 約束註解的完整清單
