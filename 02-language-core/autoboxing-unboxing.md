# Autoboxing 與 Unboxing

Java 會在基本型別與包裝類別之間自動轉換。

## Autoboxing：`int` → `Integer`

```java
public static void main(String[] args) {
    int i1 = 10;
    test(i1);           // int 自動裝箱成 Integer
}

private static void test(Integer i1) {
    System.out.println(i1);
}
```

## Unboxing：`Integer` → `int`

```java
public static void main(String[] args) {
    Integer i1 = 10;
    test(i1);           // Integer 自動拆箱成 int
}

private static void test(int i1) {
    System.out.println(i1);
}
```

## 注意事項

- Unboxing 一個 `null` 的包裝物件會拋 `NullPointerException`：
  ```java
  Integer i = null;
  int x = i;   // NPE!
  ```
- 迴圈中反覆裝箱會產生大量臨時物件，影響效能
- `Integer` 有 -128 ~ 127 的快取區間，`==` 比較在區間內外行為不同——比較包裝類別永遠用 `equals()`
