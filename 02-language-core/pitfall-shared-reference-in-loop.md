# 經典陷阱：迴圈裡共用同一個物件

新手最常這樣做，然後問為什麼。

## 錯誤版本

```java
public static void main(String[] args) {
    List<Member> list1 = new ArrayList<>();
    Member m1 = new Member();              // 只 new 了一次！
    for (int i = 0; i < 2; i++) {
        m1.count = String.valueOf(i);
        list1.add(m1);                     // 每次加進去的是同一個參考
    }
    for (Member m2 : list1) {
        System.out.println(m2.count);      // 印出 1, 1 —— 不是 0, 1
    }
}

private static class Member {
    public String count;
}
```

`list1` 裡的兩個元素都指向**同一個** `Member` 物件，最後一次賦值覆蓋了前面的。

## 正確版本

```java
public static void main(String[] args) {
    List<Member> list1 = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
        Member m1 = new Member();          // 每輪 new 一個新物件
        m1.count = String.valueOf(i);
        list1.add(m1);
    }
    for (Member m2 : list1) {
        System.out.println(m2.count);      // 0, 1 ✅
    }
}
```

## 背後的原理

集合存的是**參考**不是值。`list.add(m1)` 加進去的是「指向那個物件的地址」，
物件本體只有一個，改它就是改所有參考看到的東西。

延伸閱讀：[資料型態：基本型別 vs 參考型別](data-types.md)
