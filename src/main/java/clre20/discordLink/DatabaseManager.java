package clre20.discordLink;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class DatabaseManager {
    private final String url;

    public DatabaseManager(File dataFolder, String dbName) {
        this.url = "jdbc:sqlite:" + new File(dataFolder, dbName).getAbsolutePath();
        initDatabase();
    }

    // 在 DatabaseManager 類別中修改 initDatabase 方法
    private void initDatabase() {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt1 = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS bindings (uuid VARCHAR(36) PRIMARY KEY, dc_id VARCHAR(20))");
             PreparedStatement stmt2 = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS bypass_list (uuid VARCHAR(36) PRIMARY KEY)")) {
            stmt1.execute();
            stmt2.execute(); // 建立豁免名單資料表
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean isBypassed(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM bypass_list WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public synchronized void addBypass(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR IGNORE INTO bypass_list (uuid) VALUES (?)")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized void removeBypass(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM bypass_list WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean isBound(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM bindings WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false; // 異常時預設未綁定，確保安全性
        }
    }

    public synchronized void bind(UUID uuid, String discordId) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO bindings (uuid, dc_id) VALUES (?, ?)")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, discordId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized int getBindingCount(String discordId) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM bindings WHERE dc_id = ?")) {
            stmt.setString(1, discordId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public synchronized String getDiscordId(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT dc_id FROM bindings WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("dc_id");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized java.util.List<UUID> getBoundUuids(String discordId) {
        java.util.List<UUID> list = new java.util.ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid FROM bindings WHERE dc_id = ?")) {
            stmt.setString(1, discordId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(UUID.fromString(rs.getString("uuid")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public synchronized java.util.Map<UUID, String> getAllBindings() {
        java.util.Map<UUID, String> map = new java.util.HashMap<>();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid, dc_id FROM bindings")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put(UUID.fromString(rs.getString("uuid")), rs.getString("dc_id"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    public synchronized void unbind(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM bindings WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized void unbindDiscord(String discordId) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM bindings WHERE dc_id = ?")) {
            stmt.setString(1, discordId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}