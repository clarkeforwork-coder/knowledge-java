# Spring MVC 請求處理流程

## 前言

你每天寫的其實只是一個方法：

```java
@PostMapping("/orders")
OrderResp create(@RequestBody OrderReq req) { ... }
```

但停下來想：HTTP 請求是**誰**派給這個方法的？`req` 這個物件是**誰**從 JSON 變出來的？回傳的 record 又是**誰**變回 JSON 的？當你在 stack trace 裡看到 `DispatcherServlet.doDispatch` 卻不知道那是什麼，debug 就只能瞎子摸象。這篇跟著一個 request 走完全程——每一站都有實測的過站紀錄。

## 技術背景

### 先修正視角：Controller 不是入口

入口是 **`DispatcherServlet`**——前端控制器（Front Controller）模式：**所有**請求先進同一扇門，由它統一調度。你的 controller 是流程的後段站點之一。而在這扇門之前，還有 servlet 規格層的關卡：

### 一個 request 的一生

```
Tomcat 收到請求（從池裡撥一條 thread —— 07 章的 thread-per-request）
  │
① Filter chain（Servlet 規格層——Spring Security、編碼、CORS 都住這）
  │
DispatcherServlet（前端控制器，統一入口）
  │
② HandlerMapping：這個 URL＋方法該誰處理？ → 找到 OrderController#create
  │
③ Interceptor.preHandle（Spring 層關卡——此刻已知道 handler 是誰）
  │
④ HandlerAdapter：呼叫前的苦工
  │   ├ ArgumentResolver：把 HTTP 請求變成方法參數
  │   │   （@PathVariable ← URL、@RequestParam ← query、
  │   │     @RequestBody ← HttpMessageConverter 讀 body 反序列化）
  │   └──> 呼叫你的方法 <──┐
  │   └ ReturnValueHandler：│回傳值 → HttpMessageConverter 序列化成 JSON
  │
⑤ Interceptor.postHandle
⑥ Interceptor.afterCompletion（回應寫出後，無論成敗都執行——清理點）
  │
⑦ Filter chain（離開方向）
```

實測輸出（見實際案例）與這張圖逐站對應。

### Filter vs Interceptor：兩層關卡怎麼分工

| | Filter | HandlerInterceptor |
|---|---|---|
| 屬於誰 | **Servlet 規格**（無 Spring 也存在） | Spring MVC |
| 位置 | DispatcherServlet **之前/之後** | HandlerMapping 之後——**知道 handler 是誰** |
| 拿得到什麼 | 只有 request/response | request/response ＋ **handler**（實測印出了方法簽名）＋ ModelAndView |
| 典型用途 | 編碼、CORS、認證（Security 的 filter chain） | 授權檢查、耗時統計、審計 log |

選擇心法：**跟「這個請求由哪個方法處理」有關的邏輯用 Interceptor**（它知道 handler）；更底層、與 Spring 無關的用 Filter。

### 參數與回傳的變形記：HttpMessageConverter

`@RequestBody OrderReq req` 的幕後：`HandlerAdapter` 遍歷一排 **ArgumentResolver**，找到認得 `@RequestBody` 的那個，它再委託 **HttpMessageConverter**（JSON 場景就是 Jackson 的 converter）把 body 反序列化成物件。回程對稱：`@RestController` 的回傳值交給同一批 converter 序列化。

所以兩個日常問題有了答案：**「JSON 欄位對不上」查 Jackson**（converter 層的事，跟 MVC 流程無關）；**「415 Unsupported Media Type」查 Content-Type**（沒有 converter 認領這種格式）。

## 實際案例

驗證環境：`MockMvc` standalone 模式（spring-test）——**不起容器就能跑整條 MVC 管線**，這本身就是值得學的測試技巧。

### 過站紀錄：七站直播

Filter、Interceptor、Controller 每站報到，發一個 POST：

```
1. Filter（進入，DispatcherServlet 之前）
2. Interceptor.preHandle（已知道 handler 是誰：demo.OrderController#create(OrderReq)）
3. → HandlerAdapter 開始解析參數…
4. Controller 方法（收到 OrderReq[id=A01, amount=500]）
5. Interceptor.postHandle（方法回來了）
6. Interceptor.afterCompletion（回應已寫出）
7. Filter（離開，一切結束之後）
HTTP 200，回應 JSON：{"id":"A01","amount":500,"status":"CREATED"}
```

四個看點：

1. **順序就是那張圖**：Filter 最外層（1 進 7 出）、Interceptor 在內（2 進 5/6 出）——洋蔥模型
2. **第 2 站的括號是關鍵證據**：preHandle 時已經拿得到 `OrderController#create(OrderReq)`——HandlerMapping 在它之前就完成了選路，這就是 Interceptor 比 Filter「多知道的事」
3. **第 4 站收到的是現成物件**：`OrderReq[id=A01, amount=500]`——JSON 字串到 record 的變形發生在第 3～4 站之間（ArgumentResolver ＋ MessageConverter）
4. **回應是 JSON**：回傳的 record 被 converter 序列化——你的方法從頭到尾沒碰過 JSON 字串

## 技術優缺點

### 前端控制器模式買到的

- **橫切關注點集中**：認證、編碼、log 各有標準掛載點（Filter/Interceptor），不用散進每個 controller
- **擴充點豐富**：ArgumentResolver、MessageConverter 都可自訂——「讓 `@CurrentUser` 註解自動注入登入者」這類需求就是自訂一個 resolver
- **MockMvc 的測試紅利**：整條管線可以不起容器就測（本篇實測就是證明）——web 層測試又快又完整

### 代價

- **stack trace 很深**：一個請求穿過幾十層框架碼才到你的方法——不懂流程圖就找不到自己的位置
- **魔法出錯時難查**：參數 null、415、404，錯的常常不是你的方法而是某一站的規則（mapping 沒對上、converter 沒認領）——按站排查是唯一有效路徑
- **每請求一條 thread 的模型**：管線再優雅，底層還是 [07 章](../07-concurrency/executorservice-and-thread-pools.md)的 thread pool——高併發下的等待問題（以及 virtual threads 的解法）在那邊

## 小結

- 入口是 **DispatcherServlet** 不是 controller——前端控制器統一調度，你的方法是後段站點
- 流程背骨架：**Filter →（DispatcherServlet）→ HandlerMapping → preHandle → 參數解析 → 你的方法 → 回傳序列化 → postHandle → afterCompletion → Filter 出**（七站實測為證）
- **Filter 是規格層、Interceptor 是 Spring 層**——後者知道 handler 是誰（實測印出方法簽名）
- JSON ↔ 物件的變形是 **HttpMessageConverter** 的事：欄位對不上查 Jackson、415 查 Content-Type
- `MockMvc` standalone 不起容器測整條管線——寫 web 層測試的首選姿勢

流程圖上有一站還沒講：你的方法**炸了**之後，例外沿著這條管線往回走——誰接住它、怎麼變成一個像樣的錯誤回應？見規劃中的〈統一例外處理與 Validation〉。

## 常見面試題

1. 描述 Spring MVC 處理一個請求的完整流程。（提示：七站背骨架，DispatcherServlet 是入口）
2. Filter 和 Interceptor 差在哪？各適合做什麼？（提示：規格層 vs Spring 層；誰知道 handler）
3. `@RequestBody` 的 JSON 是怎麼變成物件的？（提示：ArgumentResolver → HttpMessageConverter → Jackson）

## 延伸閱讀

- [Spring Framework 官方文件：DispatcherServlet](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html) — 特殊 bean 清單與處理流程的官方版
- [Spring Framework 官方文件：Annotated Controllers](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html) — 各種參數註解與對應的解析規則
