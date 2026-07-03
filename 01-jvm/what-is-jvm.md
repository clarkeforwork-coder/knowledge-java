# What is JVM

> 📝 待補：原教材此段以架構圖為主，待整理成文字版。

## 大綱（待展開）

- JVM、JRE、JDK 的關係
- 編譯與執行流程：`.java` → `javac` → `.class`（bytecode）→ JVM
- Write once, run anywhere 的原理
- JVM 主要組成：
  - Class Loader
  - Runtime Data Areas（見 [記憶體：Stack 與 Heap](memory-stack-and-heap.md)）
  - Execution Engine（Interpreter / JIT Compiler）
  - Garbage Collector
- GC 基本觀念與分代回收
