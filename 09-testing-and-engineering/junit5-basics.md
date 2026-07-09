# JUnit 5 基礎

## 前言

前面八章寫了 40 幾篇筆記，每一篇的程式碼都「跑過」——但那是我手動 `java Xxx.java` 看輸出。**手動驗證不叫測試**：改了程式碼要重跑、輸出要用眼睛比對、換個人就不知道該驗什麼。真正的測試是**可重複、自動判定、留在版控裡**的程式碼。

這篇講 JUnit 5——Java 測試的事實標準。用一組真的跑起來的測試（6 個案例全綠）示範三根支柱：生命週期、斷言、參數化。這是 09 工程實務章的地基，後面的 Mockito、分層測試都建立在這套 API 上。

## 技術背景

### JUnit 5 ＝ 三個模組（知道就好）

JUnit 5 不是單一 jar，是 **Jupiter**（你寫測試用的新 API）＋ **Platform**（跑測試的引擎）＋ **Vintage**（相容跑 JUnit 4 舊測試）。日常只碰 Jupiter——引入 `junit-jupiter` 一個座標即可。

### 一個測試長什麼樣

```java
class CalcTest {
    Calc calc;

    @BeforeEach void setUp() { calc = new Calc(); }   // 每個測試前重建——隔離的關鍵

    @Test
    @DisplayName("正常除法")                            // 給人看的名字（可中文、可空格）
    void divide_normal() {
        assertEquals(5, calc.divide(10, 2));           // 斷言：期望 vs 實際
    }
}
```

- **`@Test`** 標記一個測試方法（不用像 JUnit 4 那樣 `public`）
- **`@DisplayName`** 讓報告可讀——`divide_normal` 是給編譯器的，`"正常除法"` 是給讀報告的人的
- 方法命名慣例：`被測方法_情境_期望`（如 `divide_byZero_throws`）——測試名就是規格文件

### 生命週期：隔離是測試的命根子

```java
@BeforeAll  static void beforeAll()  { }   // 整個類別跑一次（連 DB、啟重物件）
@BeforeEach void  beforeEach()       { }   // 每個測試「之前」都跑——重建狀態
@AfterEach  void  afterEach()        { }   // 每個測試「之後」——清理
@AfterAll   static void afterAll()   { }   // 整個類別結束一次
```

實測順序（見案例一）印證一件事：**`@BeforeEach` 每個測試都重跑一次**。這不是形式——它保證每個測試從**乾淨的狀態**開始，測試之間互不影響。共用可變狀態的測試會「時而過時而敗」（跟 [07 章的共享可變狀態](../07-concurrency/synchronized-and-volatile.md) 同一種病，換到測試場景），`@BeforeEach` 重建是解藥。

### 斷言：值、例外、多重

```java
assertEquals(expected, actual);              // 相等（物件用 equals——契約篇的規矩在這也適用）
assertTrue(cond);  assertNull(x);            // 布林、null
assertThrows(IllegalArgumentException.class, // 斷言「會拋這個例外」——測錯誤路徑的正解
        () -> calc.divide(1, 0));
assertAll(                                    // 多重斷言：全部都驗，不會第一個失敗就停
        () -> assertEquals("A", u.name()),
        () -> assertEquals(18, u.age()));
```

`assertThrows` 特別重要——**錯誤路徑跟正常路徑一樣要測**（[例外處理篇](../02-language-core/exception-handling.md) 的紀律：例外是規格的一部分）。它回傳那個例外，讓你進一步斷言訊息。

### 參數化測試：一個邏輯、多組資料

```java
@ParameterizedTest
@CsvSource({ "2, true", "3, false", "0, true", "-4, true" })
void isEven(int input, boolean expected) {
    assertEquals(expected, calc.isEven(input));
}
```

一個方法、四組輸入——**邊界值（0、負數）一次測齊**，而不是複製貼上四個幾乎一樣的 `@Test`。資料來源除了 `@CsvSource` 還有 `@ValueSource`（單參數）、`@MethodSource`（複雜物件由方法產生）、`@EnumSource`（列舉全值）。

## 實際案例

驗證環境：JUnit 5.10 ＋ Maven Surefire（Docker 實測，6 個案例全綠）。

### 案例一：生命週期順序直播

```
@BeforeAll：整個類別跑一次
  @BeforeEach：每個測試前     ← 每個測試各一次……
  @AfterEach：每個測試後
  @BeforeEach：每個測試前
  @AfterEach：每個測試後
  …（每個測試方法重複）
@AfterAll：整個類別結束
```

`@BeforeEach`/`@AfterEach` 的重複次數 = 測試方法數——**每個測試都拿到全新的 `calc`**。這個包裹式的順序（All 在最外、Each 貼著每個測試）就是隔離的機制本身。

### 案例二：三種測試一次到位

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

6 ＝ `divide_normal`（1）＋ `divide_byZero`（1，assertThrows 驗例外與訊息）＋ `isEven`（4 組參數各算一個）。**參數化的每一組都是獨立計數的測試**——一組掛了報告會精確告訴你是哪組（`[2, true]` 還是 `[-4, true]`），不用猜。

## 技術優缺點

### 自動化測試買到的

- **可重複、零人力**：`mvn test` 一句話跑全部——改完程式碼立刻知道有沒有弄壞舊功能（迴歸防護）
- **測試即規格**：`@DisplayName` ＋ 命名慣例讓測試清單讀起來就是「這個類別該做什麼」
- **CI 的地基**：測試綠了才准合併——工程品質的閘門（這一章後面會回到）

### 寫測試的紀律

- **一個測試一個行為**：`divide_byZero` 只驗除零，別塞五件事——失敗時才知道壞在哪
- **隔離**：靠 `@BeforeEach` 重建，別讓測試依賴執行順序（依賴順序＝共享狀態的壞味道）
- **測錯誤路徑**：`assertThrows` 不是選配——邊界與例外常是 bug 的家
- **別測框架、別測 getter**：測你的**業務邏輯**，不是測 JDK 或 Hibernate 有沒有 bug

## 小結

- JUnit 5 ＝ Jupiter（寫）＋ Platform（跑）＋ Vintage（相容）——日常只碰 Jupiter
- 生命週期**包裹式**：`@BeforeAll` 一次 → 每個測試 `@BeforeEach`/`@AfterEach` → `@AfterAll` 一次（實測順序為證）——**每個測試從乾淨狀態開始**是隔離的命根子
- 斷言三類：值（`assertEquals`）、例外（`assertThrows`——錯誤路徑必測）、多重（`assertAll`）
- **參數化**一個邏輯測多組資料（實測 4 組邊界值），每組獨立計數
- 紀律：一測一行為、靠 `@BeforeEach` 隔離、測業務不測框架

有了測試框架，下一個問題馬上來：被測的類別依賴了資料庫、遠端 API、其他 service——**測一個 service 難道要連真的 DB 嗎**？把依賴換成可控的替身，見規劃中的〈Mockito 與測試替身〉。

## 常見面試題

1. `@BeforeEach` 和 `@BeforeAll` 差在哪？為什麼需要 `@BeforeEach`？（提示：每測試 vs 每類別；隔離）
2. 怎麼測一個「應該拋例外」的方法？（提示：assertThrows，回傳例外可續驗訊息）
3. 參數化測試解決什麼問題？（提示：一邏輯多資料、邊界值、避免複製貼上）

## 延伸閱讀

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/) — 註解與斷言的完整官方文件
- [JUnit 5 Assertions API](https://junit.org/junit5/docs/current/api/org.junit.jupiter.api/org/junit/jupiter/api/Assertions.html) — 全部斷言方法
