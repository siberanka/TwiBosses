package com.siberanka.twibosses.commands;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import com.siberanka.twibosses.manager.SpawnManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
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
                if (!(sender instanceof Player)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.only-players"));
                    return true;
                }
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "spawn")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.spawn"));
                    return true;
                }
                String mobType = args[1];
                if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", mobType)));
                    return true;
                }
                Player player = (Player)sender;
                if (!this.plugin.getSecurityGuard().allowManualSpawn(player, mobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.manual-spawn-cooldown"));
                    return true;
                }
                boolean result = this.plugin.getDamageTracker().spawnMobAtLocation(mobType, player.getWorld(), player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
                if (result) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.spawn-success", LanguageManager.placeholders("mobtype", mobType)));
                    break;
                }
                sender.sendMessage(this.plugin.getLanguageManager().get("commands.spawn-failed", LanguageManager.placeholders("mobtype", mobType)));
                break;
            }
            case "setspawn": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.only-players"));
                    return true;
                }
                if (!this.plugin.getSecurityGuard().hasPermission(sender, "setspawn")) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.usage.setspawn"));
                    return true;
                }
                String setMobType = args[1];
                if (!this.plugin.getConfigManager().getTrackedMobs().contains(setMobType)) {
                    sender.sendMessage(this.plugin.getLanguageManager().get("commands.unknown-mob", LanguageManager.placeholders("mobtype", setMobType)));
                    return true;
                }
                Player setPlayer = (Player)sender;
                this.plugin.getSpawnManager().saveSpawnLocation(setMobType, setPlayer.getLocation());
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

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("reload", "toggle", "spawn", "setspawn", "deletespawn", "killall", "help");
            return this.filter(args[0], subs);
        }
        if (args.length == 2 && Arrays.asList("spawn", "setspawn", "deletespawn").contains(args[0].toLowerCase())) {
            return this.filter(args[1], new ArrayList<String>(this.plugin.getConfigManager().getTrackedMobs()));
        }
        if ("killall".equalsIgnoreCase(args[0])) {
            return this.completeKillAll(args);
        }
        return Collections.emptyList();
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
}





