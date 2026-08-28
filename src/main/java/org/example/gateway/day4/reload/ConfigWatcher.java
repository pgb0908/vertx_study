package org.example.gateway.day4.reload;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;

/**
 * day3에는 없던 신규 컴포넌트. day3까지는 설정 파일을 기동 시 한 번만 읽었지만,
 * day4는 이 클래스로 파일 변경을 계속 감시해서 MainVerticle.reload()를 반복 트리거한다.
 *
 * java.nio.file.WatchService.take()는 블로킹 호출이라 이벤트 루프에서 절대 부르면 안 되고,
 * 여기서는 전용 데몬 스레드에서 무한 루프로 돌린다. 즉 이 클래스는 Vert.x Context 바깥의
 * "진짜 별개 스레드"에서 실행된다 — 그래서 감지된 변경사항을 실제로 처리할 때는
 * onChange 콜백 안에서 vertx.runOnContext(...)로 Verticle의 이벤트 루프로 되돌려줘야
 * 레이스 컨디션 없이 안전하다 (MainVerticle에서 그렇게 사용한다).
 */
public class ConfigWatcher {

    private final Path dir;
    private final Set<String> watchedFileNames;
    private final Runnable onChange;

    public ConfigWatcher(Path dir, Set<String> watchedFileNames, Runnable onChange) {
        this.dir = dir;
        this.watchedFileNames = watchedFileNames;
        this.onChange = onChange;
    }

    public void start() {
        Thread watcherThread = new Thread(this::watchLoop, "config-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            System.out.println("[watcher] watching " + dir.toAbsolutePath());

            while (true) {
                WatchKey key = watchService.take();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (context instanceof Path changed && watchedFileNames.contains(changed.getFileName().toString())) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant) {
                    onChange.run();
                }
            }
        } catch (IOException e) {
            System.err.println("[watcher] failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
