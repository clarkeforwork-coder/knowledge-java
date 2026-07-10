# Mockito 與測試替身

## 前言

[JUnit 篇](junit5-basics.md)結尾的問題：要測一個 `PolicyService.activate()`，它會查資料庫、改狀態、發通知——**難道測試要連真的 DB、真的寄一封通知信出去嗎？**

不行，理由有三：慢（真 DB 往返）、脆（DB 掛了測試就紅，但不是你的 code 錯）、有副作用（真的寄信給客戶）。解法是**測試替身（test double）**——用可控的假物件替換真依賴，讓測試只聚焦「這個 service 的邏輯對不對」。Mockito 是 Java 的事實標準。這篇用一個保單 service 的真測試（兩案例全綠）示範替身的兩種核心用法：**stub**（餵輸入）和 **verify**（驗互動）。

## 技術背景

### 為什麼要替身：隔離被測單元

單元測試的「單元」是**一個類別的邏輯**。`PolicyService` 依賴 `PolicyRepository` 和 `Notifier`——測 service 時，這些依賴的真實實作**不該參與**：

```
真實：PolicyService → 真 Repository → 真 DB（慢、脆）
                   → 真 Notifier → 真的寄信（副作用！）

替身：PolicyService → mock Repository（你叫它回什麼就回什麼）
                   → mock Notifier（記錄有沒有被呼叫，但不真寄）
```

替身讓被測類別跑在一個**完全受你控制的環境**裡——輸入你給、副作用你攔。

### 替身的種類（術語對齊）

| 種類 | 作用 | Mockito |
|---|---|---|
| **Stub** | 餵預設好的回傳值（「被查詢時回這個」） | `when(...).thenReturn(...)` |
| **Mock** | 記錄互動，事後驗證（「save 有沒有被呼叫」） | `verify(...)` |
| **Spy** | 包真物件，選擇性替換部分方法 | `spy(...)` |
| Fake | 有簡化實作的替身（如記憶體版 DB） | 手寫 |

實務上 Mockito 的 mock 物件**同時能 stub 和 verify**——不用糾結術語，記住兩個動作：**stub 餵輸入、verify 驗行為**。

### 三個核心註解

```java
@ExtendWith(MockitoExtension.class)          // 啟用 Mockito 與 JUnit 5 的整合
class PolicyServiceTest {
    @Mock PolicyRepository repo;              // 造一個假 repo
    @Mock Notifier notifier;                 // 造一個假 notifier
    @InjectMocks PolicyService service;       // 自動把上面兩個 mock 注入 service 的建構子
}
```

`@InjectMocks` 是便利魔法——它看 `PolicyService` 的建構子需要什麼，把對應型別的 `@Mock` 塞進去。這也回頭印證了[建構子注入](../03-spring-to-spring-boot/bean-lifecycle-and-scope.md)的好處：**依賴從建構子進來，測試才能輕鬆替換**（field injection 的私有欄位 Mockito 得靠反射硬塞，脆得多）。

### stub 與 verify：一體兩面

```java
// stub：安排「當被這樣呼叫，就回這個」——控制輸入
when(repo.findByNo("P001")).thenReturn(Optional.of(policy));
when(repo.findByNo("X999")).thenReturn(Optional.empty());     // 模擬查無資料

// verify：斷言「這個互動有/沒有發生」——驗證行為（尤其副作用）
verify(repo).save(policy);                    // save 剛好被呼叫一次
verify(notifier, never()).send(any(), any()); // send 一次都沒被呼叫
verify(repo, times(2)).findByNo(anyString()); // 被呼叫兩次
```

**verify 是測「副作用」的唯一辦法**：`activate` 有沒有真的存檔、有沒有發通知——這些沒有回傳值，只能靠「mock 有沒有被那樣呼叫」來驗。argument matcher（`any()`、`eq()`、`contains()`）讓你精確或寬鬆地描述期望的參數。

## 實際案例

驗證環境：Mockito 5.11 ＋ JUnit 5（Docker Maven 實測，兩案例全綠）。被測的 `PolicyService.activate()`：查保單 → 改狀態 → 存檔 → 發通知。

### 案例一：成功路徑——stub 餵資料、verify 驗副作用

```java
Policy p = new Policy("P001", "王小明", "PENDING");
when(repo.findByNo("P001")).thenReturn(Optional.of(p));       // stub：查得到

String result = service.activate("P001");

assertEquals("ACTIVE", result);                              // 回傳值對
verify(repo).save(p);                                        // 有存檔
verify(notifier).send(eq("王小明"), contains("P001"));        // 通知內容對
```

**沒有連任何 DB、沒有寄任何信**，卻完整驗證了 service 的三件事：狀態變 ACTIVE、有存檔、通知內容正確。stub 決定了「repo 說保單存在」，verify 確認了「service 做了該做的副作用」。

### 案例二：例外路徑——驗證「沒做什麼」

```java
when(repo.findByNo("X999")).thenReturn(Optional.empty());    // stub：查不到

assertThrows(IllegalArgumentException.class, () -> service.activate("X999"));

verify(repo, never()).save(any());                           // 絕不存檔
verify(notifier, never()).send(any(), any());                // 絕不發通知
```

這案例的價值全在 **`never()`**：查無保單時，不只要拋例外，更要保證**沒有錯誤的副作用**——沒有存一筆壞資料、沒有發一封「保單已生效」的錯誤通知。這種「確認壞事沒發生」的驗證，是替身測試獨有的能力（真 DB 測試很難乾淨地驗這個）。

兩案例實測：

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

## 技術優缺點

### 替身買到的

- **快**：無 IO，純記憶體——幾百個測試幾秒跑完
- **穩**：不依賴外部環境（DB、網路）——測試紅了一定是你的 code 問題
- **可測邊界**：`thenThrow` 模擬 DB 爆炸、`Optional.empty()` 模擬查無——真環境難製造的情境，stub 一行搞定
- **驗副作用**：verify 是「有沒有存檔/發通知」的唯一驗法

### 過度 mock 的陷阱

- **測到實作而非行為**：`verify` 綁死「呼叫了哪些方法、幾次」——重構（換個等價實作）會讓測試紅，即使行為沒變。**verify 用在有意義的副作用上**（存檔、發通知），別驗每個內部呼叫
- **mock 到自己都看不懂**：一個測試 stub 十個方法＝被測類別依賴太多，是**設計**在報警（該拆了），不是測試的錯
- **別 mock 你不擁有的型別**：mock 第三方 API 的行為＝你在賭它的行為，它改了你不知道——那類邊界用整合測試
- **值物件不用 mock**：`new Policy(...)` 直接建就好——mock 一個 record 是浪費（[record 篇](../02-language-core/record-and-immutability.md)）

## 小結

- 測試替身**隔離被測單元**：把慢、脆、有副作用的真依賴換成可控假物件
- 兩個核心動作：**stub**（`when...thenReturn` 餵輸入）、**verify**（驗互動，尤其副作用）
- 三註解：`@Mock`（造替身）、`@InjectMocks`（注入被測類別）、`@ExtendWith(MockitoExtension)`——建構子注入讓這一切乾淨
- **`verify(never())` 驗「壞事沒發生」**（實測：查無保單時不存檔、不通知）——替身獨有能力
- 別過度 mock：驗行為不驗實作、stub 太多是設計在報警、不 mock 值物件與第三方

單元測試把依賴全 mock 掉了——但「service 和真 repository 接起來對不對」「Controller 的 JSON 轉換對不對」這些**接縫**，mock 測不到。什麼時候該用真的、用到哪一層，見規劃中的〈Spring Boot Test：分層測試策略〉。

## 常見面試題

1. stub 和 verify（或 mock 和 stub）差在哪？（提示：餵輸入 vs 驗互動；副作用只能 verify）
2. `verify(never())` 有什麼用？（提示：確認壞事沒發生——不存錯資料、不發錯通知）
3. 什麼時候不該用 mock？（提示：過度 mock 測到實作、依賴太多是設計問題、值物件、第三方型別）

## 延伸閱讀

- [Mockito 官方文件](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html) — stub/verify/matcher 的完整 API 與範例
- [Martin Fowler: Mocks Aren't Stubs](https://martinfowler.com/articles/mocksArentStubs.html) — 測試替身術語與「驗狀態 vs 驗行為」的經典文章
