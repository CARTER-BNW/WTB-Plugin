package wtb;

import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class WTBReloadCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!sender.hasPermission("wtb.reload")) {
            if (sender instanceof Player p) {
                p.sendMessage(Main.msg("no_permission"));
            } else {
                sender.sendMessage("[WTB] You do not have permission to reload.");
            }
            return true;
        }

        Main.reloadSettings();

        if (sender instanceof Player p) {
            p.sendMessage(Main.msg("settings_reloaded"));
        } else {
            sender.sendMessage("[WTB] Settings reloaded.");
        }
        return true;
    }
}