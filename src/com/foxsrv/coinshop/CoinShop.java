package com.foxsrv.coinshop;

import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;
import com.foxsrv.coincard.CoinCardPlugin.TransferCallback;
import com.foxsrv.coincard.CoinCardPlugin.BalanceCallback;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class CoinShop extends JavaPlugin implements Listener {
    // ====================================================
    // CONSTANTS & CONFIG
    // ====================================================
    private static final DecimalFormat COIN_FORMAT;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("0.########", symbols);
    }
    private FileConfiguration config;
    private File usersFolder;
    private File storeFile;
    private StoreData storeData;
    // Config values
    private String serverCardId;
    private double taxRate;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private long cooldownMs;
    // CoinCard API
    private CoinCardAPI coinCardAPI;
    // Transaction queue
    private final Queue<Transaction> transactionQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingPurchase> pendingPurchases = new ConcurrentHashMap<>();
    private BukkitTask queueProcessorTask;
    private final AtomicLong lastProcessTime = new AtomicLong(0);
    // Player session tracking
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
   
    // Pending item returns for offline players
    private final Map<UUID, List<ItemStack>> pendingItemReturns = new ConcurrentHashMap<>();
    // Cache temporário para evitar chamadas repetidas à API (apenas em memória)
    private final Map<UUID, String> cardCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cardCacheTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 5 * 60 * 1000; // 5 minutos
    // Map para controlar cooldown de compras por jogador
    private final Map<UUID, Long> playerPurchaseCooldown = new ConcurrentHashMap<>();
    private static final long PURCHASE_COOLDOWN = 1000; // 1 segundo
    // ====================================================
    // ON ENABLE / DISABLE
    // ====================================================
    @Override
    public void onEnable() {
        // Check if CoinCard is installed
        if (!setupCoinCardAPI()) {
            getLogger().severe("CoinCard plugin not found! Disabling CoinShop...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
       
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveResource("config.yml", false);
        }
       
        loadConfig();
        setupFolders();
        createExampleUser();
        loadStoreData();
        loadPendingReturns();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("coinshop")).setExecutor(new CoinShopCommand());
        Objects.requireNonNull(getCommand("cshop")).setExecutor(new CoinShopCommand());
        startQueueProcessor();
        COIN_FORMAT.setRoundingMode(RoundingMode.DOWN);
        COIN_FORMAT.setMinimumFractionDigits(0);
        COIN_FORMAT.setMaximumFractionDigits(8);
        getLogger().info("CoinShop v" + getDescription().getVersion() + " enabled successfully with CoinCard integration!");
        getLogger().info("Data folder: " + getDataFolder().getAbsolutePath());
    }
    @Override
    public void onDisable() {
        if (queueProcessorTask != null) {
            queueProcessorTask.cancel();
        }
        saveStoreData();
        savePendingReturns();
        cardCache.clear();
        cardCacheTimestamp.clear();
        getLogger().info("CoinShop disabled.");
    }
    // ====================================================
    // COINCARD API SETUP
    // ====================================================
    private boolean setupCoinCardAPI() {
        try {
            RegisteredServiceProvider<CoinCardAPI> provider =
                getServer().getServicesManager().getRegistration(CoinCardAPI.class);
            if (provider == null) {
                return false;
            }
            coinCardAPI = provider.getProvider();
            return coinCardAPI != null;
        } catch (Exception e) {
            getLogger().severe("Failed to setup CoinCard API: " + e.getMessage());
            return false;
        }
    }
    // ====================================================
    // CONFIGURATION
    // ====================================================
    private void loadConfig() {
        reloadConfig();
        config = getConfig();
        config.addDefault("Server", "");
        config.addDefault("Tax", 0.1);
        config.addDefault("Min", 0.00000001);
        config.addDefault("Max", 1000.0);
        config.addDefault("Cooldown", 1000);
        config.options().copyDefaults(true);
        saveConfig();
        serverCardId = config.getString("Server", "");
        taxRate = config.getDouble("Tax", 0.1);
        minPrice = BigDecimal.valueOf(config.getDouble("Min", 0.00000001));
        maxPrice = BigDecimal.valueOf(config.getDouble("Max", 1000.0));
        cooldownMs = config.getLong("Cooldown", 1000);
    }
    private void setupFolders() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        usersFolder = new File(getDataFolder(), "users");
        if (!usersFolder.exists()) {
            usersFolder.mkdirs();
            getLogger().info("Created users folder: " + usersFolder.getAbsolutePath());
        }
        storeFile = new File(getDataFolder(), "store.dat");
    }
    private void createExampleUser() {
        File exampleFile = new File(usersFolder, "example-uuid.yml");
        if (!exampleFile.exists()) {
            try {
                YamlConfiguration example = new YamlConfiguration();
                example.set("Name", "Example Shop");
                example.save(exampleFile);
                getLogger().info("Created example user file: " + exampleFile.getName());
            } catch (IOException e) {
                getLogger().warning("Could not create example user file: " + e.getMessage());
            }
        }
    }
    // ====================================================
    // DATA STORAGE
    // ====================================================
    private void loadStoreData() {
        if (storeFile.exists()) {
            try (Reader reader = new FileReader(storeFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                storeData = new StoreData();
               
                if (jsonObject.has("items")) {
                    JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                    for (JsonElement element : itemsArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                       
                        String transactionId = itemObj.has("transactionId") ? itemObj.get("transactionId").getAsString() : UUID.randomUUID().toString();
                       
                        // Handle potential null UUID
                        UUID sellerUuid = null;
                        if (itemObj.has("sellerUuid") && !itemObj.get("sellerUuid").isJsonNull()) {
                            try {
                                sellerUuid = UUID.fromString(itemObj.get("sellerUuid").getAsString());
                            } catch (IllegalArgumentException e) {
                                getLogger().warning("Invalid seller UUID in store data");
                                continue;
                            }
                        }
                       
                        String sellerName = itemObj.has("sellerName") ? itemObj.get("sellerName").getAsString() : "";
                        String sellerShopName = itemObj.has("sellerShopName") ? itemObj.get("sellerShopName").getAsString() : "";
                       
                        ItemStack item = null;
                        if (itemObj.has("item")) {
                            try {
                                String itemBase64 = itemObj.get("item").getAsString();
                                byte[] data = Base64.getDecoder().decode(itemBase64);
                                ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                                try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                                    item = (ItemStack) dataInput.readObject();
                                }
                            } catch (Exception e) {
                                getLogger().warning("Failed to decode item: " + e.getMessage());
                            }
                        }
                       
                        BigDecimal price = itemObj.has("price") ? new BigDecimal(itemObj.get("price").getAsString()) : BigDecimal.ZERO;
                        long listedAt = itemObj.has("listedAt") ? itemObj.get("listedAt").getAsLong() : System.currentTimeMillis();
                       
                        if (sellerUuid != null && item != null) {
                            ShopItem shopItem = new ShopItem(
                                transactionId, sellerUuid, sellerName, sellerShopName, item, price, listedAt
                            );
                            storeData.items.add(shopItem);
                        }
                    }
                }
               
                getLogger().info("Loaded " + storeData.items.size() + " items from store data.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load store data, creating new", e);
                storeData = new StoreData();
            }
        } else {
            storeData = new StoreData();
            getLogger().info("Created new store data file.");
        }
        long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        int before = storeData.items.size();
        storeData.items.removeIf(item -> item.listedAt < cutoff);
        int after = storeData.items.size();
        if (before != after) {
            getLogger().info("Removed " + (before - after) + " expired listings.");
        }
    }
    private void saveStoreData() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
           
            JsonObject jsonObject = new JsonObject();
            JsonArray itemsArray = new JsonArray();
           
            for (ShopItem shopItem : storeData.items) {
                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("transactionId", shopItem.transactionId);
                if (shopItem.sellerUuid != null) {
                    itemObj.addProperty("sellerUuid", shopItem.sellerUuid.toString());
                }
                itemObj.addProperty("sellerName", shopItem.sellerName);
                itemObj.addProperty("sellerShopName", shopItem.sellerShopName);
               
                try {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                        dataOutput.writeObject(shopItem.item);
                    }
                    itemObj.addProperty("item", Base64.getEncoder().encodeToString(outputStream.toByteArray()));
                } catch (Exception e) {
                    getLogger().warning("Failed to encode item: " + e.getMessage());
                }
               
                itemObj.addProperty("price", shopItem.price.toString());
                itemObj.addProperty("listedAt", shopItem.listedAt);
               
                itemsArray.add(itemObj);
            }
           
            jsonObject.add("items", itemsArray);
           
            try (Writer writer = new FileWriter(storeFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save store data", e);
        }
    }
    private void saveStoreDataAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveStoreData();
            }
        }.runTaskAsynchronously(CoinShop.this);
    }
    private void loadPendingReturns() {
        File pendingFile = new File(getDataFolder(), "pending_returns.dat");
        if (pendingFile.exists()) {
            try (Reader reader = new FileReader(pendingFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("returns")) {
                    JsonArray returnsArray = jsonObject.getAsJsonArray("returns");
                    for (JsonElement element : returnsArray) {
                        JsonObject returnObj = element.getAsJsonObject();
                        UUID playerUuid = UUID.fromString(returnObj.get("uuid").getAsString());
                        JsonArray itemsArray = returnObj.getAsJsonArray("items");
                        List<ItemStack> items = new ArrayList<>();
                       
                        for (JsonElement itemElement : itemsArray) {
                            try {
                                String itemBase64 = itemElement.getAsString();
                                byte[] data = Base64.getDecoder().decode(itemBase64);
                                ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                                try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                                    items.add((ItemStack) dataInput.readObject());
                                }
                            } catch (Exception e) {
                                getLogger().warning("Failed to decode pending return item: " + e.getMessage());
                            }
                        }
                       
                        if (!items.isEmpty()) {
                            pendingItemReturns.put(playerUuid, items);
                        }
                    }
                }
                getLogger().info("Loaded " + pendingItemReturns.size() + " pending item returns.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load pending returns", e);
            }
        }
    }
    private void savePendingReturns() {
        File pendingFile = new File(getDataFolder(), "pending_returns.dat");
        try {
            JsonObject jsonObject = new JsonObject();
            JsonArray returnsArray = new JsonArray();
           
            for (Map.Entry<UUID, List<ItemStack>> entry : pendingItemReturns.entrySet()) {
                JsonObject returnObj = new JsonObject();
                returnObj.addProperty("uuid", entry.getKey().toString());
               
                JsonArray itemsArray = new JsonArray();
                for (ItemStack item : entry.getValue()) {
                    try {
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                            dataOutput.writeObject(item);
                        }
                        itemsArray.add(Base64.getEncoder().encodeToString(outputStream.toByteArray()));
                    } catch (Exception e) {
                        getLogger().warning("Failed to encode pending return item: " + e.getMessage());
                    }
                }
               
                returnObj.add("items", itemsArray);
                returnsArray.add(returnObj);
            }
           
            jsonObject.add("returns", returnsArray);
           
            try (Writer writer = new FileWriter(pendingFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save pending returns", e);
        }
    }
    private void savePendingReturnsAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                savePendingReturns();
            }
        }.runTaskAsynchronously(CoinShop.this);
    }
    // ====================================================
    // PLAYER DATA (APENAS NOME DA LOJA)
    // ====================================================
    private PlayerData getPlayerData(UUID uuid) {
        if (uuid == null) {
            return null;
        }
       
        File playerFile = new File(usersFolder, uuid.toString() + ".yml");
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(playerFile);
        String name = yamlConfig.getString("Name");
        if (name == null || name.isEmpty()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            name = (offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown") + "'s shop";
        }
        return new PlayerData(uuid, name, playerFile);
    }
    private void savePlayerData(PlayerData data) {
        if (data == null || data.uuid == null) {
            return;
        }
       
        try {
            if (!usersFolder.exists()) {
                usersFolder.mkdirs();
            }
           
            YamlConfiguration yamlConfig = new YamlConfiguration();
            yamlConfig.set("Name", data.shopName);
            yamlConfig.save(data.file);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save player data for " + data.uuid, e);
        }
    }
    // ====================================================
    // COINCARD API COMMUNICATION (VIA API DO PLUGIN)
    // ====================================================
   
    /**
     * Obtém o card ID de um jogador usando a API do CoinCard
     */
    private String getPlayerCardId(UUID uuid) {
        if (uuid == null) return null;
       
        // Verificar cache primeiro
        Long cachedTime = cardCacheTimestamp.get(uuid);
        if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CACHE_DURATION) {
            String cached = cardCache.get(uuid);
            if (cached != null) return cached;
        }
       
        // Chamar a API para obter o card
        String cardId = coinCardAPI.getPlayerCard(uuid);
       
        // Atualizar cache
        if (cardId != null && !cardId.isEmpty()) {
            cardCache.put(uuid, cardId);
            cardCacheTimestamp.put(uuid, System.currentTimeMillis());
        }
       
        return cardId;
    }
   
    /**
     * Obtém o card ID de um jogador pelo nome (offline)
     */
    private String getPlayerCardIdByNick(String nick) {
        if (nick == null || nick.isEmpty()) return null;
       
        // Primeiro tenta encontrar online
        Player player = Bukkit.getPlayerExact(nick);
        if (player != null) {
            return getPlayerCardId(player.getUniqueId());
        }
       
        // Se não estiver online, usa a API diretamente
        return coinCardAPI.getPlayerCardByNick(nick);
    }
   
    /**
     * Verifica se um jogador tem card configurado
     */
    private boolean hasPlayerCard(UUID uuid) {
        if (uuid == null) return false;
       
        // Verificar cache primeiro
        if (cardCache.containsKey(uuid)) {
            String cached = cardCache.get(uuid);
            return cached != null && !cached.isEmpty();
        }
       
        // Chamar a API
        boolean hasCard = coinCardAPI.hasCard(uuid);
       
        // Se tiver card, atualizar cache
        if (hasCard) {
            String cardId = coinCardAPI.getPlayerCard(uuid);
            if (cardId != null && !cardId.isEmpty()) {
                cardCache.put(uuid, cardId);
                cardCacheTimestamp.put(uuid, System.currentTimeMillis());
            }
        }
       
        return hasCard;
    }
    private void transferWithCoinCard(String fromCard, String toCard, BigDecimal amount,
                                       Transaction transaction, PendingPurchase purchase) {
        if (fromCard == null || toCard == null || amount == null || transaction == null || purchase == null) {
            getLogger().warning("Invalid parameters for transfer");
            if (transaction != null && purchase != null) {
                handleFailedTransaction(transaction, purchase, "Invalid transfer parameters");
            }
            return;
        }
       
        double amountDouble = amount.doubleValue();
        coinCardAPI.transfer(fromCard, toCard, amountDouble, new TransferCallback() {
            @Override
            public void onSuccess(String txId, double amount) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleSuccessfulTransaction(transaction, purchase, txId, BigDecimal.valueOf(amount));
                    }
                }.runTask(CoinShop.this);
            }
            @Override
            public void onFailure(String error) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleFailedTransaction(transaction, purchase, error);
                    }
                }.runTask(CoinShop.this);
            }
        });
    }
    private void checkPlayerBalance(String cardId, BalanceCheckCallback callback) {
        if (cardId == null || cardId.isEmpty()) {
            if (callback != null) {
                callback.onFailure("Invalid card ID");
            }
            return;
        }
       
        coinCardAPI.getBalance(cardId, new BalanceCallback() {
            @Override
            public void onResult(double balance, String error) {
                if (callback != null) {
                    if (error != null && !error.isEmpty()) {
                        callback.onFailure(error);
                    } else {
                        callback.onSuccess(BigDecimal.valueOf(balance));
                    }
                }
            }
        });
    }
    private void getPlayerBalanceByUUID(UUID playerUuid, BalanceCheckCallback callback) {
        if (playerUuid == null) {
            if (callback != null) {
                callback.onFailure("Invalid player UUID");
            }
            return;
        }
       
        coinCardAPI.getPlayerBalance(playerUuid, new BalanceCallback() {
            @Override
            public void onResult(double balance, String error) {
                if (callback != null) {
                    if (error != null && !error.isEmpty()) {
                        callback.onFailure(error);
                    } else {
                        callback.onSuccess(BigDecimal.valueOf(balance));
                    }
                }
            }
        });
    }
    // ====================================================
    // QUEUE PROCESSOR
    // ====================================================
    private void startQueueProcessor() {
        queueProcessorTask = new BukkitRunnable() {
            @Override
            public void run() {
                processQueue();
            }
        }.runTaskTimer(this, 20L, Math.max(1, cooldownMs / 50));
    }
    private void processQueue() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime.get() < cooldownMs) {
            return;
        }
        Transaction transaction = transactionQueue.poll();
        if (transaction == null) {
            return;
        }
        lastProcessTime.set(now);
        // Find associated purchase
        PendingPurchase purchase = pendingPurchases.get(transaction.id);
        if (purchase == null) {
            getLogger().warning("No pending purchase found for transaction: " + transaction.id);
            return;
        }
        // Process transaction with CoinCard API
        transferWithCoinCard(transaction.fromCard, transaction.toCard, transaction.amount,
                            transaction, purchase);
    }
    private void handleSuccessfulTransaction(Transaction transaction, PendingPurchase purchase,
                                              String txId, BigDecimal actualAmount) {
        if (transaction == null || purchase == null) {
            getLogger().warning("handleSuccessfulTransaction called with null parameters");
            return;
        }
       
        purchase.completed = true;
        purchase.txId = txId;
        // Safe player retrieval with null checks
        Player buyer = purchase.buyerUuid != null ? Bukkit.getPlayer(purchase.buyerUuid) : null;
        Player seller = purchase.sellerUuid != null ? Bukkit.getPlayer(purchase.sellerUuid) : null;
       
        // Get seller's shop name from store data
        String sellerShopName = "";
        for (ShopItem item : storeData.items) {
            if (item != null && item.transactionId != null && item.transactionId.equals(transaction.id)) {
                sellerShopName = item.sellerShopName != null ? item.sellerShopName : "";
                break;
            }
        }
       
        BigDecimal taxAmount = transaction.amount.multiply(BigDecimal.valueOf(taxRate))
                .setScale(8, RoundingMode.DOWN);
        BigDecimal sellerAmount = purchase.price;
        // Give items to buyer
        if (!purchase.items.isEmpty() && purchase.items.get(0) != null) {
            String itemName = purchase.items.get(0).getType().toString();
            if (purchase.items.get(0).getItemMeta() != null &&
                purchase.items.get(0).getItemMeta().hasDisplayName()) {
                itemName = purchase.items.get(0).getItemMeta().getDisplayName();
            }
           
            // Send sale success message ONLY to the seller (shop owner)
            if (seller != null && seller.isOnline()) {
                seller.sendMessage(ChatColor.GREEN + "= " + ChatColor.GOLD + "ITEM SOLD! " + ChatColor.GREEN + "=");
                seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + purchase.items.get(0).getAmount() + "x " + itemName);
                seller.sendMessage(ChatColor.GRAY + "Buyer: " + ChatColor.WHITE + (buyer != null ? buyer.getName() : "Unknown"));
                seller.sendMessage(ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(purchase.price));
                if (taxRate > 0) {
                    seller.sendMessage(ChatColor.GRAY + "Tax (" + (taxRate * 100) + "%): " + ChatColor.RED + "-" + formatCoin(taxAmount));
                    seller.sendMessage(ChatColor.GRAY + "You received: " + ChatColor.GREEN + formatCoin(sellerAmount));
                }
                seller.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE + (txId != null ? txId.substring(0, 8) + "..." : "unknown"));
                seller.sendMessage(ChatColor.GREEN + "The amount has been credited to your card!");
            }
            // Give items to buyer
            if (buyer != null && buyer.isOnline()) {
                for (ItemStack item : purchase.items) {
                    if (item != null) {
                        HashMap<Integer, ItemStack> leftover = buyer.getInventory().addItem(item.clone());
                        if (!leftover.isEmpty()) {
                            World world = buyer.getWorld();
                            Location loc = buyer.getLocation();
                            for (ItemStack drop : leftover.values()) {
                                if (drop != null) {
                                    world.dropItemNaturally(loc, drop);
                                }
                            }
                            buyer.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
                        }
                    }
                }
                buyer.sendMessage(ChatColor.GREEN + "Your purchase of " + formatCoin(transaction.amount) +
                        " coins has been completed! Transaction ID: " + (txId != null ? txId.substring(0, 8) + "..." : "unknown"));
            }
        }
        // Process tax if applicable
        if (taxRate > 0 && serverCardId != null && !serverCardId.isEmpty() &&
            taxAmount != null && taxAmount.compareTo(BigDecimal.ZERO) > 0) {
            processTaxTransaction(transaction.toCard, taxAmount, transaction.id);
        }
        pendingPurchases.remove(transaction.id);
        storeData.items.removeIf(item -> item != null && item.transactionId != null && item.transactionId.equals(transaction.id));
        saveStoreDataAsync();
       
        // Atualizar todas as lojas abertas após 1 segundo
        new BukkitRunnable() {
            @Override
            public void run() {
                refreshAllOpenShops();
            }
        }.runTaskLater(this, 20L); // 1 segundo = 20 ticks
    }
    private void processTaxTransaction(String fromCard, BigDecimal taxAmount, String originalTxId) {
        if (fromCard == null || taxAmount == null || serverCardId == null || serverCardId.isEmpty()) {
            return;
        }
       
        String taxTxId = UUID.randomUUID().toString();
        Transaction taxTransaction = new Transaction(
                taxTxId,
                fromCard,
                serverCardId,
                taxAmount
        );
        // Create a temporary purchase for tax tracking
        PendingPurchase taxPurchase = new PendingPurchase(
                taxTxId,
                null,
                null,
                Collections.emptyList(),
                taxAmount
        );
        pendingPurchases.put(taxTxId, taxPurchase);
        transferWithCoinCard(fromCard, serverCardId, taxAmount, taxTransaction, taxPurchase);
    }
    private void handleFailedTransaction(Transaction transaction, PendingPurchase purchase, String error) {
        if (transaction == null || purchase == null) {
            getLogger().warning("handleFailedTransaction called with null parameters");
            return;
        }
       
        Player buyer = purchase.buyerUuid != null ? Bukkit.getPlayer(purchase.buyerUuid) : null;
        Player seller = purchase.sellerUuid != null ? Bukkit.getPlayer(purchase.sellerUuid) : null;
       
        // Get seller's data
        PlayerData sellerData = purchase.sellerUuid != null ? getPlayerData(purchase.sellerUuid) : null;
       
        // Recreate the item in the shop
        for (ItemStack item : purchase.items) {
            if (item != null) {
                ShopItem shopItem = new ShopItem(
                    transaction.id,
                    purchase.sellerUuid,
                    sellerData != null ? sellerData.shopName : "Unknown",
                    sellerData != null ? sellerData.shopName : "Unknown",
                    item,
                    purchase.price,
                    System.currentTimeMillis()
                );
               
                storeData.items.add(shopItem);
            }
        }
       
        saveStoreDataAsync();
        if (buyer != null && buyer.isOnline()) {
            buyer.sendMessage(ChatColor.RED + "[FAILED] Transaction failed: " + (error != null ? error : "Unknown error"));
            buyer.sendMessage(ChatColor.YELLOW + "The item has been returned to the shop. You were not charged.");
        }
        // Notify the seller about the failure
        if (seller != null && seller.isOnline() && !purchase.items.isEmpty() && purchase.items.get(0) != null) {
            String itemName = purchase.items.get(0).getType().toString();
            if (purchase.items.get(0).getItemMeta() != null &&
                purchase.items.get(0).getItemMeta().hasDisplayName()) {
                itemName = purchase.items.get(0).getItemMeta().getDisplayName();
            }
           
            seller.sendMessage(ChatColor.RED + "[FAILED] " + ChatColor.YELLOW + "SALE FAILED!");
            seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + purchase.items.get(0).getAmount() + "x " + itemName);
            seller.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.RED + (error != null ? error : "Unknown error"));
            seller.sendMessage(ChatColor.GREEN + "The item has been returned to your shop inventory.");
        }
        pendingPurchases.remove(transaction.id);
        saveStoreDataAsync();
       
        // Atualizar todas as lojas abertas
        refreshAllOpenShops();
    }
    // ====================================================
    // SHOP LOGIC
    // ====================================================
    private boolean createListing(Player player, ItemStack item, BigDecimal price) {
        if (player == null || item == null || price == null) {
            return false;
        }
       
        if (price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
            player.sendMessage(ChatColor.RED + "Price must be between " + formatCoin(minPrice) +
                    " and " + formatCoin(maxPrice));
            return false;
        }
        // Verificar se o jogador tem card usando a API
        if (!hasPlayerCard(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            return false;
        }
        String transactionId = UUID.randomUUID().toString();
        PlayerData data = getPlayerData(player.getUniqueId());
       
        ShopItem shopItem = new ShopItem(
                transactionId,
                player.getUniqueId(),
                player.getName(),
                data.shopName != null ? data.shopName : player.getName() + "'s shop",
                item.clone(),
                price,
                System.currentTimeMillis()
        );
        storeData.items.add(shopItem);
        saveStoreDataAsync();
        // Send global announcement when item is LISTED for sale
        String itemName = item.getType().toString();
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            itemName = item.getItemMeta().getDisplayName();
        }
       
        BigDecimal totalWithTax = price.add(price.multiply(BigDecimal.valueOf(taxRate)))
                .setScale(8, RoundingMode.DOWN);
       
        String announcement = ChatColor.GOLD + "= " + ChatColor.GREEN + "NEW LISTING " + ChatColor.GOLD + "=\n" +
                ChatColor.YELLOW + (data.shopName != null ? data.shopName : player.getName() + "'s shop") +
                ChatColor.GRAY + " is selling " +
                ChatColor.AQUA + item.getAmount() + "x " + itemName + "\n" +
                ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(price) +
                ChatColor.GRAY + " (Total with " + (int)(taxRate * 100) + "% tax: " +
                ChatColor.YELLOW + formatCoin(totalWithTax) + ChatColor.GRAY + ")";
       
        Bukkit.broadcastMessage(announcement);
        player.sendMessage(ChatColor.GREEN + "Item listed for sale at " + formatCoin(price) + " coins!");
        return true;
    }
    private boolean purchaseItem(Player buyer, ShopItem item) {
        if (buyer == null || item == null) {
            return false;
        }
       
        // Verificar cooldown de compra
        Long lastPurchase = playerPurchaseCooldown.get(buyer.getUniqueId());
        long now = System.currentTimeMillis();
        if (lastPurchase != null && (now - lastPurchase) < PURCHASE_COOLDOWN) {
            return false; // Silenciosamente ignorar cliques muito rápidos
        }
       
        // Verificar se o item ainda está disponível
        if (!storeData.items.contains(item)) {
            buyer.sendMessage(ChatColor.RED + "This item is no longer available!");
            refreshPlayerShop(buyer); // Atualizar a loja do jogador
            return false;
        }
        // Verificar se o comprador tem card usando a API
        if (!hasPlayerCard(buyer.getUniqueId())) {
            buyer.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            return false;
        }
        // Verificar se o vendedor tem card usando a API
        String sellerCardId = getPlayerCardId(item.sellerUuid);
        if (sellerCardId == null || sellerCardId.isEmpty()) {
            buyer.sendMessage(ChatColor.RED + "The seller doesn't have a card set! This item cannot be purchased.");
           
            // Remover item da loja
            storeData.items.remove(item);
            saveStoreDataAsync();
           
            // Tentar devolver ao vendedor se online
            Player seller = Bukkit.getPlayer(item.sellerUuid);
            if (seller != null && seller.isOnline()) {
                HashMap<Integer, ItemStack> leftover = seller.getInventory().addItem(item.item.clone());
                if (!leftover.isEmpty()) {
                    World world = seller.getWorld();
                    Location loc = seller.getLocation();
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            world.dropItemNaturally(loc, drop);
                        }
                    }
                    seller.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
                }
                seller.sendMessage(ChatColor.RED + "Your listing was removed because you don't have a card set!");
            } else {
                // Store items for later return
                pendingItemReturns.computeIfAbsent(item.sellerUuid, k -> new ArrayList<>())
                        .add(item.item.clone());
                savePendingReturnsAsync();
            }
           
            refreshPlayerShop(buyer);
            return false;
        }
        // Check if buyer has enough balance using getPlayerBalance
        getPlayerBalanceByUUID(buyer.getUniqueId(), new BalanceCheckCallback() {
            @Override
            public void onSuccess(BigDecimal balance) {
                BigDecimal totalPrice = item.price;
                if (taxRate > 0) {
                    totalPrice = totalPrice.add(totalPrice.multiply(BigDecimal.valueOf(taxRate)))
                            .setScale(8, RoundingMode.DOWN);
                }
                if (balance.compareTo(totalPrice) < 0) {
                    buyer.sendMessage(ChatColor.RED + "Insufficient balance! You need " +
                            formatCoin(totalPrice) + " but have " + formatCoin(balance));
                    return;
                }
                String buyerCardId = getPlayerCardId(buyer.getUniqueId());
                if (buyerCardId == null || buyerCardId.isEmpty()) {
                    buyer.sendMessage(ChatColor.RED + "Error: Could not retrieve your card ID!");
                    return;
                }
                String transactionId = UUID.randomUUID().toString();
                Transaction transaction = new Transaction(
                        transactionId,
                        buyerCardId,
                        sellerCardId,
                        totalPrice
                );
                PendingPurchase purchase = new PendingPurchase(
                        transactionId,
                        buyer.getUniqueId(),
                        item.sellerUuid,
                        Collections.singletonList(item.item.clone()),
                        item.price
                );
                pendingPurchases.put(transactionId, purchase);
                storeData.items.remove(item);
                saveStoreDataAsync();
                transactionQueue.add(transaction);
                // Atualizar cooldown
                playerPurchaseCooldown.put(buyer.getUniqueId(), System.currentTimeMillis());
               
                buyer.sendMessage(ChatColor.YELLOW + "Purchase queued! You will receive your items once the transaction completes.");
               
                // Atualizar a loja do comprador imediatamente
                refreshPlayerShop(buyer);
            }
            @Override
            public void onFailure(String error) {
                buyer.sendMessage(ChatColor.RED + "Failed to check balance: " + (error != null ? error : "Unknown error"));
            }
        });
        return true;
    }
    private void refreshAllOpenShops() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerShop(player);
        }
    }
   
    private void refreshPlayerShop(Player player) {
        if (player == null || !player.isOnline()) return;
       
        Inventory openInv = player.getOpenInventory().getTopInventory();
        if (openInv.getHolder() instanceof ShopInventoryHolder) {
            ShopInventoryHolder holder = (ShopInventoryHolder) openInv.getHolder();
           
            // Recriar o inventário baseado no tipo atual
            switch (holder.getType()) {
                case MAIN_MENU:
                    player.openInventory(buildMainMenu(player));
                    break;
                case CATEGORIES_MENU:
                    player.openInventory(buildCategoriesMenu(player));
                    break;
                case CATEGORY_SHOP:
                    if (holder.getCategory() != null) {
                        player.openInventory(buildCategoryShopMenu(player, holder.getCategory(), holder.getPage()));
                    }
                    break;
                case MY_SHOP:
                    player.openInventory(buildMyShopMenu(player, holder.getPage()));
                    break;
                case GLOBAL_SHOP:
                    player.openInventory(buildGlobalShopMenu(player, holder.getPage()));
                    break;
            }
        }
    }
    private boolean cancelListing(Player player, ShopItem item) {
        if (player == null || item == null) {
            return false;
        }
       
        if (!item.sellerUuid.equals(player.getUniqueId()) && !player.hasPermission("coinshop.admin")) {
            player.sendMessage(ChatColor.RED + "This is not your item to cancel!");
            return false;
        }
        if (storeData.items.remove(item)) {
            // Try to give items to seller if online
            Player seller = Bukkit.getPlayer(item.sellerUuid);
            if (seller != null && seller.isOnline()) {
                HashMap<Integer, ItemStack> leftover = seller.getInventory().addItem(item.item.clone());
                if (!leftover.isEmpty()) {
                    World world = seller.getWorld();
                    Location loc = seller.getLocation();
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            world.dropItemNaturally(loc, drop);
                        }
                    }
                    seller.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
                }
                seller.sendMessage(ChatColor.GREEN + "Your item has been returned to you.");
            } else {
                // Store items for later return
                pendingItemReturns.computeIfAbsent(item.sellerUuid, k -> new ArrayList<>())
                        .add(item.item.clone());
                savePendingReturnsAsync();
                player.sendMessage(ChatColor.GREEN + "Item removed from shop. It will be returned when the seller comes online.");
            }
            saveStoreDataAsync();
            refreshAllOpenShops();
            return true;
        }
        player.sendMessage(ChatColor.RED + "Item no longer exists in shop!");
        return false;
    }
    private void returnPendingItems(Player player) {
        List<ItemStack> items = pendingItemReturns.remove(player.getUniqueId());
        if (items != null && !items.isEmpty()) {
            for (ItemStack item : items) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    World world = player.getWorld();
                    Location loc = player.getLocation();
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            world.dropItemNaturally(loc, drop);
                        }
                    }
                }
            }
            player.sendMessage(ChatColor.GREEN + "You have received " + items.size() +
                    " item(s) that were removed from the shop while you were offline.");
            savePendingReturnsAsync();
        }
    }
    // ====================================================
    // SHOP INVENTORY HOLDER
    // ====================================================
    public static class ShopInventoryHolder implements InventoryHolder {
        public enum Type {
            MAIN_MENU,
            CATEGORIES_MENU,
            CATEGORY_SHOP,
            MY_SHOP,
            GLOBAL_SHOP
        }
        private final Type type;
        private final ItemCategory category;
        private final int page;
        private final UUID viewerUuid;
        public ShopInventoryHolder(Type type, ItemCategory category, int page, UUID viewerUuid) {
            this.type = type;
            this.category = category;
            this.page = page;
            this.viewerUuid = viewerUuid;
        }
        @Override
        public Inventory getInventory() {
            return null;
        }
        public Type getType() {
            return type;
        }
        public ItemCategory getCategory() {
            return category;
        }
        public int getPage() {
            return page;
        }
        public UUID getViewerUuid() {
            return viewerUuid;
        }
    }
    // ====================================================
    // CATEGORY SYSTEM
    // ====================================================
    public enum ItemCategory {
        ALL("All Items", Material.COMPASS, "All items in the shop"),
        TOOLS("Tools", Material.DIAMOND_PICKAXE, "Pickaxes, axes, shovels, hoes"),
        WEAPONS("Weapons", Material.DIAMOND_SWORD, "Swords, bows, arrows"),
        ARMOR("Armor", Material.DIAMOND_CHESTPLATE, "Helmets, chestplates, leggings, boots"),
        FOOD("Food", Material.COOKED_BEEF, "All types of food"),
        BLOCKS("Blocks", Material.GRASS_BLOCK, "Building blocks"),
        REDSTONE("Redstone", Material.REDSTONE, "Redstone components and mechanisms"),
        POTIONS("Potions", Material.POTION, "Potions and brewing items"),
        ENCHANTING("Enchanting", Material.ENCHANTING_TABLE, "Enchanting books and tables"),
        TRANSPORTATION("Transportation", Material.MINECART, "Minecarts, rails, boats"),
        DECORATION("Decoration", Material.PAINTING, "Decorative items"),
        MISC("Miscellaneous", Material.CHEST, "Other items");
        private final String displayName;
        private final Material icon;
        private final String description;
        ItemCategory(String displayName, Material icon, String description) {
            this.displayName = displayName;
            this.icon = icon;
            this.description = description;
        }
        public String getDisplayName() {
            return displayName;
        }
        public Material getIcon() {
            return icon;
        }
        public String getDescription() {
            return description;
        }
        public static ItemCategory fromMaterial(Material material) {
            String materialName = material.name();
           
            if (materialName.contains("PICKAXE") || materialName.contains("AXE") ||
                materialName.contains("SHOVEL") || materialName.contains("HOE") ||
                materialName.contains("FISHING_ROD") || materialName.contains("SHEARS") ||
                materialName.contains("FLINT_AND_STEEL")) {
                return TOOLS;
            }
            if (materialName.contains("SWORD") || materialName.contains("BOW") ||
                materialName.contains("ARROW") || materialName.contains("TRIDENT") ||
                materialName.contains("CROSSBOW") || materialName.contains("SHIELD") ||
                materialName.contains("SPECTRAL_ARROW") || materialName.contains("TIPPED_ARROW")) {
                return WEAPONS;
            }
            if (materialName.contains("HELMET") || materialName.contains("CHESTPLATE") ||
                materialName.contains("LEGGINGS") || materialName.contains("BOOTS") ||
                materialName.contains("TURTLE_HELMET") || materialName.contains("ELYTRA") ||
                materialName.contains("HORSE_ARMOR")) {
                return ARMOR;
            }
            if (material.isEdible() || material == Material.MUSHROOM_STEW ||
                material == Material.RABBIT_STEW || material == Material.BEETROOT_SOUP ||
                material == Material.SUSPICIOUS_STEW || material == Material.HONEY_BOTTLE) {
                return FOOD;
            }
            if (material.isBlock()) {
                if (materialName.contains("REDSTONE") || materialName.contains("PISTON") ||
                    materialName.contains("REPEATER") || materialName.contains("COMPARATOR") ||
                    materialName.contains("OBSERVER") || materialName.contains("HOPPER") ||
                    materialName.contains("DROPPER") || materialName.contains("DISPENSER") ||
                    materialName.contains("RAIL") || materialName.contains("DETECTOR") ||
                    materialName.contains("TARGET") || materialName.contains("DAYLIGHT") ||
                    materialName.contains("LEVER") || materialName.contains("BUTTON") ||
                    materialName.contains("PRESSURE_PLATE") || materialName.contains("TRIPWIRE") ||
                    materialName.contains("LECTERN") || materialName.contains("BELL") ||
                    materialName.contains("LANTERN") || materialName.contains("CAMPFIRE")) {
                    return REDSTONE;
                }
                return BLOCKS;
            }
            if (materialName.contains("POTION") || materialName.contains("BREWING") ||
                materialName.contains("NETHER_WART") || materialName.contains("GLASS_BOTTLE") ||
                materialName.contains("DRAGON_BREATH") || materialName.contains("GUNPOWDER") ||
                materialName.contains("BLAZE_POWDER") || materialName.contains("MAGMA_CREAM") ||
                materialName.contains("FERMENTED_SPIDER_EYE") || materialName.contains("GLISTERING_MELON")) {
                return POTIONS;
            }
            if (materialName.contains("ENCHANT") || materialName.contains("BOOKSHELF") ||
                materialName.contains("EXPERIENCE") || materialName.contains("LAPIS") ||
                materialName.contains("ANVIL") || materialName.contains("GRINDSTONE") ||
                materialName.contains("STONECUTTER") || materialName.contains("CARTOGRAPHY") ||
                materialName.contains("FLETCHING") || materialName.contains("SMITHING") ||
                materialName.contains("END_CRYSTAL") || materialName.contains("ENDER_EYE")) {
                return ENCHANTING;
            }
            if (materialName.contains("MINECART") || materialName.contains("BOAT") ||
                materialName.contains("SADDLE") || materialName.contains("RAIL") ||
                materialName.contains("CARROT_ON_A_STICK") || materialName.contains("WARPED_FUNGUS_ON_A_STICK")) {
                return TRANSPORTATION;
            }
            if (materialName.contains("PAINTING") || materialName.contains("FLOWER") ||
                materialName.contains("SAPLING") || materialName.contains("BANNER") ||
                materialName.contains("CARPET") || materialName.contains("WOOL") ||
                materialName.contains("GLASS_PANE") || materialName.contains("IRON_BARS") ||
                materialName.contains("VINE") || materialName.contains("LILY_PAD") ||
                materialName.contains("SEAGRASS") || materialName.contains("KELP") ||
                materialName.contains("CANDLE") || materialName.contains("AMETHYST")) {
                return DECORATION;
            }
           
            return MISC;
        }
    }
    // ====================================================
    // GUI BUILDERS
    // ====================================================
    private Inventory buildMainMenu(Player player) {
        ShopInventoryHolder holder = new ShopInventoryHolder(
            ShopInventoryHolder.Type.MAIN_MENU,
            null,
            0,
            player.getUniqueId()
        );
       
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.BLUE + "CoinShop");
        ItemStack myShop = new ItemStack(Material.CHEST);
        ItemMeta myShopMeta = myShop.getItemMeta();
        myShopMeta.setDisplayName(ChatColor.GREEN + "My Shop");
        myShopMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Manage your own shop",
                ChatColor.GRAY + "Sell items and cancel listings"
        ));
        myShop.setItemMeta(myShopMeta);
        inv.setItem(11, myShop);
        ItemStack categories = new ItemStack(Material.BOOKSHELF);
        ItemMeta categoriesMeta = categories.getItemMeta();
        categoriesMeta.setDisplayName(ChatColor.GOLD + "Categories");
        categoriesMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Browse items by category",
                ChatColor.GRAY + "Tools, Food, Blocks, etc."
        ));
        categories.setItemMeta(categoriesMeta);
        inv.setItem(13, categories);
        ItemStack globalShop = new ItemStack(Material.EMERALD);
        ItemMeta globalMeta = globalShop.getItemMeta();
        globalMeta.setDisplayName(ChatColor.GREEN + "Global Shop");
        globalMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Browse all items",
                ChatColor.GRAY + "from all players"
        ));
        globalShop.setItemMeta(globalMeta);
        inv.setItem(15, globalShop);
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
       
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
        return inv;
    }
    private Inventory buildCategoriesMenu(Player player) {
        ShopInventoryHolder holder = new ShopInventoryHolder(
            ShopInventoryHolder.Type.CATEGORIES_MENU,
            null,
            0,
            player.getUniqueId()
        );
       
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Shop Categories");
        int slot = 10; // Começar do slot 10 para melhor organização
        for (ItemCategory category : ItemCategory.values()) {
            // Pular slots indesejados
            while (slot % 9 == 0 || slot % 9 == 8 || slot == 17 || slot == 18 ||
                   slot == 26 || slot == 27 || slot == 35 || slot == 36 || slot == 44) {
                slot++;
            }
           
            ItemStack categoryItem = new ItemStack(category.getIcon());
            ItemMeta meta = categoryItem.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + category.getDisplayName());
           
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + category.getDescription());
           
            long itemCount = storeData.items.stream()
                    .filter(item -> item != null && item.item != null)
                    .filter(item -> category == ItemCategory.ALL ||
                           ItemCategory.fromMaterial(item.item.getType()) == category)
                    .count();
           
            lore.add(ChatColor.GREEN + "Items: " + ChatColor.WHITE + itemCount);
            meta.setLore(lore);
           
            categoryItem.setItemMeta(meta);
            inv.setItem(slot++, categoryItem);
        }
        // Botão voltar para o menu principal
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        // Preencher bordas com vidro
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
        return inv;
    }
    private Inventory buildCategoryShopMenu(Player player, ItemCategory category, int page) {
        List<ShopItem> categoryItems = storeData.items.stream()
                .filter(item -> item != null && item.item != null)
                .filter(item -> category == ItemCategory.ALL ||
                       ItemCategory.fromMaterial(item.item.getType()) == category)
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());
        ShopInventoryHolder holder = new ShopInventoryHolder(
            ShopInventoryHolder.Type.CATEGORY_SHOP,
            category,
            page,
            player.getUniqueId()
        );
       
        String title = ChatColor.GOLD + category.getDisplayName() + " - Page " + (page + 1);
        return buildShopInventory(categoryItems, page, title, holder, false);
    }
    private Inventory buildMyShopMenu(Player player, int page) {
        List<ShopItem> myItems = storeData.items.stream()
                .filter(item -> item != null && item.sellerUuid != null && item.sellerUuid.equals(player.getUniqueId()))
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());
        ShopInventoryHolder holder = new ShopInventoryHolder(
            ShopInventoryHolder.Type.MY_SHOP,
            null,
            page,
            player.getUniqueId()
        );
       
        String title = ChatColor.GREEN + "My Shop - Page " + (page + 1);
        return buildShopInventory(myItems, page, title, holder, true);
    }
    private Inventory buildGlobalShopMenu(Player player, int page) {
        List<ShopItem> allItems = storeData.items.stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());
        ShopInventoryHolder holder = new ShopInventoryHolder(
            ShopInventoryHolder.Type.GLOBAL_SHOP,
            null,
            page,
            player.getUniqueId()
        );
       
        String title = ChatColor.GREEN + "Global Shop - Page " + (page + 1);
        return buildShopInventory(allItems, page, title, holder, false);
    }
    private Inventory buildShopInventory(List<ShopItem> items, int page, String title,
                                          ShopInventoryHolder holder, boolean isMyShop) {
        int itemsPerPage = 45;
        int totalPages = (int) Math.ceil(items.size() / (double) itemsPerPage);
        page = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, items.size());
        for (int i = start; i < end; i++) {
            ShopItem shopItem = items.get(i);
            if (shopItem == null || shopItem.item == null) continue;
           
            ItemStack displayItem = shopItem.item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GOLD + "Price: " + ChatColor.WHITE + formatCoin(shopItem.price));
            if (taxRate > 0) {
                BigDecimal total = shopItem.price.add(shopItem.price.multiply(BigDecimal.valueOf(taxRate)))
                        .setScale(8, RoundingMode.DOWN);
                lore.add(ChatColor.GRAY + "Total with tax: " + ChatColor.WHITE + formatCoin(total));
            }
            lore.add(ChatColor.GRAY + "Seller: " + ChatColor.WHITE +
                    (shopItem.sellerShopName != null ? shopItem.sellerShopName : "Unknown"));
           
            // Adicionar identificador único na lore para comparação
            lore.add(ChatColor.DARK_GRAY + "ID: " + shopItem.transactionId);
           
            if (isMyShop) {
                lore.add("");
                lore.add(ChatColor.RED + "Click to cancel listing");
            } else {
                lore.add("");
                lore.add(ChatColor.GREEN + "Click to purchase");
            }
            meta.setLore(lore);
            displayItem.setItemMeta(meta);
            inv.setItem(i - start, displayItem);
        }
        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prev.getItemMeta();
        prevMeta.setDisplayName(ChatColor.GREEN + "Previous Page");
        prev.setItemMeta(prevMeta);
       
        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = next.getItemMeta();
        nextMeta.setDisplayName(ChatColor.GREEN + "Next Page");
        next.setItemMeta(nextMeta);
       
        // Botão voltar para categorias ou menu principal
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
       
        if (holder.getType() == ShopInventoryHolder.Type.CATEGORY_SHOP) {
            backMeta.setDisplayName(ChatColor.GREEN + "Back to Categories");
        } else {
            backMeta.setDisplayName(ChatColor.GREEN + "Back to Main Menu");
        }
       
        back.setItemMeta(backMeta);
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int i = 45; i < 54; i++) {
            if (i == 45 && page > 0) {
                inv.setItem(i, prev);
            } else if (i == 53 && page < totalPages - 1) {
                inv.setItem(i, next);
            } else if (i == 49) {
                inv.setItem(i, back);
            } else {
                inv.setItem(i, filler);
            }
        }
        for (int i = end - start; i < 45; i++) {
            inv.setItem(i, filler);
        }
        return inv;
    }
    // ====================================================
    // UTILITY METHODS
    // ====================================================
    private String formatCoin(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        String formatted = COIN_FORMAT.format(amount);
        if (!formatted.contains(".")) {
            formatted += ".0";
        }
        return formatted;
    }
    private boolean isAdminWithCreative(Player player) {
        return player.hasPermission("coinshop.admin") && player.getGameMode() == GameMode.CREATIVE;
    }
    // ====================================================
    // EVENT LISTENERS
    // ====================================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
       
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof ShopInventoryHolder holder)) return;
       
        // Verificar se o holder corresponde ao jogador atual
        if (!holder.getViewerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "This inventory does not belong to you!");
            return;
        }
        event.setCancelled(true);
        // Allow admins in creative to cancel items with middle click
        if (isAdminWithCreative(player) && event.getClick() == ClickType.MIDDLE) {
            handleAdminCancel(player, event.getCurrentItem(), holder, event.getSlot());
            return;
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        int slot = event.getSlot();
        ShopInventoryHolder.Type type = holder.getType();
        int page = holder.getPage();
        switch (type) {
            case MAIN_MENU:
                if (slot == 11) {
                    player.openInventory(buildMyShopMenu(player, 0));
                } else if (slot == 13) {
                    player.openInventory(buildCategoriesMenu(player));
                } else if (slot == 15) {
                    player.openInventory(buildGlobalShopMenu(player, 0));
                }
                break;
            case CATEGORIES_MENU:
                if (slot == 49) {
                    player.openInventory(buildMainMenu(player));
                    return;
                }
               
                // Mapear slots para categorias
                int categorySlot = -1;
                int[] categorySlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
                for (int i = 0; i < categorySlots.length; i++) {
                    if (slot == categorySlots[i]) {
                        categorySlot = i;
                        break;
                    }
                }
               
                if (categorySlot >= 0 && categorySlot < ItemCategory.values().length) {
                    ItemCategory category = ItemCategory.values()[categorySlot];
                    player.openInventory(buildCategoryShopMenu(player, category, 0));
                }
                break;
            case CATEGORY_SHOP:
                handlePagedShopClick(player, holder.getCategory(), page, slot, event.getCurrentItem(), false);
                break;
            case MY_SHOP:
                handlePagedShopClick(player, null, page, slot, event.getCurrentItem(), true);
                break;
            case GLOBAL_SHOP:
                handlePagedShopClick(player, null, page, slot, event.getCurrentItem(), false);
                break;
        }
    }
    private void handlePagedShopClick(Player player, ItemCategory category, int page, int slot,
                                       ItemStack clickedItem, boolean isMyShop) {
        if (slot == 49) {
            if (category != null) {
                player.openInventory(buildCategoriesMenu(player));
            } else {
                player.openInventory(buildMainMenu(player));
            }
            return;
        }
        if (slot == 45 && page > 0) {
            reopenPage(player, category, page - 1, isMyShop);
            return;
        }
        if (slot == 53) {
            reopenPage(player, category, page + 1, isMyShop);
            return;
        }
        if (slot >= 45) return;
        List<ShopItem> items = getFilteredItems(player, category, isMyShop);
        int index = page * 45 + slot;
        if (index >= items.size()) return;
        // Extrair ID do item clicado
        String clickedId = extractItemId(clickedItem);
        if (clickedId == null) return;
        ShopItem shopItem = items.get(index);
       
        // Verificar se o ID corresponde
        if (!shopItem.transactionId.equals(clickedId)) {
            player.sendMessage(ChatColor.RED + "This item is no longer available!");
            refreshPlayerShop(player);
            return;
        }
        if (isMyShop) {
            cancelListing(player, shopItem);
            reopenPage(player, category, page, true);
        } else {
            purchaseItem(player, shopItem);
        }
    }
   
    private String extractItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return null;
       
        for (String line : item.getItemMeta().getLore()) {
            String cleanLine = ChatColor.stripColor(line);
            if (cleanLine.startsWith("ID: ")) {
                return cleanLine.substring(4);
            }
        }
        return null;
    }
    private void reopenPage(Player player, ItemCategory category, int page, boolean isMyShop) {
        if (isMyShop) {
            player.openInventory(buildMyShopMenu(player, page));
        } else if (category != null) {
            player.openInventory(buildCategoryShopMenu(player, category, page));
        } else {
            player.openInventory(buildGlobalShopMenu(player, page));
        }
    }
    private List<ShopItem> getFilteredItems(Player player, ItemCategory category, boolean isMyShop) {
        if (isMyShop) {
            return storeData.items.stream()
                    .filter(item -> item != null && item.sellerUuid != null &&
                           item.sellerUuid.equals(player.getUniqueId()))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }
        if (category != null) {
            return storeData.items.stream()
                    .filter(item -> item != null && item.item != null)
                    .filter(item -> category == ItemCategory.ALL ||
                           ItemCategory.fromMaterial(item.item.getType()) == category)
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }
        return storeData.items.stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());
    }
    private void handleAdminCancel(Player admin, ItemStack clickedItem, ShopInventoryHolder holder, int slot) {
        if (clickedItem == null || clickedItem.getType() == Material.AIR ||
            !clickedItem.hasItemMeta() || clickedItem.getItemMeta().getDisplayName().equals(" ")) {
            return;
        }
        String clickedId = extractItemId(clickedItem);
        if (clickedId == null) return;
        // Encontrar o item pelo ID
        ShopItem itemToCancel = null;
        for (ShopItem item : storeData.items) {
            if (item.transactionId.equals(clickedId)) {
                itemToCancel = item;
                break;
            }
        }
        if (itemToCancel == null) {
            admin.sendMessage(ChatColor.RED + "Item no longer exists in shop!");
            refreshPlayerShop(admin);
            return;
        }
        // Remover o item da loja
        if (storeData.items.remove(itemToCancel)) {
            saveStoreDataAsync();
           
            // Tentar devolver ao vendedor original
            Player seller = Bukkit.getPlayer(itemToCancel.sellerUuid);
            String itemName = itemToCancel.item.getType().toString();
            if (itemToCancel.item.getItemMeta() != null &&
                itemToCancel.item.getItemMeta().hasDisplayName()) {
                itemName = itemToCancel.item.getItemMeta().getDisplayName();
            }
           
            if (seller != null && seller.isOnline()) {
                // Vendedor online - tentar dar o item
                HashMap<Integer, ItemStack> leftover = seller.getInventory().addItem(itemToCancel.item.clone());
               
                if (!leftover.isEmpty()) {
                    // Inventário cheio - dropar no chão
                    World world = seller.getWorld();
                    Location loc = seller.getLocation();
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            world.dropItemNaturally(loc, drop);
                        }
                    }
                    seller.sendMessage(ChatColor.YELLOW + "Your inventory was full! Some items were dropped on the ground.");
                }
               
                // Notificar o vendedor
                seller.sendMessage(ChatColor.RED + "ADMIN CANCELLATION");
                seller.sendMessage(ChatColor.GRAY + "An admin has cancelled your listing:");
                seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + itemToCancel.item.getAmount() + "x " + itemName);
                seller.sendMessage(ChatColor.GRAY + "Price: " + ChatColor.WHITE + formatCoin(itemToCancel.price));
                seller.sendMessage(ChatColor.GRAY + "Admin: " + ChatColor.WHITE + admin.getName());
                seller.sendMessage(ChatColor.GREEN + "The item has been returned to your inventory.");
               
            } else {
                // Vendedor offline - armazenar para entrega posterior
                pendingItemReturns.computeIfAbsent(itemToCancel.sellerUuid, k -> new ArrayList<>())
                        .add(itemToCancel.item.clone());
                savePendingReturnsAsync();
               
                getLogger().info("Item cancelled by admin " + admin.getName() +
                        " for offline player " + itemToCancel.sellerUuid +
                        " - Item stored for later delivery");
            }
           
            // Notificar o admin
            admin.sendMessage(ChatColor.GREEN + "ITEM CANCELLED SUCCESSFULLY");
            admin.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + itemToCancel.item.getAmount() + "x " + itemName);
            admin.sendMessage(ChatColor.GRAY + "Seller: " + ChatColor.WHITE + itemToCancel.sellerShopName);
            admin.sendMessage(ChatColor.GRAY + "Price: " + ChatColor.WHITE + formatCoin(itemToCancel.price));
           
            if (seller != null && seller.isOnline()) {
                admin.sendMessage(ChatColor.GREEN + "Item returned to seller (online)");
            } else {
                admin.sendMessage(ChatColor.YELLOW + "Seller is offline - Item will be returned when they join");
            }
           
            // Recarregar a página
            refreshAllOpenShops();
        } else {
            admin.sendMessage(ChatColor.RED + "Item no longer exists in shop!");
        }
    }
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopInventoryHolder) {
            event.setCancelled(true);
        }
    }
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof ShopInventoryHolder) {
            // Atualizar última ação do jogador
            Player player = (Player) event.getPlayer();
            playerSessions.put(player.getUniqueId(), new PlayerSession(player.getUniqueId()));
        }
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ShopInventoryHolder) {
            // Limpar sessão se necessário
            Player player = (Player) event.getPlayer();
            playerSessions.remove(player.getUniqueId());
        }
    }
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerSessions.remove(event.getPlayer().getUniqueId());
        playerPurchaseCooldown.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        returnPendingItems(player);
       
        // Limpar cache do jogador ao entrar (opcional)
        cardCache.remove(player.getUniqueId());
        cardCacheTimestamp.remove(player.getUniqueId());
    }
    // ====================================================
    // COMMAND HANDLER
    // ====================================================
    public class CoinShopCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("coinshop.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    loadConfig();
                    sender.sendMessage(ChatColor.GREEN + "CoinShop configuration reloaded!");
                    return true;
                }
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            Player player = (Player) sender;
            if (args.length == 0) {
                player.openInventory(buildMainMenu(player));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!player.hasPermission("coinshop.admin")) {
                        player.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    loadConfig();
                    player.sendMessage(ChatColor.GREEN + "CoinShop configuration reloaded!");
                    break;
                case "sell":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /cshop sell <amount> <price>");
                        return true;
                    }
                    handleSellCommand(player, args);
                    break;
                case "cancel":
                    player.openInventory(buildMyShopMenu(player, 0));
                    break;
                case "name":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /cshop name <shop name>");
                        return true;
                    }
                    String shopName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    setPlayerShopName(player, shopName);
                    break;
                case "balance":
                    checkPlayerBalanceCommand(player);
                    break;
                default:
                    player.openInventory(buildMainMenu(player));
                    break;
            }
            return true;
        }
        private void setPlayerShopName(Player player, String shopName) {
            if (player == null) return;
           
            if (shopName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Shop name too long! Maximum 32 characters.");
                return;
            }
            PlayerData data = getPlayerData(player.getUniqueId());
            if (data != null) {
                data.shopName = shopName;
                savePlayerData(data);
                player.sendMessage(ChatColor.GREEN + "Your shop name has been set to: " + shopName);
            }
        }
        /**
         * Processa o comando /cshop sell com suporte a dois formatos:
         * - Formato decimal: /cshop sell 1 0.0015
         * - Formato inteiro: /cshop sell 1 150 (converte para 0.00000150)
         */
        private void handleSellCommand(Player player, String[] args) {
            if (player == null || args == null || args.length < 3) return;
           
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must hold an item to sell!");
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if (amount <= 0 || amount > item.getAmount()) {
                    player.sendMessage(ChatColor.RED + "Invalid amount!");
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Amount must be a number!");
                return;
            }
            BigDecimal price;
            String priceStr = args[2].replace(',', '.');
           
            try {
                // Verificar se o preço contém ponto decimal (formato decimal)
                if (priceStr.contains(".")) {
                    // Formato decimal: usar diretamente
                    price = new BigDecimal(priceStr);
                } else {
                    // Formato inteiro: converter para 0.000(inteiro com 8 casas decimais)
                    // Exemplo: 150 -> 0.00000150
                    long intValue = Long.parseLong(priceStr);
                    if (intValue <= 0) {
                        player.sendMessage(ChatColor.RED + "Price must be positive!");
                        return;
                    }
                    // Converter para BigDecimal dividindo por 100.000.000 (10^8)
                    price = BigDecimal.valueOf(intValue).divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.DOWN);
                }
               
                // Validar se o preço é positivo
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    player.sendMessage(ChatColor.RED + "Price must be positive!");
                    return;
                }
               
                // Garantir 8 casas decimais
                price = price.setScale(8, RoundingMode.DOWN);
               
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price format! Use numbers like 1.5, 0.001, or 150");
                return;
            }
            ItemStack sellItem = item.clone();
            sellItem.setAmount(amount);
            item.setAmount(item.getAmount() - amount);
            player.getInventory().setItemInMainHand(item);
            createListing(player, sellItem, price);
           
            // Mostrar o preço formatado para o jogador
            player.sendMessage(ChatColor.GRAY + "Price set to: " + ChatColor.GREEN + formatCoin(price));
        }
        private void checkPlayerBalanceCommand(Player player) {
            if (player == null) return;
           
            // Verificar se o jogador tem card usando a API
            if (!hasPlayerCard(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
                return;
            }
            getPlayerBalanceByUUID(player.getUniqueId(), new BalanceCheckCallback() {
                @Override
                public void onSuccess(BigDecimal balance) {
                    player.sendMessage(ChatColor.GREEN + "Your CoinCard balance: " +
                            ChatColor.YELLOW + formatCoin(balance));
                }
                @Override
                public void onFailure(String error) {
                    player.sendMessage(ChatColor.RED + "Failed to check balance: " + (error != null ? error : "Unknown error"));
                }
            });
        }
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                completions.add("reload");
                completions.add("sell");
                completions.add("cancel");
                completions.add("name");
                completions.add("balance");
                return completions.stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return completions;
        }
    }
    // ====================================================
    // DATA CLASSES
    // ====================================================
    private static class StoreData {
        List<ShopItem> items = new CopyOnWriteArrayList<>();
    }
    private static class ShopItem {
        String transactionId;
        UUID sellerUuid;
        String sellerName;
        String sellerShopName;
        ItemStack item;
        BigDecimal price;
        long listedAt;
        ShopItem(String transactionId, UUID sellerUuid, String sellerName,
                 String sellerShopName, ItemStack item, BigDecimal price, long listedAt) {
            this.transactionId = transactionId;
            this.sellerUuid = sellerUuid;
            this.sellerName = sellerName;
            this.sellerShopName = sellerShopName;
            this.item = item;
            this.price = price;
            this.listedAt = listedAt;
        }
    }
    private static class Transaction {
        String id;
        String fromCard;
        String toCard;
        BigDecimal amount;
        Transaction(String id, String fromCard, String toCard, BigDecimal amount) {
            this.id = id;
            this.fromCard = fromCard;
            this.toCard = toCard;
            this.amount = amount;
        }
    }
    private static class PendingPurchase {
        String transactionId;
        UUID buyerUuid;
        UUID sellerUuid;
        List<ItemStack> items;
        BigDecimal price;
        boolean completed;
        String txId;
        PendingPurchase(String transactionId, UUID buyerUuid, UUID sellerUuid,
                        List<ItemStack> items, BigDecimal price) {
            this.transactionId = transactionId;
            this.buyerUuid = buyerUuid;
            this.sellerUuid = sellerUuid;
            this.items = items != null ? items : new ArrayList<>();
            this.price = price;
            this.completed = false;
        }
    }
    private static class PlayerData {
        UUID uuid;
        String shopName;
        File file;
        PlayerData(UUID uuid, String shopName, File file) {
            this.uuid = uuid;
            this.shopName = shopName;
            this.file = file;
        }
    }
    private static class PlayerSession {
        UUID uuid;
        long lastAction;
        Map<String, Object> data = new HashMap<>();
        PlayerSession(UUID uuid) {
            this.uuid = uuid;
            this.lastAction = System.currentTimeMillis();
        }
    }
    private interface BalanceCheckCallback {
        void onSuccess(BigDecimal balance);
        void onFailure(String error);
    }
}
