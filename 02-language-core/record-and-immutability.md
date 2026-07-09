# record 與不可變物件

## 前言

這個 repo 有一條反覆出現的建議：[迴圈共用物件](pitfall-shared-reference-in-loop.md)說「值物件用 record 讓 bug 不可能發生」、[Set 篇](../04-collections/set-implementations.md)說「record 一行解決去重」、[契約篇](../04-collections/equals-hashcode-contract.md)把它列為 equals/hashCode 的首選、[JMM 篇](../07-concurrency/deep-jmm-happens-before.md)說 final 欄位有安全發布保證——**每次都指向這裡，這篇是主場**。

三個問題一次講清：record 宣告一行，編譯器**到底生成了什麼**（javap 驗證）；record 就等於不可變嗎（**不等於**——實測翻車給你看）；以及什麼場景**不該**用它。

## 技術背景

### 一行宣告，編譯器代寫全套

```java
record Point(int x, int y) { }
```

`javap` 實測生成物：

```
final class Point extends java.lang.Record {
  Point(int, int);                          ← 全欄位建構子
  public final String toString();           ← Point[x=1, y=2] 格式
  public final int hashCode();              ← 按全欄位，與 equals 永遠成對
  public final boolean equals(Object);
  public int x();                           ← accessor：x() 不是 getX()
  public int y();
}
```

三個看點：**類別是 final**（[契約篇](../04-collections/equals-hashcode-contract.md)「斷繼承的路」由編譯器代勞）；equals/hashCode **永不過時**（加欄位自動同步——IDE 生成版的最大風險在這裡歸零）；accessor 命名是 `x()` 不是 `getX()`（record 的識別特徵，搭配 [Jackson 等現代框架](../03-spring-to-spring-boot/spring-mvc-request-flow.md)無縫——MVC 篇的 `OrderReq` 就是 record 直接收 JSON）。

### compact constructor：驗證與正規化的正位

```java
record Money(BigDecimal amount) {
    Money {                                              // 沒有參數列的「緊湊建構子」
        Objects.requireNonNull(amount, "金額不可為 null");
        if (amount.signum() < 0) throw new IllegalArgumentException("金額不可為負：" + amount);
    }
}
```

在欄位賦值**之前**執行——非法值連物件都建不出來（實測 `Money(-100)` 當場炸）。值物件的驗證寫在這裡，[BigDecimal 篇](floating-point-and-bigdecimal.md)的金額規矩有了型別化的家。

### 最重要的警告：record ≠ 不可變

record 保證的是**欄位 final**（參考不能換）——[static/final 篇](static-and-final.md)剛講過：**final 管指派，不管內容**。欄位是可變集合時：

```java
record Naive(List<String> items) { }     // 欄位 final，但 List 本身可變！
```

實測翻車現場：外部留著原 List 的參考照樣改、拿 `items()` 出去也照樣 add——record 的「不可變」被裡外夾攻（輸出 `[A, B（外部偷改）, C（拿到就改）]`）。修法是 compact constructor 裡**防禦性拷貝**：

```java
record Safe(List<String> items) {
    Safe { items = List.copyOf(items); }   // 拷一份不可變快照
}
```

實測：建立後外部再改無感、`items().add()` 直接 `UnsupportedOperationException`。規矩一句話：**record 的欄位若是集合，一律 `List.copyOf`／`Map.copyOf` 進門**——深不可變是自己的責任，record 只給你淺的。

### 「修改」的正確姿勢：wither

不可變物件要「改」就是造新的（[String](string-and-stringbuilder.md) 的同款哲學）：

```java
record Policy(String no, PolicyStatus status) {
    Policy withStatus(PolicyStatus s) { return new Policy(no, s); }   // 慣例命名 withX
}
```

### 什麼時候不用 record

- **JPA Entity**：需要無參建構子、代理、可變欄位——[契約篇](../04-collections/equals-hashcode-contract.md)警告過的特例，record 全部不符
- **需要繼承階層**：record 不能 extends（可以 implements 介面）
- **equals 想挑欄位**：record 全欄位參與，不能排除——要挑就回到手寫類別
- **有生命週期狀態的物件**：record 是「值」的載體，不是「東西」的載體——會變的東西本來就不該裝進值物件

## 實際案例

### 案例一：生成物清單（javap 實測見上）

一行 record ＝ final class ＋ 建構子 ＋ 全套 equals/hashCode/toString ＋ accessors——[契約篇](../04-collections/equals-hashcode-contract.md)整篇的手寫紀律，編譯器代管。

### 案例二：淺不可變翻車與修復（實測見上）

`Naive` 被裡外改成三筆；`Safe` 用 `List.copyOf` 後——外改無效、內改即炸。**「用了 record」和「真的不可變」之間差一行拷貝**。

### 案例三：compact constructor 守門（實測見上）

`Money(-100)` → `IllegalArgumentException: 金額不可為負`——非法狀態的物件不存在，後續所有程式碼免除防禦檢查。

## 技術優缺點

### record 買到的

- **樣板歸零且永不過時**：equals/hashCode/toString 隨欄位自動同步
- **不可變 bug 家族集體滅絕**：[迴圈共用](pitfall-shared-reference-in-loop.md)、[幽靈元素](../04-collections/set-implementations.md)、[幽靈 key](../04-collections/map-hashmap-basics.md)、[race condition 的共享寫入](../07-concurrency/synchronized-and-volatile.md)——前提都是「可變」，record 拆掉前提
- **併發紅利白送**：final 欄位的 [JMM 安全發布](../07-concurrency/deep-jmm-happens-before.md)——跨 thread 傳遞不需要任何同步

### 邊界與紀律

- **淺不可變**是最大陷阱（實測）——集合欄位必 `copyOf`
- 全欄位參與 equals、不能繼承、JPA 不適用——「限制」多數是 feature，但要知道邊界
- DTO／API 模型／複合 key／設定快照——現代 Java 的預設選擇；**先問「這是值還是東西」**：值用 record，有身分有生命週期的東西用 class

## 小結

- 一行 record ＝ **final class ＋全套契約方法＋accessors**（javap 實測）——equals/hashCode 永不過時
- **record ≠ 不可變**：final 管指派不管內容——集合欄位實測被裡外夾攻，`List.copyOf` 進門是鐵律
- **compact constructor** 是驗證正位：非法值連物件都不存在
- 「修改」用 wither（造新的）；不適用場景：JPA、繼承、挑欄位 equals
- 判準一句話：**值用 record、東西用 class**

**02 語言核心至此補完**（11 篇）——從型別、裝箱、參考語意、精確度、字串、例外，到 OOP 三篇與 Optional、record：整個 repo 的語言地基完工，每一篇都有後面章節的迴響。

## 常見面試題

1. record 自動生成哪些東西？跟手寫類別差在哪？（提示：javap 清單；final、全欄位契約）
2. record 是不可變的嗎？（提示：淺不可變實測；集合欄位的 copyOf 鐵律）
3. 什麼場景不該用 record？（提示：JPA、繼承、挑欄位；值 vs 東西的判準）

## 延伸閱讀

- [JEP 395: Records](https://openjdk.org/jeps/395) — 設計動機與語意的官方定案
- [Java Language Updates: Record Classes](https://docs.oracle.com/en/java/javase/17/language/records.html) — compact constructor 等細節的官方教學
