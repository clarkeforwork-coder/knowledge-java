/**
 * 製造一個必然發生的死結（deadlock），當 jstack 的練習活靶。
 *
 * 執行：java DeadlockDemo.java
 * 程式會印出兩行後永遠卡住 —— 這時用 jps 找到 pid、jstack <pid> 觀察。
 * 看完用 Ctrl+C（或 kill <pid>）結束它。
 */
public class DeadlockDemo {
    static final Object lockA = new Object();
    static final Object lockB = new Object();

    public static void main(String[] args) {
        new Thread(() -> grab(lockA, lockB), "worker-1").start();
        new Thread(() -> grab(lockB, lockA), "worker-2").start();
    }

    static void grab(Object first, Object second) {
        synchronized (first) {
            System.out.println(Thread.currentThread().getName() + " 拿到第一把鎖，等第二把…");
            sleep(100);                    // 確保兩個 thread 都先拿到各自的第一把鎖
            synchronized (second) {        // 互相等對方手上的鎖 —— 死結
                System.out.println("不會執行到這裡");
            }
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
