# String 與 StringBuilder

## String 是不可變的（immutable）

```java
String s1 = "Hello";        // 字面值存進 String Pool（在 Heap）
s1 = s1 + "World";          // 不是修改原字串！產生一個新的 String 物件
```

`s1 + "World"` 會建立新物件，原本的 `"Hello"` 沒有被改變。
在迴圈裡用 `+` 串接字串，每一輪都產生新物件，是常見的效能陷阱。

## StringBuilder：可變的字串緩衝

```java
StringBuilder builder = new StringBuilder();
builder.append("Hello");
builder.append("World");
String s1 = builder.toString();
```

`append` 是在同一個內部緩衝區上操作，不會每次產生新物件。

## 使用準則

| 情境 | 用什麼 |
|---|---|
| 少量、一次性的串接 | `String` 的 `+`（編譯器會自動優化成 StringBuilder） |
| **迴圈內**反覆串接 | `StringBuilder` |
| 多執行緒共享的串接 | `StringBuilder` 換成 `StringBuffer`（有 synchronized，較慢） |

## 重點

- String 不可變是為了安全（hash 快取、String Pool 共享、執行緒安全）
- 字串比較用 `equals()`，`==` 比的是參考（Pool 的存在讓 `==` 有時「碰巧」是 true，更危險）
