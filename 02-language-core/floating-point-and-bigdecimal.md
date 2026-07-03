# 浮點數與 BigDecimal

## float 在記憶體中怎麼存（IEEE-754）

以 `float`（32 bit）為例，`0.15625` 的儲存方式：

| 欄位 | 位元數 | 用途 |
|---|---|---|
| sign | 1 bit | 正負號 |
| exponent | 8 bit | 指數位 |
| fraction | 23 bit | 尾數位 |

- 指數位 8 bits 可表示 255 種狀態，IEEE-754 以 **127 作為指數 0 的偏移值**（bias）。
  要表示指數 -3，存的就是 -3 + 127 = 124
- 捨入規則是「向最接近的偶數捨入」（Round to Nearest, Ties to Even）：
  - 捨入位置後的數字小於 5 → 向下捨
  - 大於 5 → 向上進位
  - 剛好等於 5 → 看捨入位置前一位，取偶數

## 為什麼 1/3 印出來不精確

```java
public class FloatTest {
    public static void main(String[] args) {
        float x = 1.0f / 3.0f;
        System.out.println(x);   // 0.33333334 —— 尾數位只有 23 bit，存不下無限循環
    }
}
```

二進位無法精確表示大多數十進位小數（例如 0.1、0.8），所以**金額運算絕對不要用 float/double**。

## BigDecimal 正確用法

```java
BigDecimal b1 = new BigDecimal(0.8);     // ❌ 0.8000000000000000444089209850062616169452667236328125
BigDecimal b2 = new BigDecimal("0.8");   // ✅ 0.8 —— 一定要用字串建構
```

除不盡時必須指定捨入模式，否則拋 `ArithmeticException`：

```java
BigDecimal b1 = new BigDecimal("1");
BigDecimal b2 = new BigDecimal("3");

BigDecimal b3 = b1.divide(b2);                          // ❌ ArithmeticException
BigDecimal b3 = b1.divide(b2, 2, RoundingMode.HALF_UP); // ✅ 0.33
```

## 重點

1. 金額一律 `BigDecimal`，建構一律用字串（或 `BigDecimal.valueOf()`）
2. `divide` 必指定精度與 `RoundingMode`
3. 比較用 `compareTo()` 不用 `equals()`（`equals` 連 scale 都比：`0.1` ≠ `0.10`）
