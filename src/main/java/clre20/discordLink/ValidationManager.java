package clre20.discordLink;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ValidationManager {
    // 雙向快取，設定過期時間
    private final Cache<String, UUID> codeToUuid;
    private final Cache<UUID, String> uuidToCode;
    private final Random random = new Random();

    public ValidationManager(int expiryMinutes) {
        this.codeToUuid = CacheBuilder.newBuilder()
                .expireAfterWrite(expiryMinutes, TimeUnit.MINUTES).build();
        this.uuidToCode = CacheBuilder.newBuilder()
                .expireAfterWrite(expiryMinutes, TimeUnit.MINUTES).build();
    }

    public synchronized String generateOrRefreshCode(UUID uuid) {
        // 刷新機制：作廢舊代碼
        String oldCode = uuidToCode.getIfPresent(uuid);
        if (oldCode != null) {
            codeToUuid.invalidate(oldCode);
        }

        // 產生全新的 5 位數純數字代碼
        String newCode;
        do {
            newCode = String.format("%05d", random.nextInt(100000));
        } while (codeToUuid.getIfPresent(newCode) != null);

        codeToUuid.put(newCode, uuid);
        uuidToCode.put(uuid, newCode);
        return newCode;
    }

    public UUID getUuidFromCode(String code) {
        return codeToUuid.getIfPresent(code);
    }

    public void invalidateCode(String code, UUID uuid) {
        codeToUuid.invalidate(code);
        uuidToCode.invalidate(uuid);
    }
}