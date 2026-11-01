package dev.impossibledragon;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** All custom behaviour for one Ender Dragon. */
public final class DragonFight {

    private enum Ability { FIREBALL, SKULLS, LIGHTNING, MINIONS, ROAR, LIFT, CRYSTAL, TAIL, BREATH }

    private static final long PERIOD = 10L; // ticks between brain updates

    private final ImpossibleDragon plugin;
    private final DragonManager manager;
    private final EnderDragon dragon;
    private final BukkitTask task;
    private final BukkitTask animTask;
    private double anim = 0;

    private final Map<Ability, Long> next = new EnumMap<>(Ability.class);
    private final List<Location> crystalSpots = new ArrayList<>();
    private final Set<UUID> crystals = new HashSet<>();
    private final Set<UUID> minions = new HashSet<>();

    private long now = 0;
    private int phase = 1;
    private double maxHealth = 200;

    public DragonFight(ImpossibleDragon plugin, DragonManager manager, EnderDragon dragon, boolean fresh) {
        this.plugin = plugin;
        this.manager = manager;
        this.dragon = dragon;
        applyStats(fresh);
        scanCrystals();
        updateBar();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, PERIOD);
        this.animTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animateCrystals, 20L, 2L);
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ setup

    public void applyStats(boolean fresh) {
        double max = Math.min(1024.0, Math.max(1.0, cfg().getDouble("dragon.max-health", 800)));
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            double old = attr.getBaseValue();
            attr.setBaseValue(max);
            double hp = fresh ? max : (old > 0 ? dragon.getHealth() / old * max : max);
            dragon.setHealth(Math.min(max, Math.max(1.0, hp)));
            maxHealth = max;
        }
    }

    private void scanCrystals() {
        World w = dragon.getWorld();
        for (EnderCrystal c : w.getEntitiesByClass(EnderCrystal.class)) {
            Location l = c.getLocation();
            // pillar crystals only (the portal-respawn crystals sit much lower)
            if (l.getY() < 70) continue;
            if (l.getX() * l.getX() + l.getZ() * l.getZ() > 80 * 80) continue;
            crystalSpots.add(l.clone());
            crystals.add(c.getUniqueId());
        }
    }

    public void stop() {
        task.cancel();
        animTask.cancel();
        removeMinions();
    }

    public void removeMinions() {
        for (UUID id : minions) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        minions.clear();
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        if (!dragon.isValid()) {
            manager.untrack(dragon.getUniqueId());
            return;
        }
        now += PERIOD;

        updatePhase();
        ensureBossBar();
        if (now % 20 == 0) crystalHeal();
        if (on("crystals", 2) && ready(Ability.CRYSTAL, "crystals", 45)) respawnCrystals(1);

        List<Player> targets = targets();
        if (targets.isEmpty()) return;

        if (on("fireball", 1) && ready(Ability.FIREBALL, "fireball", 8)) {
            volley(targets, cfg().getInt("abilities.fireball.count", 3) + (phase - 1), false);
        }
        if (on("wither-skulls", 2) && ready(Ability.SKULLS, "wither-skulls", 12)) {
            volley(targets, cfg().getInt("abilities.wither-skulls.count", 3), true);
        }
        if (on("lightning", 2) && ready(Ability.LIGHTNING, "lightning", 10)) {
            lightning(targets);
        }
        if (on("tail", 1) && anyWithin(targets, cfg().getDouble("abilities.tail.range", 30))
                && ready(Ability.TAIL, "tail", 7)) {
            tailStrike();
        }
        if (on("lightning-breath", 2) && ready(Ability.BREATH, "lightning-breath", 14)) {
            breath(targets);
        }
        if (on("minions", 1) && ready(Ability.MINIONS, "minions", 25)) {
            spawnMinions(targets);
        }
        if (on("roar", 2) && ready(Ability.ROAR, "roar", 30)) {
            roar(targets);
        }
        if (on("levitation", 3) && ready(Ability.LIFT, "levitation", 25)) {
            lift(targets);
        }
    }

    private boolean on(String path, int defaultMinPhase) {
        String base = "abilities." + path;
        return cfg().getBoolean(base + ".enabled", true)
                && phase >= cfg().getInt(base + ".min-phase", defaultMinPhase);
    }

    /** Returns true when the ability may fire now and starts its cooldown. */
    private boolean ready(Ability a, String path, int defaultSeconds) {
        double secs = cfg().getDouble("abilities." + path + ".cooldown-seconds", defaultSeconds);
        if (phase >= 3) secs *= cfg().getDouble("dragon.enrage-cooldown-multiplier", 0.55);
        long cooldown = Math.max(20L, (long) (secs * 20));
        Long due = next.get(a);
        if (due == null) { // first time seen: start the timer instead of firing instantly
            next.put(a, now + cooldown);
            return false;
        }
        if (now < due) return false;
        next.put(a, now + cooldown);
        return true;
    }

    private List<Player> targets() {
        List<Player> list = new ArrayList<>();
        Location dl = dragon.getLocation();
        for (Player p : dragon.getWorld().getPlayers()) {
            if (p.isDead()) continue;
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
            if (p.getLocation().distanceSquared(dl) > 200 * 200) continue;
            list.add(p);
        }
        return list;
    }

    private Player randomOf(List<Player> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    // ------------------------------------------------------------------ phases

    private void updatePhase() {
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) maxHealth = attr.getValue();
        double frac = dragon.getHealth() / maxHealth;
        int newPhase = frac > 0.66 ? 1 : (frac > 0.33 ? 2 : 3);
        if (newPhase == phase) return;

        boolean up = newPhase > phase;
        phase = newPhase;
        updateBar();
        if (!up) return;

        String msg = cfg().getString(phase == 2 ? "messages.phase-2" : "messages.phase-3", "");
        World w = dragon.getWorld();
        for (Player p : w.getPlayers()) {
            if (!msg.isEmpty()) p.sendMessage(color(msg));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4f, 0.5f);
        }
        if (cfg().getBoolean("abilities.crystals.enabled", true)
                && cfg().getBoolean("abilities.crystals.respawn-on-phase-change", true)) {
            respawnCrystals(99);
        }
    }

    private void updateBar() {
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) return;
        BossBar bar = battle.getBossBar();
        String title = cfg().getString("dragon.boss-bar-title", "&5&lImpossible Ender Dragon");
        bar.setTitle(color(title) + ChatColor.GRAY + " - Phase " + phase);
        bar.setColor(phase == 3 ? BarColor.RED : (phase == 2 ? BarColor.PINK : BarColor.PURPLE));
    }

    // ------------------------------------------------------------------ crystals

    private int aliveCrystals() {
        crystals.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        return crystals.size();
    }

    private void crystalHeal() {
        double per = cfg().getDouble("dragon.crystal-heal-per-second", 0.5);
        if (per <= 0 || dragon.getHealth() >= maxHealth) return;
        int alive = aliveCrystals();
        if (alive == 0) return;
        dragon.setHealth(Math.min(maxHealth, dragon.getHealth() + alive * per));
    }

    private void respawnCrystals(int limit) {
        World w = dragon.getWorld();
        int spawned = 0;
        for (Location spot : crystalSpots) {
            if (spawned >= limit) break;
            if (!w.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) continue;
            boolean exists = !w.getNearbyEntities(spot, 1.5, 1.5, 1.5, e -> e instanceof EnderCrystal).isEmpty();
            if (exists) continue;
            EnderCrystal c = w.spawn(spot, EnderCrystal.class, ec -> ec.setShowingBottom(false));
            crystals.add(c.getUniqueId());
            w.spawnParticle(Particle.REVERSE_PORTAL, spot, 60, 0.5, 0.8, 0.5, 0.2);
            w.playSound(spot, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 3f, 0.8f);
            spawned++;
        }
    }

    // ------------------------------------------------------------------ abilities

    private void volley(List<Player> targets, int count, boolean skull) {
        for (int i = 0; i < count; i++) {
            final Player t = randomOf(targets);
            final long delay = i * 6L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dragon.isValid() && t.isOnline() && !t.isDead()
                        && t.getWorld().equals(dragon.getWorld())) {
                    fire(t, skull);
                }
            }, delay);
        }
    }

    private void fire(Player target, boolean skull) {
        Location from = dragon.getLocation().add(0, 1.5, 0);
        Vector dir = target.getEyeLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 1) return;
        dir.normalize();
        Location spawn = from.clone().add(dir.clone().multiply(5));
        World w = dragon.getWorld();
        if (skull) {
            boolean blue = ThreadLocalRandom.current().nextDouble() < 0.3;
            w.spawn(spawn, WitherSkull.class, s -> {
                s.setShooter(dragon);
                s.setDirection(dir);
                s.setCharged(blue);
            });
        } else {
            w.spawn(spawn, DragonFireball.class, f -> {
                f.setShooter(dragon);
                f.setDirection(dir);
            });
        }
        w.playSound(from, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 4f, 1f);
    }

    private void lightning(List<Player> targets) {
        int bolts = cfg().getInt("abilities.lightning.bolts", 2) + (phase >= 3 ? 1 : 0);
        for (int i = 0; i < bolts; i++) {
            final Location spot = randomOf(targets).getLocation().clone();
            final World w = spot.getWorld();
            if (w == null) continue;
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!dragon.isValid()) {
                        cancel();
                        return;
                    }
                    for (int k = 0; k < 16; k++) {
                        double a = k * Math.PI / 8;
                        w.spawnParticle(Particle.ELECTRIC_SPARK,
                                spot.getX() + Math.cos(a) * 2, spot.getY() + 0.2, spot.getZ() + Math.sin(a) * 2,
                                1, 0, 0, 0, 0);
                    }
                    w.playSound(spot, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 1.5f, 2f);
                    if (++t >= 5) {
                        w.strikeLightning(spot);
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 4L);
        }
    }

    private void spawnMinions(List<Player> targets) {
        minions.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        int cap = cfg().getInt("abilities.minions.max-alive", 10);
        int perWave = cfg().getInt("abilities.minions.per-wave", 3);
        int life = cfg().getInt("abilities.minions.lifetime-seconds", 60);
        List<String> types = cfg().getStringList("abilities.minions.types");
        if (types.isEmpty()) return;

        for (int i = 0; i < perWave && minions.size() < cap; i++) {
            Player p = randomOf(targets);
            EntityType type;
            try {
                type = EntityType.valueOf(types.get(ThreadLocalRandom.current().nextInt(types.size())).toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Location loc = spotNear(p.getLocation());
            Entity e;
            try {
                e = p.getWorld().spawnEntity(loc, type);
            } catch (Exception ex) {
                continue;
            }
            e.getPersistentDataContainer().set(plugin.minionKey(), PersistentDataType.BYTE, (byte) 1);
            if (e instanceof Mob m) m.setTarget(p);
            minions.add(e.getUniqueId());
            p.getWorld().spawnParticle(Particle.PORTAL, loc, 40, 0.4, 0.8, 0.4, 0.3);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.isValid()) e.remove();
            }, life * 20L);
        }
    }

    private Location spotNear(Location center) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 10; attempt++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = 5 + r.nextDouble() * 4;
            Location l = center.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy = 2; dy >= -6; dy--) {
                Block b = l.clone().add(0, dy, 0).getBlock();
                if (b.getType().isSolid()
                        && b.getRelative(BlockFace.UP).isPassable()
                        && b.getRelative(0, 2, 0).isPassable()) {
                    return b.getLocation().add(0.5, 1, 0.5);
                }
            }
        }
        return center.clone().add(0, 1, 0);
    }

    private void roar(List<Player> targets) {
        double radius = cfg().getDouble("abilities.roar.radius", 50);
        Location dl = dragon.getLocation();
        dragon.getWorld().playSound(dl, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.6f);
        String msg = cfg().getString("messages.roar", "");
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(dl) > radius * radius) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            if (!msg.isEmpty()) p.sendActionBar(net.kyori.adventure.text.Component.text(color(msg)));
        }
    }

    private void lift(List<Player> targets) {
        int ticks = cfg().getInt("abilities.levitation.duration-ticks", 40);
        String msg = cfg().getString("messages.lift", "");
        for (Player p : targets) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, ticks, 1));
            if (!msg.isEmpty()) p.sendActionBar(net.kyori.adventure.text.Component.text(color(msg)));
        }
    }

    // ------------------------------------------------------------------ boss bar

    /** Makes sure every player in the End world is actually subscribed to the boss bar. */
    private void ensureBossBar() {
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) return;
        BossBar bar = battle.getBossBar();
        bar.setVisible(true);
        for (Player p : dragon.getWorld().getPlayers()) bar.addPlayer(p);
    }

    // ------------------------------------------------------------------ tail + mouth

    /** Horizontal unit vector pointing from the dragon's body towards its tail. */
    private Vector tailDir() {
        Vector v = dragon.getLocation().getDirection().setY(0);
        if (v.lengthSquared() < 1.0E-4) v = new Vector(0, 0, 1);
        v.normalize();
        if (cfg().getBoolean("dragon.flip-facing", false)) v.multiply(-1);
        return v;
    }

    private Location mouth() {
        return dragon.getLocation().add(tailDir().multiply(-6.5)).add(0, 1.5, 0);
    }

    private static boolean vulnerable(Player p) {
        if (p.isDead()) return false;
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private boolean anyWithin(List<Player> targets, double range) {
        Location dl = dragon.getLocation();
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(dl) <= range * range) return true;
        }
        return false;
    }

    /** Tail strike: telegraphed wind-up, then a 140 degree sweep behind the dragon. */
    private void tailStrike() {
        final double damage = cfg().getDouble("abilities.tail.damage", 8);
        final double knock = cfg().getDouble("abilities.tail.knockback", 1.4);
        final World w = dragon.getWorld();
        final Set<UUID> hit = new HashSet<>();

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!dragon.isValid()) {
                    cancel();
                    return;
                }
                Location base = dragon.getLocation().add(0, -1, 0);
                Vector dir = tailDir();

                if (t < 10) {
                    if (t == 0) {
                        w.playSound(base, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 6f, 0.6f);
                    }
                    // wind-up: flames mark where the sweep begins
                    Vector start = dir.clone().rotateAroundY(Math.toRadians(-70));
                    for (double d = 4; d <= 20; d += 2) {
                        Location l = base.clone().add(start.clone().multiply(d));
                        w.spawnParticle(Particle.FLAME, l, 2, 0.2, 0.2, 0.2, 0.01);
                    }
                } else {
                    double progress = (t - 10) / 10.0;
                    Vector swing = dir.clone().rotateAroundY(Math.toRadians(-70 + 140 * progress));
                    if (t == 10) {
                        w.playSound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 6f, 0.5f);
                    }
                    for (double d = 4; d <= 20; d += 2) {
                        Location l = base.clone().add(swing.clone().multiply(d));
                        w.spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.LARGE_SMOKE, l, 2, 0.5, 0.5, 0.5, 0.02);
                    }
                    for (Player p : w.getPlayers()) {
                        if (!vulnerable(p) || hit.contains(p.getUniqueId())) continue;
                        Location pl = p.getLocation().add(0, 1, 0);
                        for (double d = 4; d <= 20; d += 2) {
                            Location tail = base.clone().add(swing.clone().multiply(d));
                            if (pl.distanceSquared(tail) > 3.5 * 3.5) continue;
                            hit.add(p.getUniqueId());
                            p.damage(damage, dragon);
                            Vector kb = p.getLocation().toVector().subtract(dragon.getLocation().toVector()).setY(0);
                            if (kb.lengthSquared() < 1.0E-4) kb = swing.clone();
                            kb.normalize().multiply(knock).setY(0.6);
                            p.setVelocity(kb);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                            break;
                        }
                    }
                }
                if (++t > 20) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Lightning breath: charges at the mouth, then a beam fires at where you WERE standing. */
    private void breath(List<Player> targets) {
        final Player victim = randomOf(targets);
        final Location lock = victim.getLocation().add(0, 1, 0); // aim is locked -> dodge!
        final World w = dragon.getWorld();
        final int windup = Math.max(5, cfg().getInt("abilities.lightning-breath.windup-ticks", 24));
        final double damage = cfg().getDouble("abilities.lightning-breath.damage", 10);
        final double length = cfg().getDouble("abilities.lightning-breath.length", 70);
        final int beamTicks = 8;
        final Set<UUID> hit = new HashSet<>();
        final Particle.DustOptions glow = new Particle.DustOptions(Color.fromRGB(200, 170, 255), 1.5f);

        new BukkitRunnable() {
            int t = 0;
            Vector dir = null;

            @Override
            public void run() {
                if (!dragon.isValid()) {
                    cancel();
                    return;
                }
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location mouth = mouth();

                if (t < windup) {
                    if (t == 0) w.playSound(mouth, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 5f, 2f);
                    double radius = 3.0 * (1.0 - (double) t / windup) + 0.3;
                    for (int k = 0; k < 8; k++) {
                        double a = t * 0.5 + k * Math.PI / 4;
                        w.spawnParticle(Particle.ELECTRIC_SPARK,
                                mouth.getX() + Math.cos(a) * radius,
                                mouth.getY() + Math.sin(a) * radius,
                                mouth.getZ() + Math.sin(a * 1.3) * radius, 1, 0, 0, 0, 0);
                    }
                    if (t % 4 == 0) { // ring on the locked target spot
                        for (int k = 0; k < 16; k++) {
                            double a = k * Math.PI / 8;
                            w.spawnParticle(Particle.ELECTRIC_SPARK, lock.getX() + Math.cos(a) * 1.5,
                                    lock.getY() - 0.8, lock.getZ() + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
                        }
                    }
                } else {
                    if (dir == null) {
                        dir = lock.toVector().subtract(mouth.toVector());
                        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, -1, 0);
                        dir.normalize();
                        w.playSound(mouth, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 8f, 1.4f);
                    }
                    // jagged beam
                    for (double d = 0; d <= length; d += 1.2) {
                        double x = mouth.getX() + dir.getX() * d + r.nextDouble(-0.35, 0.35);
                        double y = mouth.getY() + dir.getY() * d + r.nextDouble(-0.35, 0.35);
                        double z = mouth.getZ() + dir.getZ() * d + r.nextDouble(-0.35, 0.35);
                        w.spawnParticle(Particle.ELECTRIC_SPARK, x, y, z, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, glow);
                    }
                    if (t == windup + beamTicks / 2) w.strikeLightningEffect(lock);

                    for (Player p : w.getPlayers()) {
                        if (!vulnerable(p) || hit.contains(p.getUniqueId())) continue;
                        Vector v = p.getLocation().add(0, 1, 0).toVector().subtract(mouth.toVector());
                        double along = Math.max(0, Math.min(length, v.dot(dir)));
                        Vector closest = mouth.toVector().add(dir.clone().multiply(along));
                        if (p.getLocation().add(0, 1, 0).toVector().distanceSquared(closest) <= 2.5 * 2.5) {
                            hit.add(p.getUniqueId());
                            p.damage(damage, dragon);
                            p.setFireTicks(80);
                        }
                    }
                }
                if (++t > windup + beamTicks) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ crystal magic circles

    private void dust(World w, double x, double y, double z, Particle.DustOptions o) {
        w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, o);
    }

    private void animateCrystals() {
        if (!cfg().getBoolean("effects.crystal-circles.enabled", true)) return;
        if (crystals.isEmpty()) return;
        World w = dragon.getWorld();
        List<Player> viewers = w.getPlayers();
        if (viewers.isEmpty()) return;
        anim += 0.10;

        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(170, 40, 255), 1.1f);
        Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 120, 255), 0.9f);

        for (UUID id : new ArrayList<>(crystals)) {
            Entity c = Bukkit.getEntity(id);
            if (c == null || !c.isValid()) continue;
            Location base = c.getLocation();
            boolean near = false;
            for (Player p : viewers) {
                if (p.getLocation().distanceSquared(base) < 48 * 48) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            drawRuneCircle(w, base.clone().add(0, 0.15, 0), purple, pink);
            drawShield(w, base.clone().add(0, 1, 0), purple);
        }
    }

    /** Flat magic circle on the ground: two counter-rotating dashed rings + a rotating hexagram. */
    private void drawRuneCircle(World w, Location c, Particle.DustOptions main, Particle.DustOptions accent) {
        double outer = 3.2, inner = 2.4;
        int n = 28;
        for (int i = 0; i < n; i++) {
            if (i % 4 == 3) continue; // dashes
            double a = anim * 0.6 + i * 2 * Math.PI / n;
            dust(w, c.getX() + Math.cos(a) * outer, c.getY(), c.getZ() + Math.sin(a) * outer, main);
        }
        int m = 20;
        for (int i = 0; i < m; i++) {
            double a = -anim * 0.9 + i * 2 * Math.PI / m;
            dust(w, c.getX() + Math.cos(a) * inner, c.getY(), c.getZ() + Math.sin(a) * inner, accent);
        }
        double rot = -anim * 0.4;
        for (int k = 0; k < 6; k++) {
            double a1 = rot + k * Math.PI / 3;
            double a2 = rot + ((k + 2) % 6) * Math.PI / 3;
            double x1 = Math.cos(a1) * inner, z1 = Math.sin(a1) * inner;
            double x2 = Math.cos(a2) * inner, z2 = Math.sin(a2) * inner;
            for (int s = 0; s < 5; s++) {
                double f = s / 5.0;
                dust(w, c.getX() + x1 + (x2 - x1) * f, c.getY(), c.getZ() + z1 + (z2 - z1) * f, main);
            }
        }
        // floating runes
        double ra = anim * 1.3;
        w.spawnParticle(Particle.ENCHANT, c.getX() + Math.cos(ra) * outer, c.getY() + 0.8,
                c.getZ() + Math.sin(ra) * outer, 2, 0.1, 0.3, 0.1, 0.5);
    }

    /** Spinning rings + bubble + electric arcs around the crystal itself. */
    private void drawShield(World w, Location c, Particle.DustOptions main) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = 2.2;
        int n = 16;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < n; i++) {
                double th = i * 2 * Math.PI / n;
                Vector v = new Vector(Math.cos(th) * radius, Math.sin(th) * radius, 0);
                v.rotateAroundX(Math.PI * ring / 3.0 + anim * 0.5);
                v.rotateAroundY(anim * (ring % 2 == 0 ? 0.7 : -0.7) + ring * 1.05);
                dust(w, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), main);
            }
        }
        // faint bubble surface
        for (int i = 0; i < 4; i++) {
            Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (v.lengthSquared() < 1.0E-4) continue;
            v.normalize().multiply(2.5);
            w.spawnParticle(Particle.WITCH, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(),
                    1, 0, 0, 0, 0);
        }
        // electric arc from crystal to the bubble
        if (r.nextDouble() < 0.5) {
            Vector end = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (end.lengthSquared() > 1.0E-4) {
                end.normalize().multiply(2.5);
                int segs = 6;
                for (int s = 1; s <= segs; s++) {
                    double f = s / (double) segs;
                    w.spawnParticle(Particle.ELECTRIC_SPARK,
                            c.getX() + end.getX() * f + r.nextGaussian() * 0.15,
                            c.getY() + end.getY() * f + r.nextGaussian() * 0.15,
                            c.getZ() + end.getZ() * f + r.nextGaussian() * 0.15, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ misc

    public EnderDragon dragon() {
        return dragon;
    }

    public String statusLine() {
        return String.format("§5Dragon §7HP §f%.0f§7/§f%.0f §7| Phase §f%d §7| Crystals §f%d §7| Minions §f%d",
                dragon.getHealth(), maxHealth, phase, aliveCrystals(), minions.size());
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
