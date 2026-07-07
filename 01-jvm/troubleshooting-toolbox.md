# 事故排查工具箱：jps、jstack、jmap 與 heap dump

## 前言

凌晨三點被電話叫醒：服務沒回應了。或者更常見的版本：監控告警說記憶體一路爬升，快到上限了。你連上伺服器，然後呢？

大多數人的第一反應是**重啟**。服務確實會恢復——但所有證據也跟著蒸發了：卡住的 thread 不在了、吃記憶體的物件被清空了，下次再發生，你還是只能重啟。事故處理的正確順序是：**先花三十秒留下證據，再重啟**。

這篇就是那三十秒要做的事。工具全部是 JDK 內建的（裝了 JDK 就有，伺服器上不用額外安裝），[GC 基礎與分代回收](gc-basics-and-generations.md) 結尾說的「地板爬升之後怎麼辦」，也在這裡接手。

## 技術背景

### 先分流：症狀決定工具

| 症狀 | 該抓什麼 | 工具 |
|---|---|---|
| 服務卡住、沒回應、疑似死結 | thread dump | `jstack` |
| CPU 飆到 100% 下不來 | thread dump ＋ 對照 CPU 高的 thread | `top -H` ＋ `jstack` |
| 記憶體爬升、OOM | 物件統計／heap dump | `jmap` |

不管哪一種，第一步都一樣：找到 JVM 的 process id。

### jps：先找到人

`jps -l` 列出這台機器上所有的 JVM process 和主類別（或 jar 路徑）：

```bash
$ jps -l
91253 jdk.compiler/com.sun.tools.javac.launcher.Main
91377 jdk.jcmd/sun.tools.jps.Jps
```

> 用 `java Xxx.java` 單檔模式跑的程式，顯示的是 launcher 的類別名（如上）；
> 正式部署的服務會顯示 jar 路徑或 main class，一眼就能認出來。

### jstack：thread 都在幹嘛

`jstack <pid>` 印出當下每一條 thread 的呼叫堆疊（thread dump）。每條 thread 的第一行是名字和狀態，重點看狀態：

| Thread 狀態 | 意思 | 排查意義 |
|---|---|---|
| `RUNNABLE` | 正在跑（或等 CPU） | CPU 100% 時找它們 |
| `BLOCKED` | 等別人手上的 `synchronized` 鎖 | 卡住、死結的主角 |
| `WAITING` / `TIMED_WAITING` | 主動等待（`wait`、`sleep`、等 queue） | 大量堆積代表下游慢或沒工作 |

兩個常用流程：

- **服務卡住**：直接 `jstack <pid>`，它會**自動偵測死結**並在結尾報告（見下面實際案例）；不是死結的話，看大量 thread 都 BLOCKED / WAITING 在哪一行。
- **CPU 100%**（Linux）：`top -H -p <pid>` 找出吃 CPU 的 thread id → `printf '%x\n' <tid>` 轉十六進位 → 在 jstack 輸出裡搜 `nid=0x<tid>`，那條 thread 當下執行的那一行程式碼就是嫌犯。

### jmap：記憶體被誰吃了

兩個層次，由淺入深：

```bash
jmap -histo <pid> | head -20          # 快篩：物件數量/大小排行榜（開銷小）
jmap -dump:live,format=b,file=heap.hprof <pid>   # 完整 heap dump（重，見下方警語）
```

`-histo` 的 class name 欄有一套縮寫：`[B` 是 `byte[]`、`[I` 是 `int[]`、`[Ljava.lang.Object;` 是 `Object[]`。排行榜第一名通常就是嫌犯——但要注意它常是陣列，真正的問題在「**誰抓著這些陣列**」，這就需要 heap dump 配分析工具（Eclipse MAT、VisualVM）看支配關係才知道。

⚠️ 兩個警語：

- `jmap -dump:live` 和 `-histo:live` 會先觸發一次 **Full GC**（要先確定誰活著），對線上服務是一次明顯停頓——能接受再按
- 最好的 heap dump 是**事前就設好的**：`-XX:+HeapDumpOnOutOfMemoryError` 讓 JVM 在 OOM 當下自動留檔，零人工介入，這是部署時就該開的保險（[GC 基礎與分代回收](gc-basics-and-generations.md) 的參數清單裡有它）

## 實際案例

兩個活靶都在 [examples/](examples/)，可以自己開一個 terminal 跑活靶、另一個 terminal 練工具。

### 案例一：服務卡死 → jstack 抓死結

▶ 活靶：[DeadlockDemo.java](examples/DeadlockDemo.java) —— 兩條 thread 以相反順序搶兩把鎖，必然死結。

```bash
java DeadlockDemo.java        # 印兩行後永遠卡住
jps -l                        # 找到 pid
jstack <pid>
```

實測輸出（Temurin 17，節錄）。先看 thread 狀態——兩條 worker 都 `BLOCKED`：

```
"worker-1" #14 prio=5 os_prio=31 cpu=37.74ms tid=0x... nid=0x8103 waiting for monitor entry
   java.lang.Thread.State: BLOCKED (on object monitor)
```

再拉到輸出最尾端，jstack 已經自動破案：

```
Found one Java-level deadlock:
=============================
"worker-1":
  waiting to lock monitor 0x000000088ebf8000 (object 0x00000003175503a8, a java.lang.Object),
  which is held by "worker-2"

"worker-2":
  waiting to lock monitor 0x000000088ebf80e0 (object 0x0000000317550398, a java.lang.Object),
  which is held by "worker-1"

Java stack information for the threads listed above:
===================================================
"worker-1":
	at DeadlockDemo.grab(DeadlockDemo.java:22)
	- waiting to lock <0x00000003175503a8> (a java.lang.Object)
	- locked <0x0000000317550398> (a java.lang.Object)
	...
Found 1 deadlock.
```

誰等誰、誰抓著什麼、卡在**哪個檔案的第幾行**（`DeadlockDemo.java:22`），全部直接給你。死結的排查成本其實很低——難的是知道要打這個指令。

### 案例二：記憶體爬升 → jmap 抓兇手

▶ 活靶：[LeakDemo.java](examples/LeakDemo.java) —— 一個只進不出的 static cache，每 100ms 漏 256KB。

```bash
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError LeakDemo.java   # 約 20 秒後 OOM
jps -l && jmap -histo <pid> | head -8                        # 趁它活著時快篩
```

實測快篩結果（跑到 20MB 時取樣）：

```
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         14744       31730592  [B (java.base@17.0.19)
   2:          1011        3608456  [I (java.base@17.0.19)
   3:          3143         377584  java.lang.Class (java.base@17.0.19)
   4:         14468         347232  java.lang.String (java.base@17.0.19)
```

`[B`（`byte[]`）以 31MB 遙遙領先——跟第二名差了快十倍，這種斷崖式的第一名就是洩漏的長相。放著不管讓它 OOM，JVM 在斷氣前自動留下了證據：

```
java.lang.OutOfMemoryError: Java heap space
Dumping heap to java_pid91000.hprof ...
Heap dump file created [54499597 bytes in 0.044 secs]
```

這個 `.hprof` 丟進 Eclipse MAT 開「Dominator Tree」，就能看到是 `LeakDemo.cache` 這個 static List 支配著所有 byte[]——從「哪種物件在吃」前進到「**誰抓著不放**」，案子才算破。

## 技術優缺點

### JDK 內建工具的優勢

- **零安裝**：伺服器有 JDK 就有這些工具，事故當下不用求人裝東西
- **零前置**：不需要事先開 agent 或改啟動參數（`HeapDumpOnOutOfMemoryError` 除外，但那正是它的價值——事前一行，事後全自動）
- **夠用**：死結、洩漏、CPU 熱點這三大類事故，它們能解掉絕大多數

### 侷限

- **侵入性**：`jmap -dump` 會觸發 Full GC 和明顯停頓，對正在掙扎的線上服務要三思
- **單張快照**：jstack/jmap 都是「那一瞬間」的照片，間歇性問題可能拍不到——連拍幾張間隔比對是基本功，但要連續觀測就得靠 JFR 這類常駐工具（見規劃中的〈進階剖析：JFR 與 profiler〉）
- **權限**：attach 到目標 JVM 需要同一個 OS user——容器環境還得先 `docker exec` 進去，而且 image 裡要有 JDK 而不是只有 JRE

## 小結

- **先留證據再重啟**：三十秒抓 dump，換未來不用再猜
- 分流心法：卡住 → `jstack`（死結會自動偵測）；CPU 100% → `top -H` 對照 `jstack` 的 `nid`；記憶體 → `jmap -histo` 快篩、heap dump 深查
- `-histo` 第一名斷崖式領先就是洩漏的長相；但陣列只是贓物，要用 heap dump 找到「誰抓著不放」才是兇手
- `jmap -dump` 有 Full GC 代價，線上慎用；`-XX:+HeapDumpOnOutOfMemoryError` 是每個服務都該開的事前保險
- thread 狀態速讀：`BLOCKED` 在等鎖、`WAITING` 在等事、`RUNNABLE` 在燒 CPU

工具箱到這裡是「事故當下」的層次；想要常駐飛行記錄、拍到間歇性問題，見規劃中的〈進階剖析：JFR 與 profiler〉。

## 常見面試題

1. 線上服務 CPU 100%，怎麼定位到具體哪一行程式碼？（提示：`top -H` 找 thread → 十六進位 → jstack 的 `nid`）
2. 服務 OOM 了，事後要怎麼分析？部署時該先開什麼參數？（提示：`HeapDumpOnOutOfMemoryError`、MAT 的 Dominator Tree）
3. thread dump 裡 `BLOCKED` 和 `WAITING` 差在哪？（提示：一個在等鎖、一個在等通知；排查時的指向完全不同）

## 延伸閱讀

- [Java Platform Troubleshooting Guide（Java 17）](https://docs.oracle.com/en/java/javase/17/troubleshoot/) — 官方事故排查指南，本篇工具的完整版
- [jstack](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jstack.html)、[jmap](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jmap.html) 官方手冊
