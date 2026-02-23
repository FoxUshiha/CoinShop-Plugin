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
                example.set("Card", "CARD_ID_HERE");
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
                        
                        String sellerCardId = itemObj.has("sellerCardId") ? itemObj.get("sellerCardId").getAsString() : "";
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
                                transactionId, sellerUuid, sellerCardId, sellerName, sellerShopName, item, price, listedAt
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
                itemObj.addProperty("sellerCardId", shopItem.sellerCardId);
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

    // ====================================================
    // PLAYER DATA
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

        String card = yamlConfig.getString("Card", "");

        return new PlayerData(uuid, name, card, playerFile);
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
            yamlConfig.set("Card", data.cardId);
            yamlConfig.save(data.file);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save player data for " + data.uuid, e);
        }
    }

    // ====================================================
    // COINCARD API COMMUNICATION
    // ====================================================
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

        // Global sale notification (only if we have valid data)
        if (!purchase.items.isEmpty() && purchase.items.get(0) != null) {
            String itemName = purchase.items.get(0).getType().toString();
            if (purchase.items.get(0).getItemMeta() != null && 
                purchase.items.get(0).getItemMeta().hasDisplayName()) {
                itemName = purchase.items.get(0).getItemMeta().getDisplayName();
            }
            
            String globalMessage = ChatColor.GOLD + "[SHOP] " + ChatColor.GREEN + "Shop " + 
                    ChatColor.YELLOW + (sellerShopName.isEmpty() ? "Unknown" : sellerShopName) + 
                    ChatColor.GREEN + " sold " + 
                    ChatColor.AQUA + purchase.items.get(0).getAmount() + "x " + itemName +
                    ChatColor.GREEN + " for " + 
                    ChatColor.YELLOW + formatCoin(purchase.price) + " coins!";
            
            Bukkit.broadcastMessage(globalMessage);

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
                        " coins has been completed! Transaction ID: " + (txId != null ? txId : "unknown"));
            }

            // Notification for seller
            if (seller != null && seller.isOnline()) {
                seller.sendMessage(ChatColor.GREEN + "[SUCCESS] " + ChatColor.YELLOW + "ITEM SOLD!");
                seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + purchase.items.get(0).getAmount() + "x " + itemName);
                seller.sendMessage(ChatColor.GRAY + "Buyer: " + ChatColor.WHITE + (buyer != null ? buyer.getName() : "Unknown"));
                seller.sendMessage(ChatColor.GRAY + "Item price: " + ChatColor.GREEN + formatCoin(purchase.price));
                seller.sendMessage(ChatColor.GRAY + "Tax (" + (taxRate * 100) + "%): " + ChatColor.RED + "-" + formatCoin(taxAmount));
                seller.sendMessage(ChatColor.GRAY + "Amount received: " + ChatColor.GREEN + formatCoin(sellerAmount));
                seller.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE + (txId != null ? txId : "unknown"));
                seller.sendMessage(ChatColor.GREEN + "The amount has been credited to your card!");
            }
        }

        // Process tax if applicable
        if (taxRate > 0 && serverCardId != null && !serverCardId.isEmpty() && 
            taxAmount != null && taxAmount.compareTo(BigDecimal.ZERO) > 0) {
            processTaxTransaction(transaction.toCard, taxAmount, transaction.id);
        }

        pendingPurchases.remove(transaction.id);
        storeData.items.removeIf(item -> item != null && item.transactionId != null && item.transactionId.equals(transaction.id));
        saveStoreData();
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
                    transaction.toCard,
                    sellerData != null ? sellerData.shopName : "Unknown",
                    sellerData != null ? sellerData.shopName : "Unknown",
                    item,
                    purchase.price,
                    System.currentTimeMillis()
                );
                
                storeData.items.add(shopItem);
            }
        }
        
        saveStoreData();

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
        saveStoreData();
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

        PlayerData data = getPlayerData(player.getUniqueId());
        if (data == null || data.cardId == null || data.cardId.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You don't have a card ID set! Please contact an administrator.");
            return false;
        }

        String transactionId = UUID.randomUUID().toString();
        ShopItem shopItem = new ShopItem(
                transactionId,
                player.getUniqueId(),
                data.cardId,
                player.getName(),
                data.shopName != null ? data.shopName : player.getName() + "'s shop",
                item.clone(),
                price,
                System.currentTimeMillis()
        );

        storeData.items.add(shopItem);
        saveStoreData();

        player.sendMessage(ChatColor.GREEN + "Item listed for sale at " + formatCoin(price) + " coins!");
        return true;
    }

    private boolean purchaseItem(Player buyer, ShopItem item) {
        if (buyer == null || item == null) {
            return false;
        }
        
        if (!storeData.items.contains(item)) {
            buyer.sendMessage(ChatColor.RED + "This item is no longer available!");
            return false;
        }

        PlayerData buyerData = getPlayerData(buyer.getUniqueId());
        if (buyerData == null || buyerData.cardId == null || buyerData.cardId.isEmpty()) {
            buyer.sendMessage(ChatColor.RED + "You don't have a card ID set! Please contact an administrator.");
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

                String transactionId = UUID.randomUUID().toString();
                Transaction transaction = new Transaction(
                        transactionId,
                        buyerData.cardId,
                        item.sellerCardId,
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
                saveStoreData();
                transactionQueue.add(transaction);

                buyer.sendMessage(ChatColor.YELLOW + "Purchase queued! You will receive your items once the transaction completes.");
            }

            @Override
            public void onFailure(String error) {
                buyer.sendMessage(ChatColor.RED + "Failed to check balance: " + (error != null ? error : "Unknown error"));
            }
        });

        return true;
    }

    private boolean cancelListing(Player player, ShopItem item) {
        if (player == null || item == null) {
            return false;
        }
        
        if (!item.sellerUuid.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "This is not your item to cancel!");
            return false;
        }

        if (storeData.items.remove(item)) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.item.clone());
            if (!leftover.isEmpty()) {
                World world = player.getWorld();
                Location loc = player.getLocation();
                for (ItemStack drop : leftover.values()) {
                    if (drop != null) {
                        world.dropItemNaturally(loc, drop);
                    }
                }
                player.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
            }

            saveStoreData();

            player.sendMessage(ChatColor.GREEN + "Item removed from shop!");
            return true;
        }

        player.sendMessage(ChatColor.RED + "Item no longer exists in shop!");
        return false;
    }

    // ====================================================
    // GUI BUILDERS
    // ====================================================
    private Inventory buildMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.BLUE + "CoinShop");

        ItemStack myShop = new ItemStack(Material.CHEST);
        ItemMeta myShopMeta = myShop.getItemMeta();
        myShopMeta.setDisplayName(ChatColor.GREEN + "My Shop");
        myShopMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Manage your own shop",
                ChatColor.GRAY + "Sell items and cancel listings"
        ));
        myShop.setItemMeta(myShopMeta);
        inv.setItem(11, myShop);

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

    private Inventory buildMyShopMenu(Player player, int page) {
        List<ShopItem> myItems = storeData.items.stream()
                .filter(item -> item != null && item.sellerUuid != null && item.sellerUuid.equals(player.getUniqueId()))
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());

        return buildShopInventory(myItems, page, ChatColor.GREEN + "My Shop - Page " + (page + 1), true);
    }

    private Inventory buildGlobalShopMenu(Player player, int page) {
        List<ShopItem> allItems = storeData.items.stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());

        return buildShopInventory(allItems, page, ChatColor.GREEN + "Global Shop - Page " + (page + 1), false);
    }

    private Inventory buildShopInventory(List<ShopItem> items, int page, String title, boolean isMyShop) {
        int itemsPerPage = 45;
        int totalPages = (int) Math.ceil(items.size() / (double) itemsPerPage);
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54, title);

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
            if (isMyShop) {
                lore.add("");
                lore.add(ChatColor.RED + "Click to cancel listing");
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
        
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
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

    // ====================================================
    // EVENT LISTENERS
    // ====================================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.contains("CoinShop") || title.contains("Shop")) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            ItemStack clickedItem = event.getCurrentItem();
            String displayName = clickedItem.getItemMeta().getDisplayName();
            Material type = clickedItem.getType();

            if (title.equals(ChatColor.BLUE + "CoinShop")) {
                if (event.getSlot() == 11) {
                    player.openInventory(buildMyShopMenu(player, 0));
                } else if (event.getSlot() == 15) {
                    player.openInventory(buildGlobalShopMenu(player, 0));
                }
                return;
            }

            boolean isMyShop = title.startsWith(ChatColor.GREEN + "My Shop");
            boolean isGlobalShop = title.startsWith(ChatColor.GREEN + "Global Shop");

            if (isMyShop || isGlobalShop) {
                if (event.getSlot() == 49 && type == Material.BARRIER && 
                    displayName.equals(ChatColor.RED + "Back to Main Menu")) {
                    player.openInventory(buildMainMenu(player));
                    return;
                }

                int currentPage = extractPageNumber(title);

                if (event.getSlot() == 45 && type == Material.ARROW && 
                    displayName.equals(ChatColor.GREEN + "Previous Page")) {
                    if (currentPage > 0) {
                        if (isMyShop) {
                            player.openInventory(buildMyShopMenu(player, currentPage - 1));
                        } else {
                            player.openInventory(buildGlobalShopMenu(player, currentPage - 1));
                        }
                    }
                    return;
                }

                if (event.getSlot() == 53 && type == Material.ARROW && 
                    displayName.equals(ChatColor.GREEN + "Next Page")) {
                    if (isMyShop) {
                        player.openInventory(buildMyShopMenu(player, currentPage + 1));
                    } else {
                        player.openInventory(buildGlobalShopMenu(player, currentPage + 1));
                    }
                    return;
                }

                if (event.getSlot() < 45 && type != Material.AIR && 
                    !displayName.equals(" ") && 
                    type != Material.GRAY_STAINED_GLASS_PANE) {
                    
                    handleItemClick(player, event.getSlot(), clickedItem, title, isMyShop);
                }
            }
        }
    }

    private void handleItemClick(Player player, int slot, ItemStack clickedItem, String title, boolean isMyShop) {
        if (player == null) return;
        
        List<ShopItem> items;
        int page = extractPageNumber(title);

        if (isMyShop) {
            items = storeData.items.stream()
                    .filter(item -> item != null && item.sellerUuid != null && item.sellerUuid.equals(player.getUniqueId()))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        } else {
            items = storeData.items.stream()
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }

        int index = page * 45 + slot;
        if (index < items.size()) {
            ShopItem shopItem = items.get(index);
            if (shopItem == null) return;

            if (isMyShop) {
                cancelListing(player, shopItem);
                player.openInventory(buildMyShopMenu(player, page));
            } else {
                purchaseItem(player, shopItem);
                player.openInventory(buildGlobalShopMenu(player, page));
            }
        }
    }

    private int extractPageNumber(String title) {
        try {
            String[] parts = title.split("Page ");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1].trim()) - 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            playerSessions.remove(event.getPlayer().getUniqueId());
        }
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

                case "open":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /cshop open <shopname>");
                        return true;
                    }
                    openShopByName(player, args[1]);
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
            try {
                price = new BigDecimal(args[2].replace(',', '.'));
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    player.sendMessage(ChatColor.RED + "Price must be positive!");
                    return;
                }
                price = price.setScale(8, RoundingMode.DOWN);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price format! Use numbers like 1.5 or 0.001");
                return;
            }

            ItemStack sellItem = item.clone();
            sellItem.setAmount(amount);
            item.setAmount(item.getAmount() - amount);
            player.getInventory().setItemInMainHand(item);

            createListing(player, sellItem, price);
        }

        private void openShopByName(Player player, String shopName) {
            if (player == null || shopName == null) return;
            
            UUID ownerUuid = null;
            File[] userFiles = usersFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (userFiles != null) {
                for (File file : userFiles) {
                    YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(file);
                    String name = yamlConfig.getString("Name", "");
                    if (name.equalsIgnoreCase(shopName)) {
                        try {
                            ownerUuid = UUID.fromString(file.getName().replace(".yml", ""));
                            break;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }

            if (ownerUuid == null) {
                player.sendMessage(ChatColor.RED + "Shop not found: " + shopName);
                return;
            }

            UUID finalOwnerUuid = ownerUuid;
            List<ShopItem> shopItems = storeData.items.stream()
                    .filter(item -> item != null && item.sellerUuid != null && item.sellerUuid.equals(finalOwnerUuid))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());

            Inventory inv = Bukkit.createInventory(null, 54,
                    ChatColor.GREEN + shopName + "'s Shop - Page 1");

            int slot = 0;
            for (ShopItem item : shopItems) {
                if (slot >= 45 || item == null || item.item == null) break;

                ItemStack displayItem = item.item.clone();
                ItemMeta meta = displayItem.getItemMeta();
                List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GOLD + "Price: " + ChatColor.WHITE + formatCoin(item.price));
                if (taxRate > 0) {
                    BigDecimal total = item.price.add(item.price.multiply(BigDecimal.valueOf(taxRate)))
                            .setScale(8, RoundingMode.DOWN);
                    lore.add(ChatColor.GRAY + "Total with tax: " + ChatColor.WHITE + formatCoin(total));
                }
                meta.setLore(lore);
                displayItem.setItemMeta(meta);
                inv.setItem(slot++, displayItem);
            }

            ItemStack back = new ItemStack(Material.BARRIER);
            ItemMeta backMeta = back.getItemMeta();
            backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
            back.setItemMeta(backMeta);
            inv.setItem(49, back);

            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = filler.getItemMeta();
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);

            for (int i = slot; i < 45; i++) {
                inv.setItem(i, filler);
            }

            player.openInventory(inv);
        }

        private void checkPlayerBalanceCommand(Player player) {
            if (player == null) return;
            
            PlayerData data = getPlayerData(player.getUniqueId());
            if (data == null || data.cardId == null || data.cardId.isEmpty()) {
                player.sendMessage(ChatColor.RED + "You don't have a card ID set! Please contact an administrator.");
                return;
            }

            // Use getPlayerBalanceByUUID instead of checkPlayerBalance
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
                completions.add("open");
                completions.add("sell");
                completions.add("cancel");
                completions.add("name");
                completions.add("balance");
                return completions.stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
                File[] userFiles = usersFolder.listFiles((dir, name) -> name.endsWith(".yml"));
                if (userFiles != null) {
                    for (File file : userFiles) {
                        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(file);
                        String name = yamlConfig.getString("Name", "");
                        if (name.toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(name);
                        }
                    }
                }
            }

            return completions;
        }
    }

    // ====================================================
    // DATA CLASSES
    // ====================================================
    private static class StoreData {
        List<ShopItem> items = new ArrayList<>();
    }

    private static class ShopItem {
        String transactionId;
        UUID sellerUuid;
        String sellerCardId;
        String sellerName;
        String sellerShopName;
        ItemStack item;
        BigDecimal price;
        long listedAt;

        ShopItem(String transactionId, UUID sellerUuid, String sellerCardId, String sellerName,
                 String sellerShopName, ItemStack item, BigDecimal price, long listedAt) {
            this.transactionId = transactionId;
            this.sellerUuid = sellerUuid;
            this.sellerCardId = sellerCardId;
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
        String cardId;
        File file;

        PlayerData(UUID uuid, String shopName, String cardId, File file) {
            this.uuid = uuid;
            this.shopName = shopName;
            this.cardId = cardId;
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
