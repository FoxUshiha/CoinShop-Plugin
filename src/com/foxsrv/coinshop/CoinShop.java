package com.foxsrv.coinshop;

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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private String apiBaseUrl;
    private long cooldownMs;

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

        getLogger().info("CoinShop enabled successfully!");
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
    // CONFIGURATION
    // ====================================================
    private void loadConfig() {
        reloadConfig();
        config = getConfig();

        config.addDefault("Server", "");
        config.addDefault("Tax", 0.1);
        config.addDefault("Min", 0.00000001);
        config.addDefault("Max", 1000.0);
        config.addDefault("API", "https://bank.foxsrv.net");
        config.addDefault("Cooldown", 1000);
        config.options().copyDefaults(true);
        saveConfig();

        serverCardId = config.getString("Server", "");
        taxRate = config.getDouble("Tax", 0.1);
        minPrice = BigDecimal.valueOf(config.getDouble("Min", 0.00000001));
        maxPrice = BigDecimal.valueOf(config.getDouble("Max", 1000.0));
        apiBaseUrl = config.getString("API", "https://bank.foxsrv.net").replaceAll("/$", "");
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
                        UUID sellerUuid = itemObj.has("sellerUuid") ? UUID.fromString(itemObj.get("sellerUuid").getAsString()) : null;
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
                itemObj.addProperty("sellerUuid", shopItem.sellerUuid.toString());
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
    // API COMMUNICATION
    // ====================================================
    private CompletableFuture<ApiResponse> transferBetweenCards(String fromCard, String toCard, BigDecimal amount) {
        CompletableFuture<ApiResponse> future = new CompletableFuture<>();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String formattedAmount = formatCoinForApi(amount);

                    URL url = new URL(apiBaseUrl + "/api/card/pay");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);

                    JsonObject requestBody = new JsonObject();
                    requestBody.addProperty("fromCard", fromCard);
                    requestBody.addProperty("toCard", toCard);
                    requestBody.addProperty("amount", formattedAmount);

                    String jsonBody = GSON.toJson(requestBody);
                    
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    }

                    int responseCode = conn.getResponseCode();
                    StringBuilder response = new StringBuilder();

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(
                                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    ApiResponse apiResponse;
                    if (responseCode == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                            boolean success = json.has("success") && json.get("success").getAsBoolean();

                            if (success) {
                                String txId = json.has("txId") ? json.get("txId").getAsString() : null;
                                apiResponse = new ApiResponse(true, txId, null);
                            } else {
                                String error = json.has("error") ? json.get("error").getAsString() : "Unknown error";
                                apiResponse = new ApiResponse(false, null, error);
                            }
                        } catch (Exception e) {
                            apiResponse = new ApiResponse(false, null, "Invalid JSON response: " + response);
                        }
                    } else {
                        apiResponse = new ApiResponse(false, null, "HTTP " + responseCode + ": " + response);
                    }

                    future.complete(apiResponse);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        }.runTaskAsynchronously(this);

        return future;
    }

    private String formatCoinForApi(BigDecimal amount) {
        String formatted = amount.setScale(8, RoundingMode.DOWN).toPlainString();
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0*$", "");
            if (formatted.endsWith(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
        }
        return formatted;
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
        processTransaction(transaction);
    }

    private void processTransaction(Transaction transaction) {
        transferBetweenCards(transaction.fromCard, transaction.toCard, transaction.amount)
                .thenAccept(response -> {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (response.success) {
                                handleSuccessfulTransaction(transaction, response.txId);
                            } else {
                                handleFailedTransaction(transaction, response.error);
                            }
                        }
                    }.runTask(this);
                })
                .exceptionally(throwable -> {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            handleFailedTransaction(transaction, throwable.getMessage());
                        }
                    }.runTask(this);
                    return null;
                });
    }

    private void handleSuccessfulTransaction(Transaction transaction, String txId) {
        for (Map.Entry<String, PendingPurchase> entry : pendingPurchases.entrySet()) {
            PendingPurchase purchase = entry.getValue();
            if (purchase.transactionId.equals(transaction.id)) {
                purchase.completed = true;
                purchase.txId = txId;

                Player buyer = Bukkit.getPlayer(purchase.buyerUuid);
                Player seller = Bukkit.getPlayer(purchase.sellerUuid);
                
                // Get seller's shop name from store data
                String sellerShopName = "";
                for (ShopItem item : storeData.items) {
                    if (item.transactionId.equals(transaction.id)) {
                        sellerShopName = item.sellerShopName;
                        break;
                    }
                }
                
                BigDecimal taxAmount = transaction.amount.multiply(BigDecimal.valueOf(taxRate))
                        .setScale(8, RoundingMode.DOWN);
                BigDecimal sellerAmount = purchase.price;

                // Global sale notification
                String itemName = purchase.items.get(0).getType().toString();
                if (purchase.items.get(0).getItemMeta().hasDisplayName()) {
                    itemName = purchase.items.get(0).getItemMeta().getDisplayName();
                }
                
                String globalMessage = ChatColor.GOLD + "[SHOP] " + ChatColor.GREEN + "Shop " + 
                        ChatColor.YELLOW + sellerShopName + 
                        ChatColor.GREEN + " sold " + 
                        ChatColor.AQUA + purchase.items.get(0).getAmount() + "x " + itemName +
                        ChatColor.GREEN + " for " + 
                        ChatColor.YELLOW + formatCoin(purchase.price) + " coins!";
                
                Bukkit.broadcastMessage(globalMessage);

                if (buyer != null && buyer.isOnline()) {
                    for (ItemStack item : purchase.items) {
                        HashMap<Integer, ItemStack> leftover = buyer.getInventory().addItem(item.clone());
                        if (!leftover.isEmpty()) {
                            World world = buyer.getWorld();
                            Location loc = buyer.getLocation();
                            for (ItemStack drop : leftover.values()) {
                                world.dropItemNaturally(loc, drop);
                            }
                            buyer.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
                        }
                    }
                    buyer.sendMessage(ChatColor.GREEN + "Your purchase of " + formatCoin(transaction.amount) +
                            " coins has been completed! Transaction ID: " + txId);
                }

                // Notification for seller
                if (seller != null && seller.isOnline()) {
                    seller.sendMessage(ChatColor.GREEN + "[SUCCESS] " + ChatColor.YELLOW + "ITEM SOLD!");
                    seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + purchase.items.get(0).getAmount() + "x " + itemName);
                    seller.sendMessage(ChatColor.GRAY + "Buyer: " + ChatColor.WHITE + (buyer != null ? buyer.getName() : "Unknown"));
                    seller.sendMessage(ChatColor.GRAY + "Item price: " + ChatColor.GREEN + formatCoin(purchase.price));
                    seller.sendMessage(ChatColor.GRAY + "Tax (" + (taxRate * 100) + "%): " + ChatColor.RED + "-" + formatCoin(taxAmount));
                    seller.sendMessage(ChatColor.GRAY + "Amount received: " + ChatColor.GREEN + formatCoin(sellerAmount));
                    seller.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE + txId);
                    seller.sendMessage(ChatColor.GREEN + "The amount has been credited to your card!");
                }

                if (taxRate > 0 && serverCardId != null && !serverCardId.isEmpty()) {
                    if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
                        Transaction taxTransaction = new Transaction(
                                UUID.randomUUID().toString(),
                                transaction.toCard,
                                serverCardId,
                                taxAmount
                        );
                        transactionQueue.add(taxTransaction);
                    }
                }

                pendingPurchases.remove(entry.getKey());
                break;
            }
        }

        storeData.items.removeIf(item -> item.transactionId.equals(transaction.id));
        saveStoreData();
    }

    private void handleFailedTransaction(Transaction transaction, String error) {
        for (Map.Entry<String, PendingPurchase> entry : pendingPurchases.entrySet()) {
            if (entry.getValue().transactionId.equals(transaction.id)) {
                PendingPurchase purchase = entry.getValue();
                
                Player buyer = Bukkit.getPlayer(purchase.buyerUuid);
                Player seller = Bukkit.getPlayer(purchase.sellerUuid);
                
                // Get seller's data
                PlayerData sellerData = getPlayerData(purchase.sellerUuid);
                
                // Recreate the item in the shop
                for (ItemStack item : purchase.items) {
                    ShopItem shopItem = new ShopItem(
                        transaction.id,
                        purchase.sellerUuid,
                        transaction.toCard,
                        sellerData.shopName,
                        sellerData.shopName,
                        item,
                        purchase.price,
                        System.currentTimeMillis()
                    );
                    
                    storeData.items.add(shopItem);
                }
                
                saveStoreData();

                if (buyer != null && buyer.isOnline()) {
                    buyer.sendMessage(ChatColor.RED + "[FAILED] Transaction failed: " + error);
                    buyer.sendMessage(ChatColor.YELLOW + "The item has been returned to the shop. You were not charged.");
                }

                // Notify the seller about the failure
                if (seller != null && seller.isOnline()) {
                    String itemName = purchase.items.get(0).getType().toString();
                    if (purchase.items.get(0).getItemMeta().hasDisplayName()) {
                        itemName = purchase.items.get(0).getItemMeta().getDisplayName();
                    }
                    
                    seller.sendMessage(ChatColor.RED + "[FAILED] " + ChatColor.YELLOW + "SALE FAILED!");
                    seller.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + purchase.items.get(0).getAmount() + "x " + itemName);
                    seller.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.RED + error);
                    seller.sendMessage(ChatColor.GREEN + "The item has been returned to your shop inventory.");
                }

                pendingPurchases.remove(entry.getKey());
                break;
            }
        }

        saveStoreData();
    }

    // ====================================================
    // SHOP LOGIC
    // ====================================================
    private boolean createListing(Player player, ItemStack item, BigDecimal price) {
        if (price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
            player.sendMessage(ChatColor.RED + "Price must be between " + formatCoin(minPrice) +
                    " and " + formatCoin(maxPrice));
            return false;
        }

        PlayerData data = getPlayerData(player.getUniqueId());
        if (data.cardId == null || data.cardId.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You don't have a card ID set! Use /cshop card <CardID>");
            return false;
        }

        String transactionId = UUID.randomUUID().toString();
        ShopItem shopItem = new ShopItem(
                transactionId,
                player.getUniqueId(),
                data.cardId,
                player.getName(),
                data.shopName,
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
        if (!storeData.items.contains(item)) {
            buyer.sendMessage(ChatColor.RED + "This item is no longer available!");
            return false;
        }

        PlayerData buyerData = getPlayerData(buyer.getUniqueId());
        if (buyerData.cardId == null || buyerData.cardId.isEmpty()) {
            buyer.sendMessage(ChatColor.RED + "You don't have a card ID set! Use /cshop card <CardID>");
            return false;
        }

        BigDecimal totalPrice = item.price;
        if (taxRate > 0) {
            totalPrice = totalPrice.add(totalPrice.multiply(BigDecimal.valueOf(taxRate)))
                    .setScale(8, RoundingMode.DOWN);
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
        return true;
    }

    private boolean cancelListing(Player player, ShopItem item) {
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
                    world.dropItemNaturally(loc, drop);
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
                .filter(item -> item.sellerUuid.equals(player.getUniqueId()))
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());

        return buildShopInventory(myItems, page, ChatColor.GREEN + "My Shop - Page " + (page + 1), true);
    }

    private Inventory buildGlobalShopMenu(Player player, int page) {
        List<ShopItem> allItems = storeData.items.stream()
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
            lore.add(ChatColor.GRAY + "Seller: " + ChatColor.WHITE + shopItem.sellerShopName);
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
        List<ShopItem> items;
        int page = extractPageNumber(title);

        if (isMyShop) {
            items = storeData.items.stream()
                    .filter(item -> item.sellerUuid.equals(player.getUniqueId()))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        } else {
            items = storeData.items.stream()
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }

        int index = page * 45 + slot;
        if (index < items.size()) {
            ShopItem shopItem = items.get(index);

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
        playerSessions.remove(event.getPlayer().getUniqueId());
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

                case "card":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /cshop card <CardID>");
                        return true;
                    }
                    setPlayerCard(player, args[1]);
                    break;

                case "name":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /cshop name <shop name>");
                        return true;
                    }
                    String shopName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    setPlayerShopName(player, shopName);
                    break;

                default:
                    player.openInventory(buildMainMenu(player));
                    break;
            }

            return true;
        }

        private void setPlayerCard(Player player, String cardId) {
            PlayerData data = getPlayerData(player.getUniqueId());
            data.cardId = cardId;
            savePlayerData(data);
            player.sendMessage(ChatColor.GREEN + "Your Card ID has been set to: " + cardId);
        }

        private void setPlayerShopName(Player player, String shopName) {
            if (shopName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Shop name too long! Maximum 32 characters.");
                return;
            }
            PlayerData data = getPlayerData(player.getUniqueId());
            data.shopName = shopName;
            savePlayerData(data);
            player.sendMessage(ChatColor.GREEN + "Your shop name has been set to: " + shopName);
        }

        private void handleSellCommand(Player player, String[] args) {
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
                    .filter(item -> item.sellerUuid.equals(finalOwnerUuid))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());

            Inventory inv = Bukkit.createInventory(null, 54,
                    ChatColor.GREEN + shopName + "'s Shop - Page 1");

            int slot = 0;
            for (ShopItem item : shopItems) {
                if (slot >= 45) break;

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

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                completions.add("reload");
                completions.add("open");
                completions.add("sell");
                completions.add("cancel");
                completions.add("card");
                completions.add("name");
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
            this.items = items;
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

    private static class ApiResponse {
        boolean success;
        String txId;
        String error;

        ApiResponse(boolean success, String txId, String error) {
            this.success = success;
            this.txId = txId;
            this.error = error;
        }
    }
}
