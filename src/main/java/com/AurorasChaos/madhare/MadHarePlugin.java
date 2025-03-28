package com.AurorasChaos.madhare;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class MadHarePlugin extends JavaPlugin implements Listener {

    private final Map<String, BukkitRunnable> healingTasks = new HashMap<>();
    private final Map<String, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Double> damageTracker = new HashMap<>();
    private Scoreboard scoreboard;
    private Objective objective;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);

        this.getCommand("summonmad").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("madhare.summon")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (sender instanceof Player player) {
                spawnMadHare(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "The Mad Hare has been summoned!");
            }
            return true;
        });

        this.getCommand("madreload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("madhare.reload")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to reload the plugin.");
                return true;
            }
            reloadConfig();
            sender.sendMessage(ChatColor.YELLOW + "MadHarePlugin config reloaded!");
            return true;
        });
    }

    private void spawnMadHare(Location loc) {
        Rabbit boss = (Rabbit) loc.getWorld().spawnEntity(loc, EntityType.RABBIT);
        boss.setCustomName(ChatColor.RED + "The Mad Hare");
        boss.setCustomNameVisible(true);
        boss.setInvulnerable(false);

        double baseHealth = getConfig().getDouble("boss.maxHealth", 100.0);
        double finalHealth = baseHealth;
        if (getConfig().getBoolean("scaling.enabled", false)) {
            double radius = getConfig().getDouble("scaling.radius", 20.0);
            double multiplier = getConfig().getDouble("scaling.healthMultiplier", 0.5);
            int players = countPlayersWithin(loc, radius);
            finalHealth = baseHealth * (1 + (players * multiplier));
        }
        boss.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).setBaseValue(finalHealth);
        boss.setHealth(finalHealth);

        setEntityScale(boss, getConfig().getDouble("scale.boss", 1.0));

        BossBar bar = Bukkit.createBossBar(ChatColor.DARK_RED + "Mad Hare", BarColor.PURPLE, BarStyle.SOLID);
        bar.setProgress(1.0);
        bossBars.put(boss.getUniqueId().toString(), bar);
        Bukkit.getOnlinePlayers().forEach(bar::addPlayer);

        startScoreboardTask(boss);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    bar.removeAll();
                    bossBars.remove(boss.getUniqueId().toString());
                    cancel();
                    return;
                }
                double progress = boss.getHealth() / boss.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).getBaseValue();
                bar.setProgress(progress);
            }
        }.runTaskTimer(this, 0L, 10L);

        scheduleAbility(boss, "carrotMissile", this::launchCarrotMissile);
        scheduleAbility(boss, "burrow", this::burrow);

        long summonFreq = getConfig().getLong("abilities.summonMiniBunnies.frequency", 300L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    cancel();
                    return;
                }
                int count = countPlayersWithin(boss.getLocation(), 50) * 2;
                String key = getConfig().getBoolean("damageReduction.enabled") ?
                        "chat.summonMiniBunnies.damageReduction" : "chat.summonMiniBunnies.normal";
                if (getConfig().isString(key))
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString(key)));
                summonMiniBunnies(boss.getLocation(), count);
            }
        }.runTaskTimer(this, summonFreq, summonFreq);

        long healPhaseFreq = getConfig().getLong("abilities.healPhase.frequency", 400L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    cancel();
                    return;
                }
                if (!healingTasks.containsKey(boss.getUniqueId().toString())) {
                    startHealPhase(boss);
                }
            }
        }.runTaskTimer(this, healPhaseFreq, healPhaseFreq);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Rabbit rabbit)) return;
        if (!(event.getDamager() instanceof Player player)) return;
		String uuid = rabbit.getUniqueId().toString();
		if (healingTasks.containsKey(uuid)) {
			healingTasks.get(uuid).cancel();
			healingTasks.remove(uuid);
			rabbit.getWorld().spawnParticle(Particle.valueOf("SMOKE_LARGE"), rabbit.getLocation(), 10);

			// ✅ Add this check
			String interruptMsg = getConfig().getString("chat.healPhase.interrupted", "");
			if (!interruptMsg.isEmpty()) {
				Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', interruptMsg));
			}
		}

        if (rabbit.getCustomName() == null) return;
        if (rabbit.getCustomName().contains("The Mad Hare") || rabbit.getCustomName().contains("Mini Bunny")) {
            damageTracker.put(player.getUniqueId(),
                    damageTracker.getOrDefault(player.getUniqueId(), 0.0) + event.getFinalDamage());
        }
    }

    private void startScoreboardTask(Rabbit boss) {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = scoreboard.registerNewObjective("madhare", "dummy", ChatColor.GOLD + "»» " + ChatColor.LIGHT_PURPLE + "Mad Hare Fight" + ChatColor.GOLD + " ««");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        new BukkitRunnable() {
            int tickPhase = 0;
            final String[] healthIcons = {"❤", "♥♥", "❤❤❤"};
            final String[] bunnyIcons = {"🐇", "🐰"};
            final String[] titleRotations = {"⚔ Top Damage Dealers", "🗡 Most Ruthless Players", "💥 Big Hitters"};

            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
					// Delay scoreboard removal
					new BukkitRunnable() {
						@Override
						public void run() {
							for (Player player : Bukkit.getOnlinePlayers()) {
								player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
							}
						}
					}.runTaskLater(MadHarePlugin.this, 20 * 30); // 30 seconds
					cancel();
					return;
				}

                objective.unregister();
                objective = scoreboard.registerNewObjective("madhare", "dummy", ChatColor.GOLD + "»» " + ChatColor.LIGHT_PURPLE + "Mad Hare Fight" + ChatColor.GOLD + " ««");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);

                double health = boss.getHealth() / boss.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).getBaseValue();
                int miniCount = countMiniBunnies(boss);

                objective.getScore(ChatColor.RED + healthIcons[tickPhase % healthIcons.length] + " Health: " + ChatColor.WHITE + (int)(health * 100) + "%").setScore(8);
                objective.getScore(ChatColor.YELLOW + bunnyIcons[tickPhase % bunnyIcons.length] + " Mini Bunnies: " + ChatColor.WHITE + miniCount).setScore(7);
                objective.getScore(ChatColor.AQUA + titleRotations[tickPhase % titleRotations.length]).setScore(6);

                List<Map.Entry<UUID, Double>> top5 = damageTracker.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                        .limit(5)
                        .collect(Collectors.toList());

                int score = 5;
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : top5) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null) {
                        String line = ChatColor.GRAY + "" + rank + ". " + ChatColor.AQUA + player.getName() + ChatColor.WHITE + " - " + entry.getValue().intValue();
                        objective.getScore(line).setScore(score--);
                        rank++;
                    }
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.setScoreboard(scoreboard);
                }

                tickPhase++;
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    private void summonMiniBunnies(Location loc, int count) {
        loc.getWorld().spawnParticle(Particle.valueOf("SPELL_WITCH"), loc, 40, 1, 1, 1, 0.1);
		loc.getWorld().spawnParticle(Particle.valueOf("PORTAL"), loc, 30, 1, 1, 1, 0.1);
        Random rand = new Random();
        for (int i = 0; i < count; i++) {
            Location spawnLoc = loc.clone().add(rand.nextDouble() - 0.5, 0, rand.nextDouble() - 0.5);
            Rabbit mini = (Rabbit) loc.getWorld().spawnEntity(spawnLoc, EntityType.RABBIT);
            mini.setCustomName(ChatColor.YELLOW + "Mini Bunny");
            mini.setCustomNameVisible(true);
            mini.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).setBaseValue(10.0);
            mini.setHealth(10.0);
            mini.addPotionEffect(new PotionEffect(PotionEffectType.getByName("SLOW"), Integer.MAX_VALUE, 1));
            setEntityScale(mini, getConfig().getDouble("scale.miniBunny", 0.5));
        }
    }

    private void launchCarrotMissile(Rabbit boss) {
        double range = 15.0;
        Random rand = new Random();
        for (Player target : boss.getWorld().getPlayers()) {
            if (target.getLocation().distance(boss.getLocation()) <= range && rand.nextBoolean()) {
                Location spawnLoc = boss.getLocation().add(0, 1, 0);
                Vector velocity = target.getLocation().toVector().subtract(spawnLoc.toVector()).normalize()
                        .multiply(getConfig().getDouble("abilities.carrotMissile.velocityMultiplier", 0.7));
                Item carrot = boss.getWorld().dropItem(spawnLoc, new ItemStack(Material.CARROT));
				boss.getWorld().spawnParticle(Particle.valueOf("CRIT_MAGIC"), spawnLoc, 20, 0.5, 1, 0.5, 0.1);
                carrot.setPickupDelay(9999);
                carrot.setVelocity(velocity);

                double damage = getConfig().getDouble("abilities.carrotMissile.damage", 4.0);
                int duration = 100;
                PotionEffectType jump = PotionEffectType.getByName(getConfig().getString("potionEffects.jump", "JUMP_BOOST"));
                PotionEffectType slow = PotionEffectType.getByName(getConfig().getString("potionEffects.slow", "SLOWNESS"));

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (target.isOnline()) {
                            target.damage(damage);
                            if (jump != null) target.addPotionEffect(new PotionEffect(jump, duration, 1));
                            if (slow != null) target.addPotionEffect(new PotionEffect(slow, duration, 0));
                        }
                        if (!carrot.isDead()) carrot.remove();
                    }
                }.runTaskLater(this, 20L);
            }
        }
    }

    private void burrow(Rabbit boss) {
        Location loc = boss.getLocation();
        boss.getWorld().spawnParticle(Particle.valueOf("EXPLOSION_LARGE"), loc, 40, 1, 1, 1, 0.2);
		boss.getWorld().spawnParticle(Particle.valueOf("CLOUD"), loc, 30, 1, 0.5, 1, 0.05);
        Location underground = loc.clone().subtract(0, getConfig().getInt("abilities.burrow.burrowDepth", 2), 0);
        boss.teleport(underground);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) return;
                boss.teleport(loc);
                boss.getWorld().spawnParticle(Particle.valueOf("FLASH"), loc, 25);
				boss.getWorld().spawnParticle(Particle.valueOf("FIREWORKS_SPARK"), loc, 50, 1, 0.5, 1, 0.05);
            }
        }.runTaskLater(this, getConfig().getLong("abilities.burrow.reappearDelay", 40L));
    }

    private void startHealPhase(Rabbit boss) {
        boss.getWorld().spawnParticle(Particle.valueOf("HEART"), boss.getLocation(), 30, 1, 1, 1, 0.05);
		boss.getWorld().spawnParticle(Particle.valueOf("HAPPY_VILLAGER"), boss.getLocation(), 40, 1, 1, 1, 0.05);

        double distance = getConfig().getDouble("abilities.healPhase.runAwayDistance", 5.0);
        double angle = new Random().nextDouble() * 2 * Math.PI;
        Location dest = boss.getLocation().clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        boss.teleport(dest);

        long delay = getConfig().getLong("abilities.healPhase.healDelay", 200L);
        int seconds = (int) (delay / 20);
        String startMessage = getConfig().getString("chat.healPhase.start", null);
        if (startMessage != null && !startMessage.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', startMessage.replace("%seconds%", String.valueOf(seconds))));
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    healingTasks.remove(boss.getUniqueId().toString());
                    cancel();
                    return;
                }
                double percent = getConfig().getDouble("abilities.healPhase.healPercent", 0.10);
                double heal = boss.getHealth() + boss.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).getBaseValue() * percent;
                boss.setHealth(Math.min(heal, boss.getAttribute(Attribute.valueOf("GENERIC_MAX_HEALTH")).getBaseValue()));
                boss.getWorld().spawnParticle(Particle.valueOf("VILLAGER_HAPPY"), boss.getLocation(), 20);
                healingTasks.remove(boss.getUniqueId().toString());
            }
        };

        healingTasks.put(boss.getUniqueId().toString(), task);
        task.runTaskLater(this, delay);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead() || !healingTasks.containsKey(boss.getUniqueId().toString())) {
                    cancel();
                    return;
                }
                boss.getWorld().spawnParticle(Particle.HEART, boss.getLocation(), 5);
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void setEntityScale(LivingEntity entity, double scale) {
        try {
            Method getHandle = entity.getClass().getMethod("getHandle");
            Object nmsEntity = getHandle.invoke(entity);
            Class<?> tagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            Object tag = tagClass.getConstructor().newInstance();
            nmsEntity.getClass().getMethod("save", tagClass).invoke(nmsEntity, tag);
            tagClass.getMethod("putDouble", String.class, double.class).invoke(tag, "Scale", scale);
            nmsEntity.getClass().getMethod("load", tagClass).invoke(nmsEntity, tag);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int countPlayersWithin(Location center, double radius) {
        return (int) center.getWorld().getPlayers().stream().filter(p -> p.getLocation().distance(center) <= radius).count();
    }

    private int countMiniBunnies(LivingEntity boss) {
        return (int) boss.getWorld().getEntitiesByClass(Rabbit.class).stream()
                .filter(r -> r.getCustomName() != null && r.getCustomName().contains("Mini Bunny")).count();
    }

    private void scheduleAbility(Rabbit boss, String key, java.util.function.Consumer<Rabbit> action) {
        long freq = getConfig().getLong("abilities." + key + ".frequency", 200L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    cancel();
                    return;
                }
                if (getConfig().isString("chat." + key)) {
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("chat." + key)));
                }
                action.accept(boss);
            }
        }.runTaskTimer(this, freq, freq);
    }
	@EventHandler
	public void onBossDeath(EntityDeathEvent event) {
    if (!(event.getEntity() instanceof Rabbit rabbit)) return;
    if (rabbit.getCustomName() == null || !rabbit.getCustomName().contains("The Mad Hare")) return;

    String uuid = rabbit.getUniqueId().toString();

    // Remove boss bar
    if (bossBars.containsKey(uuid)) {
        bossBars.get(uuid).removeAll();
        bossBars.remove(uuid);
    }

    // Broadcast boss defeat message
    String msg = getConfig().getString("chat.bossDeath", "");
    if (!msg.isEmpty()) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    // Drop 1 resurrection egg at boss location
    ItemStack egg = new ItemStack(Material.EGG);
    ItemMeta eggMeta = egg.getItemMeta();
    eggMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Egg of Resurrection");
    egg.setItemMeta(eggMeta);
    rabbit.getWorld().dropItemNaturally(rabbit.getLocation(), egg);

    // Sort top 5 damagers
    List<Map.Entry<UUID, Double>> top5 = damageTracker.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(5)
            .toList();

    // Reward top 5 players with enchanted helmet
    int rank = 1;
    for (Map.Entry<UUID, Double> entry : top5) {
        Player player = Bukkit.getPlayer(entry.getKey());
        if (player != null) {
            ItemStack helmet = new ItemStack(Material.IRON_HELMET);
            ItemMeta meta = helmet.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + "Champion's Crown");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Victory over the Mad Hare",
                    ChatColor.DARK_PURPLE + "Rank #" + rank
            ));
            Enchantment enchant = Enchantment.getByName("PROTECTION_ENVIRONMENTAL");
			if (enchant != null) {
				meta.addEnchant(enchant, 2, true);
			}
            helmet.setItemMeta(meta);
            player.getInventory().addItem(helmet);

            // Apply Jump Boost when worn (if using persistent potion or attribute is desired, needs extra handling)
            player.addPotionEffect(new PotionEffect(PotionEffectType.getByName("JUMP_BOOST"), Integer.MAX_VALUE, 2)); // Jump Boost III
        }
        rank++;
    }

    // Optional: clear damage tracker
    damageTracker.clear();
}
}
