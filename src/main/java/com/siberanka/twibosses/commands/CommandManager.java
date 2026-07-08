package com.siberanka.twibosses.commands;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import com.siberanka.twibosses.manager.SpawnManager;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class CommandManager
implements CommandExecutor,
TabCompleter {
    private final TwiBosses plugin;

    public CommandManager(TwiBosses plugin) {
        this.plugin = plugin;
        PluginCommand command = plugin.getCommand("twiboss");
        if (command != null) {
            command.setExecutor((CommandExecutor)this);
            command.setTabCompleter((TabCompleter)this);
        } else {
            plugin.getLogger().warning(plugin.getLanguageManager().raw("logs.command-missing"));
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub;
        if (!this.plugin.getSecurityGuard().allowCommand(sender)) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.rate-limit"));
            return true;
        }
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }
        switch (sub = args[0].toLowerCase()) {
            case "reload": {
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "reload")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                this.plugin.reloadPluginConfiguration();
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.reload"));
                break;
            }
            case "toggle": {
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "toggle")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                boolean current = this.plugin.getDamageTracker().isEnabled();
                this.plugin.getDamageTracker().setEnabled(!current);
                if (!current) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.toggle-on"));
                    break;
                }
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.toggle-off"));
                break;
            }
            case "spawn": {
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "spawn")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.spawn"));
                    return true;
                }
                String mobType = args[1];
                if (!this.isSafeCommandToken(mobType, 96) || !this.isKnownMythicMob(mobType) || !this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", mobType)));
                    return true;
                }
                CommandLocation parsedLocation = this.parseCommandLocation(sender, args, 2);
                if (!parsedLocation.valid()) {
                    this.sendLocationError(sender, parsedLocation, "commands.usage.spawn");
                    return true;
                }
                if (sender instanceof Player player && !this.plugin.getSecurityGuard().allowManualSpawn(player, mobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.manual-spawn-cooldown"));
                    return true;
                }
                Location location = parsedLocation.location();
                boolean result = this.plugin.getDamageTracker().spawnMobAtLocation(mobType, location.getWorld(), location.getX(), location.getY(), location.getZ());
                if (result) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.spawn-success", LanguageManager.placeholders("mobtype", mobType)));
                    break;
                }
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.spawn-failed", LanguageManager.placeholders("mobtype", mobType)));
                break;
            }
            case "setspawn": {
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "setspawn")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.setspawn"));
                    return true;
                }
                String setMobType = args[1];
                if (!this.isSafeCommandToken(setMobType, 96) || !this.isKnownMythicMob(setMobType) || !this.plugin.getConfigManager().getTrackedMobs().contains(setMobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", setMobType)));
                    return true;
                }
                CommandLocation parsedLocation = this.parseCommandLocation(sender, args, 2);
                if (!parsedLocation.valid()) {
                    this.sendLocationError(sender, parsedLocation, "commands.usage.setspawn");
                    return true;
                }
                this.plugin.getSpawnManager().saveSpawnLocation(setMobType, parsedLocation.location());
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.setspawn-success", LanguageManager.placeholders("mobtype", setMobType)));
                break;
            }
            case "deletespawn": {
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "deletespawn")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.deletespawn"));
                    return true;
                }
                String delMobType = args[1];
                if (!this.plugin.getConfigManager().getTrackedMobs().contains(delMobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", delMobType)));
                    return true;
                }
                boolean deleted = this.plugin.getSpawnManager().deleteSpawnLocation(delMobType);
                if (deleted) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.deletespawn-success", LanguageManager.placeholders("mobtype", delMobType)));
                    break;
                }
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.deletespawn-failed", LanguageManager.placeholders("mobtype", delMobType)));
                break;
            }
            case "killall": {
                this.handleKillAll(sender, args);
                break;
            }
            default: {
                this.sendHelp(sender);
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : this.plugin.getLanguageManager().list("commands.help", Collections.emptyMap())) {
            sender.sendMessage(line);
        }
    }

    private void handleKillAll(CommandSender sender, String[] args) {
        if (!this.plugin.getSecurityGuard().hasPermission(sender, "killall")) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
            return;
        }
        KillAllArguments parsed = this.parseKillAllArguments(args);
        if (!parsed.valid()) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.killall"));
            return;
        }
        if (parsed.mobType() != null && !this.plugin.getConfigManager().getTrackedMobs().contains(parsed.mobType())) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", parsed.mobType())));
            return;
        }
        SpawnManager.KillAllResult result = this.plugin.getSpawnManager().killAllBosses(parsed.mobType(), parsed.worldName());
        if (result.worldMissing()) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.killall-world-missing", LanguageManager.placeholders("world", parsed.worldName())));
            return;
        }
        sender.sendMessage(this.plugin.getLanguageManager().get("commands.killall-success", LanguageManager.placeholders(
                "matched", String.valueOf(result.matched()),
                "killed", String.valueOf(result.killed()),
                "failed", String.valueOf(result.failed()))));
    }

    private KillAllArguments parseKillAllArguments(String[] args) {
        String mobType = null;
        String worldName = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("-w".equalsIgnoreCase(arg)) {
                if (worldName != null || i + 1 >= args.length || !this.isSafeCommandToken(args[i + 1], 64)) {
                    return new KillAllArguments(null, null, false);
                }
                worldName = args[++i];
                continue;
            }
            if (mobType != null || arg.startsWith("-") || !this.isSafeCommandToken(arg, 96)) {
                return new KillAllArguments(null, null, false);
            }
            mobType = arg;
        }
        return new KillAllArguments(mobType, worldName, true);
    }

    private boolean isSafeCommandToken(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isKnownMythicMob(String mobType) {
        try {
            return MythicBukkit.inst().getMobManager().getMythicMob(mobType).isPresent();
        } catch (RuntimeException e) {
            this.plugin.logError("logs.mythicmob-lookup-failed", e);
            return false;
        }
    }

    private List<String> getMythicMobNames() {
        ArrayList<String> names = new ArrayList<>();
        try {
            for (String name : MythicBukkit.inst().getMobManager().getMobNames()) {
                if (this.isSafeCommandToken(name, 96)) {
                    names.add(name);
                }
            }
        } catch (RuntimeException e) {
            this.plugin.logError("logs.mythicmob-lookup-failed", e);
        }
        if (names.isEmpty()) {
            names.addAll(this.plugin.getConfigManager().getTrackedMobs());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private CommandLocation parseCommandLocation(CommandSender sender, String[] args, int startIndex) {
        Player player = sender instanceof Player ? (Player)sender : null;
        World world = null;
        boolean worldProvided = false;
        boolean coordinatesProvided = false;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if ("-w".equalsIgnoreCase(arg)) {
                if (worldProvided || i + 1 >= args.length || !this.isSafeCommandToken(args[i + 1], 64)) {
                    return CommandLocation.invalid(LocationError.USAGE, null);
                }
                worldProvided = true;
                world = Bukkit.getWorld(args[++i]);
                if (world == null) {
                    return CommandLocation.invalid(LocationError.WORLD_MISSING, args[i]);
                }
                continue;
            }
            if ("-c".equalsIgnoreCase(arg)) {
                if (coordinatesProvided || i + 3 >= args.length) {
                    return CommandLocation.invalid(LocationError.USAGE, null);
                }
                try {
                    x = Double.parseDouble(args[++i]);
                    y = Double.parseDouble(args[++i]);
                    z = Double.parseDouble(args[++i]);
                } catch (NumberFormatException e) {
                    return CommandLocation.invalid(LocationError.COORDINATES, null);
                }
                coordinatesProvided = true;
                continue;
            }
            return CommandLocation.invalid(LocationError.USAGE, null);
        }
        if (worldProvided != coordinatesProvided) {
            return CommandLocation.invalid(LocationError.USAGE, null);
        }
        if (!worldProvided) {
            if (player == null) {
                return CommandLocation.invalid(LocationError.PLAYER_REQUIRED, null);
            }
            world = player.getWorld();
        }
        float yaw = player == null ? 0.0F : player.getLocation().getYaw();
        float pitch = player == null ? 0.0F : player.getLocation().getPitch();
        if (!coordinatesProvided) {
            if (player == null) {
                return CommandLocation.invalid(LocationError.PLAYER_REQUIRED, null);
            }
            Location playerLocation = player.getLocation();
            x = playerLocation.getX();
            y = playerLocation.getY();
            z = playerLocation.getZ();
        }
        Location location = new Location(world, x, y, z, yaw, pitch);
        if (!this.isSafeCommandLocation(location)) {
            return CommandLocation.invalid(LocationError.LOCATION, null);
        }
        return new CommandLocation(location, true, null, null);
    }

    private boolean isSafeCommandLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())
                || !Float.isFinite(location.getYaw()) || !Float.isFinite(location.getPitch())) {
            return false;
        }
        World world = location.getWorld();
        if (location.getY() < world.getMinHeight() || location.getY() > world.getMaxHeight()) {
            return false;
        }
        if (Math.abs(location.getX()) > 30_000_000.0 || Math.abs(location.getZ()) > 30_000_000.0) {
            return false;
        }
        WorldBorder border = world.getWorldBorder();
        if (border != null && !border.isInside(location)) {
            return false;
        }
        return world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void sendLocationError(CommandSender sender, CommandLocation parsedLocation, String usagePath) {
        if (parsedLocation.error() == LocationError.PLAYER_REQUIRED) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.only-players"));
            return;
        }
        if (parsedLocation.error() == LocationError.WORLD_MISSING) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.killall-world-missing", LanguageManager.placeholders("world", parsedLocation.errorValue())));
            return;
        }
        if (parsedLocation.error() == LocationError.COORDINATES) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.invalid-coordinates"));
            return;
        }
        if (parsedLocation.error() == LocationError.LOCATION) {
            sender.sendMessage(this.plugin.getLanguageManager().get("commands.invalid-command-location"));
            return;
        }
        sender.sendMessage(this.plugin.getLanguageManager().get(usagePath));
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("reload", "toggle", "spawn", "setspawn", "deletespawn", "killall", "help");
            return this.filter(args[0], subs);
        }
        if (args.length == 2 && Arrays.asList("spawn", "setspawn").contains(args[0].toLowerCase())) {
            return this.filter(args[1], this.getMythicMobNames());
        }
        if (args.length == 2 && "deletespawn".equalsIgnoreCase(args[0])) {
            return this.filter(args[1], new ArrayList<String>(this.plugin.getConfigManager().getTrackedMobs()));
        }
        if (Arrays.asList("spawn", "setspawn").contains(args[0].toLowerCase(Locale.ROOT))) {
            return this.completeLocationCommand(sender, args);
        }
        if ("killall".equalsIgnoreCase(args[0])) {
            return this.completeKillAll(args);
        }
        return Collections.emptyList();
    }

    private List<String> completeLocationCommand(CommandSender sender, String[] args) {
        int coordinateFlag = this.indexOfFlag(args, "-c");
        if (coordinateFlag >= 0 && args.length - 1 >= coordinateFlag + 1 && args.length - 1 <= coordinateFlag + 3) {
            return this.filter(args[args.length - 1], this.coordinateSuggestion(sender, args.length - coordinateFlag - 2));
        }
        if (args.length >= 3 && "-w".equalsIgnoreCase(args[args.length - 2])) {
            ArrayList<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return this.filter(args[args.length - 1], worlds);
        }
        ArrayList<String> options = new ArrayList<>();
        if (this.indexOfFlag(args, "-w") < 0) {
            options.add("-w");
        }
        if (coordinateFlag < 0) {
            options.add("-c");
        }
        return this.filter(args[args.length - 1], options);
    }

    private int indexOfFlag(String[] args, String flag) {
        for (int i = 2; i < args.length; i++) {
            if (flag.equalsIgnoreCase(args[i])) {
                return i;
            }
        }
        return -1;
    }

    private List<String> coordinateSuggestion(CommandSender sender, int coordinateIndex) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        Location location = player.getLocation();
        int value = switch (coordinateIndex) {
            case 0 -> location.getBlockX();
            case 1 -> location.getBlockY();
            case 2 -> location.getBlockZ();
            default -> 0;
        };
        return Collections.singletonList(String.valueOf(value));
    }

    private List<String> completeKillAll(String[] args) {
        if (args.length == 2) {
            ArrayList<String> options = new ArrayList<>(this.plugin.getConfigManager().getTrackedMobs());
            options.add("-w");
            return this.filter(args[1], options);
        }
        if (args.length >= 3 && "-w".equalsIgnoreCase(args[args.length - 2])) {
            ArrayList<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return this.filter(args[args.length - 1], worlds);
        }
        if (args.length == 3 && !"-w".equalsIgnoreCase(args[1])) {
            return this.filter(args[2], Collections.singletonList("-w"));
        }
        return Collections.emptyList();
    }

    private List<String> filter(String arg, List<String> options) {
        if (arg == null || arg.isEmpty()) {
            return options;
        }
        ArrayList<String> result = new ArrayList<String>();
        for (String opt : options) {
            if (!opt.toLowerCase().startsWith(arg.toLowerCase())) continue;
            result.add(opt);
        }
        return result;
    }

    private record KillAllArguments(String mobType, String worldName, boolean valid) {
    }

    private record CommandLocation(Location location, boolean valid, LocationError error, String errorValue) {
        private static CommandLocation invalid(LocationError error, String errorValue) {
            return new CommandLocation(null, false, error, errorValue);
        }
    }

    private enum LocationError {
        USAGE,
        PLAYER_REQUIRED,
        WORLD_MISSING,
        COORDINATES,
        LOCATION
    }
}





