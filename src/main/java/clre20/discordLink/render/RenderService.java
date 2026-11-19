package clre20.discordLink.render;

import clre20.discordLink.DiscordLink;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Discord 圖片與外送聊天的背景工作。
 * <ul>
 *   <li>單一「繪圖」執行緒：依序處理要送到 Discord 的聊天（保持順序）與畫圖；佇列有上限，滿了就改送純文字。</li>
 *   <li>下載資源用另一條一次性的執行緒，不會擋住聊天。</li>
 * </ul>
 */
public final class RenderService {

    private static final int QUEUE = 100;

    private final DiscordLink plugin;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE), r -> {
        Thread t = new Thread(r, "DiscordLink-Render");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordLink-Assets");
        t.setDaemon(true);
        return t;
    });

    private volatile AssetPack pack;
    private volatile ImageComposer composer;
    private volatile String loadedKey = "";

    public RenderService(DiscordLink plugin) {
        this.plugin = plugin;
    }

    /** 依設定準備（或重新準備）資源；不需要時釋放。 */
    public void configure() {
        boolean want = plugin.config().discordImages() && plugin.config().showcaseEnabled();
        String lang = AssetDownloader.safeLanguage(plugin.config().imageLanguage());
        int scale = plugin.config().imageScale();
        String key = want ? lang + "@" + scale : "";
        if (key.equals(loadedKey)) {
            return;
        }
        loadedKey = key;
        String version = Bukkit.getMinecraftVersion();
        File root = new File(plugin.getDataFolder(), "assets");
        loader.execute(() -> {
            // 先在繪圖執行緒關閉舊的，避免正在畫圖時被關掉
            AssetPack old = pack;
            ImageComposer oldComposer = composer;
            composer = null;
            pack = null;
            if (old != null) {
                submitOrRun(() -> {
                    if (oldComposer != null) {
                        oldComposer.close();
                    }
                    old.close();
                });
            }
            if (!want) {
                return;
            }
            try {
                AssetPack p = AssetDownloader.prepare(root, version, lang, step -> {
                    if (step.equals("download-start")) {
                        plugin.getLogger().info(plugin.messages().console("assets-downloading"));
                    }
                });
                ImageComposer c = new ImageComposer(p, scale);
                pack = p;
                composer = c;
                plugin.getLogger().info(plugin.messages().console("assets-ready", "version", version));
            } catch (Throwable e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                plugin.getLogger().warning(plugin.messages().console("assets-failed", "reason", reason));
            }
        });
    }

    /** 圖片功能可用時回傳繪圖器（只能在繪圖執行緒使用），否則 null。 */
    public ImageComposer composer() {
        return composer;
    }

    public boolean ready() {
        return composer != null;
    }

    /** 文字（含可翻譯文字）轉純文字：資源就緒時用設定的語言，否則用伺服器內建的英文。任何執行緒都可以呼叫。 */
    public String plain(Component c) {
        ImageComposer ic = composer;
        if (ic != null) {
            try {
                return ic.flattener().plain(c);
            } catch (RuntimeException ignored) {
            }
        }
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * 排入繪圖執行緒。
     *
     * @return false 表示佇列已滿（呼叫端應改用不畫圖的方式）
     */
    public boolean submit(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void submitOrRun(Runnable task) {
        if (!submit(task)) {
            task.run();
        }
    }

    public void shutdown() {
        loader.shutdownNow();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AssetPack p = pack;
        ImageComposer c = composer;
        pack = null;
        composer = null;
        if (c != null) {
            c.close();
        }
        if (p != null) {
            p.close();
        }
    }
}
